package com.systemsgo.hex.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.systemsgo.hex.security.CryptoHelper

// 🔴 REFACTOR (CI build-failure fix, round 3): all Room migrations (v1→v64)
// were previously inlined directly in SystemsGoDatabase.kt, making that a single
// ~1500-line file containing the @Database class, every DAO, AND 63 anonymous
// 'object : Migration(...)' expressions. That combination was reliably crashing
// KSP2 with a bare 'KSP failed with exit code: PROCESSING_ERROR' and no further
// diagnostic, in every build variant (kspDebugKotlin, kspBenchmarkReleaseKotlin),
// regardless of the KSP patch version or available heap. Splitting the migrations
// out into their own file (same package, so every 'import ...MIGRATION_x_y' in
// AppModule.kt keeps working unchanged) reduces the single-file complexity KSP2's
// Analysis-API resolver has to process in one pass for SystemsGoDatabase.kt.

/**
 * v1 -> v2: introduced multi-protocol support (RDP / VNC / SSH).
 * Adds protocolType plus RD Gateway, VNC, and SSH columns. All new columns
 * are given safe defaults so existing RDP profiles keep working unmodified
 * (they implicitly become protocolType = 'RDP').
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN protocolType TEXT NOT NULL DEFAULT 'RDP'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPort INTEGER NOT NULL DEFAULT 443")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayDomain TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncViewOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v2 -> v3: Added ConnectionLog entity and advanced RDP fields
 * (colorDepth, width, height, performanceFlags, enableSound, enableClipboard,
 * enableDriveRedirect, sortOrder).
 *
 * NOTE: This is the single authoritative MIGRATION_2_3 — the duplicate in
 * ConnectionLogDao.kt has been removed to prevent the compile-time "duplicate
 * top-level declaration" error and the runtime schema mismatch it caused.
 * Schema matches ConnectionLog.kt exactly: profileId nullable, port present,
 * disconnectReason (not errorMessage), disconnectedAt NOT NULL DEFAULT 0.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Advanced RDP display/performance columns
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN colorDepth INTEGER NOT NULL DEFAULT 32")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN performanceFlags INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableSound INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableClipboard INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableDriveRedirect INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        // ConnectionLog table — schema must exactly match ConnectionLog.kt entity
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS connection_logs (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT,
                profileName TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                protocolType TEXT NOT NULL DEFAULT 'RDP',
                connectedAt INTEGER NOT NULL,
                disconnectedAt INTEGER NOT NULL DEFAULT 0,
                disconnectReason TEXT,
                wasSuccessful INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

/**
 * v3 -> v4: Added SSH Tunnel fields for RDP/VNC profiles.
 * All columns default to safe empty/false values so existing profiles
 * continue working without modification.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPort INTEGER NOT NULL DEFAULT 22")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v4 -> v5: Added Wake-on-LAN fields.
 * Safe defaults: WoL disabled, empty MAC, broadcast = 255.255.255.255.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolMacAddress TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolBroadcastAddress TEXT NOT NULL DEFAULT '255.255.255.255'")
    }
}

/**
 * v5 -> v6: Added lastScreenshotFilename column to rdp_profiles.
 * BUG-3 FIX: RdpProfile.kt added this field but no migration existed →
 * Room throws IllegalStateException on launch for all existing users.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN lastScreenshotFilename TEXT")
    }
}

/**
 * v6 -> v7: Added acceptSelfSignedCertificate field to RdpProfile.
 * BUG-3 FIX: ignoreCert was hard-wired to false after the MITM-vuln patch,
 * blocking all self-signed RDP servers. New column lets users opt-in per profile.
 * Default is 0 (false) so existing profiles keep the secure behaviour.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN acceptSelfSignedCertificate INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v7 -> v8 (CRIT-R1 FIX): Re-encrypt SSH Tunnel credentials that were stored as plaintext.
 *
 * MIGRATION_3_4 added sshTunnelPassword / sshTunnelPrivateKey / sshTunnelPrivateKeyPassphrase
 * with DEFAULT '' — plain text in the SQLCipher DB.  FIX S1 in RdpProfileRepository patched
 * new saves, but profiles created or last-saved on DB versions 3-6 still have unencrypted
 * values in those three columns.  This migration iterates every row where sshTunnelEnabled = 1
 * and encrypts any credential that CryptoHelper.decrypt() cannot successfully parse
 * (i.e. it was never passed through withEncryptedSecrets()).
 *
 * Detection strategy:
 *   • decrypt() succeeds           → value is already encrypted → leave unchanged.
 *   • decrypt() throws             → value is plaintext (or corrupt) → encrypt now.
 *   • encrypt() throws             → Keystore unavailable (Direct Boot, etc.)
 *                                    → leave current value; DAO will fix on next saveProfile().
 *   • value.isBlank()              → no credential to protect → skip.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query(
            "SELECT id, sshTunnelPassword, sshTunnelPrivateKey, sshTunnelPrivateKeyPassphrase " +
            "FROM rdp_profiles WHERE sshTunnelEnabled = 1"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id      = it.getString(0) ?: continue
                val pwd     = it.getString(1) ?: ""
                val privKey = it.getString(2) ?: ""
                val pkPass  = it.getString(3) ?: ""

                // ROOT-HARDENING FIX: AAD context must exactly match what
                // RdpProfileRepository.aad(id, field) uses, since that's what
                // reads these columns back afterwards.
                val encPwd     = encryptIfPlaintext(pwd, "$id:sshTunnelPassword")
                val encPrivKey = encryptIfPlaintext(privKey, "$id:sshTunnelPrivateKey")
                val encPkPass  = encryptIfPlaintext(pkPass, "$id:sshTunnelPrivateKeyPassphrase")

                // Only write back if at least one value actually changed,
                // to avoid unnecessary write-amplification on large profile sets.
                if (encPwd != pwd || encPrivKey != privKey || encPkPass != pkPass) {
                    db.execSQL(
                        "UPDATE rdp_profiles " +
                        "SET sshTunnelPassword = ?, sshTunnelPrivateKey = ?, " +
                        "    sshTunnelPrivateKeyPassphrase = ? " +
                        "WHERE id = ?",
                        arrayOf(encPwd, encPrivKey, encPkPass, id)
                    )
                }
            }
        }
    }

    /**
     * Try CryptoHelper.decrypt() — success means the value is already encrypted;
     * any exception means it is plaintext (or corrupt), so we encrypt it.
     * Returns the value unchanged if both decrypt AND encrypt fail (Keystore unavailable).
     */
    private fun encryptIfPlaintext(value: String, aad: String): String {
        // NEW-BUG-3 FIX: Use isEmpty() instead of isBlank().
        // isBlank() returns true for whitespace-only strings (e.g. " "), causing
        // an SSH tunnel credential that is literally a space to bypass re-encryption.
        // isEmpty() only skips truly empty strings (unconfigured fields), which is
        // the correct semantic here. Consistent with the isBlank()→isEmpty() fix
        // already applied to CryptoHelper.encrypt() (BUG-2) and
        // AppSettingsRepository.updatePinLock (NEW-BUG-2).
        if (value.isEmpty()) return value
        return try {
            CryptoHelper.decrypt(value, aad)   // succeeds → already encrypted
            value                              // return the encrypted blob unchanged
        } catch (_: Exception) {
            // SecurityException from decrypt() means value is plaintext (or corrupt).
            // Encrypt it; if encryption also fails leave the original so the DB
            // schema transition still completes (the DAO will re-encrypt on next save).
            try { CryptoHelper.encrypt(value, aad) } catch (_: Exception) { value }
        }
    }
}

