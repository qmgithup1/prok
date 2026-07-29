package com.systemsgo.hex.amt.protocol

import java.io.RandomAccessFile
import kotlin.math.ceil

/**
 * AMT-VPRO FEATURE phase 5 (IDE-R), the floppy half that AMT_VPRO_ROADMAP.md
 * flagged as "still not done": an ATA (not ATAPI/SCSI) command processor for
 * a virtual floppy drive backed by a local `.img` file.
 *
 * ## Why this is a separate class from [AmtIderDiskEmulator]
 * IDE-R exposes two independent devices on the same virtual IDE channel —
 * a floppy (ATA "master", `IDERBootDevice`/[AmtIderMediaType] value `0`) and
 * a CD/DVD-ROM (ATAPI "slave", value `1`) — mirroring a real IDE bus where
 * master/slave devices can be a plain ATA disk and an ATAPI optical drive
 * side by side (confirmed generically for IDE-R by Intel's own "Healing the
 * Platforms" AMT book, ch. 9: "these two devices are a standard floppy disk
 * drive and a standard CD-ROM drive"). ATAPI devices (the CD-ROM side)
 * answer SCSI Command Descriptor Blocks wrapped in an ATA PACKET command —
 * [AmtIderDiskEmulator]'s job. Plain ATA devices (the floppy side) have no
 * CDB/PACKET concept at all: the command *is* the ATA register file itself
 * (Features/SectorCount/LBA low-mid-high/Device-Head/Command, the classic
 * 7-register IDE task file) — a completely different, and completely
 * public, T13 (not SCSI/T10) command set: IDENTIFY DEVICE, READ SECTORS,
 * INITIALIZE DEVICE PARAMETERS, and friends.
 *
 * ## Where this command data comes from in [AmtIderSession]'s envelope
 * [AmtIderSession]'s `CMD_COMMAND_WRITTEN` handler was already parsing two
 * fields out of the 20-byte message body *before* this class existed —
 * `featureRegister` at body offset 1 (message offset 9) and the
 * device-select bit at body offset 6 (message offset 14, tested as
 * `and 0x10`, the standard ATA task file's DRV bit) — both used only to
 * decide CD-ROM vs floppy and DMA vs PIO. Those two offsets are not
 * arbitrary: offsets 9-15 are exactly the 7 IDE task-file registers in
 * their standard wire order — Features(9), Sector Count(10), LBA Low(11),
 * LBA Mid(12), LBA High(13), Device/Head(14), Command(15) — which is why
 * the device-select bit the existing code already relies on lives at
 * exactly the offset (14) a Device/Head register would. The separate
 * 12-byte field at offsets 16-27 that [AmtIderDiskEmulator] consumes as a
 * SCSI CDB is specific to the ATAPI PACKET command's payload and carries no
 * meaning for a plain ATA (floppy) command.
 *
 * **Flagged explicitly, matching this codebase's practice of separating
 * confirmed-from-source facts from engineering inference:** the envelope
 * *framing* above (message boundaries, sequence numbers, DATA_TO_HOST/
 * COMMAND_END_RESPONSE) is the same byte-for-byte-transcribed envelope
 * [AmtIderSession] already uses for the CD-ROM path. What's inferred here —
 * not transcribed from a source with a floppy example — is that offsets
 * 9-15 hold the task file in standard IDE register order; that inference
 * rests on public T13 documentation of what those seven registers mean and
 * the order they appear on a real IDE bus, plus the two offsets
 * [AmtIderSession] was already using being exactly consistent with it, not
 * on a confirmed floppy-specific trace. If a real firmware capture ever
 * disagrees, only [AmtIderSession]'s `CMD_COMMAND_WRITTEN` field extraction
 * needs to change — this class only depends on receiving a correctly
 * decoded [AtaTaskFile], not on the wire layout.
 *
 * ## Write support (opt-in, floppy/HDD-image side only)
 * ARCH DECISION (IDE-R write support): unlike [AmtIderDiskEmulator]'s
 * CD/DVD-ROM side — which stays read-only permanently, matching real
 * optical media that was never writable to begin with — a `.img`
 * floppy/HDD image mounted here is the actually-writable half of IDE-R:
 * a real use case is scratch space during a recovery boot, driver
 * injection, or writing back a log, not just read-only OS-install media.
 * Writing is **opt-in** via the [writable] constructor parameter, default
 * `false`, so mounting an existing image never silently starts accepting
 * writes that could corrupt it — a caller has to explicitly ask for a
 * writable mount (see [AmtIderSession.mountAndServe] and
 * `BmcManagementActivity.amtMountIderMedia`). WRITE SECTORS / WRITE
 * SECTORS NO RETRY (0x30/0x31) are implemented; their 48-bit LBA
 * counterparts are deliberately excluded for the same reason the read side
 * excludes them (meaningless for a floppy image that never exceeds a few
 * MB — see below), and WRITE DMA (0xCA/0xCB) is left unimplemented, same
 * as before this change, since nothing in this app's IDE-R flow issues it.
 *
 * Scope: implements the small set of ATA-3/ATA-4 commands a real BIOS or OS
 * floppy driver actually issues during boot/DOS-style access: IDENTIFY
 * DEVICE, READ SECTOR(S) (PIO and DMA), READ VERIFY SECTOR(S), WRITE
 * SECTOR(S) (PIO, opt-in — see above), INITIALIZE DEVICE PARAMETERS,
 * RECALIBRATE, SEEK, SET FEATURES, and EXECUTE DEVICE DIAGNOSTIC — every
 * command class a pre-boot/real-mode INT13h-style consumer needs,
 * deliberately excluding FORMAT and 48-bit LBA (meaningless for a floppy
 * image that never exceeds a few MB).
 */
