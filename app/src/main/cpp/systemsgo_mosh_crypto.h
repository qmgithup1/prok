/*
 * systemsgo_mosh_crypto.h — MOSH-PROTOCOL FEATURE, Part 3/N (crypto slice only).
 *
 * Scope of this module, deliberately narrow: turning the base64 session
 * key from a `MOSH CONNECT` line (see MoshSessionManager.kt /
 * MoshConnectInfo.kt) into 16 raw key bytes, and AES-128-OCB
 * encrypting/decrypting a single UDP payload given a 64-bit
 * direction+sequence number. This is the part of Mosh's SSP
 * (State Synchronization Protocol) that:
 *   - Doesn't depend on mosh's own source tree or protobuf (OpenSSL only,
 *     already a dependency of this app via FreeRDP's OPENSSL_ABI_DIR).
 *   - Was locally round-trip- and tamper-tested against OpenSSL's EVP
 *     AES-128-OCB implementation (nonce shape, tag size, AAD-less framing)
 *     before being committed here — see the commit notes in mosh/NOTES.md
 *     for exactly what was and wasn't verified, and why.
 *
 * What this module does NOT do, on purpose: open any socket, define the
 * mosh Instruction/UserMessage/HostMessage protobuf message schema, or run
 * any terminal emulation. Those need mosh's actual .proto definitions
 * and/or its terminal-emulation source, which this sandbox cannot fetch
 * (no network access) and which are not reproduced here from memory —
 * see mosh/NOTES.md, "Why the message schema isn't included here".
 */

#ifndef SYSTEMSGO_MOSH_CRYPTO_H
#define SYSTEMSGO_MOSH_CRYPTO_H

#include <stdint.h>

#define SYSTEMSGO_MOSH_KEY_LEN 16      /* AES-128 key */
#define SYSTEMSGO_MOSH_KEY_B64_LEN 22  /* unpadded base64 length of a 16-byte key */
#define SYSTEMSGO_MOSH_NONCE_LEN 12    /* OCB nonce length this module uses */
#define SYSTEMSGO_MOSH_TAG_LEN 16      /* OCB authentication tag length */

/*
 * Decodes a 22-character unpadded standard-base64 (RFC 4648) session key —
 * the format of the <key> field in "MOSH CONNECT <port> <key>" — into
 * SYSTEMSGO_MOSH_KEY_LEN raw bytes.
 *
 * Returns 0 on success, -1 if the input isn't exactly 22 base64 characters
 * or if the 4 leftover bits past the 128th decoded bit are non-zero (the
 * same validity check mosh's own key parser applies — 22 base64 chars
 * decode 132 bits, but only the first 128 are meaningful).
 */
int systemsgo_mosh_decode_key(const char *key_b64_22chars, unsigned char out_key[SYSTEMSGO_MOSH_KEY_LEN]);

/*
 * Builds the 12-byte OCB nonce for a given direction+sequence value:
 * 4 zero bytes followed by the 8-byte big-endian encoding of
 * direction_seq (bit 63 of direction_seq is the direction flag; the low
 * 63 bits are the packet sequence number). Caller is responsible for
 * setting bit 63 consistently for "client to server" vs "server to
 * client" so the two directions never reuse a nonce under the same key.
 */
void systemsgo_mosh_build_nonce(uint64_t direction_seq, unsigned char out_nonce[SYSTEMSGO_MOSH_NONCE_LEN]);

/*
 * AES-128-OCB encrypt. `out_ct` must have room for pt_len bytes, `out_tag`
 * for SYSTEMSGO_MOSH_TAG_LEN bytes. No associated data (AAD) is used — the
 * plaintext/tag pair, plus the sequence number carried alongside it, is
 * all a receiver needs to bind ciphertext to nonce, matching mosh's own
 * packet layout. Returns pt_len on success, -1 on failure.
 */
int systemsgo_mosh_ocb_encrypt(const unsigned char key[SYSTEMSGO_MOSH_KEY_LEN],
                             const unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN],
                             const unsigned char *pt, int pt_len,
                             unsigned char *out_ct, unsigned char out_tag[SYSTEMSGO_MOSH_TAG_LEN]);

/*
 * AES-128-OCB decrypt + authenticate. `out_pt` must have room for ct_len
 * bytes. Returns ct_len (== plaintext length, OCB in this mode doesn't
 * change length) on success, -1 if authentication fails (tampered or
 * wrong key/nonce) — callers MUST treat -1 as "drop this packet", not as
 * a recoverable error, per SSP's threat model.
 */
int systemsgo_mosh_ocb_decrypt(const unsigned char key[SYSTEMSGO_MOSH_KEY_LEN],
                             const unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN],
                             const unsigned char *ct, int ct_len,
                             const unsigned char tag[SYSTEMSGO_MOSH_TAG_LEN],
                             unsigned char *out_pt);

#endif /* SYSTEMSGO_MOSH_CRYPTO_H */
