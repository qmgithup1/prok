package com.systemsgo.hex.ipmi.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * IPMI-over-LAN (RMCP+ / IPMI 2.0) client for BMC out-of-band management —
 * the IPMI counterpart to [com.systemsgo.hex.redfish.protocol.RedfishClient].
 *
 * Usage:
 * ```
 * val client = IpmiClient(host, port = 623, username, password)
 * client.connect()
 * client.powerControl(IpmiPowerAction.POWER_UP)
 * val sol = client.openSolChannel()
 * client.disconnect()
 * ```
 *
 * All I/O methods are suspend functions dispatched on Dispatchers.IO — call
 * from a ViewModel/coroutine scope, never directly from the UI thread.
 *
 * Scope note: covers Chassis power control/status/identify, SEL (event log)
 * read, Get Device ID, FRU inventory (chassis/board/product info), a full
 * SDR-repository-driven sensor list (real names/units, linear-conversion
 * applied), SOL, BMC LAN configuration (view + set static IP), BMC-local
 * user account management, PEF enable/disable, and the watchdog timer.
 * Non-linear sensor curves (§36.4) surface raw-only. Vendor OEM commands
 * (e.g. Dell RACADM-over-IPMI or Supermicro OEM) are out of scope — there's
 * no generic IPMI spec for these, each vendor defines its own, so there's
 * nothing generic to implement; for firmware and virtual media, prefer
 * [com.systemsgo.hex.redfish.protocol.RedfishClient], which standardizes
 * both; fall back to IPMI only for BMCs with no Redfish support at all.
 */
