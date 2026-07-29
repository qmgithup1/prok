package com.systemsgo.hex.rdp.native

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder as CertBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.File
import java.io.FileWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * TLS-SERVER FEATURE: generates (and caches on disk) a self-signed X.509
 * cert/RSA key pair as PEM files, for [AFreeRdpServerBridge.start]'s
 * certPath/keyPath — those go straight into FreeRDP's native OpenSSL-based
 * server transport (`FreeRDP_CertificateFile`/`FreeRDP_PrivateKeyFile` in
 * systemsgo_server_jni.c), which needs real PEM files on disk, not a Java
 * KeyStore/SSLContext object the way [com.systemsgo.hex.transfer.FileTransferManager]'s
 * `buildSslContext()` uses for the HTTPS file-transfer server. Same
 * RSA-2048 + SHA-256 self-signed-cert recipe as that class (already a
 * proven pattern in this project — see its FIX-HTTPS comments), just
 * exported to PEM instead of wrapped in an SSLContext.
 *
 * TOFU FINGERPRINT: like FileTransferManager's `certFingerprint`, this
 * exposes the SHA-256 fingerprint of the generated cert so the connecting
 * side's UI can show it to the user for out-of-band comparison — a
 * self-signed cert on its own only stops passive eavesdropping, not an
 * active MITM on the same LAN, unless the user actually checks the
 * fingerprint matches what this device shows.
 *
 * Cached for 24h (same validity window FileTransferManager uses) in the
 * app's private files dir — regenerated automatically once expired, or if
 * the files are missing/unreadable for any reason. Never call this from
 * the main thread the first time (key generation + a self-signed cert
 * build is on the order of tens of milliseconds on modern hardware but
 * isn't guaranteed instant on lower-end devices).
 */
object RdpServerCertificateGenerator {
    private const val TAG = "RdpServerCertGen"
    private const val VALIDITY_MS = 24L * 60 * 60 * 1000 // 24 hours, matches FileTransferManager

    data class ServerCertificate(
        val certPath: String,
        val keyPath: String,
        /** SHA-256 fingerprint, colon-separated hex — same format as
         * FileTransferManager.certFingerprint, for the same TOFU UX. */
        val fingerprint: String,
    )

    /**
     * Returns a valid (cert, key) PEM pair for [context], generating a
     * fresh one if none is cached or the cached one has expired. Do not
     * call on the main thread.
     */
    @Synchronized
    fun getOrCreate(context: Context): ServerCertificate? {
        val dir = File(context.filesDir, "rdp_server_tls").apply { mkdirs() }
        val certFile = File(dir, "server.pem")
        val keyFile = File(dir, "server.key")
        val metaFile = File(dir, "server.meta") // stores expiry epoch millis + fingerprint

        val cached = readCached(certFile, keyFile, metaFile)
        if (cached != null) return cached

        return try {
            generate(certFile, keyFile, metaFile)
        } catch (e: Exception) {
            // Never crash the caller over this — AFreeRdpServerBridge.start()
            // already tolerates null certPath/keyPath by falling back to the
            // old unauthenticated-transport tier (see its doc comment).
            Log.e(TAG, "Failed to generate self-signed RDP server certificate — " +
                "falling back to no-TLS Standard RDP Security", e)
            null
        }
    }

    private fun readCached(certFile: File, keyFile: File, metaFile: File): ServerCertificate? {
        if (!certFile.exists() || !keyFile.exists() || !metaFile.exists()) return null
        return try {
            val lines = metaFile.readLines()
            val expiry = lines.getOrNull(0)?.toLongOrNull() ?: return null
            val fingerprint = lines.getOrNull(1) ?: return null
            if (System.currentTimeMillis() >= expiry) return null // expired, regenerate
            ServerCertificate(certFile.absolutePath, keyFile.absolutePath, fingerprint)
        } catch (e: Exception) {
            Log.w(TAG, "Cached RDP server cert unreadable, regenerating", e)
            null
        }
    }

    private fun generate(certFile: File, keyFile: File, metaFile: File): ServerCertificate {
        val rng = SecureRandom()
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048, rng) }.generateKeyPair()

        val name = X500Name("CN=SystemsGo-Server,O=SystemsGo,C=US")
        val serial = BigInteger.valueOf(rng.nextLong().and(0x7FFF_FFFF_FFFF_FFFFL))
        val now = Date()
        val expiry = Date(now.time + VALIDITY_MS)

        val certHolder = CertBuilder(name, serial, now, expiry, name, keyPair.public)
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { b -> "%02X".format(b) }

        // Write PEM cert
        PemWriter(FileWriter(certFile)).use { it.writeObject(PemObject("CERTIFICATE", cert.encoded)) }
        // Write PEM private key (PKCS#8, matches what OpenSSL/FreeRDP's
        // PrivateKeyFile loader expects)
        PemWriter(FileWriter(keyFile)).use { it.writeObject(PemObject("PRIVATE KEY", keyPair.private.encoded)) }

        metaFile.writeText("${expiry.time}\n$fingerprint")

        Log.i(TAG, "Generated new self-signed RDP server certificate, fingerprint=$fingerprint")
        return ServerCertificate(certFile.absolutePath, keyFile.absolutePath, fingerprint)
    }
}