/**
 * v8 -> v9: Added sshAgentForwardingEnabled column to rdp_profiles.
 * Default is 0 (false) — agent forwarding must be explicitly opted into per
 * profile, matching the safe-by-default pattern already used for
 * acceptSelfSignedCertificate (MIGRATION_6_7).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshAgentForwardingEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 -> v10: Added socksProxyEnabled / socksProxyPort columns to rdp_profiles
 * for the dynamic SOCKS5 proxy feature (the equivalent of `ssh -D <port>`).
 * Default is disabled with the conventional SOCKS port (1080) — matches the
 * safe-by-default, explicit opt-in pattern used for every other tunneling
 * feature (sshTunnelEnabled, sshAgentForwardingEnabled).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN socksProxyEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN socksProxyPort INTEGER NOT NULL DEFAULT 1080")
    }
}

/**
 * v10 -> v11: Added enableMicRedirect column to rdp_profiles for the
 * microphone (audio-capture / MS-RDPEAI "audin") redirection feature — the
 * input-direction counterpart to the existing enableSound (playback) column.
 * Default is disabled, matching enableSound's own default and the
 * safe-by-default opt-in pattern used for every other redirection toggle
 * (enableDriveRedirect, sshAgentForwardingEnabled, socksProxyEnabled, ...).
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableMicRedirect INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v11 -> v12: Added folders (categories) for saved connections.
 * Creates the new connection_folders table and adds a nullable folderId
 * column to rdp_profiles. Existing profiles get folderId = NULL (unfiled),
 * so nothing changes for users until they actually create a folder.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS connection_folders (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN folderId TEXT")
    }
}

/**
 * v12 -> v13: Added tags for saved connections.
 * Tags are stored as a single delimited string column (see Converters.kt) —
 * no new table, keeping the feature lightweight. Default '' decodes to an
 * empty tag list via Converters.toTagList(), so existing profiles simply
 * start out with no tags.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v13 -> v14: Added the Favorites feature.
 * Adds a boolean isFavorite column to rdp_profiles, defaulting to 0 (false)
 * so every existing connection stays unfavorited after the upgrade until the
 * user explicitly stars one. Purely additive — folders, tags, search, and
 * sortOrder are all untouched by this migration.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v14 -> v15: Added Wake & Connect fields to rdp_profiles.
 * wolPort defaults to 9 (the conventional WoL discard port, matching
 * WakeOnLanManager.DEFAULT_WOL_PORT) so existing profiles that already had
 * WoL configured (MIGRATION_4_5) keep sending their Magic Packet exactly the
 * same way. The timeout/retry-interval/max-retries columns only affect the
 * new "Wake & Connect" action (wait-for-reachable + auto-connect); manually
 * sending a Magic Packet via the existing "Wake" action is unaffected.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolPort INTEGER NOT NULL DEFAULT 9")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolConnectTimeoutSeconds INTEGER NOT NULL DEFAULT 60")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolRetryIntervalSeconds INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolMaxRetries INTEGER NOT NULL DEFAULT 20")
    }
}

/**
 * v15 -> v16: MULTI-MONITOR FEATURE. Adds preferredMonitorId to rdp_profiles
 * so the session toolbar's monitor selector can remember the user's last
 * choice per saved connection (see RdpProfile.preferredMonitorId's doc).
 * Defaults to -1 (com.systemsgo.hex.display.MonitorSelection.ALL_MONITORS_ID,
 * "All Monitors") so every existing profile behaves exactly as before —
 * a single-monitor session — until the user explicitly picks a specific
 * monitor for that connection.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN preferredMonitorId INTEGER NOT NULL DEFAULT -1")
    }
}

/**
 * v16 -> v17: PRINTER-REDIRECT FEATURE. Adds enablePrinterRedirect to
 * rdp_profiles for MS-RDPEPC printer redirection (over the same "rdpdr"
 * device-redirection channel enableDriveRedirect already uses, with a
 * "printer" device instead of a "drive" one). Defaults to 0 (false) so every
 * existing connection keeps behaving exactly as before until the user
 * explicitly opts in for that profile — same safe-by-default pattern as
 * MIGRATION_10_11 (enableMicRedirect).
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enablePrinterRedirect INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v17 -> v18: WEBCAM-REDIRECT FEATURE. Adds enableWebcamRedirect to
 * rdp_profiles for MS-RDPECAM camera redirection (a dynamic virtual
 * channel, "rdpecam" — unlike enablePrinterRedirect, this does not ride the
 * "rdpdr" device-redirection channel). Defaults to 0 (false) so every
 * existing connection keeps behaving exactly as before until the user
 * explicitly opts in for that profile — same safe-by-default pattern as
 * MIGRATION_16_17 (enablePrinterRedirect).
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableWebcamRedirect INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v18 -> v19: REMOTEAPP-WINDOWS FEATURE. Adds remoteAppDisplayMode to
 * rdp_profiles — the user's saved single-vs-multi-window preference for this
 * profile's RemoteApp session (see RemoteAppDisplayMode's doc comment).
 * Stored as its enum name (Converters.fromRemoteAppDisplayMode/
 * toRemoteAppDisplayMode), so the default here must match
 * RemoteAppDisplayMode.SINGLE_WINDOW.name exactly. Defaults every existing
 * profile to single-window — the behavior every RemoteApp-enabled profile
 * already had before this column existed (a single full-screen app surface,
 * since no per-window switcher existed yet) — so no existing connection's
 * behavior changes until the user explicitly opens the new picker and
 * switches to multi-window.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN remoteAppDisplayMode TEXT NOT NULL DEFAULT 'SINGLE_WINDOW'")
    }
}

/**
 * v19 -> v20: SMARTCARD-REDIRECT FEATURE. Adds enableSmartcardRedirect to
 * rdp_profiles for MS-RDPESC smart-card redirection over the "rdpdr"
 * device-redirection channel — same shape as enablePrinterRedirect
 * (MIGRATION_16_17), just with a "smartcard" device instead of "printer".
 * Defaults to 0 (false) so every existing connection keeps behaving exactly
 * as before until the user explicitly opts in for that profile. See
 * AFreeRdpBridge.isSmartcardBackendAvailable's doc comment for the current
 * native-build gap and the "channel compiled in but no on-device PC/SC
 * resource manager yet" caveat this flag is still subject to even when the
 * backend reports available.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableSmartcardRedirect INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v20 -> v21: CODEC-NEGOTIATION FEATURE. Adds codecPreference to
 * rdp_profiles — which RDPGFX codec(s) this profile offers the server (see
 * com.systemsgo.hex.data.model.CodecPreference's doc comment and
 * AFreeRdpBridge.CodecPreference for the full mapping onto the native
 * capability exchange). Stored as its enum name
 * (Converters.fromCodecPreference/toCodecPreference), so the default here
 * must match CodecPreference.AUTO.name exactly. Defaults every existing
 * profile to AUTO — the same fully-automatic "offer everything both ends
 * support, let FreeRDP's own negotiation pick" behavior every connection
 * already had before this column existed — so no existing connection's
 * behavior changes until the user explicitly opens Advanced Settings and
 * picks a different option.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN codecPreference TEXT NOT NULL DEFAULT 'AUTO'")
    }
}

/**
 * v21 -> v22: PARALLEL-REDIRECT FEATURE. Adds enableParallelRedirect and
 * parallelPortPath to rdp_profiles for RDPDR parallel-port redirection over
 * the same "rdpdr" device-redirection channel enableDriveRedirect/
 * enablePrinterRedirect/enableSmartcardRedirect already use — same shape as
 * MIGRATION_19_20 (enableSmartcardRedirect), plus a text column for the
 * user-supplied local device path (no per-profile path column existed yet
 * for any redirection toggle — drive redirect reuses a fixed app directory
 * instead — so this is the first one). Both default to 0/'' so every
 * existing connection keeps behaving exactly as before until the user
 * explicitly opts in and fills in a device path for that profile.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableParallelRedirect INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN parallelPortPath TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v22 -> v23: SERIAL-REDIRECT FEATURE. Adds enableSerialRedirect and
 * serialPortPath to rdp_profiles for RDPDR serial-port redirection over the
 * same "rdpdr" device-redirection channel every other device toggle uses —
 * same shape as MIGRATION_21_22 (enableParallelRedirect/parallelPortPath),
 * just a "serial" device instead of "parallel". Both default to 0/'' so
 * every existing connection keeps behaving exactly as before until the
 * user explicitly opts in and fills in a device path for that profile.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableSerialRedirect INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialPortPath TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v35 -> v36: SERIAL-OVER-NETWORK FEATURE. Adds serialRedirectMode (stored
 * as its enum name, "LOCAL_DEVICE"/"RAW_TCP"/"RFC_2217") plus
 * serialNetworkHost/serialNetworkPort to rdp_profiles, so the existing
 * serial redirect (MIGRATION_22_23) can be backed by a network device
 * server (Raw TCP or RFC 2217) instead of only a local device node.
 * Defaults to LOCAL_DEVICE/''/2217 so every existing connection keeps
 * behaving exactly as before — serialPortPath is only ignored once a
 * profile explicitly switches serialRedirectMode away from LOCAL_DEVICE.
 * See RdpProfile.serialRedirectMode's doc comment and
 * com.systemsgo.hex.rdp.serial.SerialNetworkBridge for the client-side
 * implementation this unlocks.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialRedirectMode TEXT NOT NULL DEFAULT 'LOCAL_DEVICE'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialNetworkHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialNetworkPort INTEGER NOT NULL DEFAULT 2217")
    }
}

/**
 * v36 -> v37: WEB-PORTAL FEATURE. Adds ProtocolType.WEB as a fifth
 * selectable profile protocol — an embedded-browser session (see
 * com.systemsgo.hex.web.WebPortalActivity) for any HTTPS management portal
 * (Guacamole, ESXi/vCenter, iDRAC/iLO, Proxmox, pfSense, ...), and — via
 * WebPortalActivity's "open in browser" fallback — the RD Web Access
 * "Pages" login itself when [com.systemsgo.hex.webfeed.RdWebFeedClient]'s
 * Basic-auth feed endpoint reports AuthRequired. Purely additive, same
 * shape as MIGRATION_35_36: defaults leave every existing RDP/VNC/SSH/
 * Telnet row untouched, and no WEB profile row can already exist since
 * protocolType was never persisted as 'WEB' before now.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webTrustSelfSignedCertificate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webAutoFillHttpAuth INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v37 -> v38: WEB-PORTAL-SMART-AUTOFILL FEATURE. Adds webAutoFillLoginForm to
 * rdp_profiles — a *separate* opt-in from webAutoFillHttpAuth (MIGRATION_36_37),
 * since the two cover different auth surfaces WebPortalActivity can meet:
 * webAutoFillHttpAuth answers the WebView's onReceivedHttpAuthRequest (a
 * browser-level 401 challenge, no page content involved), while this one
 * gates WebPortalScreen's onPageFinished JS injection (see
 * WebPortalLoginAutofill.kt) that recognizes and fills the *in-page* HTML
 * login form a handful of common self-hosted portals — Guacamole, ESXi/
 * vCenter, iDRAC/iLO, Proxmox VE — render for a normal username/password
 * sign-in. Defaults to 1 (on) like webAutoFillHttpAuth: the fill is
 * conservative (only known, hand-verified selectors; never auto-submits;
 * no-ops instantly on any other portal's markup), so it is safe to default
 * on for existing WEB profiles the same way HTTP-auth autofill already is.
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webAutoFillLoginForm INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v23 -> v24: OUTBOUND-PROXY FEATURE. Adds proxyEnabled/proxyType/proxyHost/
 * proxyPort/proxyUsername/proxyPassword to rdp_profiles — routes the RDP
 * client's own outbound TCP connection through a SOCKS/HTTP proxy, the
 * FreeRDP-native counterpart to mstsc's /proxy: flag. See
 * com.systemsgo.hex.data.model.ProxyType's doc comment and
 * AFreeRdpBridge.connect()'s proxyEnabled param for the full picture,
 * including how this differs from the pre-existing gatewayEnabled/gateway*
 * columns (RD Gateway, added long before this migration) and from
 * socksProxyEnabled/socksProxyPort (MIGRATION_9_10 — this app's own inbound
 * SSH-tunneled SOCKS server for *other* apps, unrelated direction). proxyType
 * defaults to 'SOCKS' (matching ProxyType.SOCKS, the enum's own fallback in
 * fromName()) but is inert while proxyEnabled=0, so every existing profile
 * keeps connecting exactly as before until a user explicitly turns this on.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyType TEXT NOT NULL DEFAULT 'SOCKS'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyPort INTEGER NOT NULL DEFAULT 1080")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxyPassword TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v24 -> v25: TELNET FEATURE. Adds ProtocolType.TELNET as a fourth
 * selectable profile protocol (see RemoteSessionFactory/TelnetClient) plus
 * its one profile-level column, telnetUseTls — whether the session wraps
 * the socket in TLS ("telnets") before Telnet negotiation starts. Defaults
 * to 0 (off) so this is purely additive: no existing RDP/VNC/SSH profile
 * row is touched, and a Telnet profile created before this migration
 * existed simply isn't possible yet (protocolType was never persisted as
 * 'TELNET' before now), so there's no historical data to backfill here.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN telnetUseTls INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v25 -> v26: X11 FORWARDING FEATURE (SSH only). Adds the four columns
 * backing RdpProfile.x11ForwardingEnabled — the equivalent of OpenSSH's
 * `ssh -X`/`-Y`, relaying remote GUI programs' X11 traffic through the SSH
 * session to a local X server app on the device (Termux:X11, XSDL, ...).
 * See RdpProfile.x11ForwardingEnabled's doc comment and SshClient.connect()
 * for the full picture. x11ForwardingEnabled defaults to 0 (off) and
 * x11DisplayHost/x11DisplayNumber default to the conventional local
 * loopback display :0 (127.0.0.1:6000), so this is purely additive — no
 * existing SSH profile's behaviour changes until a user explicitly turns
 * it on.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN x11ForwardingEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN x11DisplayHost TEXT NOT NULL DEFAULT '127.0.0.1'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN x11DisplayNumber INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN x11AuthCookie TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v26 -> v27: SSH-PORT-FORWARD FEATURE (SSH only). Adds the column backing
 * RdpProfile.sshPortForwards — user-defined static `ssh -L`/`-R` forwards,
 * set up on the same authenticated session alongside the interactive shell.
 * See SshPortForwardRule/SshPortForwardCodec and SshClient.connect() for the
 * JSch wiring. Stored as a single delimited TEXT column (same approach as
 * `tags`), so it defaults to "" (empty list) — purely additive, no existing
 * SSH profile's behaviour changes until a user explicitly adds a rule.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPortForwards TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v27 -> v28: FOLDER-APPEARANCE FEATURE. Adds the two columns backing
 * ConnectionFolder.color/icon — a user-chosen swatch (FolderColor) and
 * glyph (FolderIcon) so folders can be told apart at a glance once there
 * are more than a couple of them. Both default to "" (no color/icon
 * chosen), which the UI layer resolves to the same neutral tint + plain
 * folder glyph every folder already rendered with before this feature
 * existed — purely additive, no existing folder's appearance changes until
 * a user explicitly picks one from the new NewFolderDialog/RenameFolderDialog
 * swatch+glyph rows.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE connection_folders ADD COLUMN color TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE connection_folders ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v28 -> v29: ULTRAVNC-REPEATER FEATURE. Adds the two columns backing a VNC
 * profile's optional UltraVNC Repeater (Mode II, ID-based) routing — see the
 * doc comment on RdpProfile.vncRepeaterEnabled / RfbConnectable's class doc
 * for the wire protocol. Both default off/empty, same "purely additive, no
 * existing profile's behavior changes" pattern as every prior *Enabled
 * column (e.g. enableSmartcardRedirect, MIGRATION_19_20) — an existing VNC
 * profile keeps connecting directly to `host`/`port` exactly as before until
 * a user explicitly turns this on for that profile.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncRepeaterEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncRepeaterId TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v29 -> v30: ENTRA-ID-AUTH FEATURE (Gateway hop). Adds the two columns
 * backing GatewayAuthMode + the display-only linked-account UPN — see
 * RdpProfile.gatewayAuthMode / entraLinkedUpn's doc comments. Same
 * "purely additive" pattern as every prior *AuthMode/*Enabled column
 * (e.g. MIGRATION_28_29): an existing profile defaults to
 * GatewayAuthMode.PASSWORD.name ('PASSWORD') and an empty linked UPN, so it
 * keeps authenticating to its Gateway with gatewayUsername/gatewayPassword
 * exactly as before until a user explicitly switches that profile to
 * "Sign in with Microsoft" in GatewaySection.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayAuthMode TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN entraLinkedUpn TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v30 -> v31: ENTRA-ID-AUTH FEATURE (per-profile Gateway scope). Adds the
 * column backing RdpProfile.gatewayScopeUri — see that field's doc comment.
 * SystemsGo is a general-purpose client that can connect to any organization's
 * RD Gateway, each behind its own distinct Azure AD Application Proxy app
 * registration (different tenant, different Application ID URI) — so unlike
 * MIGRATION_29_30's gatewayAuthMode (a fixed enum of client-side behaviors),
 * the scope itself cannot be a single value hardcoded in
 * GatewayTokenProvider; it has to be per-profile, same as gatewayHost.
 * Defaults to '' so every existing Entra-ID-mode profile (necessarily none
 * before this migration, since MIGRATION_29_30 only just introduced that
 * mode) simply prompts the user to fill it in before GatewayTokenProvider
 * will resolve a token for it — see GatewayTokenProvider.resolve()'s
 * MissingScope result.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayScopeUri TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v31 -> v32: ULTRAVNC-REPEATER FEATURE (Mode I/II). Adds the column backing
 * RdpProfile.vncRepeaterMode — see VncRepeaterMode's doc comment. Defaults
 * to 'MODE_II' so every profile with vncRepeaterEnabled already true
 * (necessarily using ID-based routing, since Mode I didn't exist as an
 * option before this migration) keeps sending the same "ID:<id>" frame it
 * always has — purely additive, same pattern as MIGRATION_28_29.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncRepeaterMode TEXT NOT NULL DEFAULT 'MODE_II'")
    }
}

/**
 * v32 -> v33: LISTEN-MODE FEATURE (reverse VNC). Adds the two columns
 * backing a VNC profile's optional listening-viewer mode — see the doc
 * comment on RdpProfile.vncListenModeEnabled / Connection.useListenMode for
 * the wire protocol. Both default off/5500, same "purely additive, no
 * existing profile's behavior changes" pattern as MIGRATION_28_29: an
 * existing VNC profile keeps dialing out to host/port exactly as before
 * until a user explicitly turns this on for that profile.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncListenModeEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncListenPort INTEGER NOT NULL DEFAULT 5500")
    }
}

/**
 * v33 -> v34: RD-WEB-FEED FEATURE.
 * Adds the web_feed_subscriptions table (saved feed URL + credentials — see
 * [com.systemsgo.hex.data.model.WebFeedSubscription]) and two purely-additive
 * tracking columns on rdp_profiles so a connection imported from a feed
 * resource can be matched back to it on a later refresh. Existing profiles
 * get '' for both new columns, meaning "not from a feed" — no behavior
 * change for them.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webFeedSubscriptionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webFeedAlias TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS web_feed_subscriptions (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                feedUrl TEXT NOT NULL,
                username TEXT NOT NULL DEFAULT '',
                password TEXT NOT NULL DEFAULT '',
                domain TEXT NOT NULL DEFAULT '',
                acceptSelfSignedCertificate INTEGER NOT NULL DEFAULT 0,
                autoImportNewResources INTEGER NOT NULL DEFAULT 0,
                targetFolderId TEXT NOT NULL DEFAULT '',
                lastRefreshed INTEGER NOT NULL DEFAULT 0,
                lastError TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }
}

/**
 * v34 -> v35: SSH-PROXYJUMP-CHAIN FEATURE. Adds sshTunnelHops to rdp_profiles
 * — an ordered chain of [com.systemsgo.hex.data.model.SshJumpHop], encoded
 * as a single delimited string (see
 * [com.systemsgo.hex.data.model.SshJumpHopCodec]), same storage approach as
 * sshPortForwards (MIGRATION_26_27). Purely additive: defaults to '' (empty
 * list) for every existing row, so no existing single-hop tunnel profile's
 * behavior changes here — RdpProfile.effectiveSshTunnelHops is what
 * transparently upgrades those rows' existing sshTunnelHost/sshTunnelPort/…
 * columns into a single-entry list on read, without this migration needing
 * to touch that data at all.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelHops TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v38 -> v39: PAC-SUPPORT FEATURE. Adds pacUrl to rdp_profiles — an
 * optional Proxy Auto-Config file URL that dynamically decides the
 * outbound proxy per-destination, instead of (or as an override of) the
 * fixed proxyHost/proxyPort/... columns added by MIGRATION_23_24. See
 * RdpProfile.pacUrl's doc comment for the priority between the two.
 * Defaults to '' (empty = feature unused) so every existing profile keeps
 * connecting exactly as before — through its existing static proxy config,
 * if any — until a user explicitly fills this in.
 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN pacUrl TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v39 -> v40: RDP-OVER-WEBSOCKET FEATURE. Adds transportMode and
 * webSocketConfig to rdp_profiles — see RdpProfile.transportMode/
 * webSocketConfig's doc comments. transportMode defaults to 'TCP' (the
 * String-backed-enum default, same pattern as gatewayAuthMode's 'PASSWORD'
 * in MIGRATION_29_30) and webSocketConfig defaults to '' (decodes to
 * RdpWebSocketConfig()'s all-default instance via RdpWebSocketConfigCodec),
 * so every existing profile keeps connecting over plain TCP exactly as
 * before until a user explicitly switches a profile's Transport setting.
 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN transportMode TEXT NOT NULL DEFAULT 'TCP'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webSocketConfig TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v40 -> v41: REDFISH-IPMI FEATURE. Adds ProtocolType.REDFISH and
 * ProtocolType.IPMI as selectable profile protocols (see ProtocolType's doc
 * comment) plus the single column specific to IPMI, ipmiPrivilegeLevel —
 * the RAKP "Requested Maximum Privilege Level" to request during session
 * setup (see com.systemsgo.hex.ipmi.protocol.IpmiClient.IpmiPrivilege).
 * Both new protocols reuse host/port/username/password/
 * acceptSelfSignedCertificate exactly like RDP/VNC/SSH/Telnet/Web already
 * do, so no other column is needed. Defaults to 'ADMINISTRATOR' — the
 * privilege level every IPMI walkthrough (ipmitool, vendor docs) assumes by
 * default, and required for Chassis Control (power) anyway — so a freshly
 * created IPMI profile works with zero extra configuration. Purely
 * additive: no existing RDP/VNC/SSH/Telnet/Web row is touched, and no
 * REDFISH/IPMI profile row can already exist since those protocolType
 * values were never persisted before now.
 */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ipmiPrivilegeLevel TEXT NOT NULL DEFAULT 'ADMINISTRATOR'")
    }
}

