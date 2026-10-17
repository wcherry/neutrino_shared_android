package com.neutrino.shared.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Content written as several secretstream pushes, as Neutrino Photos on iOS writes large files,
 * and the saved-place envelope Calendar reads and writes.
 */
class ChunkedAndPlaceTest {

    private val chunked: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/chunked_stream_vectors.json")!!.readText(),
    ).jsonObject
    private val key = B64.decode(chunked["key"]!!.jsonPrimitive.content)!!

    private fun expected(length: Int) = ByteArray(length) { i -> ((i * 7 + 3) and 0xff).toByte() }

    @Test
    fun opensEveryStreamLibsodiumWroteInChunks() {
        for (case in chunked["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val data = B64.decode(c["data"]!!.jsonPrimitive.content)!!
            val opened = SecretStream.decryptChunked(data, key, c["chunkSize"]!!.jsonPrimitive.int)
            assertArrayEquals(c["name"]!!.jsonPrimitive.content, expected(c["length"]!!.jsonPrimitive.int), opened)
        }
    }

    @Test
    fun refusesATruncatedReorderedOrTamperedStream() {
        val c = chunked["cases"]!!.jsonArray[0].jsonObject
        val data = B64.decode(c["data"]!!.jsonPrimitive.content)!!
        val chunk = 256 + SecretStream.ABYTES
        // Cut after the second chunk: every chunk left authenticates, but there is no FINAL.
        assertNull(SecretStream.decryptChunked(data.copyOf(SecretStream.HEADER_BYTES + 2 * chunk), key, 256))
        // The first two chunks swapped.
        val swapped = data.copyOf()
        System.arraycopy(data, SecretStream.HEADER_BYTES + chunk, swapped, SecretStream.HEADER_BYTES, chunk)
        System.arraycopy(data, SecretStream.HEADER_BYTES, swapped, SecretStream.HEADER_BYTES + chunk, chunk)
        assertNull(SecretStream.decryptChunked(swapped, key, 256))
        // One bit flipped, and bytes after the end.
        val flipped = data.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertNull(SecretStream.decryptChunked(flipped, key, 256))
        assertNull(SecretStream.decryptChunked(data + ByteArray(3), key, 256))
        // Read with the wrong framing.
        assertNull(SecretStream.decryptChunked(data, key, 128))
    }

    @Test
    fun fileCryptoPicksTheFramingFromTheMetadata() {
        val dek = FileCrypto.generateDek()
        val single = FileCrypto.encryptFile("hello".toByteArray(), dek)
        assertEquals("hello", FileCrypto.decryptFile(single, dek).decodeToString())
        assertEquals("hello", FileCrypto.decryptFile(single, dek, chunkSize = null).decodeToString())
        // A chunkSize is part of the metadata only when set, so the web reads the same JSON as before.
        val meta = FileCrypto.encryptMetadata("a.txt", "text/plain", dek)
        val opened = FileCrypto.decryptMetadata(meta, dek)
        assertEquals(FileCrypto.Metadata("a.txt", "text/plain", null), opened)
        assertEquals("""{"name":"a.txt","mimeType":"text/plain"}""", SecretStream.decrypt(B64.decode(meta)!!, dek)!!.decodeToString())
    }

    @Test
    fun placesRoundTripAndRefuseTheWrongKeyOrAnotherVersion() {
        val pair = Sodium.boxKeyPair()
        val payload = PlaceEnvelope.Payload(lat = 51.5007, lng = -0.1246, name = "Westminster", radiusM = 150)
        val sealed = PlaceEnvelope.seal(payload, pair.publicKey, keyVersion = 3)
        assertEquals(3, PlaceEnvelope.keyVersion(sealed))
        assertEquals(payload, PlaceEnvelope.open(sealed, pair.secretKey))
        try {
            PlaceEnvelope.open(sealed, Sodium.boxKeyPair().secretKey); fail()
        } catch (_: FileCrypto.CryptoException) {
        }
        val v2 = sealed.replace("\"v\":1", "\"v\":2")
        try {
            PlaceEnvelope.keyVersion(v2); fail()
        } catch (e: PlaceEnvelope.PlaceException.UnsupportedVersion) {
            assertEquals(2, e.version)
        }
        try {
            PlaceEnvelope.open("not json", pair.secretKey); fail()
        } catch (_: PlaceEnvelope.PlaceException.Malformed) {
        }
    }
}
