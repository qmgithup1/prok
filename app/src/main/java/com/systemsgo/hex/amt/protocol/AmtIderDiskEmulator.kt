package com.systemsgo.hex.amt.protocol

import java.io.RandomAccessFile

/**
 * AMT-VPRO FEATURE phase 5 (IDE-R, verified half): a SCSI Primary Commands
 * (SPC) / Multi-Media Commands (MMC) processor for a virtual CD/DVD-ROM
 * backed by a local `.iso` file.
 *
 * [AmtIderSession]'s doc comment explains the real blocker for IDE-R: past
 * opening the APF channel, AMT expects *this app* to answer an ATA/ATAPI
 * command stream, and the message framing AMT uses to wrap that stream
 * over its redirection channel is Intel-proprietary and undocumented
 * outside the full AMT SDK's C headers — not something this app has a
 * verified copy of, and not something worth guessing at (a wrong envelope
 * byte silently mounts a "drive" that doesn't boot rather than failing
 * loudly).
 *
 * The *command set* itself, though, is a completely different, and fully
 * public, story: SPC/MMC are open T10 standards — the same commands every
 * USB/SATA optical drive, and every existing ISO-emulation tool, already
 * implements, with zero dependency on anything Intel-specific. Splitting
 * that verified half out into its own class means the *only* remaining gap
 * for real IDE-R media redirection is the envelope — this class is already
 * complete, self-contained, and independently testable (feed it raw CDB
 * bytes from any source — a unit test, a captured trace, or eventually a
 * decoded AMT envelope — and it returns correct SCSI data-in/status either
 * way), so wiring it into [AmtIderSession] the moment the envelope is
 * confirmed is a small, low-risk change rather than a fresh implementation.
 *
 * Scope: read-only virtual CD/DVD-ROM only — an ISO's normal use case
 * (OS install/recovery/live-boot media), and the case Intel's own IDE-R
 * examples focus on. Virtual floppy/HDD redirection uses the plain ATA
 * command set (IDENTIFY DEVICE / READ SECTORS, no CDB/PACKET wrapping) —
 * a different command set, deliberately out of scope here.
 */
class AmtIderDiskEmulator(isoFile: java.io.File) : AutoCloseable {

    init {
        require(isoFile.isFile) { "IDE-R: ${isoFile.path} is not a file" }
    }

    private val raf = RandomAccessFile(isoFile, "r")

    /** MMC's fixed 2048-byte logical block size — the ISO 9660 sector
     *  size, and the only block size a CD/DVD-ROM SCSI target reports. */
    private val sectorSize = 2048
    private val sectorCount: Long = raf.length() / sectorSize

    data class ScsiResult(
        val data: ByteArray = ByteArray(0),
        val statusGood: Boolean = true,
        val senseKey: Int = SENSE_NO_SENSE,
        val senseAsc: Int = 0,
    )

    /** SENSE FIX (item 12 follow-up): SPC's REQUEST SENSE is defined to report
     *  the sense data left behind by the *previous* command on this same
     *  logical unit, then clear back to "no sense" once read — it does not
     *  take sense info from itself. [process] now records that here whenever
     *  a command fails, and [requestSenseData] consumes-and-clears it,
     *  instead of unconditionally reporting "no sense" regardless of what
     *  actually just failed. */
    private var pendingSenseKey: Int = SENSE_NO_SENSE
    private var pendingSenseAsc: Int = 0