/**
 * v41 -> v42: AMT-VPRO FEATURE. Adds ProtocolType.AMT as a third
 * BMC-management-family protocol alongside REDFISH/IPMI (see ProtocolType's
 * doc comment) plus the single column specific to AMT, amtUseTls — whether
 * to speak WS-Management over TLS (port 16993) instead of plain HTTP (port
 * 16992). Reuses host/port/username/password/acceptSelfSignedCertificate
 * exactly like REDFISH/IPMI already do, so no other column is needed.
 * Defaults to 0/false (plain HTTP) — the common lab/SMB "admin control
 * mode" case an unprovisioned AMT box ships in — so a freshly created AMT
 * profile works with zero extra configuration. Purely additive: no
 * existing row of any protocol is touched, and no AMT profile row can
 * already exist since ProtocolType.AMT was never persisted before now.
 */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN amtUseTls INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v42 -> v43: RLOGIN FEATURE. Adds ProtocolType.RLOGIN as a sixth
 * selectable profile protocol (see RemoteSessionFactory/RloginClient) plus
 * its two profile-level columns: rloginRemoteUsername (the RFC 1282
 * handshake's "server user name" field, defaults to '' meaning "same as
 * username") and rloginTerminalType (the handshake's terminal type/speed
 * field, defaults to 'xterm/38400'). Purely additive — no existing
 * RDP/VNC/SSH/Telnet/REDFISH/IPMI/AMT profile row is touched, and a
 * profile with protocolType 'RLOGIN' wasn't possible before this migration
 * existed, so there's no historical data to backfill.
 */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN rloginRemoteUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN rloginTerminalType TEXT NOT NULL DEFAULT 'xterm/38400'")
    }
}

