package com.neutrino.shared.core.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The libsodium primitives Neutrino uses, byte-compatible with libsodium, in pure Kotlin.
 *
 * This is the **fifth** implementation of the scheme — after the web's `e2e-crypto`, iOS's
 * `NeutrinoCrypto`, the macOS client's `EncryptionService` and the Windows client's `crypto.rs` —
 * and it must agree with all of them byte for byte (root CLAUDE.md, coupling rule 5). It wraps no
 * native library: BouncyCastle supplies X25519, XSalsa20, ChaCha20, Poly1305 and BLAKE2b, and the
 * two pieces libsodium composes from them that BouncyCastle does not expose (HSalsa20 and
 * HChaCha20) are written out here. `SodiumVectorsTest` proves it opens what the web's libsodium
 * sealed, and `scripts/crosscheck-web-crypto.sh` proves the web opens what this seals.
 *
 * No JNI means no native `.so` to keep 16 KB-page aligned, and every test runs on the JVM.
 */
object Sodium {

    const val KEY_BYTES = 32
    const val BOX_PUBLIC_KEY_BYTES = 32
    const val BOX_SEAL_BYTES = 32 + 16
    const val SECRETBOX_NONCE_BYTES = 24
    const val SECRETBOX_MAC_BYTES = 16

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    // ── X25519 ──────────────────────────────────────────────────────────────────────────────────

    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    /** `crypto_box_keypair`: a random clamped-on-use scalar and its public point. */
    fun boxKeyPair(): KeyPair {
        val secret = randomBytes(KEY_BYTES)
        return KeyPair(publicKeyFromSecret(secret), secret)
    }

    /** `crypto_scalarmult_base` — the public half of a secret key, clamped like libsodium. */
    fun publicKeyFromSecret(secretKey: ByteArray): ByteArray {
        require(secretKey.size == KEY_BYTES) { "secret key must be 32 bytes" }
        val out = ByteArray(KEY_BYTES)
        X25519.scalarMultBase(secretKey, 0, out, 0)
        return out
    }

    /** `crypto_scalarmult`, refusing the all-zero output libsodium also refuses. */
    private fun scalarMult(secretKey: ByteArray, publicKey: ByteArray): ByteArray? {
        if (secretKey.size != KEY_BYTES || publicKey.size != KEY_BYTES) return null
        val out = ByteArray(KEY_BYTES)
        X25519.scalarMult(secretKey, 0, publicKey, 0, out, 0)
        return if (out.all { it.toInt() == 0 }) null else out
    }

    // ── crypto_box_seal ─────────────────────────────────────────────────────────────────────────

