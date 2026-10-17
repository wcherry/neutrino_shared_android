package com.neutrino.shared.core.keys

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.Sodium
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One keypair, as the legacy key file and the web's PIN-protected "mobile key code" carry it:
 * `{"public_key", "private_key", "key_version"}` (or the short `pk`/`sk`/`v`), all strings.
 *
 * It predates key versioning and carries one keypair, so it is adopted as a one-entry keyring —
 * the account's retired versions then come from the key file ([ArchivedKeys]).
 */
data class KeyBundle(val publicKey: String, val privateKey: String, val keyVersion: String) {
    override fun toString() = "KeyBundle(publicKey=$publicKey, privateKey=<redacted>, keyVersion=$keyVersion)"

    /** `key_version` is honoured; unparseable means version 1. */
    fun toKeyring(userId: String): Keyring =
        Keyring.fromKeyPair(userId, B64.decode(privateKey)!!, keyVersion.trim().toIntOrNull() ?: 1)
}

sealed class KeyImportException(message: String) : Exception(message) {
    class InvalidJson : KeyImportException("The key is not valid JSON.")
    class MissingFields : KeyImportException("The key file is missing required fields.")
    class InvalidBase64 : KeyImportException("The key contains invalid key data. Keys must be base64-encoded and 32 bytes.")
    class KeyPairMismatch : KeyImportException("The public and private keys do not form a matching pair.")
    class UnsupportedFormat : KeyImportException("PEM-encoded keys are not supported. Use the key exported by the Neutrino web app.")
}

object KeyBundleParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse and validate. The halves must be X25519 keys of the right length and the public key
     * must really be the secret key's public half — derived and compared, which is the check that
     * actually applies to these keys.
     */
    fun parse(text: String): KeyBundle {
        val obj = try {
            json.parseToJsonElement(text.trim()) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw KeyImportException.InvalidJson()
        fun field(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            (obj[n] as? JsonPrimitive)?.takeIf { it.isString || it.intOrNull != null }?.content
        }
        val pk = field("public_key", "pk") ?: throw KeyImportException.MissingFields()
        val sk = field("private_key", "sk") ?: throw KeyImportException.MissingFields()
        val version = field("key_version", "v") ?: "1"
        if (pk.startsWith("-----BEGIN") || sk.startsWith("-----BEGIN")) throw KeyImportException.UnsupportedFormat()
        val public = B64.decode(pk) ?: throw KeyImportException.InvalidBase64()
        val secret = B64.decode(sk) ?: throw KeyImportException.InvalidBase64()
        if (public.size != 32 || secret.size != 32) throw KeyImportException.InvalidBase64()
        if (!Sodium.publicKeyFromSecret(secret).contentEquals(public)) throw KeyImportException.KeyPairMismatch()
        return KeyBundle(pk, sk, version)
    }
}

/**
 * The PIN-protected key QR the web app shows for phones (`web/.../e2e-crypto/src/mobileKeyQr.ts`).
 *
 * ```
 * { "v": 1, "alg": "pbkdf2-sha256+xsalsa20", "salt", "nonce", "ct", "iter": 600000 }
 * ```
 *
 * PBKDF2-SHA256 over the PIN → 32-byte key → `crypto_secretbox_open_easy`. The plaintext is the
 * [KeyBundle] JSON. Weaker than [Pairing] (a photograph plus an offline PIN grind yields the key),
 * which is why pairing is offered first; this exists because the web still offers it.
 */
object MobileKeyQr {
    const val DEFAULT_ITERATIONS = 600_000
    private const val ALG = "pbkdf2-sha256+xsalsa20"

    sealed class QrException(message: String) : Exception(message) {
        class InvalidFormat : QrException("QR code format not recognised.")
        class UnsupportedVersion : QrException("The QR code uses an unsupported version.")
        class UnsupportedAlgorithm : QrException("The QR code uses an unsupported encryption algorithm.")
        class Base64 : QrException("Failed to decode the key payload data.")
        class WrongPin : QrException("Failed to decrypt the key. Check your PIN and try again.")
    }

    @Serializable
    private data class Envelope(
        val v: JsonPrimitive? = null,
        val alg: String? = null,
        val salt: String? = null,
        val nonce: String? = null,
        val ct: String? = null,
        val iter: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** True when `raw` is shaped like this envelope, to route a scanned QR without a PIN prompt. */
    fun looksLikeEnvelope(raw: String): Boolean = runCatching {
        json.decodeFromString(Envelope.serializer(), raw.trim()).alg == ALG
    }.getOrDefault(false)

    /** Open the envelope, returning the inner key JSON (validate it with [KeyBundleParser]). */
    fun decrypt(raw: String, pin: String): String {
        val env = try {
            json.decodeFromString(Envelope.serializer(), raw.trim())
        } catch (_: Exception) {
            throw QrException.InvalidFormat()
        }
        val version = env.v?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: throw QrException.UnsupportedVersion()
        if (version != 1) throw QrException.UnsupportedVersion()
        if (env.alg == null) throw QrException.InvalidFormat()
        if (env.alg != ALG) throw QrException.UnsupportedAlgorithm()
        val salt = env.salt?.let(B64::decode) ?: throw QrException.Base64()
        val nonce = env.nonce?.let(B64::decode) ?: throw QrException.Base64()
        val ct = env.ct?.let(B64::decode) ?: throw QrException.Base64()
        if (nonce.size != Sodium.SECRETBOX_NONCE_BYTES) throw QrException.Base64()
        val key = Sodium.pbkdf2Sha256(pin.trim(), salt, env.iter ?: DEFAULT_ITERATIONS, 32)
        val plain = try {
            Sodium.secretBoxOpenEasy(ct, nonce, key)
        } finally {
            key.fill(0)
        } ?: throw QrException.WrongPin()
        return plain.decodeToString().also { plain.fill(0) }
    }

    /** Build an envelope — the web's `exportKeyQr`, used by tests to prove the round trip. */
    fun encrypt(innerJson: String, pin: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = Sodium.randomBytes(16)
        val nonce = Sodium.randomBytes(Sodium.SECRETBOX_NONCE_BYTES)
        val key = Sodium.pbkdf2Sha256(pin, salt, iterations, 32)
        val ct = Sodium.secretBoxEasy(innerJson.toByteArray(), nonce, key)
        return """{"v":1,"alg":"$ALG","salt":"${B64.encode(salt)}","nonce":"${B64.encode(nonce)}","ct":"${B64.encode(ct)}","iter":$iterations}"""
    }
}