/**
 * v43 -> v44: SERIAL-CONSOLE FEATURE (Part 1/N). Adds ProtocolType.SERIAL_CONSOLE
 * as a new selectable profile protocol (see RemoteSessionFactory/
 * SerialConsoleClient) — a standalone terminal session onto a serial
 * console, separate from the pre-existing RDP-serial-redirect columns
 * (enableSerialRedirect/serialPortPath/serialRedirectMode/serialNetworkHost/
 * serialNetworkPort, unchanged by this migration). Reuses this profile's
 * existing host/port columns for the endpoint, so the only new columns are
 * the serial-line parameters: serialConsoleTransport ('RFC_2217' default —
 * matches RdpProfile.serialConsoleTransport's Kotlin default), baud rate,
 * data bits, parity, stop bits, and the (not-yet-functional, see
 * SerialConsoleClient) local-USB-device path. Purely additive — no existing
 * RDP/VNC/SSH/Telnet/Rlogin/REDFISH/IPMI/AMT profile row is touched, and a
 * profile with protocolType 'SERIAL_CONSOLE' wasn't possible before this
 * migration existed, so there's no historical data to backfill.
 */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleTransport TEXT NOT NULL DEFAULT 'RFC_2217'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleBaudRate INTEGER NOT NULL DEFAULT 9600")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleDataBits INTEGER NOT NULL DEFAULT 8")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleParity TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleStopBits TEXT NOT NULL DEFAULT 'ONE'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleDevicePath TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * PIN-CONNECTION FEATURE: adds isPinned/pinnedOrder to rdp_profiles — see
 * those fields' doc comments on [RdpProfile] for exactly what each one
 * means and how they interact with the existing isFavorite (MIGRATION_13_14)
 * and sortOrder (MIGRATION_2_3) columns. Purely additive: no existing
 * RDP/VNC/SSH/Telnet/Rlogin/SERIAL_CONSOLE/REDFISH/IPMI/AMT/WEB profile row
 * is touched, and every existing profile defaults to isPinned=0,
 * pinnedOrder=0 (i.e. simply unpinned) after upgrade.
 */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN pinnedOrder INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * RESTCONF FEATURE (Part 1/4): adds ProtocolType.RESTCONF plus every
 * RESTCONF-specific column on [com.systemsgo.hex.data.model.RdpProfile] —
 * see that class's "── RESTCONF FEATURE (Part 1/4) ──" block for what each
 * one means. Purely additive: no existing RDP/VNC/SSH/.../REDFISH/IPMI/AMT/
 * WEB profile row is touched, and ProtocolType itself needs no migration
 * (Room persists enums by name in a plain TEXT column already, same as
 * every other protocol added since MIGRATION_40_41).
 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfAuthType TEXT NOT NULL DEFAULT 'BASIC'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfDataFormat TEXT NOT NULL DEFAULT 'JSON'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfUseHttps INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfBearerToken TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfJwtToken TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfApiKeyHeaderName TEXT NOT NULL DEFAULT 'X-API-Key'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfApiKeyValue TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfCustomHeaders TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfClientCertAlias TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfMutualTlsEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfOAuth2TokenUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfOAuth2ClientId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfOAuth2ClientSecret TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfOAuth2Scope TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfCertificatePins TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfHttp2Enabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfCompressionEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN restconfKeepAliveSeconds INTEGER NOT NULL DEFAULT 60")
    }
}

