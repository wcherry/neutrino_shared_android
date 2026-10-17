package com.neutrino.shared.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Drive E2EE scheme, shared byte for byte with every other client:
 *
 * - **File content**: one [SecretStream] push under a random 32-byte DEK, `[header][ciphertext]`.
 * - **DEK transport**: `crypto_box_seal` to a keyring entry's public key, base64url.
 * - **Metadata**: `{"name","mimeType"}` JSON through the same file encryption, base64url — the
 *   `encrypted_metadata` upload field.
 */
object FileCrypto {

    const val DEK_BYTES = 32

    class CryptoException(message: String) : Exception(message)

    /** `chunkSize` is set only for content written as several pushes (Neutrino Photos on iOS). */
    @Serializable
    data class Metadata(val name: String, val mimeType: String, val chunkSize: Int? = null)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun generateDek(): ByteArray = Sodium.randomBytes(DEK_BYTES)

    fun encryptFile(plaintext: ByteArray, dek: ByteArray): ByteArray = SecretStream.encrypt(plaintext, dek)

    /** `chunkSize` comes from the file's metadata; `null` (or 0) for the usual single push. */
    fun decryptFile(data: ByteArray, dek: ByteArray, chunkSize: Int? = null): ByteArray =
        (if (chunkSize != null && chunkSize > 0) SecretStream.decryptChunked(data, dek, chunkSize) else SecretStream.decrypt(data, dek))
            ?: throw CryptoException("File content could not be decrypted — the data may be corrupt.")

    fun encryptMetadata(name: String, mimeType: String, dek: ByteArray): String =
        B64.encode(encryptFile(json.encodeToString(Metadata.serializer(), Metadata(name, mimeType)).toByteArray(), dek))

    fun decryptMetadata(encrypted: String, dek: ByteArray): Metadata {
        val data = B64.decode(encrypted) ?: throw CryptoException("Metadata is not valid base64.")
        return json.decodeFromString(Metadata.serializer(), decryptFile(data, dek).decodeToString())
    }

    /**
     * `crypto_box_seal(dek, recipient)` as base64url.
     *
     * Refuses a key that is not 32 bytes rather than sealing to it: the result would be ciphertext
     * nobody can open, and nothing would fail until someone tried.
     */
    fun sealDek(dek: ByteArray, recipientPublicKey: ByteArray): String {
        if (recipientPublicKey.size != Sodium.BOX_PUBLIC_KEY_BYTES) {
            throw CryptoException("That encryption key is not valid.")
        }
        return B64.encode(Sodium.boxSeal(dek, recipientPublicKey))
    }

    fun sealDek(dek: ByteArray, recipientPublicKeyB64: String): String =
        sealDek(dek, B64.decode(recipientPublicKeyB64) ?: throw CryptoException("That encryption key is not valid."))

    /** Opens a base64url sealed DEK with a keyring entry's secret key, or `null`. */
    fun openSealedDek(sealedB64: String, secretKey: ByteArray): ByteArray? {
        val sealed = B64.decode(sealedB64) ?: return null
        val dek = Sodium.boxSealOpen(sealed, secretKey) ?: return null
        return if (dek.size == DEK_BYTES) dek else null
    }

    /** PKCE code challenge: base64url(SHA-256(verifier)). */
    fun pkceChallenge(verifier: String): String = B64.encode(Sodium.sha256(verifier.toByteArray()))

    fun sha256Hex(data: ByteArray): String = Sodium.sha256(data).joinToString("") { "%02x".format(it) }
}
