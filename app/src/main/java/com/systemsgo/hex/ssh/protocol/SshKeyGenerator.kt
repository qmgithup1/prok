package com.systemsgo.hex.ssh.protocol

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Local SSH key-pair generation, complementing the existing "paste a PEM you
 * already have" import flow (see SshClient / SshTunnelManager / FileTransferManager,
 * which all accept a pasted OpenSSH/PEM private key via RdpProfile.sshPrivateKey /
 * sshTunnelPrivateKey).
 *
 * Uses JSch's own keygen (com.jcraft.jsch.KeyPair) — the same library already used
 * for every SSH/SFTP connection in this app — so no new crypto dependency is needed
 * and the generated private key is written in the OpenSSH format JSch already knows
 * how to read back via jsch.addIdentity(). Generation, encoding, and disposal all
 * happen in-process; nothing here ever touches the network.
 */
enum class SshKeyAlgorithm { ED25519, RSA_4096 }

data class GeneratedSshKeyPair(
    /** OpenSSH-format private key (passphrase-encrypted iff [SshKeyGenerator.generate]
     *  was given a non-empty passphrase). Meant to be dropped straight into this
     *  app's own "Private Key" field — never leaves the device. */
    val privateKeyPem: String,
    /** Single-line "algo base64-key comment", exactly the form OpenSSH expects in
     *  ~/.ssh/authorized_keys. This (and only this) is what gets copied to the
     *  remote server — it contains no secret material. */
    val publicKeyLine: String,
    /** "SHA256:xxxx" fingerprint of the public key, in the same format `ssh-keygen
     *  -lf` prints, so the user can visually cross-check it against the server. */
    val fingerprintSha256: String,
)

object SshKeyGenerator {

    private const val KEY_COMMENT = "systemsgo"

    /**
     * Generates a new key pair. This is CPU-bound (RSA-4096 in particular can take
     * a noticeable moment on low-end devices) — callers should invoke it from a
     * background dispatcher (e.g. Dispatchers.Default), never directly on the main
     * thread.
     */
    fun generate(
        algorithm: SshKeyAlgorithm,
        passphrase: String = "",
    ): GeneratedSshKeyPair {
        val jsch = JSch()
        val keyPair = when (algorithm) {
            SshKeyAlgorithm.ED25519  -> KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            SshKeyAlgorithm.RSA_4096 -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
        }
        try {
            val privateOut = ByteArrayOutputStream()
            if (passphrase.isNotEmpty()) {
                keyPair.writePrivateKey(privateOut, passphrase.toByteArray(Charsets.UTF_8))
            } else {
                keyPair.writePrivateKey(privateOut)
            }

            val publicOut = ByteArrayOutputStream()
            keyPair.writePublicKey(publicOut, KEY_COMMENT)
            val publicKeyLine = publicOut.toString(Charsets.UTF_8.name()).trim()

            return GeneratedSshKeyPair(
                privateKeyPem     = privateOut.toString(Charsets.UTF_8.name()).trim(),
                publicKeyLine     = publicKeyLine,
                fingerprintSha256 = sha256Fingerprint(publicKeyLine),
            )
        } finally {
            // KeyPair keeps the raw private key material resident (as byte[]) until
            // dispose() zeroes it — clear it the moment we've serialized what we need,
            // mirroring how SshCredentials/SshTunnelCredentials zero their CharArrays.
            keyPair.dispose()
        }
    }

    /**
     * Computes the fingerprint independently of JSch's own getFingerPrint() (which
     * historically defaults to MD5 for legacy-compat reasons) so the value shown to
     * the user always matches modern `ssh-keygen -lf` output: SHA256 of the raw
     * public-key blob, base64-encoded without padding.
     */
    private fun sha256Fingerprint(publicKeyLine: String): String {
        val fields = publicKeyLine.split(" ")
        require(fields.size >= 2) { "Malformed public key line" }
        val blob = Base64.getDecoder().decode(fields[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }
}