/**
 * RESTCONF FEATURE (Part 3/4): the API Explorer's storage — Saved
 * Requests/Collections/History (Favorites/Recent are views over these, no
 * extra tables needed). All three are purely additive new tables; nothing
 * on `rdp_profiles` or any other existing table changes.
 */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS restconf_saved_requests (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT NOT NULL,
                collectionId TEXT,
                name TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                queryParams TEXT NOT NULL DEFAULT '',
                headers TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                dataFormat TEXT NOT NULL DEFAULT 'JSON',
                isFavorite INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                lastUsedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_restconf_saved_requests_profileId ON restconf_saved_requests(profileId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS restconf_collections (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_restconf_collections_profileId ON restconf_collections(profileId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS restconf_history (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                statusCode INTEGER NOT NULL,
                elapsedMillis INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_restconf_history_profileId ON restconf_history(profileId)")
    }
}

/**
 * RESTCONF FEATURE (Part 5): Environment Variables — one purely additive new
 * table, same shape/reasoning as MIGRATION_46_47's three tables. Nothing on
 * `restconf_saved_requests`/`restconf_collections`/`restconf_history` or any
 * other existing table changes.
 */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS restconf_environments (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT NOT NULL,
                name TEXT NOT NULL,
                variables TEXT NOT NULL DEFAULT '',
                isActive INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_restconf_environments_profileId ON restconf_environments(profileId)")
    }
}

/**
 * SNMP FEATURE: adds the columns backing [ProtocolType.SNMP] plus the
 * "SNMP as a monitoring add-on for any existing connection" mode — see
 * [RdpProfile.snmpMonitoringEnabled] and its neighboring fields' doc
 * comments for what each one means. All columns are purely additive with
 * safe defaults (snmpVersion defaults to the still-near-universal 'V2C',
 * community defaults to the SNMP-standard 'public'), so every existing
 * non-SNMP profile row is unaffected, and no profile with protocolType
 * 'SNMP' existed before this migration to backfill.
 */
val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpVersion TEXT NOT NULL DEFAULT 'V2C'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpCommunity TEXT NOT NULL DEFAULT 'public'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3Username TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3SecurityLevel TEXT NOT NULL DEFAULT 'AUTH_PRIV'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3AuthProtocol TEXT NOT NULL DEFAULT 'SHA1'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3AuthPassphrase TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3PrivProtocol TEXT NOT NULL DEFAULT 'AES128'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3PrivPassphrase TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpV3ContextName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpPort INTEGER NOT NULL DEFAULT 161")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpMonitoringEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN snmpFavoriteOids TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * NETCONF FEATURE: adds the NETCONF-specific columns to rdp_profiles — see
 * those fields' doc comments on [com.systemsgo.hex.data.model.RdpProfile] for
 * what each means. Connection identity/auth/proxy/jump-host reuse the
 * existing host/port/username/password/sshAuthType/sshPrivateKey/
 * sshPrivateKeyPassphrase/sshTunnelHops/proxyType/pacUrl columns (same as SSH
 * profiles), so no new columns are needed for those. Purely additive — no
 * existing profile row of any protocol is touched, and every existing
 * profile defaults to netconfDefaultDatastore='running' (the only mandatory
 * NETCONF datastore), netconfKeepAliveMs=15000, netconfConnectTimeoutMs=15000,
 * netconfCompressionEnabled=false, netconfAutoReconnect=true after upgrade.
 */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfDefaultDatastore TEXT NOT NULL DEFAULT 'running'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfExtraCapabilities TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfKeepAliveMs INTEGER NOT NULL DEFAULT 15000")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfConnectTimeoutMs INTEGER NOT NULL DEFAULT 15000")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCompressionEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfOpenSshCertificate TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfAutoReconnect INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * CALL-HOME FEATURE (RFC 8071, Part 12): adds the three columns
 * [com.systemsgo.hex.data.model.RdpProfile.netconfCallHomeEnabled]/
 * [com.systemsgo.hex.data.model.RdpProfile.netconfCallHomeListenPort]/
 * [com.systemsgo.hex.data.model.RdpProfile.netconfCallHomeAllowedSourceHost] —
 * see their doc comments on RdpProfile. Purely additive, same shape as
 * MIGRATION_49_50: every existing profile of every protocol defaults to
 * netconfCallHomeEnabled=false (i.e. behaves exactly as before this
 * upgrade — outbound-only), netconfCallHomeListenPort=4334 (RFC 8071/IANA
 * default), netconfCallHomeAllowedSourceHost='' (no allowlist).
 */
val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCallHomeEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCallHomeListenPort INTEGER NOT NULL DEFAULT 4334")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCallHomeAllowedSourceHost TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * CALL-HOME-TLS FEATURE (RFC 8071's `netconf-ch-tls` variant, RFC 7589
 * transport): adds [com.systemsgo.hex.data.model.RdpProfile.netconfCallHomeTransport]
 * and [com.systemsgo.hex.data.model.RdpProfile.netconfCallHomeTlsClientCertificatePem] —
 * see their doc comments on RdpProfile. Purely additive, same shape as
 * MIGRATION_50_51: every existing profile defaults to
 * netconfCallHomeTransport='SSH' (i.e. every profile that already had Call
 * Home enabled keeps using the SSH listener exactly as before this upgrade)
 * and netconfCallHomeTlsClientCertificatePem='' (no client certificate — TLS
 * handshake, if this profile is later switched to the TLS transport,
 * proceeds without one until the user supplies one).
 */
