package com.systemsgo.hex.amt.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AMT-VPRO FEATURE phase 5 (IDE-R floppy): plain-JVM tests for
 * [AmtIderFloppyEmulator], feeding it hand-built [AmtIderFloppyEmulator.AtaTaskFile]
 * values directly — no envelope/socket involved, matching the same
 * "feed it raw commands from a unit test" independence
 * [AmtIderDiskEmulator]'s doc comment describes.
 */
class AmtIderFloppyEmulatorTest {

    /** A 1.44MB image (2880 * 512 bytes), each sector filled with its own
     *  sector index so reads can be checked byte-for-byte. */
    private fun make144Image(): File {
        val f = File.createTempFile("systemsgo-ider-floppy-test", ".img")
        f.deleteOnExit()
        f.outputStream().use { out ->
            for (sector in 0 until 2880) {
                out.write(ByteArray(512) { (sector and 0xFF).toByte() })
            }
        }
        return f
    }

    private fun lbaTaskFile(command: Int, lba: Long, sectorCount: Int): AmtIderFloppyEmulator.AtaTaskFile =
        AmtIderFloppyEmulator.AtaTaskFile(
            features = 0,
            sectorCount = sectorCount,
            lbaLow = (lba and 0xFF).toInt(),
            lbaMid = ((lba shr 8) and 0xFF).toInt(),
            lbaHigh = ((lba shr 16) and 0xFF).toInt(),
            deviceHead = 0x40 or ((lba shr 24) and 0x0F).toInt(), // bit6 = LBA mode
            command = command,
        )

    private fun chsTaskFile(command: Int, cylinder: Int, head: Int, sector: Int, sectorCount: Int): AmtIderFloppyEmulator.AtaTaskFile =
        AmtIderFloppyEmulator.AtaTaskFile(
            features = 0,
            sectorCount = sectorCount,
            lbaLow = sector, // 1-based in CHS mode
            lbaMid = cylinder and 0xFF,
            lbaHigh = (cylinder shr 8) and 0xFF,
            deviceHead = head and 0x0F, // bit6 clear = CHS mode
            command = command,
        )

    @Test
    fun `IDENTIFY DEVICE reports 1_44M CHS geometry and LBA sector count`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        val result = emulator.process(AmtIderFloppyEmulator.AtaTaskFile(0, 0, 0, 0, 0, 0, command = 0xEC))
        assertTrue(result.statusGood)
        assertEquals(512, result.data.size)

        fun word(i: Int): Int = (result.data[i * 2].toInt() and 0xFF) or ((result.data[i * 2 + 1].toInt() and 0xFF) shl 8)