class IpmiClient(
    private val host: String,
    private val port: Int = 623,
    private val username: String,
    private val password: String,
    private val privilegeLevel: IpmiPrivilege = IpmiPrivilege.ADMINISTRATOR,
    /** The BMC's "Kg"/"BMC key" for a two-key login — see [IpmiSession]'s constructor param of the same name. Null/blank for the default one-key login. */
    private val bmcKey: String? = null,
) {
    enum class IpmiPrivilege(val code: Int) { USER(0x02), OPERATOR(0x03), ADMINISTRATOR(0x04) }

    private var session: IpmiSession? = null

    val isConnected: Boolean get() = session?.established == true

    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = IpmiSession(host, port, username, password, privilegeLevel.code, kgKey = bmcKey)
        s.open()
        session = s
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        session?.close()
        session = null
    }

    private fun requireSession(): IpmiSession =
        session ?: throw IpmiException("Not connected — call connect() first")

    // ── Chassis (NetFn 0x00) ─────────────────────────────────────────

    suspend fun getChassisStatus(): IpmiChassisStatus = withContext(Dispatchers.IO) {
        val resp = requireSession().sendIpmiRequest(netFn = 0x00, cmd = 0x01) // Get Chassis Status
        val b0 = resp[0].toInt() and 0xFF
        val causeCode = (resp.getOrElse(1) { 0 }.toInt() and 0x0F)
        IpmiChassisStatus(
            powerIsOn = (b0 and 0x01) != 0,
            overload = (b0 and 0x02) != 0,
            interlock = (b0 and 0x04) != 0,
            fault = (b0 and 0x08) != 0,
            controlFault = (b0 and 0x10) != 0,
            lastPowerOnCause = powerOnCauseLabel(causeCode),
            identifySupported = true,
        )
    }

    suspend fun powerControl(action: IpmiPowerAction) = withContext(Dispatchers.IO) {
        requireSession().sendIpmiRequest(netFn = 0x00, cmd = 0x02, data = byteArrayOf(action.code.toByte())) // Chassis Control
        Unit
    }

    suspend fun setChassisIdentify(seconds: Int = 15) = withContext(Dispatchers.IO) {
        // Chassis Identify (0x00/0x04); 0 = turn off, 0xFF = indefinite, else seconds.
        requireSession().sendIpmiRequest(netFn = 0x00, cmd = 0x04, data = byteArrayOf(seconds.coerceIn(0, 255).toByte()))
        Unit
    }

    // ── App (NetFn 0x06) ─────────────────────────────────────────────

    suspend fun getDeviceId(): IpmiDeviceId = withContext(Dispatchers.IO) {
        val r = requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x01) // Get Device ID
        val fwMajor = r[2].toInt() and 0x7F
        val fwMinorBcd = r[3].toInt() and 0xFF
        val fwMinor = ((fwMinorBcd shr 4) * 10) + (fwMinorBcd and 0x0F)
        val manuf = (r[6].toInt() and 0xFF) or ((r[7].toInt() and 0xFF) shl 8) or ((r[8].toInt() and 0xFF) shl 16)
        val prod = (r[9].toInt() and 0xFF) or ((r[10].toInt() and 0xFF) shl 8)
        val ipmiVerByte = r[4].toInt() and 0xFF
        IpmiDeviceId(
            deviceId = r[0].toInt() and 0xFF,
            firmwareVersion = "$fwMajor.$fwMinor",
            manufacturerId = manuf,
            productId = prod,
            ipmiVersion = "${ipmiVerByte and 0x0F}.${(ipmiVerByte shr 4) and 0x0F}",
        )
    }

    // ── Storage / SEL — System Event Log (NetFn 0x0A) ──────────────────

    suspend fun getSelInfo(): IpmiSelInfo = withContext(Dispatchers.IO) {
        val r = requireSession().sendIpmiRequest(netFn = 0x0A, cmd = 0x40) // Get SEL Info
        val versionByte = r[0].toInt() and 0xFF
        val count = (r[1].toInt() and 0xFF) or ((r[2].toInt() and 0xFF) shl 8)
        val free = (r[3].toInt() and 0xFF) or ((r[4].toInt() and 0xFF) shl 8)
        IpmiSelInfo(
            version = "${versionByte and 0x0F}.${(versionByte shr 4) and 0x0F}",
            entryCount = count,
            freeSpaceBytes = free,
            supportsOverflow = (r.getOrElse(15) { 0 }.toInt() and 0x80) != 0,
        )
    }

    /**
     * Reads up to [maxEntries] SEL records, newest first. Walks the log via
     * "Get SEL Entry" starting from record 0xFFFF (which every BMC
     * interprets as "last record") and following each entry's "previous
     * record ID" backwards — avoids needing Reserve SEL / partial reads.
     */
    suspend fun getSelEntries(maxEntries: Int = 50): List<IpmiSelEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<IpmiSelEntry>()
        var nextId = 0xFFFF
        var guard = 0
        while (out.size < maxEntries && nextId != 0x0000 && guard < maxEntries + 5) {
            guard++
            val reqData = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0) // reserve ID = 0 (no reservation held)
                .putShort(nextId.toShort())
                .put(0) // offset
                .put(0xFF.toByte()) // bytes to read: 0xFF = entire record
                .array()
            val r = try {
                requireSession().sendIpmiRequest(netFn = 0x0A, cmd = 0x43, data = reqData) // Get SEL Entry
            } catch (e: IpmiException) {
                break // log empty or record erased mid-walk
            }
            val nextRecordId = (r[0].toInt() and 0xFF) or ((r[1].toInt() and 0xFF) shl 8)
            val record = r.copyOfRange(2, r.size)
            out += parseSelRecord(record)
            if (nextRecordId == 0xFFFF || nextRecordId == 0x0000) break
            nextId = nextRecordId
        }
        out
    }

    private fun parseSelRecord(rec: ByteArray): IpmiSelEntry {
        val recordId = (rec[0].toInt() and 0xFF) or ((rec[1].toInt() and 0xFF) shl 8)
        val recordType = rec[2].toInt() and 0xFF
        val ts = if (recordType < 0xC0) {
            ((rec[3].toInt() and 0xFF).toLong()) or
                ((rec[4].toInt() and 0xFF).toLong() shl 8) or
                ((rec[5].toInt() and 0xFF).toLong() shl 16) or
                ((rec[6].toInt() and 0xFF).toLong() shl 24)
        } else 0L
        val sensorType = rec.getOrElse(10) { 0 }.toInt() and 0xFF
        val sensorNumber = rec.getOrElse(11) { 0 }.toInt() and 0xFF
        val eventDir = rec.getOrElse(12) { 0 }.toInt() and 0xFF
        val offset = eventDir and 0x0F
        val assertion = (eventDir and 0x80) == 0
        return IpmiSelEntry(
            recordId = recordId,
            timestamp = ts,
            sensorType = sensorType,
            sensorNumber = sensorNumber,
            eventDescription = "${sensorTypeLabel(sensorType)} sensor #$sensorNumber: " +
                "${if (assertion) "asserted" else "deasserted"} (offset 0x${offset.toString(16)})",
            raw = rec,
        )
    }

    // ── Sensors (best-effort; full SDR-driven decoding is future work) ─

    /**
     * Reads a raw sensor by its SDR sensor number (Get Sensor Reading,
     * NetFn 0x04/0x2D). Without a full SDR walk we can't resolve the
     * sensor's name/unit/linear-conversion factors automatically, so
     * callers that know their BMC's sensor numbering (common for a fixed
     * fleet of the same server model) can pass them directly; otherwise use
     * [getSelEntries]/[getChassisStatus] for now and treat this as raw data.
     */
    suspend fun getSensorReading(sensorNumber: Int, name: String = "Sensor $sensorNumber"): IpmiSensorReading =
        withContext(Dispatchers.IO) {
            val r = requireSession().sendIpmiRequest(netFn = 0x04, cmd = 0x2D, data = byteArrayOf(sensorNumber.toByte()))
            val reading = r[0].toInt() and 0xFF
            val statusByte = r.getOrElse(1) { 0 }.toInt() and 0xFF
            val unavailable = (statusByte and 0x20) != 0
            IpmiSensorReading(
                sensorNumber = sensorNumber,
                name = name,
                rawReading = reading,
                convertedValue = null, // needs the sensor's SDR (M, B, linearization) to convert
                unit = "raw",
                eventStatusRaw = r.getOrElse(2) { 0 }.toInt() and 0xFF,
                readingUnavailable = unavailable,
            )
        }

    // ── FRU (Field Replaceable Unit) inventory (NetFn 0x0A) ─────────────

    /**
     * Reads and parses the FRU inventory at [fruDeviceId] (0 = the BMC's own
     * controller, which on essentially every server board *is* the
     * baseboard FRU — the same default `ipmitool fru print` uses). Decodes
     * the Chassis/Board/Product Info Areas per IPMI Platform Management FRU
     * spec §11.
     *
     * Only Type/Length-encoded fields using type code 11b (8-bit ASCII+
     * Latin1 — what virtually every vendor uses for these fields) are
     * decoded to text; binary/BCD-plus/6-bit-packed-ASCII fields (rare here
     * in practice) come back as `null` rather than mis-decoded.
     */
    suspend fun getFruInventory(fruDeviceId: Int = 0): IpmiFruInfo = withContext(Dispatchers.IO) {
        val s = requireSession()
        val infoResp = s.sendIpmiRequest(netFn = 0x0A, cmd = 0x10, data = byteArrayOf(fruDeviceId.toByte())) // Get FRU Inventory Area Info
        val areaSize = (infoResp[0].toInt() and 0xFF) or ((infoResp[1].toInt() and 0xFF) shl 8)
        if (areaSize <= 0) throw IpmiException("FRU device $fruDeviceId reports an empty inventory area")

        val data = ByteArray(areaSize)
        var offset = 0
        val chunk = 16
        while (offset < areaSize) {
            val readLen = minOf(chunk, areaSize - offset)
            val reqData = byteArrayOf(fruDeviceId.toByte(), (offset and 0xFF).toByte(), ((offset shr 8) and 0xFF).toByte(), readLen.toByte())
            val r = try {
                s.sendIpmiRequest(netFn = 0x0A, cmd = 0x11, data = reqData) // Read FRU Data
            } catch (e: IpmiException) {
                break // some BMCs cap the readable window short of the declared area size
            }
            val countRead = r[0].toInt() and 0xFF
            if (countRead <= 0) break
            val body = r.copyOfRange(1, minOf(1 + countRead, r.size))
            System.arraycopy(body, 0, data, offset, body.size)
            offset += body.size
            if (body.size < readLen) break // short read — BMC gave us less than asked, stop rather than loop forever
        }

        parseFruData(data)
    }

    private fun parseFruData(data: ByteArray): IpmiFruInfo {
        if (data.size < 8) return IpmiFruInfo(null, null, null, null, null, null, null, null, null, null, null, null)
        val chassisAreaOffset = (data.getOrElse(2) { 0 }.toInt() and 0xFF) * 8
        val boardAreaOffset = (data.getOrElse(3) { 0 }.toInt() and 0xFF) * 8
        val productAreaOffset = (data.getOrElse(4) { 0 }.toInt() and 0xFF) * 8

        var chassisType: String? = null
        var chassisPartNumber: String? = null
        var chassisSerial: String? = null
        if (chassisAreaOffset in 1 until data.size) {
            chassisType = chassisTypeLabel(data.getOrElse(chassisAreaOffset + 2) { 0 }.toInt() and 0xFF)
            val fields = parseTypeLengthFields(data, chassisAreaOffset + 3, maxFields = 2)
            chassisPartNumber = fields.getOrNull(0)
            chassisSerial = fields.getOrNull(1)
        }

        var boardManufacturer: String? = null
        var boardProduct: String? = null
        var boardSerial: String? = null
        var boardPartNumber: String? = null
        if (boardAreaOffset in 1 until data.size) {
            // byte0=version, byte1=length, byte2=language code, bytes3-5=mfg date/time (skip)
            val fields = parseTypeLengthFields(data, boardAreaOffset + 6, maxFields = 4)
            boardManufacturer = fields.getOrNull(0)
            boardProduct = fields.getOrNull(1)
            boardSerial = fields.getOrNull(2)
            boardPartNumber = fields.getOrNull(3)
        }

        var productManufacturer: String? = null
        var productName: String? = null
        var productPartNumber: String? = null
        var productSerial: String? = null
        var productAssetTag: String? = null
        if (productAreaOffset in 1 until data.size) {
            // byte0=version, byte1=length, byte2=language code
            val fields = parseTypeLengthFields(data, productAreaOffset + 3, maxFields = 5)
            productManufacturer = fields.getOrNull(0)
            productName = fields.getOrNull(1)
            productPartNumber = fields.getOrNull(2)
            // fields[3] is Product Version — not surfaced in IpmiFruInfo, kept minimal like AMT's Info tab
            productSerial = fields.getOrNull(4)
        }
        // Asset tag is the field *after* serial number in the Product Info Area;
        // re-walk once more asking for 6 fields so we can pick it up too.
        if (productAreaOffset in 1 until data.size) {
            val fields = parseTypeLengthFields(data, productAreaOffset + 3, maxFields = 6)
            productAssetTag = fields.getOrNull(5)
        }

        return IpmiFruInfo(
            chassisType = chassisType, chassisPartNumber = chassisPartNumber, chassisSerial = chassisSerial,
            boardManufacturer = boardManufacturer, boardProduct = boardProduct, boardSerial = boardSerial, boardPartNumber = boardPartNumber,
            productManufacturer = productManufacturer, productName = productName, productPartNumber = productPartNumber,
            productSerial = productSerial, productAssetTag = productAssetTag,
        )
    }

    /**
     * Decodes up to [maxFields] consecutive Type/Length-encoded FRU text
     * fields starting at [start], stopping early at the 0xC1 end-of-area
     * marker. Only type code 11b (8-bit ASCII+Latin1) is decoded to text —
     * other type codes come back as `null` at that position (see
     * [getFruInventory]'s doc comment).
     */
    private fun parseTypeLengthFields(data: ByteArray, start: Int, maxFields: Int): List<String?> {
        val out = mutableListOf<String?>()
        var pos = start
        while (out.size < maxFields && pos in data.indices) {
            val tl = data[pos].toInt() and 0xFF
            if (tl == 0xC1) break // end-of-area marker
            val typeCode = (tl shr 6) and 0x03
            val len = tl and 0x3F
            val fieldStart = pos + 1
            if (fieldStart + len > data.size) break
            out += if (typeCode == 3 && len > 0) {
                String(data, fieldStart, len, Charsets.ISO_8859_1).trim().ifEmpty { null }
            } else null
            pos = fieldStart + len
        }
        while (out.size < maxFields) out += null
        return out
    }

    private fun chassisTypeLabel(code: Int): String = when (code) {
        0x01 -> "Other"; 0x02 -> "Unknown"; 0x03 -> "Desktop"; 0x04 -> "Low Profile Desktop"
        0x06 -> "Mini Tower"; 0x07 -> "Tower"; 0x08 -> "Portable"; 0x09 -> "Laptop"
        0x0B -> "Notebook"; 0x0C -> "Hand Held"; 0x0D -> "Docking Station"; 0x0E -> "All in One"
        0x0F -> "Sub Notebook"; 0x11 -> "Main Server Chassis"; 0x17 -> "Rack Mount Chassis"
        0x18 -> "Sealed-case PC"; 0x1C -> "Multi-system chassis"; 0x1D -> "Blade"; 0x1E -> "Blade Enclosure"
        else -> "Type 0x${code.toString(16)}"
    }

    // ── Sensors — full SDR (Sensor Data Record) repository walk ────────

    /**
     * Walks the SDR Repository (Reserve SDR Repository + Get SDR, following
     * "next record ID" the same way [getSelEntries] walks the SEL) and
     * resolves each Full/Compact sensor record's name/unit/type, then reads
     * its live value via Get Sensor Reading, applying the linear-conversion
     * factors (M, B, B-exponent, R-exponent) a Full record carries. This is
     * how sensor names actually get resolved — [getSensorReading] alone
     * can't do it, since a bare Get Sensor Reading response is just a raw
     * byte with no idea what sensor number 12 *is*.
     *
     * Capped at [maxSensors] records to keep this bounded on a mobile
     * connection against BMCs with hundreds of SDR entries; non-linear
     * sensors (temperature curves etc., linearization byte != 0) are
     * surfaced with their raw reading only ([IpmiSensor.value] = null) since
     * decoding the non-linear tables (§36.4) is out of scope here — same
     * boundary this class already draws for the standalone
     * [getSensorReading] method.
     */
    suspend fun getSensors(maxSensors: Int = 48): List<IpmiSensor> = withContext(Dispatchers.IO) {
        val s = requireSession()
        val reservation = try {
            val r = s.sendIpmiRequest(netFn = 0x0A, cmd = 0x22) // Reserve SDR Repository
            (r[0].toInt() and 0xFF) or ((r[1].toInt() and 0xFF) shl 8)
        } catch (e: IpmiException) {
            0 // some BMCs don't require/support a reservation for a full (non-partial-write) walk
        }

        val out = mutableListOf<IpmiSensor>()
        var nextId = 0x0000
        var guard = 0
        while (out.size < maxSensors && guard < maxSensors * 3 + 10) {
            guard++
            val record = try {
                readOneSdr(s, reservation, nextId)
            } catch (e: IpmiException) {
                break
            } ?: break
            val (recordNextId, recordBytes) = record
            if (recordBytes.size >= 5) {
                val recordType = recordBytes[3].toInt() and 0xFF
                if (recordType == 0x01 || recordType == 0x02) {
                    parseSensorSdr(recordBytes, recordType == 0x01)?.let { partial ->
                        val (raw, unavailable, statusByte) = runCatching { readSensorRaw(s, partial.sensorNumber) }
                            .getOrDefault(Triple(0, true, 0))
                        val value = if (!unavailable && partial.canConvert) {
                            convertLinear(raw, partial.m, partial.b, partial.bExp, partial.rExp, partial.analogFormat)
                        } else null
                        out += IpmiSensor(
                            sensorNumber = partial.sensorNumber, name = partial.name, sensorType = partial.sensorType,
                            sensorTypeLabel = sensorTypeLabel(partial.sensorType), entityId = partial.entityId,
                            isFullRecord = partial.isFullRecord, value = value, unit = partial.unit,
                            rawReading = raw, readingUnavailable = unavailable,
                            stateAsserted = (statusByte and 0x80) != 0,
                        )
                    }
                }
            }
            if (recordNextId == 0xFFFF || recordNextId == nextId) break
            nextId = recordNextId
        }
        out
    }

    /** Reads one complete SDR record (5-byte header + body), chunked since a
     *  BMC may cap how much it returns per Get SDR call. Returns (nextRecordId, fullRecordBytes) or null past the end of the repository. */
    private fun readOneSdr(s: IpmiSession, reservation: Int, recordId: Int): Pair<Int, ByteArray>? {
        // First read: just the 5-byte header (Record ID + SDR Version + Record Type + Record Length).
        val headerReq = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(reservation.toShort()).putShort(recordId.toShort()).put(0).put(5).array()
        val headerResp = s.sendIpmiRequest(netFn = 0x0A, cmd = 0x23, data = headerReq) // Get SDR
        val nextRecordId = (headerResp[0].toInt() and 0xFF) or ((headerResp[1].toInt() and 0xFF) shl 8)
        val header = headerResp.copyOfRange(2, headerResp.size)
        if (header.size < 5) return nextRecordId to header
        val bodyLen = header[4].toInt() and 0xFF
        val full = header.copyOf(5 + bodyLen)
        var have = 5
        val step = 16
        while (have < full.size) {
            val want = minOf(step, full.size - have)
            val req = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(reservation.toShort()).putShort(recordId.toShort()).put(have.toByte()).put(want.toByte()).array()
            val resp = try {
                s.sendIpmiRequest(netFn = 0x0A, cmd = 0x23, data = req)
            } catch (e: IpmiException) {
                break // partial record — parse whatever we got
            }
            val chunkBody = resp.copyOfRange(2, resp.size)
            if (chunkBody.isEmpty()) break
            val n = minOf(chunkBody.size, full.size - have)
            System.arraycopy(chunkBody, 0, full, have, n)
            have += n
            if (chunkBody.size < want) break
        }
        return nextRecordId to full
    }

    private data class ParsedSensorSdr(
        val sensorNumber: Int, val name: String, val sensorType: Int, val entityId: Int,
        val isFullRecord: Boolean, val unit: String, val canConvert: Boolean,
        val m: Int, val b: Int, val bExp: Int, val rExp: Int, val analogFormat: Int,
    )

    private fun parseSensorSdr(rec: ByteArray, isFull: Boolean): ParsedSensorSdr? {
        if (rec.size < 13) return null
        val sensorNumber = rec[7].toInt() and 0xFF
        val entityId = rec.getOrElse(8) { 0 }.toInt() and 0xFF
        val sensorType = rec.getOrElse(12) { 0 }.toInt() and 0xFF
        val idOffset = if (isFull) 47 else 31
        val name = rec.getOrElse(idOffset) { 0 }.let { tl ->
            val tlByte = tl.toInt() and 0xFF
            val typeCode = (tlByte shr 6) and 0x03
            val len = tlByte and 0x3F
            val start = idOffset + 1
            if (typeCode == 3 && len > 0 && start + len <= rec.size) {
                String(rec, start, len, Charsets.ISO_8859_1).trim()
            } else "Sensor #$sensorNumber"
        }.ifBlank { "Sensor #$sensorNumber" }

        if (!isFull) {
            val baseUnit = rec.getOrElse(21) { 0 }.toInt() and 0xFF
            return ParsedSensorSdr(sensorNumber, name, sensorType, entityId, false, sensorUnitLabel(baseUnit), false, 0, 0, 0, 0, 0)
        }

        val units1 = rec.getOrElse(20) { 0 }.toInt() and 0xFF
        val analogFormat = (units1 shr 6) and 0x03
        val baseUnit = rec.getOrElse(21) { 0 }.toInt() and 0xFF
        val linearization = rec.getOrElse(23) { 0 }.toInt() and 0x7F
        val mLsb = rec.getOrElse(24) { 0 }.toInt() and 0xFF
        val mMsb = (rec.getOrElse(25) { 0 }.toInt() and 0xC0) shr 6
        val m = signExtend((mMsb shl 8) or mLsb, 10)
        val bLsb = rec.getOrElse(26) { 0 }.toInt() and 0xFF
        val bMsb = (rec.getOrElse(27) { 0 }.toInt() and 0xC0) shr 6
        val b = signExtend((bMsb shl 8) or bLsb, 10)
        val rexpBexp = rec.getOrElse(29) { 0 }.toInt() and 0xFF
        val rExp = signExtend((rexpBexp shr 4) and 0x0F, 4)
        val bExp = signExtend(rexpBexp and 0x0F, 4)
        // linearization==0 means "linear" — the only curve this client
        // converts; non-linear (log, exponential, ...) sensors are surfaced
        // raw-only (see getSensors's doc comment).
        val canConvert = linearization == 0
        return ParsedSensorSdr(sensorNumber, name, sensorType, entityId, true, sensorUnitLabel(baseUnit), canConvert, m, b, bExp, rExp, analogFormat)
    }

    private fun readSensorRaw(s: IpmiSession, sensorNumber: Int): Triple<Int, Boolean, Int> {
        val r = s.sendIpmiRequest(netFn = 0x04, cmd = 0x2D, data = byteArrayOf(sensorNumber.toByte()))
        val reading = r[0].toInt() and 0xFF
        val statusByte = r.getOrElse(1) { 0 }.toInt() and 0xFF
        val unavailable = (statusByte and 0x20) != 0
        return Triple(reading, unavailable, statusByte)
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    private fun convertLinear(raw: Int, m: Int, b: Int, bExp: Int, rExp: Int, analogFormat: Int): Double {
        val x = when (analogFormat) {
            0 -> raw.toDouble() // unsigned
            else -> if (raw and 0x80 != 0) (raw - 256).toDouble() else raw.toDouble() // 1's/2's complement, treated alike
        }
        return (m * x + b * Math.pow(10.0, bExp.toDouble())) * Math.pow(10.0, rExp.toDouble())
    }

    private fun sensorUnitLabel(code: Int): String = when (code) {
        1 -> "°C"; 2 -> "°F"; 3 -> "K"; 4 -> "V"; 5 -> "A"; 6 -> "W"; 7 -> "J"
        12 -> "N"; 18 -> "RPM"; 19 -> "Hz"; 22 -> "ms"; 23 -> "s"; 41 -> "%"
        43 -> "gravities"; 54 -> "ohms"; 65 -> "watts"; 96 -> "unspecified"
        else -> ""
    }

    // ── LAN Configuration Parameters (NetFn 0x0C) ───────────────────────

    /**
     * Reads the BMC's own LAN identity on [channel] (1 is the LAN channel on
     * essentially every server BMC) — separate from whatever IP the host OS
     * itself uses, since the BMC has its own independent NIC/MAC.
     */
    suspend fun getLanConfig(channel: Int = 1): IpmiLanConfig = withContext(Dispatchers.IO) {
        val s = requireSession()
        val ip = getLanParam(s, channel, 3)
        val sourceByte = getLanParam(s, channel, 4).getOrElse(0) { 0 }.toInt() and 0x0F
        val mac = getLanParam(s, channel, 5)
        val mask = getLanParam(s, channel, 6)
        val gw = getLanParam(s, channel, 12)
        val vlan = runCatching { getLanParam(s, channel, 20) }.getOrDefault(ByteArray(0))
        val vlanEnabled = vlan.size >= 2 && ((vlan[1].toInt() and 0x80) != 0)
        val vlanId = if (vlanEnabled) ((vlan[0].toInt() and 0xFF) or ((vlan[1].toInt() and 0x0F) shl 8)) else null
        IpmiLanConfig(
            channel = channel,
            ipAddress = formatIpv4(ip),
            ipSource = when (sourceByte) { 1 -> "Static"; 2 -> "DHCP"; 3 -> "BIOS/other"; else -> "unspecified" },
            subnetMask = formatIpv4(mask),
            macAddress = formatMac(mac),
            defaultGateway = formatIpv4(gw),
            vlanEnabled = vlanEnabled,
            vlanId = vlanId,
        )
    }

    /** Sets a static IP/subnet/gateway on [channel] and switches the address source to Static. Takes effect immediately on most BMCs; some require a "commit"/reset that this doesn't attempt. */
    suspend fun setLanStaticConfig(channel: Int = 1, ipAddress: String, subnetMask: String, defaultGateway: String) = withContext(Dispatchers.IO) {
        val s = requireSession()
        setLanParam(s, channel, 4, byteArrayOf(0x01)) // IP Address Source = Static
        setLanParam(s, channel, 3, parseIpv4(ipAddress))
        setLanParam(s, channel, 6, parseIpv4(subnetMask))
        setLanParam(s, channel, 12, parseIpv4(defaultGateway))
        Unit
    }

    private fun getLanParam(s: IpmiSession, channel: Int, param: Int): ByteArray {
        val req = byteArrayOf(channel.toByte(), param.toByte(), 0x00, 0x00) // set/block selector = 0
        val r = s.sendIpmiRequest(netFn = 0x0C, cmd = 0x02, data = req) // Get LAN Configuration Parameters
        return if (r.size > 1) r.copyOfRange(1, r.size) else ByteArray(0) // drop parameter revision byte
    }

    private fun setLanParam(s: IpmiSession, channel: Int, param: Int, data: ByteArray) {
        s.sendIpmiRequest(netFn = 0x0C, cmd = 0x01, data = byteArrayOf(channel.toByte(), param.toByte()) + data) // Set LAN Configuration Parameters
    }

    private fun formatIpv4(b: ByteArray): String =
        if (b.size >= 4) "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}" else "0.0.0.0"

    private fun parseIpv4(s: String): ByteArray {
        val parts = s.trim().split(".").map { it.toInt().coerceIn(0, 255) }
        require(parts.size == 4) { "Invalid IPv4 address: $s" }
        return ByteArray(4) { parts[it].toByte() }
    }

    private fun formatMac(b: ByteArray): String =
        if (b.size >= 6) (0 until 6).joinToString(":") { "%02X".format(b[it].toInt() and 0xFF) } else "00:00:00:00:00:00"

    // ── User Management (NetFn 0x06 App) ─────────────────────────────────

    /**
     * Lists BMC-local IPMI users on [channel] (Get User Access + Get User
     * Name per ID). [IpmiUserAccount.enabled] is a best-effort inference
     * (non-blank name and a privilege other than "No Access") since the
     * spec doesn't expose a directly-readable "administratively disabled"
     * bit distinct from privilege — only [setUserEnabled] (Set User
     * Password, disable/enable operation) can *change* that state.
     */
    suspend fun getUsers(channel: Int = 1, maxUserId: Int = 15): List<IpmiUserAccount> = withContext(Dispatchers.IO) {
        val s = requireSession()
        val out = mutableListOf<IpmiUserAccount>()
        var discoveredMax = maxUserId
        for (userId in 1..discoveredMax) {
            val access = try {
                s.sendIpmiRequest(netFn = 0x06, cmd = 0x44, data = byteArrayOf((channel and 0x0F).toByte(), (userId and 0x3F).toByte())) // Get User Access
            } catch (e: IpmiException) {
                break // BMC ran out of valid user IDs
            }
            if (userId == 1) {
                val maxUsers = access.getOrElse(0) { 0 }.toInt() and 0x3F
                if (maxUsers in 1..maxUserId) discoveredMax = maxUsers
            }
            val flagsByte = access.getOrElse(3) { 0 }.toInt() and 0xFF
            val privCode = flagsByte and 0x0F
            val name = try {
                val r = s.sendIpmiRequest(netFn = 0x06, cmd = 0x46, data = byteArrayOf(userId.toByte())) // Get User Name
                String(r, Charsets.US_ASCII).trim('\u0000', ' ')
            } catch (e: IpmiException) { "" }
            val privLabel = privilegeCodeLabel(privCode)
            out += IpmiUserAccount(
                userId = userId, name = name,
                enabled = name.isNotBlank() && privCode != 0x0F,
                privilege = privLabel,
                callInEnabled = (flagsByte and 0x40) == 0, // bit set = *restricted* to callback, i.e. call-in-only; we surface the common case as "enabled" when unrestricted
                linkAuthEnabled = (flagsByte and 0x20) != 0,
                ipmiMessagingEnabled = (flagsByte and 0x10) != 0,
            )
        }
        out
    }

    /** Sets [userId]'s privilege limit and messaging/link-auth/callback flags on [channel]. To change only the privilege and leave the others as they were, pass `ipmiMessagingEnabled`/`linkAuthEnabled` straight from [getUsers]' result and `callInRestricted = !account.callInEnabled`. */
    suspend fun setUserAccess(
        channel: Int = 1, userId: Int, privilege: IpmiPrivilege,
        ipmiMessagingEnabled: Boolean = true, linkAuthEnabled: Boolean = true, callInRestricted: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        var b1 = 0x80 or (channel and 0x0F) // bit7 = apply the flag bits below rather than leaving them unchanged
        if (ipmiMessagingEnabled) b1 = b1 or 0x10
        if (linkAuthEnabled) b1 = b1 or 0x20
        if (callInRestricted) b1 = b1 or 0x40
        val data = byteArrayOf(b1.toByte(), (userId and 0x3F).toByte(), (privilege.code and 0x0F).toByte(), 0x00)
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x43, data = data) // Set User Access
        Unit
    }

    suspend fun setUserName(userId: Int, name: String) = withContext(Dispatchers.IO) {
        val nameBytes = name.toByteArray(Charsets.US_ASCII).copyOf(16) // null-padded to 16 bytes
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x45, data = byteArrayOf(userId.toByte()) + nameBytes) // Set User Name
        Unit
    }

    /** Sets (or clears, with an empty string) [userId]'s password — always as a 16-byte legacy-format password (widely compatible; BMCs that need the 20-byte IPMI 2.0 extended format aren't targeted here). */
    suspend fun setUserPassword(userId: Int, password: String) = withContext(Dispatchers.IO) {
        val pwBytes = password.toByteArray(Charsets.UTF_8).copyOf(16)
        val data = byteArrayOf((userId and 0x3F).toByte(), 0x02) + pwBytes // operation 2 = Set Password
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x47, data = data) // Set User Password
        Unit
    }

    suspend fun setUserEnabled(userId: Int, enabled: Boolean) = withContext(Dispatchers.IO) {
        val data = byteArrayOf((userId and 0x3F).toByte(), if (enabled) 0x01 else 0x00) // operation 0=disable,1=enable
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x47, data = data) // Set User Password (disable/enable operation, no password bytes needed)
        Unit
    }

    private fun privilegeCodeLabel(code: Int): String = when (code) {
        0x01 -> "Callback"; 0x02 -> "User"; 0x03 -> "Operator"; 0x04 -> "Administrator"
        0x05 -> "OEM"; 0x0F -> "No Access"; else -> "Unknown"
    }

    // ── PEF (Platform Event Filtering) (NetFn 0x04 App) ─────────────────

    /** Reads whether PEF (the BMC's built-in alerting-on-sensor-events engine) is supported and currently enabled. Full alert-policy/filter-table editing is out of scope — this covers the master on/off switch. */
    suspend fun getPefStatus(): IpmiPefStatus = withContext(Dispatchers.IO) {
        val s = requireSession()
        val caps = try {
            s.sendIpmiRequest(netFn = 0x04, cmd = 0x10) // Get PEF Capabilities
        } catch (e: IpmiException) {
            return@withContext IpmiPefStatus(supported = false, pefEnabled = false, pefEventMessagesEnabled = false, version = "-", supportedActionsRaw = 0)
        }
        val version = "${(caps.getOrElse(0) { 0 }.toInt() and 0x0F)}.${(caps.getOrElse(0) { 0 }.toInt() shr 4) and 0x0F}"
        val actionsRaw = caps.getOrElse(1) { 0 }.toInt() and 0xFF
        val control = try {
            val r = s.sendIpmiRequest(netFn = 0x04, cmd = 0x13, data = byteArrayOf(0x01, 0x00, 0x00)) // Get PEF Configuration Parameters, param 1 = PEF Control
            r.getOrElse(1) { 0 }.toInt() and 0xFF
        } catch (e: IpmiException) { 0 }
        IpmiPefStatus(
            supported = true,
            pefEnabled = (control and 0x01) != 0,
            pefEventMessagesEnabled = (control and 0x02) != 0,
            version = version,
            supportedActionsRaw = actionsRaw,
        )
    }

    suspend fun setPefEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val s = requireSession()
        val current = try {
            val r = s.sendIpmiRequest(netFn = 0x04, cmd = 0x13, data = byteArrayOf(0x01, 0x00, 0x00))
            r.getOrElse(1) { 0 }.toInt() and 0xFF
        } catch (e: IpmiException) { 0 }
        val updated = if (enabled) (current or 0x01) else (current and 0x01.inv())
        s.sendIpmiRequest(netFn = 0x04, cmd = 0x12, data = byteArrayOf(0x01, updated.toByte())) // Set PEF Configuration Parameters, param 1
        Unit
    }

    // ── Watchdog Timer (NetFn 0x06 App) ─────────────────────────────────

    /** Reads the BMC's hardware watchdog timer state — countdowns are reported in seconds (the wire format is 100ms/decisecond units). */
    suspend fun getWatchdogConfig(): IpmiWatchdogConfig = withContext(Dispatchers.IO) {
        val r = requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x25) // Get Watchdog Timer
        val timerUseByte = r.getOrElse(0) { 0 }.toInt() and 0xFF
        val running = (timerUseByte and 0x40) != 0
        val use = IpmiWatchdogUse.entries.find { it.code == (timerUseByte and 0x07) } ?: IpmiWatchdogUse.OEM
        val actionByte = r.getOrElse(1) { 0 }.toInt() and 0xFF
        val action = IpmiWatchdogAction.entries.find { it.code == (actionByte and 0x07) } ?: IpmiWatchdogAction.NO_ACTION
        val pretimeout = r.getOrElse(2) { 0 }.toInt() and 0xFF
        val initialDs = (r.getOrElse(4) { 0 }.toInt() and 0xFF) or ((r.getOrElse(5) { 0 }.toInt() and 0xFF) shl 8)
        val presentDs = (r.getOrElse(6) { 0 }.toInt() and 0xFF) or ((r.getOrElse(7) { 0 }.toInt() and 0xFF) shl 8)
        IpmiWatchdogConfig(
            running = running, use = use, action = action,
            preTimeoutIntervalSeconds = pretimeout,
            initialCountdownSeconds = initialDs / 10.0,
            presentCountdownSeconds = presentDs / 10.0,
        )
    }

    /** Configures (but does not start/reset) the watchdog — call [resetWatchdog] afterward to actually start the countdown. */
    suspend fun setWatchdogConfig(use: IpmiWatchdogUse, action: IpmiWatchdogAction, countdownSeconds: Int, dontLog: Boolean = false) = withContext(Dispatchers.IO) {
        val countdownDs = (countdownSeconds * 10).coerceIn(0, 0xFFFF)
        val timerUseByte = (use.code and 0x07) or (if (dontLog) 0x80 else 0x00)
        val data = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(timerUseByte.toByte())
            .put((action.code and 0x07).toByte())
            .put(0x00) // pre-timeout interval seconds (0 = no pre-timeout interrupt)
            .put(0x00) // timer use expiration flags to clear — none
            .putShort(countdownDs.toShort())
            .array()
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x24, data = data) // Set Watchdog Timer
        Unit
    }

    /** Starts/restarts the countdown from the last-configured initial value (must call [setWatchdogConfig] first at least once, per spec). */
    suspend fun resetWatchdog() = withContext(Dispatchers.IO) {
        requireSession().sendIpmiRequest(netFn = 0x06, cmd = 0x22) // Reset Watchdog Timer
        Unit
    }

    // ── SOL (Serial-over-LAN) ───────────────────────────────────────────

    suspend fun openSolChannel(): IpmiSolChannel = withContext(Dispatchers.IO) {
        val s = requireSession()
        // Activate Payload (App / 0x06/0x48): payload type SOL (0x01), payload instance 1
        val data = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00)
        s.sendIpmiRequest(netFn = 0x06, cmd = 0x48, data = data)
        IpmiSolChannel(s)
    }

    // ── labels ───────────────────────────────────────────────────────

    private fun powerOnCauseLabel(code: Int): String = when (code) {
        0 -> "Unknown"
        1 -> "Power up via power switch"
        2 -> "Power restored after AC loss"
        3 -> "Power up via IPMI command"
        4 -> "Watchdog expiration"
        5 -> "OEM"
        6 -> "Power up after power fault"
        7 -> "Power cycle via IPMI"
        else -> "Reserved (0x${code.toString(16)})"
    }

    private fun sensorTypeLabel(code: Int): String = when (code) {
        0x01 -> "Temperature"; 0x02 -> "Voltage"; 0x04 -> "Fan"
        0x05 -> "Chassis intrusion"; 0x07 -> "Processor"; 0x08 -> "Power supply"
        0x09 -> "Power unit"; 0x0C -> "Memory"; 0x0F -> "Boot/POST"
        0x13 -> "Critical interrupt"; 0x23 -> "Watchdog"; 0x25 -> "Session audit"
        else -> "Type 0x${code.toString(16)}"
    }
}