val MIGRATION_51_52 = object : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCallHomeTransport TEXT NOT NULL DEFAULT 'SSH'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN netconfCallHomeTlsClientCertificatePem TEXT NOT NULL DEFAULT ''")
    }
}

// GUACAMOLE-PROTOCOL FEATURE: see RdpProfile.guacServerUrl's doc comment
// for why these are new columns rather than reusing host/port, and
// guacRememberSession's doc comment for the remember-session toggle.
val MIGRATION_52_53 = object : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacServerUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacDataSource TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacConnectionIdentifier TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacConnectionName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacConnectionProtocol TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN guacRememberSession INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * SERIAL-CONSOLE HW-FLOW-CONTROL FEATURE: adds serialConsoleHardwareFlowControl
 * (RTS/CTS) — only meaningful when serialConsoleTransport == LOCAL_DEVICE
 * (MIGRATION_43_44 added serialConsoleTransport/etc.), wired through
 * UsbSerialDriverPort.setFlowControl on each concrete driver in
 * UsbSerialDrivers.kt. Purely additive, same "no existing profile's
 * behavior changes" pattern as MIGRATION_28_29 — defaults to false (off),
 * matching every chipset's own power-on default.
 */
val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN serialConsoleHardwareFlowControl INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * IPMI-KG-FEATURE: adds ipmiKgKey, the BMC's separately-configured "Kg"/
 * "BMC key" for a two-key RAKP login (see IpmiSession's kgKey constructor
 * param and IpmiCrypto.deriveSik's doc comment) — same "one column, reuses
 * everything else" pattern as MIGRATION_40_41's ipmiPrivilegeLevel. Blank
 * default means "one-key login, no BMC key configured", the factory
 * default on essentially every BMC, so every existing IPMI profile row
 * keeps behaving exactly as it did before this migration.
 */
val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ipmiKgKey TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * MOSH FEATURE: adds the five mosh-server flags with no SSH/RDP/VNC
 * equivalent (see RdpProfile's MOSH-specific fields doc comment) — Mosh's
 * SSH-bootstrap auth itself reuses the existing username/password/
 * sshAuthType/sshPrivateKey/sshPrivateKeyPassphrase columns, so no migration
 * is needed for those. Purely additive, same "one migration per new feature's
 * columns" pattern as every migration above; every existing non-Mosh profile
 * row is unaffected since these columns are simply never read for them.
 */
val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN moshRemoteServerCommand TEXT NOT NULL DEFAULT 'mosh-server'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN moshUdpPortRange TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN moshRemoteLocale TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN moshColorMode INTEGER NOT NULL DEFAULT 256")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN moshPredictionMode TEXT NOT NULL DEFAULT 'ADAPTIVE'")
    }
}

