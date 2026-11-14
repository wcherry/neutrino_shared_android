package com.neutrino.shared.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Chunked file bodies against the platform's canonical vectors (neutrino#318).
 *
 * `chunked_file_vectors.json` is a byte-identical copy of
 * `neutrino/web/packages/e2e-crypto/src/__tests__/fixtures/chunked_file_vectors.json`, the only
 * editable one, generated from an independent libsodium implementation
 * (`scripts/sync-chunked-file-vectors.sh` refreshes it). Opening every case here is what proves
 * this hand-written secretstream — now with additional data — agrees with libsodium.
 */
class ChunkedFileVectorsTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/chunked_file_vectors.json")!!.readText(),
    ).jsonObject
    private val dek = std(vectors["dek"]!!.jsonPrimitive.content)

    private fun std(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun plaintext(n: Int) = ByteArray(n) { ((it * 7 + 3) and 0xff).toByte() }

    @Test
    fun opensEveryCase() {
        val cases = vectors["cases"]!!.jsonArray
        assertEquals(11, cases.size)
        for (case in cases) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val plain = FileCrypto.decryptFile(std(c["ciphertext"]!!.jsonPrimitive.content), dek,
                c["chunkSize"]?.jsonPrimitive?.intOrNull)
            assertEquals(name, c["length"]!!.jsonPrimitive.int, plain.size)
            assertEquals(name, c["sha256"]!!.jsonPrimitive.content, sha256(plain))
        }
    }

    @Test
    fun opensAPhotosBodyWithoutItsMetadata() {
        val c = vectors["cases"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "photos-1mib" }
        val plain = FileCrypto.decryptFile(std(c["ciphertext"]!!.jsonPrimitive.content), dek)
        assertEquals(c["sha256"]!!.jsonPrimitive.content, sha256(plain))
    }

    @Test
    fun rejectsEveryRejectionWithAndWithoutMetadata() {
        val rejected = vectors["rejected"]!!.jsonArray
        assertEquals(13, rejected.size)
        for (entry in rejected) {
            val r = entry.jsonObject
            val name = r["name"]!!.jsonPrimitive.content
            val key = r["dek"]?.jsonPrimitive?.content?.let(::std) ?: dek
            val data = std(r["ciphertext"]!!.jsonPrimitive.content)
            val chunkSize = r["chunkSize"]?.jsonPrimitive?.intOrNull
            for (size in listOf(chunkSize, null)) {
                try {
                    FileCrypto.decryptFile(data, key, size)
                    fail("$name (chunkSize $size) should not decrypt")
                } catch (_: FileCrypto.CryptoException) {
                }
            }
        }
    }

    @Test
    fun neb1RoundTripsAtTheBoundarySizes() {
        for (chunk in listOf(SecretStream.NEB1_MIN_CHUNK_SIZE, 65_536)) {
            for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 1)) {
                val key = FileCrypto.generateDek()
                val plain = plaintext(size)
                val blob = SecretStream.encryptNeb1(plain, key, chunk)
                assertEquals(44 + size + 17 * maxOf(1, (size + chunk - 1) / chunk), blob.size)
                assertArrayEquals("$size at $chunk", plain, FileCrypto.decryptFile(blob, key))
            }
        }
    }

    @Test
    fun aTamperedNeb1BlobIsAuthenticationNotHeader() {
        val key = FileCrypto.generateDek()
        val blob = SecretStream.encryptNeb1(plaintext(10_000), key, 4096)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertTrue(SecretStream.decryptNeb1(blob, key) is SecretStream.Neb1Result.BadAuthentication)
        val badVersion = SecretStream.encryptNeb1(plaintext(100), key)
        badVersion[4] = 9
        assertTrue(SecretStream.decryptNeb1(badVersion, key) is SecretStream.Neb1Result.BadHeader)
    }

    @Test
    fun v0IsUnchanged() {
        val key = FileCrypto.generateDek()
        for (size in listOf(1, 4096, 2 * 1024 * 1024 + 3)) {
            val plain = plaintext(size)
            assertArrayEquals(plain, FileCrypto.decryptFile(FileCrypto.encryptFile(plain, key), key))
        }
    }
}