    /**
     * Dispatches one SCSI Command Descriptor Block — the same bytes that
     * would arrive as the payload of an ATAPI PACKET command — and returns
     * the data-in phase (if any) plus status/sense. Deliberately doesn't
     * care how [cdb] arrived; that's the caller's (eventually
     * [AmtIderSession]'s) concern once the envelope is known.
     */
    fun process(cdb: ByteArray): ScsiResult {
        val result = if (cdb.isEmpty()) {
            ScsiResult(statusGood = false, senseKey = SENSE_ILLEGAL_REQUEST, senseAsc = ASC_INVALID_COMMAND)
        } else {
            try {
                when (cdb[0].toInt() and 0xFF) {
                    OP_TEST_UNIT_READY -> ScsiResult(statusGood = true)
                    OP_REQUEST_SENSE -> ScsiResult(data = requestSenseData())
                    OP_INQUIRY -> ScsiResult(data = inquiryData())
                    OP_READ_CAPACITY_10 -> ScsiResult(data = readCapacity10())
                    OP_MODE_SENSE_10 -> ScsiResult(data = modeSense10())
                    OP_READ_10 -> readSectors(lba = readBe32(cdb, 2), count = readBe16(cdb, 7), cdbLen = 10, cdb = cdb)
                    OP_READ_12 -> readSectors(lba = readBe32(cdb, 2), count = readBe32(cdb, 6), cdbLen = 12, cdb = cdb)
                    // ARCH DECISION (IDE-R write support): the CD/DVD-ROM side
                    // stays read-only, matching real optical media — a real
                    // CD/DVD-ROM SCSI target has no WRITE(10)/WRITE(12)
                    // implementation to fall back to, so an initiator that
                    // issues one is expected to get ILLEGAL_REQUEST, not a
                    // fake success. Called out explicitly (rather than
                    // silently landing in the generic `else` below, which
                    // already returns the same sense/ASC) so the "ISOs are
                    // never writable" decision is visible in the command
                    // table itself. See AmtIderFloppyEmulator for the media
                    // type that *does* support writes.
                    OP_WRITE_10, OP_WRITE_12 -> ScsiResult(statusGood = false, senseKey = SENSE_ILLEGAL_REQUEST, senseAsc = ASC_INVALID_COMMAND)
                    else -> ScsiResult(statusGood = false, senseKey = SENSE_ILLEGAL_REQUEST, senseAsc = ASC_INVALID_COMMAND)
                }
            } catch (e: Exception) {
                ScsiResult(statusGood = false, senseKey = SENSE_ABORTED_COMMAND)
            }
        }
        // Latch sense for the *next* REQUEST SENSE — but a REQUEST SENSE
        // command itself must not overwrite what it just reported (it
        // already consumed/cleared the pending sense inside requestSenseData()).
        if (cdb.isNotEmpty() && (cdb[0].toInt() and 0xFF) != OP_REQUEST_SENSE && !result.statusGood) {
            pendingSenseKey = result.senseKey
            pendingSenseAsc = result.senseAsc
        }
        return result
    }

    // ── individual command responses ────────────────────────────────────

    private fun inquiryData(): ByteArray {
        // Standard INQUIRY data (SPC), 36 bytes — the minimum every
        // initiator actually reads before issuing anything else.
        val buf = ByteArray(36)
        buf[0] = 0x05          // Peripheral device type: CD/DVD
        buf[1] = 0x80.toByte() // RMB=1: removable medium
        buf[2] = 0x00          // Version: does not claim SPC compliance level
        buf[3] = 0x02          // Response data format
        buf[4] = (36 - 5).toByte() // Additional length
        writeAscii(buf, 8, "SYSTEMSGO", 8)
        writeAscii(buf, 16, "Virtual CD-ROM", 16)
        writeAscii(buf, 32, "1.0", 4)
        return buf
    }

    private fun requestSenseData(): ByteArray {
        // Fixed-format sense data (SPC), 18 bytes. SENSE FIX (item 12
        // follow-up): reports whatever [process] latched from the command
        // that failed immediately before this REQUEST SENSE — and per SPC,
        // reading it clears it back to "no sense" (a second REQUEST SENSE
        // in a row correctly reports nothing left to report).
        val buf = ByteArray(18)
        buf[0] = 0x70 // Response code: current errors, fixed format
        buf[2] = pendingSenseKey.toByte()
        buf[7] = (18 - 8).toByte() // Additional sense length
        buf[12] = pendingSenseAsc.toByte() // ASC — a plain single-byte code, matching
                                            // how ASC_INVALID_COMMAND/ASC_LBA_OUT_OF_RANGE
                                            // are already defined below (no ASCQ-distinguishing
                                            // case exists in this emulator, so ASCQ stays 0)
        buf[13] = 0                        // ASCQ
        pendingSenseKey = SENSE_NO_SENSE
        pendingSenseAsc = 0
        return buf
    }