/**
 * PROXMOX-API FEATURE: adds the 4 Proxmox-specific columns (host/port/
 * username/password/acceptSelfSignedCertificate are all reused as-is, same
 * as every other protocol) — purely additive, same shape as every migration
 * above; existing non-Proxmox rows are unaffected since these are simply
 * never read for them.
 */
val MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxmoxAuthMode TEXT NOT NULL DEFAULT 'TOKEN'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxmoxTokenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxmoxTokenSecret TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN proxmoxAcceptSelfSignedCertificate INTEGER NOT NULL DEFAULT 1")
    }
}

/** MODBUS-TCP FEATURE (Part 2/2): adds the 6 Modbus-specific columns (host/port are reused as-is). Purely additive, same shape as every migration above. */
val MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusUnitId INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusConnectTimeoutMs INTEGER NOT NULL DEFAULT 5000")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusResponseTimeoutMs INTEGER NOT NULL DEFAULT 3000")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusRetries INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusPollIntervalMs INTEGER NOT NULL DEFAULT 1000")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN modbusPoints TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * VIRTUALBOX-VRDE FEATURE (Part 1/N): adds the 2 VRDE-specific columns
 * (host/port/username/password/domain/acceptSelfSignedCertificate are all
 * reused as-is from the RDP columns, since VIRTUALBOX_VRDE connects through
 * the same RdpRemoteAdapter path — see ProtocolType.VIRTUALBOX_VRDE's doc
 * comment) — purely additive, same shape as every migration above; existing
 * non-VRDE rows are unaffected since these are simply never read for them.
 */
