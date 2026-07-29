package com.systemsgo.hex.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TESTS-FIX: first test in the project. Starts with the pure-Kotlin
 * `fromName()` mappings in RdpProfile.kt — no Android framework dependency,
 * so these run as plain JVM unit tests (`./gradlew test`), no emulator
 * needed. Deliberately narrow in scope: each `fromName()` here is a single
 * source of a real bug class (a value persisted to Room/DataStore as a raw
 * string — see RdpProfile's @Entity — that no longer matches any enum
 * constant after a rename, or that was corrupted/blank on read). The
 * fallback-to-default behavior is exactly what stands between that scenario
 * and a crash, so it's what's asserted here.
 */
class RdpProfileEnumsTest {

    // ── ProtocolType ─────────────────────────────────────────────────────

    @Test
    fun `ProtocolType fromName resolves each real constant`() {
        assertEquals(ProtocolType.RDP, ProtocolType.fromName("RDP"))
        assertEquals(ProtocolType.VNC, ProtocolType.fromName("VNC"))
        assertEquals(ProtocolType.SSH, ProtocolType.fromName("SSH"))
        assertEquals(ProtocolType.TELNET, ProtocolType.fromName("TELNET"))
        // WEB-PORTAL FEATURE
        assertEquals(ProtocolType.WEB, ProtocolType.fromName("WEB"))
    }

    @Test
    fun `ProtocolType fromName falls back to RDP for unknown or corrupt values`() {
        // Simulates a value written by a future/older build, or a corrupted
        // DB row — must never throw, must default to RDP (documented fallback).
        assertEquals(ProtocolType.RDP, ProtocolType.fromName("NOT_A_REAL_PROTOCOL"))
        assertEquals(ProtocolType.RDP, ProtocolType.fromName(""))
    }

    @Test
    fun `ProtocolType fromName is case-sensitive by design`() {
        // entries.firstOrNull { it.name == name } is an exact match — a
        // lowercase "rdp" is NOT the same as "RDP" and should fall back,
        // not silently match. This test pins that behavior down so a future
        // "helpful" change to case-insensitive matching doesn't slip in
        // unnoticed (it would change what gets persisted vs read back).
        assertEquals(ProtocolType.RDP, ProtocolType.fromName("rdp"))
    }

    @Test
    fun `each ProtocolType has the correct default port`() {
        assertEquals(3389, ProtocolType.RDP.defaultPort)
        assertEquals(5900, ProtocolType.VNC.defaultPort)
        assertEquals(22, ProtocolType.SSH.defaultPort)
        assertEquals(23, ProtocolType.TELNET.defaultPort)
        // WEB-PORTAL FEATURE: 443, matching the HTTPS convention every real
        // management-portal deployment this targets uses.
        assertEquals(443, ProtocolType.WEB.defaultPort)
        // SERIAL-CONSOLE FEATURE: 2217, the conventional RFC 2217 port
        // (also ser2net's own default) — see ProtocolType.SERIAL_CONSOLE's
        // doc comment.
        assertEquals(2217, ProtocolType.SERIAL_CONSOLE.defaultPort)
    }

    @Test
    fun `isTerminal is true only for SSH and TELNET`() {
        assertEquals(false, ProtocolType.RDP.isTerminal)
        assertEquals(false, ProtocolType.VNC.isTerminal)
        assertEquals(true, ProtocolType.SSH.isTerminal)
        assertEquals(true, ProtocolType.TELNET.isTerminal)
        // WEB-PORTAL FEATURE: not a terminal — it's an embedded WebView, see
        // WebPortalActivity, so it must never route into the SSH/TELNET
        // "text terminal" UI branch this flag exists to select.
        assertEquals(false, ProtocolType.WEB.isTerminal)
        // SERIAL-CONSOLE FEATURE: a standalone terminal session, exactly
        // like SSH/TELNET/RLOGIN — must route through the same TerminalScreen
        // UI branch, not the RDP/VNC framebuffer one.
        assertEquals(true, ProtocolType.SERIAL_CONSOLE.isTerminal)
    }

    // ── SerialParity / SerialStopBits (SERIAL-CONSOLE FEATURE) ─────────────
    // RFC 2217 §3's SET-PARITY/SET-STOPSIZE subnegotiation codes — pinned
    // down here because SerialConsoleClient sends these raw Int codes on the
    // wire; a silent renumbering here would desync against any real RFC
    // 2217 server without failing loudly anywhere else.

    @Test
    fun `SerialParity rfc2217Code matches RFC 2217 section 3`() {
        assertEquals(1, SerialParity.NONE.rfc2217Code)
        assertEquals(2, SerialParity.ODD.rfc2217Code)
        assertEquals(3, SerialParity.EVEN.rfc2217Code)
        assertEquals(4, SerialParity.MARK.rfc2217Code)
        assertEquals(5, SerialParity.SPACE.rfc2217Code)
    }

    @Test
    fun `SerialStopBits rfc2217Code matches RFC 2217 section 3`() {
        assertEquals(1, SerialStopBits.ONE.rfc2217Code)
        assertEquals(2, SerialStopBits.TWO.rfc2217Code)
        assertEquals(3, SerialStopBits.ONE_POINT_FIVE.rfc2217Code)
    }

    // ── RemoteAppDisplayMode ─────────────────────────────────────────────

    @Test
    fun `RemoteAppDisplayMode fromName resolves each real constant`() {
        assertEquals(RemoteAppDisplayMode.SINGLE_WINDOW, RemoteAppDisplayMode.fromName("SINGLE_WINDOW"))
        assertEquals(RemoteAppDisplayMode.MULTI_WINDOW, RemoteAppDisplayMode.fromName("MULTI_WINDOW"))
    }

    @Test
    fun `RemoteAppDisplayMode fromName falls back to SINGLE_WINDOW for unknown values`() {
        assertEquals(RemoteAppDisplayMode.SINGLE_WINDOW, RemoteAppDisplayMode.fromName("GARBAGE"))
    }

    // ── CodecPreference ──────────────────────────────────────────────────

    @Test
    fun `CodecPreference fromName round-trips every constant`() {
        // Guards against the exact bug class this pattern exists to prevent:
        // renaming/reordering enum constants without updating fromName, which
        // would silently start returning the fallback for a value that used
        // to resolve correctly.
        for (codec in CodecPreference.entries) {
            assertEquals(codec, CodecPreference.fromName(codec.name))
        }
    }
}