        assertEquals(80, word(1))  // cylinders
        assertEquals(2, word(3))   // heads
        assertEquals(18, word(6))  // sectors per track
        assertTrue((word(49) and (1 shl 9)) != 0) // LBA supported
        val totalSectors = word(60) or (word(61) shl 16)
        assertEquals(2880, totalSectors)
        emulator.close()
    }

    @Test
    fun `READ SECTORS by LBA returns the requested sectors`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        val result = emulator.process(lbaTaskFile(command = 0x20, lba = 5, sectorCount = 2))
        assertTrue(result.statusGood)
        assertEquals(1024, result.data.size)
        assertArrayEquals(ByteArray(512) { 5 }, result.data.copyOfRange(0, 512))
        assertArrayEquals(ByteArray(512) { 6 }, result.data.copyOfRange(512, 1024))
        emulator.close()
    }

    @Test
    fun `READ SECTORS sector count zero means 256 sectors`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        val result = emulator.process(lbaTaskFile(command = 0x20, lba = 0, sectorCount = 0))
        assertTrue(result.statusGood)
        assertEquals(256 * 512, result.data.size)
        emulator.close()
    }

    @Test
    fun `READ SECTORS by CHS matches the equivalent LBA read`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        // LBA 37 on an 80,2,18 floppy: cyl=1, head=0, sector=(37 % 18)+1=1 -> cyl 1, head 1... compute directly:
        // LBA = (cyl*heads + head)*spt + (sector-1) => for cyl=1, head=0, sector=1: (1*2+0)*18+0 = 36
        val chsResult = emulator.process(chsTaskFile(command = 0x20, cylinder = 1, head = 0, sector = 1, sectorCount = 1))
        val lbaResult = emulator.process(lbaTaskFile(command = 0x20, lba = 36, sectorCount = 1))
        assertTrue(chsResult.statusGood)
        assertArrayEquals(lbaResult.data, chsResult.data)
        emulator.close()
    }

    @Test
    fun `READ SECTORS past end of image fails cleanly`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        val result = emulator.process(lbaTaskFile(command = 0x20, lba = 2879, sectorCount = 2))
        assertFalse(result.statusGood)
        assertTrue(result.data.isEmpty())
        emulator.close()
    }

    @Test
    fun `INITIALIZE DEVICE PARAMETERS overrides geometry for subsequent CHS reads`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        // Ask for a nonstandard translation: 4 heads, 30 sectors/track.
        val initTaskFile = AmtIderFloppyEmulator.AtaTaskFile(
            features = 0, sectorCount = 30, lbaLow = 0, lbaMid = 0, lbaHigh = 0,
            deviceHead = 3, // (heads-1) = 3 -> 4 heads
            command = 0x91,
        )
        assertTrue(emulator.process(initTaskFile).statusGood)

        // cyl=0, head=1, sector=2 (1-based) under the new 4-head/30-spt translation -> LBA = (0*4+1)*30 + 1 = 31
        val chsResult = emulator.process(chsTaskFile(command = 0x20, cylinder = 0, head = 1, sector = 2, sectorCount = 1))
        val lbaResult = emulator.process(lbaTaskFile(command = 0x20, lba = 31, sectorCount = 1))
        assertTrue(chsResult.statusGood)
        assertArrayEquals(lbaResult.data, chsResult.data)
        emulator.close()
    }

    @Test
    fun `read dma returns the same data as read sectors`() {
        // FIX (item 8): READ DMA (0xC8) used to be unhandled — see
        // AmtIderFloppyEmulator.process's doc comment for why it now shares
        // read sectors' handler.
        val emulator = AmtIderFloppyEmulator(make144Image())
        val dmaResult = emulator.process(lbaTaskFile(command = 0xC8, lba = 5, sectorCount = 2))
        val pioResult = emulator.process(lbaTaskFile(command = 0x20, lba = 5, sectorCount = 2))
        assertTrue(dmaResult.statusGood)
        assertArrayEquals(pioResult.data, dmaResult.data)
        assertTrue(emulator.isDmaCommand(0xC8))
        assertFalse(emulator.isDmaCommand(0x20))
        emulator.close()
    }

    @Test
    fun `unsupported command is rejected rather than silently mishandled`() {
        val emulator = AmtIderFloppyEmulator(make144Image())
        // WRITE DMA — still genuinely unsupported (this class only
        // implements PIO writes; see its top doc comment), unlike WRITE
        // SECTORS (PIO) below.
        val result = emulator.process(AmtIderFloppyEmulator.AtaTaskFile(0, 0, 0, 0, 0, 0, command = 0xCA))
        assertFalse(result.statusGood)
        emulator.close()
    }

    // ── IDE-R write support ──────────────────────────────────────────────

    @Test
    fun `WRITE SECTORS then READ SECTORS round-trips the written bytes`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        val payload = ByteArray(1024) { (it and 0xFF).toByte() } // 2 sectors' worth, distinct from the image's own fill pattern
        val taskFile = lbaTaskFile(command = 0x30, lba = 10, sectorCount = 2)

        assertEquals(1024, emulator.writeByteCount(taskFile))
        val writeResult = emulator.writeSectors(taskFile, payload)
        assertTrue(writeResult.statusGood)

        val readResult = emulator.process(lbaTaskFile(command = 0x20, lba = 10, sectorCount = 2))
        assertTrue(readResult.statusGood)
        assertArrayEquals(payload, readResult.data)
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS NO RETRY is recognized as a write command`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        assertTrue(emulator.isWriteCommand(0x30))
        assertTrue(emulator.isWriteCommand(0x31))
        assertFalse(emulator.isWriteCommand(0x20)) // READ SECTORS is not a write
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS is rejected with ABRT when the emulator is not writable`() {
        // Default constructor — writable defaults to false, matching every
        // mount before write support existed.
        val emulator = AmtIderFloppyEmulator(make144Image())
        val payload = ByteArray(512) { 0x42 }
        val result = emulator.writeSectors(lbaTaskFile(command = 0x30, lba = 0, sectorCount = 1), payload)
        assertFalse(result.statusGood)
        assertEquals(0x04 /* ATA_ERR_ABRT */, result.errorRegister)

        // And the image itself must be untouched — a rejected write must
        // not have partially landed on disk.
        val readResult = emulator.process(lbaTaskFile(command = 0x20, lba = 0, sectorCount = 1))
        assertArrayEquals(ByteArray(512) { 0 }, readResult.data)
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS past end of image fails cleanly and does not write`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        val payload = ByteArray(1024) { 0x55 }
        val result = emulator.writeSectors(lbaTaskFile(command = 0x30, lba = 2879, sectorCount = 2), payload)
        assertFalse(result.statusGood)
        assertEquals(0x10 /* ATA_ERR_IDNF */, result.errorRegister)
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS with a short payload is rejected rather than throwing`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        val taskFile = lbaTaskFile(command = 0x30, lba = 0, sectorCount = 2) // expects 1024 bytes
        val result = emulator.writeSectors(taskFile, ByteArray(512)) // only one sector's worth
        assertFalse(result.statusGood)
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS sector count zero means 256 sectors, matching READ`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        val taskFile = lbaTaskFile(command = 0x30, lba = 0, sectorCount = 0)
        assertEquals(256 * 512, emulator.writeByteCount(taskFile))
        val payload = ByteArray(256 * 512) { (it and 0xFF).toByte() }
        assertTrue(emulator.writeSectors(taskFile, payload).statusGood)

        val readResult = emulator.process(lbaTaskFile(command = 0x20, lba = 0, sectorCount = 0))
        assertArrayEquals(payload, readResult.data)
        emulator.close()
    }

    @Test
    fun `WRITE SECTORS by CHS resolves to the same sectors as the equivalent LBA write`() {
        val emulator = AmtIderFloppyEmulator(make144Image(), writable = true)
        val payload = ByteArray(512) { 0x99.toByte() }
        // Same CHS/LBA pair as the existing "READ SECTORS by CHS" test: cyl=1, head=0, sector=1 -> LBA 36.
        assertTrue(emulator.writeSectors(chsTaskFile(command = 0x30, cylinder = 1, head = 0, sector = 1, sectorCount = 1), payload).statusGood)

        val readResult = emulator.process(lbaTaskFile(command = 0x20, lba = 36, sectorCount = 1))
        assertArrayEquals(payload, readResult.data)
        emulator.close()
    }
}