val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vrdeAuthType TEXT NOT NULL DEFAULT 'NULL_AUTH'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vrdeMultiConnectionAllowed INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * VMWARE-VSPHERE FEATURE (Part 1/N): adds the 3 vSphere-specific columns
 * (host/port/username/password are reused as-is, same as PROXMOX) — purely
 * additive, same shape as every migration above; existing non-vSphere rows
 * are unaffected since these are simply never read for them.
 */
val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vsphereApiMode TEXT NOT NULL DEFAULT 'REST'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vsphereAcceptSelfSignedCertificate INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vsphereDatacenter TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * BUGFIX (schema/entity mismatch): RdpProfile.kt has shipped rtspStreamPath/
 * rtspTransportMode/rtspUseTls (RTSP FEATURE — a fully-wired, non-stub
 * protocol, see RemoteSessionFactory's ProtocolType.RTSP branch) since
 * before this migration existed, but no migration ever added the matching
 * columns to rdp_profiles and the database version was never bumped for
 * them. Room validates the entity's expected schema against the real
 * on-disk table on every app open; since rdp_profiles was missing these 3
 * columns, that validation would fail with "Migration didn't properly
 * handle" / a missing-column IllegalStateException the first time a build
 * containing the RTSP entity fields ran against an existing (or freshly
 * created-through-migrations) database. Purely additive, same shape as
 * every migration above — defaults match RdpProfile's own property
 * defaults exactly, so every existing (non-RTSP) profile is unaffected.
 */
val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN rtspStreamPath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN rtspTransportMode TEXT NOT NULL DEFAULT 'TCP_INTERLEAVED'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN rtspUseTls INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: adds the nine columns backing
 * the five new [com.systemsgo.hex.data.model.ProtocolType] entries (FTP,
 * FTPS, WEBDAV, SMB, NFS) — see those entries' doc comments in RdpProfile.kt
 * and the matching field doc comments just above RdpProfile's closing paren.
 * Purely additive, same shape as every migration above:
 *  - ftpSecurity/ftpPassiveMode (FTP/FTPS, shared columns — the two
 *    ProtocolTypes differ only in which security value the editor writes).
 *  - smbShare/smbDomain (SMB — host/port/username/password columns already
 *    existed and are reused as-is, same as every earlier protocol).
 *  - webdavBaseUrl (WEBDAV — replaces host/port for this protocol type; see
 *    that field's doc comment for why a single URL is used instead).
 *  - nfsExportPath/nfsUid/nfsGid/nfsMountdPort (NFS — AUTH_SYS only, no
 *    username/password; host column already existed and is reused as-is).
 * Every default here matches RdpProfile's own Kotlin property default
 * exactly, so every existing (non-file-transfer) profile is unaffected —
 * same "no behavior change for existing rows" guarantee every migration in
 * this file gives.
 */
val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ftpSecurity TEXT NOT NULL DEFAULT 'PLAIN'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ftpPassiveMode INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN smbShare TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN smbDomain TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN webdavBaseUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN nfsExportPath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN nfsUid INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN nfsGid INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN nfsMountdPort INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_62_63 = object : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): see RdpProfile.ciraEnabled's
        // doc comment. ciraRelayPort's default (8081) matches the constructor
        // default in RdpProfile.kt — an arbitrary project-owned port for the
        // relay's own WebSocket protocol, unrelated to AMT's 16992-16995.
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraRelayHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraRelayPort INTEGER NOT NULL DEFAULT 8081")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraRelayUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraRelayPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraDeviceId TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_63_64 = object : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // AMT-VPRO FEATURE — Phase 6, Part 3 (wss:// for the CIRA relay's
        // app-facing WebSocket): see RdpProfile.ciraRelayUseTls's doc
        // comment. Defaults to false (plain ws://) so every existing CIRA
        // profile row created under MIGRATION_62_63 keeps connecting
        // exactly as before this column existed — same "sane default,
        // zero config needed" reasoning as amtUseTls/ipmiPrivilegeLevel.
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN ciraRelayUseTls INTEGER NOT NULL DEFAULT 0")
    }
}