    /**
     * `crypto_box_seal`: an anonymous-sender box to `recipientPublicKey`.
     *
     * `ephemeral_pk || crypto_box_easy(m, nonce = BLAKE2b-24(ephemeral_pk || recipient_pk))`.
     */
    fun boxSeal(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == BOX_PUBLIC_KEY_BYTES) { "public key must be 32 bytes" }
        val ephemeral = boxKeyPair()
        try {
            val nonce = sealNonce(ephemeral.publicKey, recipientPublicKey)
            val shared = boxBeforeNm(recipientPublicKey, ephemeral.secretKey)
                ?: throw IllegalArgumentException("recipient public key is not usable")
            val boxed = secretBoxEasy(message, nonce, shared)
            shared.fill(0)
            return ephemeral.publicKey + boxed
        } finally {
            ephemeral.secretKey.fill(0)
        }
    }

    /** `crypto_box_seal_open`, deriving the recipient public key from its secret. `null` on failure. */
    fun boxSealOpen(sealed: ByteArray, recipientSecretKey: ByteArray): ByteArray? {
        if (sealed.size < BOX_SEAL_BYTES || recipientSecretKey.size != KEY_BYTES) return null
        val recipientPublicKey = publicKeyFromSecret(recipientSecretKey)
        val ephemeralPublicKey = sealed.copyOfRange(0, BOX_PUBLIC_KEY_BYTES)
        val nonce = sealNonce(ephemeralPublicKey, recipientPublicKey)
        val shared = boxBeforeNm(ephemeralPublicKey, recipientSecretKey) ?: return null
        return try {
            secretBoxOpenEasy(sealed.copyOfRange(BOX_PUBLIC_KEY_BYTES, sealed.size), nonce, shared)
        } finally {
            shared.fill(0)
        }
    }

    private fun sealNonce(ephemeralPublicKey: ByteArray, recipientPublicKey: ByteArray): ByteArray =
        genericHash(ephemeralPublicKey + recipientPublicKey, SECRETBOX_NONCE_BYTES)

    /** `crypto_box_beforenm`: HSalsa20 over the X25519 shared point, with a zero nonce. */
    private fun boxBeforeNm(publicKey: ByteArray, secretKey: ByteArray): ByteArray? {
        val point = scalarMult(secretKey, publicKey) ?: return null
        return try {
            hsalsa20(point, ByteArray(16))
        } finally {
            point.fill(0)
        }
    }

    // ── crypto_secretbox (XSalsa20-Poly1305) ───────────────────────────────────────────────────

    /** `crypto_secretbox_easy`: `mac(16) || ciphertext`. */
    fun secretBoxEasy(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == SECRETBOX_NONCE_BYTES && key.size == KEY_BYTES)
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val ciphertext = ByteArray(message.size)
        engine.processBytes(message, 0, message.size, ciphertext, 0)
        val mac = poly1305(polyKey, ciphertext)
        polyKey.fill(0)
        return mac + ciphertext
    }

    /** `crypto_secretbox_open_easy`. `null` when the MAC does not verify. */
    fun secretBoxOpenEasy(boxed: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (boxed.size < SECRETBOX_MAC_BYTES || nonce.size != SECRETBOX_NONCE_BYTES || key.size != KEY_BYTES) {
            return null
        }
        val engine = XSalsa20Engine()
        engine.init(false, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val mac = boxed.copyOfRange(0, SECRETBOX_MAC_BYTES)
        val ciphertext = boxed.copyOfRange(SECRETBOX_MAC_BYTES, boxed.size)
        val expected = poly1305(polyKey, ciphertext)
        polyKey.fill(0)
        if (!MessageDigest.isEqual(mac, expected)) return null
        val plaintext = ByteArray(ciphertext.size)
        engine.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)
        return plaintext
    }

    private fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(message, 0, message.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    // ── Hashes and KDFs ─────────────────────────────────────────────────────────────────────────

    /** Unkeyed BLAKE2b — libsodium's `crypto_generichash`. */
    fun genericHash(message: ByteArray, outputLength: Int): ByteArray {
        val digest = Blake2bDigest(null, outputLength, null, null)
        digest.update(message, 0, message.size)
        return ByteArray(outputLength).also { digest.doFinal(it, 0) }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** PBKDF2-HMAC-SHA256, matching iOS's `CCKeyDerivationPBKDF` and WebCrypto's `deriveBits`. */
    fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password.toByteArray(Charsets.UTF_8), salt, iterations)
        return (generator.generateDerivedParameters(keyLength * 8) as KeyParameter).key
    }

    // ── HSalsa20 / HChaCha20 ────────────────────────────────────────────────────────────────────

    private const val SIGMA0 = 0x61707865
    private const val SIGMA1 = 0x3320646e
    private const val SIGMA2 = 0x79622d32
    private const val SIGMA3 = 0x6b206574

    /** `crypto_core_hsalsa20` with the standard constants. */
    fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && input.size == 16)
        val x = IntArray(16)
        x[0] = SIGMA0; x[5] = SIGMA1; x[10] = SIGMA2; x[15] = SIGMA3
        for (i in 0 until 4) x[1 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[11 + i] = le32(key, 16 + i * 4)
        for (i in 0 until 4) x[6 + i] = le32(input, i * 4)
        repeat(10) {
            // Column round.
            salsaQuarter(x, 0, 4, 8, 12); salsaQuarter(x, 5, 9, 13, 1)
            salsaQuarter(x, 10, 14, 2, 6); salsaQuarter(x, 15, 3, 7, 11)
            // Row round.
            salsaQuarter(x, 0, 1, 2, 3); salsaQuarter(x, 5, 6, 7, 4)
            salsaQuarter(x, 10, 11, 8, 9); salsaQuarter(x, 15, 12, 13, 14)
        }
        val out = ByteArray(32)
        intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9])
            .forEachIndexed { i, v -> putLe32(out, i * 4, v) }
        x.fill(0)
        return out
    }

    private fun salsaQuarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
        x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
        x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
        x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
    }

    /** `crypto_core_hchacha20` with the standard constants. */
    fun hchacha20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && input.size == 16)
        val x = IntArray(16)
        x[0] = SIGMA0; x[1] = SIGMA1; x[2] = SIGMA2; x[3] = SIGMA3
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le32(input, i * 4)
        repeat(10) {
            chachaQuarter(x, 0, 4, 8, 12); chachaQuarter(x, 1, 5, 9, 13)
            chachaQuarter(x, 2, 6, 10, 14); chachaQuarter(x, 3, 7, 11, 15)
            chachaQuarter(x, 0, 5, 10, 15); chachaQuarter(x, 1, 6, 11, 12)
            chachaQuarter(x, 2, 7, 8, 13); chachaQuarter(x, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15])
            .forEachIndexed { i, v -> putLe32(out, i * 4, v) }
        x.fill(0)
        return out
    }

    private fun chachaQuarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    internal fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    internal fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}
