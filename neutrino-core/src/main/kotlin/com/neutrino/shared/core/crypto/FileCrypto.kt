package com.neutrino.shared.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Drive E2EE scheme, shared byte for byte with every other client:
 *
 * - **File content**: written as one [SecretStream] push under a random 32-byte DEK,
 *   `[header][ciphertext]` (v0). Read in any shape the platform writes — v0, **NEB1** and
 *   **Photos-chunked** (`neutrino/agent_docs/chunked-file-encryption.md`, #318).
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

    /**
     * Opens a file body in any shape the platform writes, chosen by the bytes (spec §3.5–§3.6):
     *
     * - **NEB1** when it starts `NEB1`. A malformed header falls through to the other shapes (a
     *   random v0 header starts `NEB1` once in 2³²); a failed authentication does not.
     * - **Photos-chunked** when the metadata gives `chunkSize`.
     * - **v0** otherwise; and when that fails on a body longer than one 1 MiB chunk, Photos' 1 MiB
     *   chunks — safe, since both readings are fully authenticated.
     */
    fun decryptFile(data: ByteArray, dek: ByteArray, chunkSize: Int? = null): ByteArray {
        val failed = CryptoException("File content could not be decrypted — the data may be corrupt.")
        if (SecretStream.hasNeb1Magic(data)) {
            when (val result = SecretStream.decryptNeb1(data, dek)) {
                is SecretStream.Neb1Result.Opened -> return result.plaintext
                SecretStream.Neb1Result.BadAuthentication -> throw failed
                SecretStream.Neb1Result.BadHeader -> Unit // structural: try the other shapes
            }
        }
        if (chunkSize != null && chunkSize > 0) {
            return SecretStream.decryptChunked(data, dek, chunkSize) ?: throw failed
        }
        SecretStream.decrypt(data, dek)?.let { return it }
        if (data.size > SecretStream.HEADER_BYTES + SecretStream.PHOTOS_CHUNK_SIZE + SecretStream.ABYTES) {
            SecretStream.decryptChunked(data, dek, SecretStream.PHOTOS_CHUNK_SIZE)?.let { return it }
        }
        throw failed
    }

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