/**
 * A SOL (Serial-over-LAN) console session — the IPMI equivalent of a serial
 * cable, used to see the BIOS POST screen / OS console before the OS's own
 * network is up. Byte-stream oriented; wrap in your terminal renderer the
 * same way [com.systemsgo.hex.telnet.protocol.TelnetClient] is used.
 */
class IpmiSolChannel internal constructor(private val session: IpmiSession) {
    private val seq = AtomicInteger(1)

    suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        // SOL payload: packet seq(1) | ack/nack seq(1) | accepted count(1) | operation/status(1) | data
        val header = byteArrayOf((seq.getAndIncrement() and 0x0F).toByte(), 0x00, 0x00, 0x00)
        session.sendSolPayload(header + data)
    }

    /**
     * SOL-FEATURE: sends a serial BREAK — the "Generate BREAK" bit (0x10) of the
     * Operation/Status byte in the remote-console→BMC SOL packet (IPMI 2.0 spec
     * §26.9, Table 26-1 "SOL Payload — Remote Console to BMC"), with a zero-length
     * character payload since BREAK is a control-only signal, not data. This is
     * what a physical BIOS/OS serial console redirection typically reacts to —
     * e.g. dropping into a bootloader/firmware menu, or (on Linux with
     * `sysrq_always_enabled`/serial SysRq wired up) a magic-SysRq trigger —
     * the closest SOL equivalent to "get the remote machine's attention" that
     * a raw async-serial link has, since there's no keyboard-controller-level
     * Ctrl+Alt+Del signal on a UART. AMT's SOL (see [com.systemsgo.hex.amt.protocol.AmtSolSession])
     * is a plain APF byte-stream channel with no such out-of-band control bit, so
     * this has no AMT counterpart — callers should only offer it for IPMI SOL.
     */
    suspend fun sendBreak() = withContext(Dispatchers.IO) {
        val header = byteArrayOf((seq.getAndIncrement() and 0x0F).toByte(), 0x00, 0x00, GENERATE_BREAK_BIT.toByte())
        session.sendSolPayload(header)
    }

    /** Suspends until a chunk of console output arrives, or returns null on timeout (poll again). */
    suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val raw = session.receiveSolPayload() ?: return@withContext null
        if (raw.size <= 4) return@withContext ByteArray(0)
        raw.copyOfRange(4, raw.size)
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        // Deactivate Payload (App / 0x06/0x49)
        try {
            session.sendIpmiRequest(netFn = 0x06, cmd = 0x49, data = byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00))
        } catch (_: IpmiException) { /* session may already be closing */ }
    }

    private companion object {
        const val GENERATE_BREAK_BIT = 0x10
    }
}