    private fun readCapacity10(): ByteArray {
        val buf = ByteArray(8)
        val lastLba = (sectorCount - 1).coerceAtLeast(0)
        writeBe32(buf, 0, lastLba.toInt())
        writeBe32(buf, 4, sectorSize)
        return buf
    }

    private fun modeSense10(): ByteArray {
        // Minimal 8-byte MODE SENSE(10) header, no mode pages — enough for
        // the "is a disc present / what's the block size" checks most
        // initiators actually rely on this command for during ISO
        // redirection, without needing to model specific mode pages
        // (write-protect, CD audio, etc.) this read-only target doesn't use.
        val buf = ByteArray(8)
        writeBe16(buf, 0, 6) // Mode data length (bytes following this field)
        buf[2] = 0x05        // Medium type: CD-ROM
        return buf
    }

    private fun readSectors(lba: Long, count: Int, cdbLen: Int, cdb: ByteArray): ScsiResult {
        if (cdb.size < cdbLen) return ScsiResult(statusGood = false, senseKey = SENSE_ILLEGAL_REQUEST, senseAsc = ASC_INVALID_COMMAND)
        if (lba < 0 || count <= 0 || lba + count > sectorCount) {
            return ScsiResult(statusGood = false, senseKey = SENSE_ILLEGAL_REQUEST, senseAsc = ASC_LBA_OUT_OF_RANGE)
        }
        val out = ByteArray(count * sectorSize)
        synchronized(raf) {
            raf.seek(lba * sectorSize)
            raf.readFully(out)
        }
        return ScsiResult(data = out)
    }

    override fun close() {
        runCatching { raf.close() }
    }

    // ── byte-order helpers ───────────────────────────────────────────────

    private fun writeAscii(buf: ByteArray, offset: Int, text: String, len: Int) {
        val bytes = text.padEnd(len).take(len).toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, offset, len)
    }

    private fun writeBe16(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v shr 8).toByte(); buf[offset + 1] = v.toByte()
    }

    private fun writeBe32(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v shr 24).toByte(); buf[offset + 1] = (v shr 16).toByte()
        buf[offset + 2] = (v shr 8).toByte(); buf[offset + 3] = v.toByte()
    }

    private fun readBe16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readBe32(b: ByteArray, o: Int): Long =
        (((b[o].toInt() and 0xFF).toLong() shl 24) or ((b[o + 1].toInt() and 0xFF).toLong() shl 16) or
            ((b[o + 2].toInt() and 0xFF).toLong() shl 8) or (b[o + 3].toInt() and 0xFF).toLong())

    companion object {
        // SCSI operation codes (SPC/MMC) — public T10 standard values.
        private const val OP_TEST_UNIT_READY = 0x00
        private const val OP_REQUEST_SENSE = 0x03
        private const val OP_INQUIRY = 0x12
        private const val OP_MODE_SENSE_10 = 0x5A
        private const val OP_READ_CAPACITY_10 = 0x25
        private const val OP_READ_10 = 0x28
        private const val OP_READ_12 = 0xA8
        // Deliberately unimplemented — see the OP_WRITE_10/OP_WRITE_12 case
        // in process()'s `when`. Kept as named constants (not left to the
        // generic `else`) so the read-only decision is explicit and
        // greppable, not an accident of an unhandled opcode.
        private const val OP_WRITE_10 = 0x2A
        private const val OP_WRITE_12 = 0xAA

        // SCSI sense keys / additional sense codes (SPC), public standard values.
        private const val SENSE_NO_SENSE = 0x00
        private const val SENSE_ILLEGAL_REQUEST = 0x05
        private const val SENSE_ABORTED_COMMAND = 0x0B
        private const val ASC_INVALID_COMMAND = 0x20
        private const val ASC_LBA_OUT_OF_RANGE = 0x21
    }
}