class AmtIderFloppyEmulator(
    imageFile: java.io.File,
    /** Opt-in write support — see this class's top doc comment. `false`
     *  (the default) opens [imageFile] read-only, exactly as before this
     *  parameter existed, so any existing caller/mount is unaffected. */
    private val writable: Boolean = false,
) : AutoCloseable {

    init {
        require(imageFile.isFile) { "IDE-R: ${imageFile.path} is not a file" }
    }

    private val raf = RandomAccessFile(imageFile, if (writable) "rw" else "r")

    /** The standard IBM-PC floppy sector size — the only size any BIOS or
     *  OS floppy driver this emulates ever assumes. */
    private val sectorSize = 512
    private val totalSectors: Long = raf.length() / sectorSize

    /** CHS geometry. Starts at the standard geometry for the image's exact
     *  size (matching a real 160K-2.88M floppy format table) or a generic
     *  fallback derived from size; [handleInitializeDeviceParameters] lets
     *  the guest override heads/sectors-per-track, exactly like a real
     *  floppy controller's INITIALIZE DEVICE PARAMETERS command, in case
     *  its own translation disagrees. */
    private var heads: Int
    private var sectorsPerTrack: Int
    private var cylinders: Int

    init {
        val geometry = standardGeometryFor(totalSectors) ?: run {
            val h = 2
            val spt = 18
            val cyl = ceil(totalSectors.toDouble() / (h * spt)).toInt().coerceAtLeast(1)
            Triple(cyl, h, spt)
        }
        cylinders = geometry.first
        heads = geometry.second
        sectorsPerTrack = geometry.third
    }

    /** One decoded 7-register IDE task file — see this class's top doc
     *  comment for where [AmtIderSession] reads these from on the wire. */
    data class AtaTaskFile(
        val features: Int,
        val sectorCount: Int,
        val lbaLow: Int,
        val lbaMid: Int,
        val lbaHigh: Int,
        val deviceHead: Int,
        val command: Int,
    ) {
        /** Standard ATA Device/Head register bit 6 ("L") selects LBA
         *  addressing over CHS. */
        val lbaMode: Boolean get() = (deviceHead and 0x40) != 0
    }

    data class AtaResult(
        val data: ByteArray = ByteArray(0),
        val statusGood: Boolean = true,
        /** SENSE FIX (item 12): [AmtIderSession.dispatchAta] now forwards
         *  this back to AMT via `sendCommandError` on failure, the same as
         *  the CD-ROM/SCSI path's senseKey/senseAsc — see that function's
         *  doc comment. Values are the `ATA_ERR_*` constants below. */
        val errorRegister: Int = 0,
    )

    /** Dispatches one decoded ATA task file and returns the data-in phase
     *  (if any) plus status. Deliberately doesn't care how [taskFile]
     *  arrived — same separation of concerns as
     *  [AmtIderDiskEmulator.process]. */
    fun process(taskFile: AtaTaskFile): AtaResult {
        return try {
            when (taskFile.command) {
                CMD_IDENTIFY_DEVICE -> AtaResult(data = identifyDeviceData())
                // FIX (item 8): READ DMA/READ DMA NO RETRY were previously
                // unhandled — this class only implemented the PIO read
                // opcodes, and [AmtIderSession.dispatchAta] separately
                // assumed DMA was never requested on this path (see its
                // comment, now updated alongside this). The task file
                // layout and returned sector data are identical between the
                // PIO and DMA READ SECTORS opcodes — the two only differ in
                // how a *real* controller moves the bytes onto the bus, and
                // this emulator produces its data-in buffer synchronously
                // in memory regardless — so it's the same handler.
                CMD_READ_SECTORS, CMD_READ_SECTORS_NO_RETRY, CMD_READ_DMA, CMD_READ_DMA_NO_RETRY -> readSectors(taskFile)
                CMD_READ_VERIFY_SECTORS, CMD_READ_VERIFY_SECTORS_NO_RETRY -> verifySectors(taskFile)
                // WRITE SECTORS (0x30/0x31) is deliberately NOT handled here.
                // Unlike every command above, a write has a data-*out* phase:
                // the payload isn't part of the ATA task file at all, it
                // arrives in a separate, later envelope message (AMT's
                // CMD_DATA_FROM_HOST — see [AmtIderSession]'s doc comment on
                // its CMD_COMMAND_WRITTEN handler). So [AmtIderSession] never
                // routes a write task file through [process]/[isDmaCommand]
                // like it does every other command — it recognizes a write
                // opcode via [isWriteCommand], holds the task file as a
                // pending write, accumulates [writeByteCount] bytes of
                // payload across one or more CMD_DATA_FROM_HOST messages,
                // then calls [writeSectors] directly with both. If [process]
                // is ever called with a write task file by mistake, it falls
                // through to the same `else` as any other unimplemented
                // opcode (ABRT) rather than silently mis-handling it.
                CMD_INITIALIZE_DEVICE_PARAMETERS -> handleInitializeDeviceParameters(taskFile)
                CMD_RECALIBRATE_BASE -> AtaResult(statusGood = true) // 0x1X: recalibrate, no seek state kept
                CMD_SEEK_BASE -> AtaResult(statusGood = true) // 0x7X: seek, no seek state kept
                CMD_SET_FEATURES -> AtaResult(statusGood = true) // transfer-mode/caching knobs — irrelevant to a read-only image
                CMD_EXECUTE_DEVICE_DIAGNOSTIC -> AtaResult(statusGood = true) // "device 0 passed, no device 1" is the healthy single-drive result
                CMD_IDLE, CMD_IDLE_IMMEDIATE, CMD_STANDBY, CMD_STANDBY_IMMEDIATE, CMD_CHECK_POWER_MODE ->
                    AtaResult(statusGood = true) // power management no-ops — this virtual drive has no motor to spin down
                else -> AtaResult(statusGood = false, errorRegister = ATA_ERR_ABRT)
            }
        } catch (e: Exception) {
            AtaResult(statusGood = false, errorRegister = ATA_ERR_ABRT)
        }
    }

    /** True for the ATA opcodes whose data-in phase moves over DMA on real
     *  hardware — currently just READ DMA / READ DMA NO RETRY. [process]
     *  doesn't need this distinction (it fills the same in-memory buffer
     *  either way, see the DMA case above), but [AmtIderSession] does: the
     *  envelope's data-to-host framing has a real DMA-vs-PIO bit AMT expects
     *  to match the opcode the guest issued. */
    fun isDmaCommand(command: Int): Boolean = command == CMD_READ_DMA || command == CMD_READ_DMA_NO_RETRY

    /** True for the ATA write opcodes this emulator implements — see the
     *  "WRITE SECTORS is deliberately not handled" comment in [process] for
     *  why [AmtIderSession] checks this instead of routing writes through
     *  [process] like every other command. */
    fun isWriteCommand(command: Int): Boolean = command == CMD_WRITE_SECTORS || command == CMD_WRITE_SECTORS_NO_RETRY

    /** Expected data-out payload size, in bytes, for a pending write
     *  [taskFile] — same "Sector Count of 0 means 256" quirk as
     *  [readSectors]/[verifySectors]. [AmtIderSession] accumulates exactly
     *  this many bytes (possibly split across several CMD_DATA_FROM_HOST
     *  envelope messages) before calling [writeSectors]. */
    fun writeByteCount(taskFile: AtaTaskFile): Int {
        val count = if (taskFile.sectorCount == 0) 256 else taskFile.sectorCount
        return count * sectorSize
    }

    /** WRITE SECTORS / WRITE SECTORS NO RETRY (0x30/0x31) — the write
     *  counterpart to [readSectors], taking the already-fully-collected
     *  [data] payload (see [writeByteCount]) alongside the task file that
     *  described where to put it. Rejects with [ATA_ERR_ABRT] — a real,
     *  well-defined ATA error, rather than an uncaught exception — if this
     *  emulator wasn't constructed with [writable] set, exactly like a real
     *  write-protected floppy would report the attempt. */
    fun writeSectors(taskFile: AtaTaskFile, data: ByteArray): AtaResult {
        if (!writable) return AtaResult(statusGood = false, errorRegister = ATA_ERR_ABRT)
        val lba = resolveLba(taskFile) ?: return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        val count = if (taskFile.sectorCount == 0) 256 else taskFile.sectorCount
        if (lba < 0 || lba + count > totalSectors) {
            return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        }
        val expected = count * sectorSize
        if (data.size < expected) {
            // Caller (AmtIderSession) is expected to have already collected
            // exactly writeByteCount(taskFile) bytes before calling this —
            // a short buffer here means an envelope-layer bug, not a normal
            // device condition, but ABRT (rather than throwing) keeps this
            // function's contract the same "never throws for a malformed
            // request" shape as every other command here.
            return AtaResult(statusGood = false, errorRegister = ATA_ERR_ABRT)
        }
        synchronized(raf) {
            raf.seek(lba * sectorSize)
            raf.write(data, 0, expected)
        }
        return AtaResult(statusGood = true)
    }

    // ── individual command responses ────────────────────────────────────

    /** IDENTIFY DEVICE (0xEC): 256 words / 512 bytes. Populates only the
     *  fields a real BIOS/DOS-era floppy driver actually reads before
     *  issuing READ SECTORS — CHS geometry, LBA total-sector count and
     *  capability bit, and identification strings — the same "minimum any
     *  initiator relies on" scope [AmtIderDiskEmulator.inquiryData] uses for
     *  SCSI INQUIRY. */
    private fun identifyDeviceData(): ByteArray {
        val words = IntArray(256)
        words[0] = 0x0080 // General config: ATA device (bit15=0), removable media device (bit7=1)
        words[1] = cylinders and 0xFFFF
        words[3] = heads and 0xFFFF
        words[6] = sectorsPerTrack and 0xFFFF
        writeAtaString(words, 10, 20, "SYSTEMSGOIDER00000001") // Serial number
        writeAtaString(words, 23, 8, "1.0")                 // Firmware revision
        writeAtaString(words, 27, 40, "SystemsGo Virtual Floppy Disk") // Model number
        words[47] = 0 // Read/Write Multiple not supported — this emulator only implements single-sector PIO
        words[49] = 1 shl 9 // Capabilities: bit9 = LBA supported
        words[53] = 0x0001 // bit0: words 54-58 (current CHS translation) are valid
        words[54] = cylinders and 0xFFFF
        words[55] = heads and 0xFFFF
        words[56] = sectorsPerTrack and 0xFFFF
        val currentCapacitySectors = (cylinders.toLong() * heads * sectorsPerTrack)
        words[57] = (currentCapacitySectors and 0xFFFF).toInt()
        words[58] = ((currentCapacitySectors shr 16) and 0xFFFF).toInt()
        words[60] = (totalSectors and 0xFFFF).toInt()      // Total addressable sectors (LBA28), low word
        words[61] = ((totalSectors shr 16) and 0xFFFF).toInt() // high word

        val buf = ByteArray(512)
        for (i in 0 until 256) {
            // ATA IDENTIFY words are transmitted little-endian on the wire,
            // same as every other multi-byte field in this file — distinct
            // from the byte-swapped-pair convention used only for the ASCII
            // string fields themselves (handled in writeAtaString).
            buf[i * 2] = (words[i] and 0xFF).toByte()
            buf[i * 2 + 1] = ((words[i] shr 8) and 0xFF).toByte()
        }
        return buf
    }

    private fun readSectors(taskFile: AtaTaskFile): AtaResult {
        val lba = resolveLba(taskFile) ?: return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        // ATA quirk: a Sector Count of 0 means 256 sectors, not zero.
        val count = if (taskFile.sectorCount == 0) 256 else taskFile.sectorCount
        if (lba < 0 || lba + count > totalSectors) {
            return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        }
        val out = ByteArray(count * sectorSize)
        synchronized(raf) {
            raf.seek(lba * sectorSize)
            raf.readFully(out)
        }
        return AtaResult(data = out)
    }

    private fun verifySectors(taskFile: AtaTaskFile): AtaResult {
        val lba = resolveLba(taskFile) ?: return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        val count = if (taskFile.sectorCount == 0) 256 else taskFile.sectorCount
        if (lba < 0 || lba + count > totalSectors) {
            return AtaResult(statusGood = false, errorRegister = ATA_ERR_IDNF)
        }
        return AtaResult(statusGood = true) // no data-in phase — verify only checks the sectors exist/are readable
    }

    /** INITIALIZE DEVICE PARAMETERS (0x91): the guest's chosen CHS
     *  translation — sector count register = sectors/track, Device/Head
     *  register bits 0-3 = (heads-1). Real floppy controllers accept this
     *  to override their default translation; mirrored here so a guest
     *  BIOS/driver that insists on a different (but still capacity-valid)
     *  geometry than [standardGeometryFor]'s guess gets what it asked for
     *  rather than silently continuing to use ours. */
    private fun handleInitializeDeviceParameters(taskFile: AtaTaskFile): AtaResult {
        val requestedSpt = if (taskFile.sectorCount == 0) 1 else taskFile.sectorCount
        val requestedHeads = (taskFile.deviceHead and 0x0F) + 1
        if (requestedSpt > 0 && requestedHeads > 0) {
            sectorsPerTrack = requestedSpt
            heads = requestedHeads
            cylinders = ceil(totalSectors.toDouble() / (heads * sectorsPerTrack)).toInt().coerceAtLeast(1)
        }
        return AtaResult(statusGood = true)
    }

    /** Resolves a task file's addressing (LBA or CHS, per the Device/Head
     *  register's LBA bit) into a flat sector offset, or null if a CHS
     *  address is out of range for the current geometry. */
    private fun resolveLba(taskFile: AtaTaskFile): Long? {
        if (taskFile.lbaMode) {
            return (((taskFile.deviceHead and 0x0F).toLong() shl 24) or
                (taskFile.lbaHigh.toLong() shl 16) or
                (taskFile.lbaMid.toLong() shl 8) or
                taskFile.lbaLow.toLong())
        }
        // CHS: Cylinder = (LBA High:LBA Mid), Head = Device/Head bits 0-3,
        // Sector = LBA Low, and — unlike LBA — Sector is 1-based, not 0-based.
        val cylinder = (taskFile.lbaHigh shl 8) or taskFile.lbaMid
        val head = taskFile.deviceHead and 0x0F
        val sector = taskFile.lbaLow
        if (sector < 1 || sector > sectorsPerTrack || head >= heads || cylinder >= cylinders) return null
        return (cylinder.toLong() * heads + head) * sectorsPerTrack + (sector - 1)
    }

    override fun close() {
        runCatching { raf.close() }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Writes an ASCII string into IDENTIFY DEVICE's word array using the
     *  ATA spec's byte-swapped-pair string convention (first character in
     *  the high byte of the first word), space-padded/truncated to
     *  [lengthChars]. [lengthChars] must be even, matching every ATA string
     *  field's fixed word-pair length. */
    private fun writeAtaString(words: IntArray, wordOffset: Int, lengthChars: Int, text: String) {
        val padded = text.padEnd(lengthChars).take(lengthChars)
        var i = 0
        var w = wordOffset
        while (i < lengthChars) {
            val hi = padded[i].code and 0xFF
            val lo = if (i + 1 < lengthChars) padded[i + 1].code and 0xFF else 0x20
            words[w] = (hi shl 8) or lo
            i += 2
            w += 1
        }
    }

    companion object {
        // ATA command codes (T13, public standard values) this emulator
        // implements — the subset a pre-boot/real-mode floppy consumer
        // actually issues.
        private const val CMD_RECALIBRATE_BASE = 0x10 // 0x10-0x1F: RECALIBRATE (low nibble historically a step-rate hint, ignored)
        private const val CMD_READ_SECTORS = 0x20
        private const val CMD_READ_SECTORS_NO_RETRY = 0x21
        private const val CMD_READ_DMA = 0xC8 // FIX (item 8): was previously unhandled — see process()
        private const val CMD_READ_DMA_NO_RETRY = 0xC9
        private const val CMD_READ_VERIFY_SECTORS = 0x40
        private const val CMD_READ_VERIFY_SECTORS_NO_RETRY = 0x41
        // IDE-R write support: PIO writes only (see this class's top doc
        // comment) — the 48-bit LBA write opcodes (0x34/0x35) and WRITE DMA
        // (0xCA/0xCB) are deliberately excluded, same reasoning as the read
        // side's equivalent omissions.
        private const val CMD_WRITE_SECTORS = 0x30
        private const val CMD_WRITE_SECTORS_NO_RETRY = 0x31
        private const val CMD_SEEK_BASE = 0x70 // 0x70-0x7F: SEEK (low nibble historically a step-rate hint, ignored)
        private const val CMD_EXECUTE_DEVICE_DIAGNOSTIC = 0x90
        private const val CMD_INITIALIZE_DEVICE_PARAMETERS = 0x91
        private const val CMD_IDLE_IMMEDIATE = 0xE1
        private const val CMD_IDLE = 0xE3
        private const val CMD_CHECK_POWER_MODE = 0xE5
        private const val CMD_STANDBY_IMMEDIATE = 0xE0
        private const val CMD_STANDBY = 0xE2
        private const val CMD_IDENTIFY_DEVICE = 0xEC
        private const val CMD_SET_FEATURES = 0xEF

        // ATA Error register bits (T13, public standard values) — kept for
        // [AtaResult.errorRegister] logging only; see that field's doc comment.
        private const val ATA_ERR_IDNF = 0x10 // ID Not Found — requested CHS/LBA sector doesn't exist
        private const val ATA_ERR_ABRT = 0x04 // Aborted Command — unsupported/invalid command

        /** Standard IBM-PC floppy format table (size in sectors → CHS),
         *  every capacity a real floppy controller/BIOS recognizes by exact
         *  size. Returns null for anything else, so the caller falls back
         *  to a generic geometry derived from the actual file size. */
        private fun standardGeometryFor(totalSectors: Long): Triple<Int, Int, Int>? = when (totalSectors) {
            320L -> Triple(40, 1, 8)     // 160K
            360L -> Triple(40, 1, 9)     // 180K
            640L -> Triple(40, 2, 8)     // 320K
            720L -> Triple(40, 2, 9)     // 360K
            1440L -> Triple(80, 2, 9)    // 720K
            2400L -> Triple(80, 2, 15)   // 1.2M
            2880L -> Triple(80, 2, 18)   // 1.44M
            5760L -> Triple(80, 2, 36)   // 2.88M
            else -> null
        }
    }
}
