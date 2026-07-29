package com.systemsgo.hex.rdp.native

import android.content.Context
import android.util.Log
import org.bouncycastle.crypto.digests.MD4Digest
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * NLA-SERVER FEATURE: generates the WinPR SAM-format credential file that
 * [AFreeRdpServerBridge.setSamFile] hands to FreeRDP (via
 * `FreeRDP_NtlmSamFile` — see systemsgo_server_jni.c's doc comment for the
 * upstream source references confirming that setting's real behavior) so
 * FreeRDP's own CredSSP/NTLM code can perform genuine NLA authentication,
 * instead of the ad-hoc string-compare [AFreeRdpServerBridge.setExpectedCredentials]
 * does at the RDP-Logon-callback layer.
 *
 * FORMAT: one line, `"<username>:::<ntlm-hash-hex>:::"` — verified against
 * multiple independent descriptions of freerdp-shadow-cli's own
 * `/sam-file:` option and the `winpr-hash` CLI tool's documented sample
 * output (`winpr-hash -u awakecoding -p password` →
 * `awakecoding:::8846f7eaee8fb117ad06bdd830b7586c:::`). This class
 * reproduces that exact line shape without needing the native `winpr-hash`
 * binary — the NTLM hash itself is just `MD4(UTF-16LE(password))`, the
 * standard (decades-old, publicly documented) NTLM hash algorithm used by
 * Windows/Samba/every RDP client for this exact purpose; MD4 comes from
 * BouncyCastle (`org.bouncycastle.crypto.digests.MD4Digest`), already a
 * project dependency (see app/build.gradle.kts's FIX-HTTPS bcprov entry).
 *
 * NOT VERIFIED AGAINST A REAL COMPILE/RUN: nothing in this project has
 * exercised FreeRDP's SAM-file parser end-to-end before — if a real
 * xfreerdp/mstsc client fails NLA against a generated file, check the
 * line format here first against libwinpr's actual SAM-file parser
 * (winpr/libwinpr/sspi/NTLM or winpr/libwinpr/sam.c) once the vendored
 * headers/source are available; this is built from documented external
 * behavior, not this project's own end-to-end test.
 */
object RdpServerNlaCredentials {
    private const val TAG = "RdpServerNlaCreds"

    /**
     * Writes (overwriting any previous file) a one-line SAM file granting
     * NLA access to [username]/[password], and returns its absolute path,
     * or null if [username] is blank/contains a colon (which would break
     * the SAM file's `:`-delimited format) or on any I/O/hashing failure.
     */
    fun writeSamFile(context: Context, username: String, password: String): String? {
        if (username.isBlank() || username.contains(':') || username.contains('\n')) {
            Log.w(TAG, "Refusing to write SAM file: username is blank or contains ':'/newline, " +
                "which would corrupt the SAM file's delimited format")
            return null
        }
        return try {
            val ntlmHashHex = ntlmHash(password)
            val dir = File(context.filesDir, "rdp_server_tls").apply { mkdirs() }
            val samFile = File(dir, "server.sam")
            samFile.writeText("$username:::$ntlmHashHex:::\n")
            samFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write NLA SAM file", e)
            null
        }
    }

    /** Deletes any previously-written SAM file, e.g. when NLA is disabled. */
    fun clear(context: Context) {
        File(File(context.filesDir, "rdp_server_tls"), "server.sam").delete()
    }

    /**
     * NTLM hash = MD4(UTF-16LE(password)), hex-encoded lowercase — the
     * standard NTLM password hash (MS-NLMP §3.3.1), unrelated to (and much
     * weaker than, by modern standards) the AES/Keystore-backed encryption
     * this project's own [com.systemsgo.hex.data.db.SystemsGoDatabase] uses
     * for credentials at rest — this hash exists solely to satisfy the
     * RDP/NTLM wire protocol's own authentication format, not as this
     * project's storage format (the plaintext [password] itself is never
     * written to disk by this function, only its NTLM hash).
     */
    private fun ntlmHash(password: String): String {
        val utf16le = password.toByteArray(StandardCharsets.UTF_16LE)
        val digest = MD4Digest()
        digest.update(utf16le, 0, utf16le.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.joinToString("") { b -> "%02x".format(b) }
    }
}
