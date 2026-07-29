/*
 * systemsgo_mosh_crypto.c — MOSH-PROTOCOL FEATURE, Part 3/N (crypto slice only).
 * See systemsgo_mosh_crypto.h for scope notes and mosh/NOTES.md for what
 * still isn't built (transport socket, message schema, terminal emulator).
 *
 * The base64-decode and AES-128-OCB logic below were both written and
 * locally round-trip-tested (encrypt->decrypt equality, plus a
 * bit-flip/tamper test asserting decrypt fails closed) against this
 * sandbox's own OpenSSL 3.0 before being committed here — see
 * mosh/NOTES.md for exactly what that test covered and its limits (it
 * confirms this module is internally self-consistent; it does NOT confirm
 * wire-compatibility with a real mosh-server, which needs the message
 * schema this file doesn't implement).
 */

#include "systemsgo_mosh_crypto.h"

#include <string.h>
#include <openssl/evp.h>

int systemsgo_mosh_decode_key(const char *key_b64_22chars, unsigned char out_key[SYSTEMSGO_MOSH_KEY_LEN])
{
    if (!key_b64_22chars || strlen(key_b64_22chars) != SYSTEMSGO_MOSH_KEY_B64_LEN) {
        return -1;
    }

    unsigned char buf[SYSTEMSGO_MOSH_KEY_LEN] = {0};
    int bitbuf = 0, bitcnt = 0, outpos = 0;

    for (int i = 0; i < SYSTEMSGO_MOSH_KEY_B64_LEN; i++) {
        char c = key_b64_22chars[i];
        int v;
        if (c >= 'A' && c <= 'Z') v = c - 'A';
        else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
        else if (c >= '0' && c <= '9') v = c - '0' + 52;
        else if (c == '+') v = 62;
        else if (c == '/') v = 63;
        else return -1; /* not a valid base64 character */

        bitbuf = (bitbuf << 6) | v;
        bitcnt += 6;
        if (bitcnt >= 8) {
            bitcnt -= 8;
            if (outpos >= SYSTEMSGO_MOSH_KEY_LEN) {
                /* Should be unreachable given 22 chars -> 132 bits -> 16
                 * whole bytes with 4 bits left over, but guard anyway
                 * rather than write out of bounds. */
                break;
            }
            buf[outpos++] = (unsigned char)((bitbuf >> bitcnt) & 0xFF);
        }
    }

    /* 22*6 = 132 bits decoded; 128 consumed into buf above, 4 leftover
     * bits must be zero for this to be a validly-encoded 128-bit key. */
    int leftover = bitbuf & ((1 << bitcnt) - 1);
    if (leftover != 0 || outpos != SYSTEMSGO_MOSH_KEY_LEN) {
        return -1;
    }

    memcpy(out_key, buf, SYSTEMSGO_MOSH_KEY_LEN);
    return 0;
}

void systemsgo_mosh_build_nonce(uint64_t direction_seq, unsigned char out_nonce[SYSTEMSGO_MOSH_NONCE_LEN])
{
    memset(out_nonce, 0, 4);
    for (int i = 0; i < 8; i++) {
        /* big-endian: most significant byte first */
        out_nonce[4 + i] = (unsigned char)((direction_seq >> (56 - 8 * i)) & 0xFF);
    }
}

int systemsgo_mosh_ocb_encrypt(const unsigned char key[SYSTEMSGO_MOSH_KEY_LEN],
                             const unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN],
                             const unsigned char *pt, int pt_len,
                             unsigned char *out_ct, unsigned char out_tag[SYSTEMSGO_MOSH_TAG_LEN])
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int len = 0, outlen = 0, ok = 1;

    if (!EVP_EncryptInit_ex(ctx, EVP_aes_128_ocb(), NULL, NULL, NULL)) ok = 0;
    if (ok && !EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, SYSTEMSGO_MOSH_NONCE_LEN, NULL)) ok = 0;
    if (ok && !EVP_EncryptInit_ex(ctx, NULL, NULL, key, nonce)) ok = 0;
    if (ok && !EVP_EncryptUpdate(ctx, out_ct, &len, pt, pt_len)) ok = 0;
    if (ok) outlen = len;
    if (ok && !EVP_EncryptFinal_ex(ctx, out_ct + outlen, &len)) ok = 0;
    if (ok) outlen += len;
    if (ok && !EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, SYSTEMSGO_MOSH_TAG_LEN, out_tag)) ok = 0;

    EVP_CIPHER_CTX_free(ctx);
    return ok ? outlen : -1;
}

int systemsgo_mosh_ocb_decrypt(const unsigned char key[SYSTEMSGO_MOSH_KEY_LEN],
                             const unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN],
                             const unsigned char *ct, int ct_len,
                             const unsigned char tag[SYSTEMSGO_MOSH_TAG_LEN],
                             unsigned char *out_pt)
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int len = 0, outlen = 0, ok = 1;

    if (!EVP_DecryptInit_ex(ctx, EVP_aes_128_ocb(), NULL, NULL, NULL)) ok = 0;
    if (ok && !EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, SYSTEMSGO_MOSH_NONCE_LEN, NULL)) ok = 0;
    if (ok && !EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce)) ok = 0;
    if (ok && !EVP_DecryptUpdate(ctx, out_pt, &len, ct, ct_len)) ok = 0;
    if (ok) outlen = len;
    /* Tag must be set before Final for OCB/GCM-style AEAD verification. */
    if (ok && !EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, SYSTEMSGO_MOSH_TAG_LEN, (void *)tag)) ok = 0;
    if (ok && !EVP_DecryptFinal_ex(ctx, out_pt + outlen, &len)) ok = 0; /* fails closed on bad tag */
    if (ok) outlen += len;

    EVP_CIPHER_CTX_free(ctx);
    return ok ? outlen : -1;
}
