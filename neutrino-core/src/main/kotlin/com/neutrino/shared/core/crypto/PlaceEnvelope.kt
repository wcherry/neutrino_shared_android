package com.neutrino.shared.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The field-level envelope a saved place travels in: `task_places.encrypted_payload` on the
 * server, which can't read it. Built only from the primitives Drive files already use, so no client
 * needs new cryptography to read it:
 *
 * ```
 * encryptedPayload = JSON { "data", "key", "keyVersion", "v": 1 }
 *   key  = crypto_box_seal(DEK, account public key), base64url without padding
 *   data = one secretstream push of the payload JSON with the DEK, base64url without padding
 * payload = JSON { "lat", "lng", "name", "radiusM" }
 * ```
 *
 * A fresh 32-byte DEK per write, sealed to the active keyring version, which the envelope names so a
 * place saved before a key rotation still opens. Fields a later version adds to the payload are
 * ignored, not rejected; a different `v` is.
 *
 * The web's `sealPlace` / `openPlace` (`neutrino/web/packages/e2e-crypto`) and the shared iOS
 * `PlaceEnvelope.swift` are the other implementations; all three open the same
 * `place_envelope_vectors.json`. Changing this is a wire-format change across every client.
 */
object PlaceEnvelope {
    const val VERSION = 1

    /** What a saved place holds. Everything here is encrypted. */
    @Serializable
    data class Payload(val lat: Double, val lng: Double, val name: String, val radiusM: Int)

    /** Keys in sorted order, as the Swift encoder writes them, so equal values encode equally. */
    @Serializable
    private data class Envelope(val data: String, val key: String, val keyVersion: Int, val v: Int)

    sealed class PlaceException(message: String) : Exception(message) {
        class Malformed : PlaceException("A saved place is damaged and can't be read.")
        class UnsupportedVersion(val version: Int) :
            PlaceException("A saved place was written by a newer version of Neutrino. Update the app to read it.")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Seals `payload` to `publicKey`, the active keypair's, recording `keyVersion` for the reader. */
    fun seal(payload: Payload, publicKey: ByteArray, keyVersion: Int): String {
        val dek = FileCrypto.generateDek()
        try {
            val body = json.encodeToString(Payload.serializer(), payload).toByteArray()
            val envelope = Envelope(
                data = B64.encode(FileCrypto.encryptFile(body, dek)),
                key = FileCrypto.sealDek(dek, publicKey),
                keyVersion = keyVersion,
                v = VERSION,
            )
            return json.encodeToString(Envelope.serializer(), envelope)
        } finally {
            dek.fill(0)
        }
    }

    /** The key version `encrypted` was sealed to, so the caller can find that keypair. */
    fun keyVersion(encrypted: String): Int = envelope(encrypted).keyVersion

    /** Opens `encrypted` with the secret key for its `keyVersion`. Throws for the wrong key or altered data. */
    fun open(encrypted: String, secretKey: ByteArray): Payload {
        val envelope = envelope(encrypted)
        val dek = FileCrypto.openSealedDek(envelope.key, secretKey)
            ?: throw FileCrypto.CryptoException("This saved place was sealed to a key this device doesn't hold.")
        try {
            val data = B64.decode(envelope.data) ?: throw PlaceException.Malformed()
            val plain = FileCrypto.decryptFile(data, dek)
            return runCatching { json.decodeFromString(Payload.serializer(), plain.decodeToString()) }
                .getOrElse { throw PlaceException.Malformed() }
        } finally {
            dek.fill(0)
        }
    }

    private fun envelope(encrypted: String): Envelope {
        val obj = runCatching { json.parseToJsonElement(encrypted) as? JsonObject }.getOrNull()
            ?: throw PlaceException.Malformed()
        // Checked before decoding the rest: a later version may change the other fields.
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: throw PlaceException.Malformed()
        if (v != VERSION) throw PlaceException.UnsupportedVersion(v)
        return runCatching { json.decodeFromJsonElement(Envelope.serializer(), obj) }
            .getOrElse { throw PlaceException.Malformed() }
    }
}
