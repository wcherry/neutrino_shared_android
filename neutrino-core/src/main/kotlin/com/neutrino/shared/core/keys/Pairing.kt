package com.neutrino.shared.core.keys

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.Sodium
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Receiving the keyring from a device that already has it, offline, in two QR codes. This device
 * is always the **receiver** — the role every phone takes; the web, macOS and Windows clients send.
 *
 * 1. receiver generates an ephemeral X25519 keypair and shows QR-A `{pk, n}`
 * 2. the sender scans QR-A, seals its keyring to that key, and shows QR-B
 * 3. this device scans QR-B and opens it with the ephemeral secret
 * 4. both show the same 6-digit code; the user confirms they match
 *
 * Step 4 is what defeats an active relay that substitutes its own ephemeral key: the code is
 * derived from the whole transcript, so a relay produces two different codes. Nothing here touches
 * the network. Byte-compatible with `web/packages/e2e-crypto/src/pairing.ts` and `Pairing.swift`.
 */
object Pairing {

    private const val NONCE_BYTES = 16
    private const val SAS_DIGITS = 6
    const val OFFER_TYPE = "neutrino-pair-offer"
    const val RESPONSE_TYPE = "neutrino-pair-response"

    private val json = Json { ignoreUnknownKeys = true }

    /** QR-A — what this device displays. */
    @Serializable
    data class Offer(val t: String, val v: Int, val pk: String, val n: String)

    /** QR-B — what the sending device shows in reply. */
    @Serializable
    data class Response(val t: String, val v: Int, val ct: String, val n: String)

    sealed class PairingException(message: String) : Exception(message) {
        class NotAPairingCode : PairingException("That is not a Neutrino pairing code.")
        class DifferentAttempt : PairingException("This code is from a different pairing attempt — start again.")
        class CouldNotOpen : PairingException("Could not read the pairing code — it may be damaged.")
    }

    /** Held between showing QR-A and scanning QR-B. [close] wipes the ephemeral secret. */
    class Session internal constructor(val offer: Offer, private val secretKey: ByteArray) {
        internal fun open(sealed: ByteArray): ByteArray? = Sodium.boxSealOpen(sealed, secretKey)
        fun close() = secretKey.fill(0)

        /** For `scripts/crosscheck-web-crypto.sh` only, which resumes a session across two processes. */
        internal fun secretForCrossCheck() = secretKey.copyOf()
    }

    internal fun sessionForCrossCheck(offer: Offer, secretKey: ByteArray) = Session(offer, secretKey)

    /** Mint the ephemeral pair and build QR-A. */
    fun createSession(): Session {
        val pair = Sodium.boxKeyPair()
        val offer = Offer(OFFER_TYPE, 1, B64.encode(pair.publicKey), B64.encode(Sodium.randomBytes(NONCE_BYTES)))
        return Session(offer, pair.secretKey)
    }

    /** The JSON payload to render as QR-A. */
    fun encode(offer: Offer): String = json.encodeToString(Offer.serializer(), offer)

    fun parseResponse(raw: String): Response {
        val parsed = try {
            json.decodeFromString(Response.serializer(), raw.trim())
        } catch (_: Exception) {
            throw PairingException.NotAPairingCode()
        }
        if (parsed.t != RESPONSE_TYPE || parsed.v != 1) throw PairingException.NotAPairingCode()
        return parsed
    }

    /**
     * Open QR-B and recover the keyring.
     *
     * Rejects a response whose nonce is not this session's — a QR left on screen from an earlier
     * attempt — and a keyring for an account other than the signed-in one.
     */
    fun accept(response: Response, session: Session, userId: String): Keyring {
        if (response.n != session.offer.n) throw PairingException.DifferentAttempt()
        val sealed = B64.decode(response.ct) ?: throw PairingException.CouldNotOpen()
        val opened = session.open(sealed) ?: throw PairingException.CouldNotOpen()
        val keyring = try {
            KeyringCodec.decodeJson(opened.decodeToString())
        } finally {
            opened.fill(0)
        }
        if (keyring.userId != userId) throw KeyringException.WrongAccount()
        return keyring
    }

    /** The 6-digit code both devices show: BLAKE2b-256 over the length-prefixed transcript. */
    fun confirmationCode(offer: Offer, response: Response): String {
        val input = java.io.ByteArrayOutputStream()
        for (part in listOf("neutrino-pair-sas-v1", offer.n, offer.pk, response.ct)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            input.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            input.write(bytes)
        }
        val digest = Sodium.genericHash(input.toByteArray(), 32)
        val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return (value % 1_000_000L).toString().padStart(SAS_DIGITS, '0')
    }
}
