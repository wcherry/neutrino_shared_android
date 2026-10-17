package com.neutrino.shared.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves this crypto opens what the web's libsodium sealed.
 *
 * `place_envelope_vectors.json` is a byte-identical copy of
 * `neutrino/web/packages/e2e-crypto/src/__tests__/fixtures/place_envelope_vectors.json`, generated
 * by the web's own `e2e-crypto` (iOS, macOS and Windows carry the same copy). The envelope uses
 * exactly the Drive primitives: `crypto_box_seal` for the DEK and one secretstream push for the
 * data. Copy the file again whenever it is regenerated.
 */
class SodiumVectorsTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/place_envelope_vectors.json")!!.readText(),
    ).jsonObject

    private fun key(version: String) = vectors["keys"]!!.jsonObject[version]!!.jsonObject
    private fun secret(version: String) = B64.decode(key(version)["secretKey"]!!.jsonPrimitive.content)!!

    /** Through the real [PlaceEnvelope], the way Calendar opens a saved place. */
    private fun open(payload: String, secretKey: ByteArray): PlaceEnvelope.Payload? {
        assertEquals(1, Json.parseToJsonElement(payload).jsonObject["v"]!!.jsonPrimitive.int)
        return runCatching { PlaceEnvelope.open(payload, secretKey) }.getOrNull()
    }

    @Test
    fun derivesTheWebPublicKeys() {
        for ((version, k) in vectors["keys"]!!.jsonObject) {
            val expected = k.jsonObject["publicKey"]!!.jsonPrimitive.content
            assertEquals(version, expected, B64.encode(Sodium.publicKeyFromSecret(secret(version))))
        }
    }

    @Test
    fun opensEveryEnvelopeTheWebSealed() {
        val cases = vectors["cases"]!!.let { Json.decodeFromJsonElement(kotlinx.serialization.json.JsonArray.serializer(), it) }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val c = case.jsonObject
            val version = c["keyVersion"]!!.jsonPrimitive.int.toString()
            val opened = open(c["encryptedPayload"]!!.jsonPrimitive.content, secret(version))
                ?: error("could not open ${c["name"]}")
            val expected = c["expected"]!!.jsonObject
            assertEquals(expected["name"]!!.jsonPrimitive.content, opened.name)
            assertEquals(expected["lat"]!!.jsonPrimitive.double, opened.lat, 0.0)
            assertEquals(expected["lng"]!!.jsonPrimitive.double, opened.lng, 0.0)
            assertEquals(expected["radiusM"]!!.jsonPrimitive.int, opened.radiusM)
            assertEquals(c["keyVersion"]!!.jsonPrimitive.int, PlaceEnvelope.keyVersion(c["encryptedPayload"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun anotherVersionsKeyCannotOpenIt() {
        val cases = vectors["cases"]!! as kotlinx.serialization.json.JsonArray
        val sealedTo2 = cases.first { it.jsonObject["keyVersion"]!!.jsonPrimitive.int == 2 }.jsonObject
        assertNull(open(sealedTo2["encryptedPayload"]!!.jsonPrimitive.content, secret("1")))
    }

    @Test
    fun genericHashMatchesLibsodiumKnownAnswer() {
        // crypto_generichash("", 32) — the BLAKE2b-256 empty-string digest.
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            Sodium.genericHash(ByteArray(0), 32).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun hsalsa20MatchesTheNaClTestVector() {
        // crypto_box_beforenm of Alice's secret and Bob's public key, from NaCl's tests/box.c —
        // the shared key `k` printed by tests/core1.c.
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val k = Sodium.hsalsa20(shared, ByteArray(16))
        assertEquals("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389", k.hex())
    }

    @Test
    fun hchacha20MatchesTheDraftVector() {
        // draft-irtf-cfrg-xchacha §2.2.1.
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hex("000000090000004a0000000031415927")
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            Sodium.hchacha20(key, nonce).hex(),
        )
    }

    @Test
    fun pkceMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            FileCrypto.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
