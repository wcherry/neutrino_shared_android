package com.neutrino.shared.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileCryptoTest {

    @Test
    fun fileRoundTripAtEveryPaddingBoundary() {
        val dek = FileCrypto.generateDek()
        for (len in listOf(0, 1, 15, 16, 17, 47, 48, 63, 64, 65, 4096, 65_535, 65_536, 65_537, 1 shl 20)) {
            val plain = ByteArray(len) { (it % 251).toByte() }
            val sealed = FileCrypto.encryptFile(plain, dek)
            assertEquals(SecretStream.HEADER_BYTES + SecretStream.ABYTES + len, sealed.size)
            assertArrayEquals("len=$len", plain, FileCrypto.decryptFile(sealed, dek))
        }
    }

    @Test
    fun streamingMatchesInMemoryAcrossOddReadSizes() {
        val dek = FileCrypto.generateDek()
        val plain = ByteArray(300_001) { (it * 7).toByte() }
        val encrypted = ByteArrayOutputStream()
        SecretStream.encrypt(TrickleStream(plain), encrypted, dek)
        // The streamed form opens with the in-memory decoder, and vice versa.
        assertArrayEquals(plain, SecretStream.decrypt(encrypted.toByteArray(), dek))
        val out = ByteArrayOutputStream()
        assert(SecretStream.decrypt(TrickleStream(SecretStream.encrypt(plain, dek)), out, dek))
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun wrongKeyTamperingAndTruncationAreRejected() {
        val dek = FileCrypto.generateDek()
        val sealed = FileCrypto.encryptFile("hello".toByteArray(), dek)
        assertNull(SecretStream.decrypt(sealed, FileCrypto.generateDek()))
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", SecretStream.decrypt(tampered, dek))
        }
        assertNull(SecretStream.decrypt(sealed.copyOf(10), dek))
        assertFalse(SecretStream.decrypt(ByteArrayInputStream(sealed.copyOf(sealed.size - 1)), ByteArrayOutputStream(), dek))
    }

    @Test
    fun sealedDekRoundTripAndWrongKey() {
        val recipient = Sodium.boxKeyPair()
        val dek = FileCrypto.generateDek()
        val sealed = FileCrypto.sealDek(dek, recipient.publicKey)
        assertEquals(Sodium.BOX_SEAL_BYTES + 32, B64.decode(sealed)!!.size)
        assertArrayEquals(dek, FileCrypto.openSealedDek(sealed, recipient.secretKey))
        assertNull(FileCrypto.openSealedDek(sealed, Sodium.boxKeyPair().secretKey))
        assertNull(FileCrypto.openSealedDek("***", recipient.secretKey))
    }

    @Test
    fun metadataIsTheWebJsonShape() {
        val dek = FileCrypto.generateDek()
        val blob = FileCrypto.encryptMetadata("Report.pdf", "application/pdf", dek)
        val json = FileCrypto.decryptFile(B64.decode(blob)!!, dek).decodeToString()
        assertEquals("""{"name":"Report.pdf","mimeType":"application/pdf"}""", json)
        assertEquals(FileCrypto.Metadata("Report.pdf", "application/pdf"), FileCrypto.decryptMetadata(blob, dek))
    }

    @Test
    fun secretBoxRoundTrip() {
        val key = Sodium.randomBytes(32)
        val nonce = Sodium.randomBytes(24)
        val boxed = Sodium.secretBoxEasy("abc".toByteArray(), nonce, key)
        assertEquals("abc", Sodium.secretBoxOpenEasy(boxed, nonce, key)!!.decodeToString())
        boxed[0] = (boxed[0].toInt() xor 1).toByte()
        assertNull(Sodium.secretBoxOpenEasy(boxed, nonce, key))
    }

    @Test
    fun base64AcceptsBothAlphabets() {
        val bytes = ByteArray(256) { it.toByte() }
        val url = B64.encode(bytes)
        assertFalse(url.contains('=') || url.contains('+') || url.contains('/'))
        assertArrayEquals(bytes, B64.decode(url))
        assertArrayEquals(bytes, B64.decode(java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(B64.decode("not base64!"))
    }

    /** Hands out data a few bytes at a time, to exercise the streams' buffering edges. */
    private class TrickleStream(private val data: ByteArray) : java.io.InputStream() {
        private var pos = 0
        private var step = 1
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            step = (step * 31 + 7) % 70_001 + 1
            val n = minOf(len, step, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }
}
