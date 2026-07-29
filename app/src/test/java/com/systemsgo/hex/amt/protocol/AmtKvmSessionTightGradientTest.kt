package com.systemsgo.hex.amt.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * AMT-VPRO FEATURE phase 4 follow-up (Tight "gradient" filter): plain-JVM
 * test for [AmtKvmSession]'s `readTightGradientPixels`, the one piece of
 * RFC 6143's Tight encoding that was previously left as a documented gap
 * (see AMT_VPRO_ROADMAP.md's "Done since this file was last accurate"
 * entry and that method's own doc comment for the predictor formula this
 * test verifies).
 *
 * This drives the private decoder directly via reflection rather than a
 * real socket/RFB handshake — same "feed it raw data, no envelope" spirit
 * as [AmtIderFloppyEmulatorTest] — by pre-loading `tightInflaters[0]` with
 * a hand-deflated Gradient-filtered byte stream computed independently
 * (encoder side) from the spec's own predictor formula, then asserting the
 * decoder reconstructs the exact original pixels.
 */
class AmtKvmSessionTightGradientTest {

    private fun newSession(): AmtKvmSession = AmtKvmSession(
        host = "192.0.2.1",
        redirectionPort = 16994,
        useTls = false,
        acceptSelfSignedCertificate = false,
        kvmPassword = "unused",
        digestUsername = "unused",
        digestPassword = "unused",
    )

    /** Independent (encoder-side) implementation of the same predictor
     *  formula documented on `readTightFilteredPixels`, used only to build
     *  the test's input — deliberately not sharing code with the
     *  production decoder, so this test can't pass merely because both
     *  sides share a bug. */
    private fun encodeGradient(w: Int, h: Int, argb: Array<IntArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun comp(v: Int, shift: Int) = (v shr shift) and 0xFF
        val above = Array(3) { IntArray(w) } // R,G,B previous-row true values
        for (y in 0 until h) {
            val left = IntArray(3)
            val aboveLeft = IntArray(3)
            val cur = Array(3) { IntArray(w) }
            for (x in 0 until w) {
                val pixel = argb[y][x]
                val trueVals = intArrayOf(comp(pixel, 16), comp(pixel, 8), comp(pixel, 0))
                for (c in 0 until 3) {
                    val up = above[c][x]
                    val pred = (left[c] + up - aboveLeft[c]).coerceIn(0, 255)
                    val d = (trueVals[c] - pred) and 0xFF
                    out.write(d)
                    aboveLeft[c] = up
                    left[c] = trueVals[c]
                    cur[c][x] = trueVals[c]
                }
            }
            for (c in 0 until 3) above[c] = cur[c]
        }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Runs the production decoder against a hand-encoded stream and
     *  returns the reconstructed pixels, using reflection to reach past
     *  `private` the same way a real `readTightRect` call would after
     *  already having primed `tightInflaters[streamId]`. */
    private fun decodeGradient(w: Int, h: Int, compressed: ByteArray): IntArray {
        val session = newSession()

        val inflatersField = AmtKvmSession::class.java.getDeclaredField("tightInflaters")
        inflatersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val inflaters = inflatersField.get(session) as Array<Inflater>
        inflaters[0].setInput(compressed)

        val method = AmtKvmSession::class.java.getDeclaredMethod(
            "readTightGradientPixels", IntArray::class.java, Int::class.java, Int::class.java, Int::class.java
        )
        method.isAccessible = true
        val pixels = IntArray(w * h)
        method.invoke(session, pixels, w, h, 0)
        return pixels
    }

    @Test
    fun reconstructsFlatColorRectangle() {
        // A uniform fill is the trivial case: every predictor lands
        // exactly on the true value, so every transmitted difference is 0.
        val w = 4; val h = 3
        val argb = Array(h) { IntArray(w) { (0xFF shl 24) or (0x11 shl 16) or (0x22 shl 8) or 0x33 } }
        val compressed = deflate(encodeGradient(w, h, argb))

        val decoded = decodeGradient(w, h, compressed)

        for (i in decoded.indices) assertEquals(argb[i / w][i % w], decoded[i])
    }

    @Test
    fun reconstructsGradientRamp() {
        // A genuine ramp (the case the filter is actually meant to help
        // compress) exercises the left/up/upper-left predictor terms with
        // varying, non-zero component values in every direction.
        val w = 6; val h = 5
        val argb = Array(h) { y ->
            IntArray(w) { x ->
                val r = (x * 40) and 0xFF
                val g = (y * 50) and 0xFF
                val b = ((x + y) * 20) and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val compressed = deflate(encodeGradient(w, h, argb))

        val decoded = decodeGradient(w, h, compressed)

        for (i in decoded.indices) assertEquals(argb[i / w][i % w], decoded[i])
    }

    @Test
    fun reconstructsRandomNoise() {
        // Random per-pixel colour is the worst case for the predictor
        // (every prediction misses), which is exactly why it's the best
        // stress test for the wraparound (`and 0xFF`) arithmetic: many
        // differences will be negative before wraparound.
        val w = 8; val h = 8
        val rnd = java.util.Random(42)
        val argb = Array(h) { IntArray(w) { (0xFF shl 24) or rnd.nextInt(0x1000000) } }
        val compressed = deflate(encodeGradient(w, h, argb))

        val decoded = decodeGradient(w, h, compressed)

        for (i in decoded.indices) assertEquals(argb[i / w][i % w], decoded[i])
    }
}
