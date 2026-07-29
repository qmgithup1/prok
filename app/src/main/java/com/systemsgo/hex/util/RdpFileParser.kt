package com.systemsgo.hex.util

import com.systemsgo.hex.data.model.RdpProfile
import java.io.InputStream

/**
 * Parses the standard Windows `.rdp` file format into an [RdpProfile].
 *
 * The format is a line-delimited text file where each line is:
 *   key:type:value
 * where type is one of:
 *   s = string
 *   i = integer
 *   b = binary (base64, rarely used)
 *
 * Reference: https://learn.microsoft.com/en-us/windows-server/remote/remote-desktop-services/clients/rdp-files
 */
object RdpFileParser {

    fun parse(stream: InputStream, fallbackName: String = "Imported"): RdpProfile {
        val props = mutableMapOf<String, String>()

        stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) return@forEachLine

            // Standard format: "key:type:value"  e.g. "full address:s:192.168.1.100"
            // Some files omit the type segment; handle both.
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) return@forEachLine

            val key = trimmed.substring(0, colonIdx).trim().lowercase()
            val rest = trimmed.substring(colonIdx + 1)

            // Check if next segment is a single-char type indicator (s / i / b)
            val value = if (rest.length >= 2 && rest[1] == ':' && rest[0] in listOf('s', 'i', 'b')) {
                rest.substring(2) // skip "s:" / "i:" / "b:"
            } else {
                rest // no type indicator — take the whole rest
            }.trim()

            props[key] = value
        }

        // ── Core fields ─────────────────────────────────────────────────────
        // "full address" may be "host", "host:port", or "[IPv6]:port"
        val rawAddress = props["full address"] ?: ""
        val host: String
        val port: Int
        // BUG #4 FIX: handle IPv6 addresses in bracket notation [::1]:3389
        when {
            rawAddress.startsWith('[') -> {
                // IPv6 with optional port: "[2001:db8::1]:3390" or "[::1]"
                val closingBracket = rawAddress.indexOf(']')
                if (closingBracket >= 0) {
                    host = rawAddress.substring(0, closingBracket + 1)
                    port = (rawAddress.getOrNull(closingBracket + 1)
                        ?.takeIf { it == ':' }
                        ?.let { rawAddress.substring(closingBracket + 2).toIntOrNull() }
                        ?: props["server port"]?.toIntOrNull() ?: 3389)
                        .coerceIn(1, 65535) // LIVE-HIGH-2 FIX: reject out-of-range ports
                } else {
                    host = rawAddress
                    port = (props["server port"]?.toIntOrNull() ?: 3389).coerceIn(1, 65535)
                }
            }
            rawAddress.contains(':') -> {
                // IPv4 or hostname with port: "192.168.1.1:3390"
                val lastColon = rawAddress.lastIndexOf(':')
                host = rawAddress.substring(0, lastColon)
                // LIVE-HIGH-2 FIX: clamp to valid port range 1..65535 so a malicious
                // .rdp file with port 0 or 99999 cannot cause IllegalArgumentException
                // or undefined behaviour in Socket.connect().
                port = (rawAddress.substring(lastColon + 1).toIntOrNull()
                    ?: props["server port"]?.toIntOrNull() ?: 3389)
                    .coerceIn(1, 65535)
            }
            else -> {
                // Plain host / IP with no port
                host = rawAddress
                port = (props["server port"]?.toIntOrNull() ?: 3389).coerceIn(1, 65535)
            }
        }

        val username    = props["username"] ?: ""
        val domain      = props["domain"]   ?: ""

        // FIX #8: Reject files that are missing the mandatory "full address" key.
        // Without this check, saving a profile with host="" crashes Room (NOT NULL
        // constraint) and shows a blank card in the UI.
        if (host.isBlank()) {
            throw IllegalArgumentException(
                "Invalid .rdp file: 'full address' is missing or empty. " +
                "A valid .rdp file must contain a line like: full address:s:192.168.1.100"
            )
        }
        val width       = props["desktopwidth"]?.toIntOrNull()  ?: 0
        val height      = props["desktopheight"]?.toIntOrNull() ?: 0
        val colorDepth  = props["session bpp"]?.toIntOrNull()   ?: 32

        // audiomode: 0 = play on client, 1 = play on server, 2 = no audio
        val audioMode   = props["audiomode"]?.toIntOrNull() ?: 2
        val enableSound = audioMode == 0
        // MIC-REDIRECT FEATURE: standard .rdp key for capture redirection —
        // "audiocapturemode:i:1" enables it (mstsc/RDCMan write this key
        // when "Record from this computer" is set to "Play on this
        // computer" in the Local Resources tab); absent or 0 means disabled.
        // Mirrors the audiomode handling immediately above, just for the
        // opposite (mic → remote) direction.
        val enableMicRedirect = props["audiocapturemode"]?.toIntOrNull() == 1

        // redirectclipboard: 1 = enabled, 0 = disabled
        val enableClipboard     = props["redirectclipboard"]?.toIntOrNull() != 0
        val enableDriveRedirect = props["redirectdrives"]?.toIntOrNull() == 1
        // redirectprinters: 1 = enabled, 0/absent = disabled. Standard mstsc
        // .rdp key for MS-RDPEPC printer redirection (Local Resources tab →
        // "Printers"). Mirrors redirectdrives immediately above.
        val enablePrinterRedirect = props["redirectprinters"]?.toIntOrNull() == 1

        // camerastoredirect: mstsc's .rdp key for MS-RDPECAM webcam
        // redirection (Local Resources tab → "Other supported Plug and Play
        // (PnP) devices" / camera picker) is a *string*, not a 0/1 flag —
        // either "*" (redirect all cameras) or a semicolon-separated list of
        // specific camera names, absent/empty meaning disabled. This app
        // doesn't support per-camera selection yet (see
        // AFreeRdpBridge.connect()'s enableWebcamRedirect — a single on/off
        // flag), so any non-blank value is treated as "on".
        val enableWebcamRedirect = !props["camerastoredirect"].isNullOrBlank()

        // redirectsmartcards: 1 = enabled, 0/absent = disabled. Standard
        // mstsc .rdp key for MS-RDPESC smart-card redirection (Local
        // Resources tab → "Smart cards"). Mirrors redirectprinters above.
        val enableSmartcardRedirect = props["redirectsmartcards"]?.toIntOrNull() == 1

        // enablecredsspsupport: 1 = NLA on, 0 = NLA off (default on if key absent)
        val useNla = props["enablecredsspsupport"]?.toIntOrNull() != 0

        // ── RD Gateway ───────────────────────────────────────────────────────
        val gatewayHost     = props["gatewayhostname"] ?: ""
        val gatewayEnabled  = gatewayHost.isNotBlank()
        val gatewayPort     = props["gatewayport"]?.toIntOrNull() ?: 443
        val gatewayUsername = props["gatewayusername"] ?: ""
        val gatewayDomain   = props["gatewaydomain"]   ?: ""

        // ── RemoteApp / RAIL ────────────────────────────────────────────────
        // RD-WEB-FEED FEATURE: standard mstsc .rdp keys for a published RemoteApp
        // (written by RD Web / RemoteApp Manager into every per-resource .rdp
        // file), so importing either a stand-alone .rdp file *or* a resource
        // fetched from an RD Web feed (see com.systemsgo.hex.webfeed.RdWebFeedClient,
        // which feeds the same file content through this exact parser) correctly
        // produces a RemoteApp-mode profile instead of a plain full-desktop one.
        // remoteapplicationmode:i:1 is the modern key; some older RD Web Access
        // versions instead only set "alternate shell:s:||alias" with no explicit
        // mode flag, so a non-blank program/shell value is also treated as "on".
        val remoteAppProgram = (props["remoteapplicationprogram"] ?: props["alternate shell"] ?: "").trim()
        val remoteAppEnabled = props["remoteapplicationmode"]?.toIntOrNull() == 1 || remoteAppProgram.isNotBlank()
        val remoteAppCmdLine = props["remoteapplicationcmdline"] ?: ""
        val remoteAppWorkingDir = props["remoteapplicationworkingdir"] ?: props["shell working directory"] ?: ""

        // ── Display name ─────────────────────────────────────────────────────
        // Prefer explicit name → derived from user@host → fallback
        val derivedName = when {
            domain.isNotBlank() && username.isNotBlank() -> "$domain\\$username@$host"
            username.isNotBlank()                        -> "$username@$host"
            host.isNotBlank()                            -> host
            else                                         -> fallbackName
        }.let { if (it.length > 50) host.ifBlank { fallbackName } else it }

        return RdpProfile(
            name                = derivedName,
            host                = host,
            port                = port,
            username            = username,
            // .rdp files never contain the password in plain text (Windows stores it
            // encrypted in a vault). We leave it empty so the user can fill it in the
            // import-review dialog before saving.
            password            = "",
            domain              = domain,
            width               = width,
            height              = height,
            colorDepth          = colorDepth,
            enableSound         = enableSound,
            enableMicRedirect   = enableMicRedirect,
            enableClipboard     = enableClipboard,
            enableDriveRedirect = enableDriveRedirect,
            enablePrinterRedirect = enablePrinterRedirect,
            enableWebcamRedirect = enableWebcamRedirect,
            enableSmartcardRedirect = enableSmartcardRedirect,
            useNla              = useNla,
            gatewayEnabled      = gatewayEnabled,
            gatewayHost         = gatewayHost,
            gatewayPort         = gatewayPort,
            gatewayUsername     = gatewayUsername,
            gatewayDomain       = gatewayDomain,
            remoteAppEnabled    = remoteAppEnabled,
            remoteAppProgram    = remoteAppProgram,
            remoteAppWorkingDir = remoteAppWorkingDir,
            remoteAppCmdLine    = remoteAppCmdLine,
        )
    }
}
