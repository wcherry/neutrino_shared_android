package com.neutrino.shared.core.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * libsodium's `crypto_secretstream_xchacha20poly1305`, restricted to what Neutrino uses: a stream
 * of exactly **one** message, serialised as `[24-byte header][tag byte][ciphertext][16-byte MAC]`.
 *
 * Every client writes file content, encrypted metadata and the search snapshot this way (one push
 * tagged `FINAL`), and reads it ignoring the tag. One writer differs: Neutrino Photos on iOS writes
 * large files as a run of pushes of a fixed plaintext size, the last tagged `FINAL`, and records
 * that size as `chunkSize` in the encrypted metadata. [decryptChunked] reads those, as the shared
 * iOS `DriveFileCrypto.decrypt(_:dek:chunkSize:)` does.
 *
 * Unlike the other clients this works on streams. One message still means one MAC over the whole
 * file, but nothing about the construction needs the plaintext in memory at once: encryption
 * writes ciphertext as it goes, and decryption holds back the last 16 bytes (the MAC) and verifies
 * at the end. A phone uploading a 2 GB video therefore never holds 2 GB. **Decrypted output is
 * untrusted until [decrypt] returns true** — callers write it somewhere temporary and discard it
 * on failure.
 *
 * One libsodium quirk is reproduced on purpose: the padding after the ciphertext is
 * `(0x10 - 64 + mlen) & 0xf` bytes, i.e. `mlen mod 16`, not the usual `-mlen mod 16`. It is what
 * libsodium does and therefore what the wire format is.
 */
object SecretStream {

    const val KEY_BYTES = 32
    const val HEADER_BYTES = 24
    /** Tag byte + Poly1305 MAC. */
    const val ABYTES = 17
    const val TAG_MESSAGE: Byte = 0
    const val TAG_REKEY: Byte = 2
    const val TAG_FINAL: Byte = 3

    private const val BUFFER = 64 * 1024

    fun ciphertextLength(plaintextLength: Long): Long = HEADER_BYTES + ABYTES + plaintextLength

    /** `[header][one FINAL-tagged message]` of `plaintext`. */
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size + HEADER_BYTES + ABYTES)
        encrypt(ByteArrayInputStream(plaintext), out, key)
        return out.toByteArray()
    }

    /** Inverse of [encrypt], or `null` when the data does not authenticate under `key`. */
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < HEADER_BYTES + ABYTES) return null
        val out = ByteArrayOutputStream(data.size)
        return if (decrypt(ByteArrayInputStream(data), out, key)) out.toByteArray() else null
    }

    /**
     * Streams `input` through one secretstream push into `output`. Returns the plaintext length.
     * Does not close either stream.
     */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, tag: Byte = TAG_FINAL): Long {
        require(key.size == KEY_BYTES) { "secretstream key must be 32 bytes" }
        val header = Sodium.randomBytes(HEADER_BYTES)
        val state = State(key, header)
        output.write(header)

        val block = ByteArray(64)
        block[0] = tag
        state.engine.processBytes(block, 0, 64, block, 0)
        state.poly.update(block, 0, 64)
        output.write(block[0].toInt())

        var mlen = 0L
        val buf = ByteArray(BUFFER)
        val cipher = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            state.engine.processBytes(buf, 0, n, cipher, 0)
            state.poly.update(cipher, 0, n)
            output.write(cipher, 0, n)
            mlen += n
        }
        buf.fill(0)
        output.write(state.finish(mlen))
        return mlen
    }

    /**
     * Streams a one-message secretstream from `input`, writing plaintext to `output` as it goes.
     *
     * Returns true only when the MAC verified. On false, everything written to `output` is
     * unauthenticated and must be discarded. Does not close either stream.
     */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray): Boolean {
        if (key.size != KEY_BYTES) return false
        val header = input.readExactly(HEADER_BYTES) ?: return false
        val state = State(key, header)

        val c0 = input.read()
        if (c0 < 0) return false
        val block = ByteArray(64)
        block[0] = c0.toByte()
        state.engine.processBytes(block, 0, 64, block, 0)
        block[0] = c0.toByte()
        state.poly.update(block, 0, 64)

        // Everything up to the last 16 bytes is ciphertext; those 16 are the MAC. The stream's
        // length is not known in advance, so the most recent 16 bytes are always held back.
        val mac = 16
        var held = ByteArray(0)
        var mlen = 0L
        val buf = ByteArray(BUFFER)
        val plain = ByteArray(BUFFER + mac)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val combined = held + buf.copyOf(n)
            val emit = combined.size - mac
            if (emit > 0) {
                state.poly.update(combined, 0, emit)
                state.engine.processBytes(combined, 0, emit, plain, 0)
                output.write(plain, 0, emit)
                mlen += emit
                held = combined.copyOfRange(emit, combined.size)
            } else {
                held = combined
            }
        }
        plain.fill(0)
        if (held.size != mac) return false
        return MessageDigest.isEqual(state.finish(mlen), held)
    }

    /**
     * Opens a stream of several messages, each `chunkSize` bytes of plaintext but the last, which
     * must be tagged `FINAL` and end the data. `null` when any chunk fails to authenticate, or the
     * stream is cut short or runs on past its final chunk.
     *
     * libsodium's `crypto_secretstream_xchacha20poly1305_pull`, message after message: each one's
     * MAC is folded into the nonce and the counter stepped, and a `REKEY` tag (or a counter that
     * wraps) derives a new key, so chunks cannot be reordered, dropped or replayed.
     */
    fun decryptChunked(data: ByteArray, key: ByteArray, chunkSize: Int): ByteArray? {
        if (key.size != KEY_BYTES || chunkSize <= 0 || data.size < HEADER_BYTES + ABYTES) return null
        var subkey = Sodium.hchacha20(key, data.copyOfRange(0, 16))
        val nonce = ByteArray(12)
        Sodium.putLe32(nonce, 0, 1)
        System.arraycopy(data, 16, nonce, 4, 8)
        val out = ByteArrayOutputStream(data.size)
        var offset = HEADER_BYTES
        try {
            while (offset < data.size) {
                val end = minOf(offset + chunkSize + ABYTES, data.size)
                if (end - offset < ABYTES) return null
                val (plain, tag, mac) = pullOne(subkey, nonce, data, offset, end) ?: return null
                out.write(plain)
                plain.fill(0)
                offset = end
                // The MAC goes into the nonce, then the counter steps (libsodium's order).
                for (i in 0 until 8) nonce[4 + i] = (nonce[4 + i].toInt() xor mac[i].toInt()).toByte()
                for (i in 0 until 4) {
                    nonce[i] = (nonce[i] + 1).toByte()
                    if (nonce[i].toInt() != 0) break
                }
                val counterWrapped = nonce[0].toInt() == 0 && nonce[1].toInt() == 0 && nonce[2].toInt() == 0 && nonce[3].toInt() == 0
                if (tag.toInt() and TAG_REKEY.toInt() != 0 || counterWrapped) subkey = rekey(subkey, nonce)
                if (tag == TAG_FINAL) return if (offset == data.size) out.toByteArray() else null
            }
            // Every chunk authenticates itself; only a missing FINAL shows a stream cut short.
            return null
        } finally {
            subkey.fill(0)
        }
    }

    /** One message of `data[from, to)`: its plaintext, its tag and its MAC, or `null`. */
    private fun pullOne(subkey: ByteArray, nonce: ByteArray, data: ByteArray, from: Int, to: Int): Triple<ByteArray, Byte, ByteArray>? {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(subkey), nonce.copyOf()))
        val poly = Poly1305()
        val block0 = ByteArray(64)
        engine.processBytes(ByteArray(64), 0, 64, block0, 0)
        poly.init(KeyParameter(block0.copyOfRange(0, 32)))
        block0.fill(0)

        val block = ByteArray(64)
        block[0] = data[from]
        engine.processBytes(block, 0, 64, block, 0)
        val tag = block[0]
        block[0] = data[from]
        poly.update(block, 0, 64)

        val mlen = to - from - ABYTES
        val cStart = from + 1
        poly.update(data, cStart, mlen)
        val pad = (mlen and 0xf)
        if (pad > 0) poly.update(ByteArray(pad), 0, pad)
        val lengths = ByteArray(16)
        putLe64(lengths, 0, 0L)
        putLe64(lengths, 8, 64L + mlen)
        poly.update(lengths, 0, 16)
        val mac = ByteArray(16).also { poly.doFinal(it, 0) }
        if (!MessageDigest.isEqual(mac, data.copyOfRange(cStart + mlen, to))) return null

        val plain = ByteArray(mlen)
        engine.processBytes(data, cStart, mlen, plain, 0)
        return Triple(plain, tag, mac)
    }

    /** `crypto_secretstream_xchacha20poly1305_rekey`: a new key and nonce tail from the stream, counter back to 1. */
    private fun rekey(subkey: ByteArray, nonce: ByteArray): ByteArray {
        val material = ByteArray(KEY_BYTES + 8)
        System.arraycopy(subkey, 0, material, 0, KEY_BYTES)
        System.arraycopy(nonce, 4, material, KEY_BYTES, 8)
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(subkey), nonce.copyOf()))
        engine.processBytes(material, 0, material.size, material, 0)
        System.arraycopy(material, KEY_BYTES, nonce, 4, 8)
        Sodium.putLe32(nonce, 0, 1)
        subkey.fill(0)
        return material.copyOfRange(0, KEY_BYTES).also { material.fill(0) }
    }

    /** The per-message state after `init_push` / `init_pull`, positioned at block 1. */
    private class State(key: ByteArray, header: ByteArray) {
        val engine = ChaCha7539Engine()
        val poly = Poly1305()

        init {
            val subkey = Sodium.hchacha20(key, header.copyOfRange(0, 16))
            // nonce = counter (LE32, starts at 1) || header[16..24]
            val nonce = ByteArray(12)
            Sodium.putLe32(nonce, 0, 1)
            System.arraycopy(header, 16, nonce, 4, 8)
            engine.init(true, ParametersWithIV(KeyParameter(subkey), nonce))
            subkey.fill(0)
            // Block 0 of the keystream is the one-time Poly1305 key.
            val block0 = ByteArray(64)
            engine.processBytes(ByteArray(64), 0, 64, block0, 0)
            poly.init(KeyParameter(block0.copyOfRange(0, 32)))
            block0.fill(0)
            // No additional data, so neither it nor its padding is authenticated.
        }

        /** Padding, the two lengths, and the MAC. */
        fun finish(mlen: Long): ByteArray {
            val pad = (mlen and 0xf).toInt()
            if (pad > 0) poly.update(ByteArray(pad), 0, pad)
            val lengths = ByteArray(16)
            putLe64(lengths, 0, 0L)
            putLe64(lengths, 8, 64L + mlen)
            poly.update(lengths, 0, 16)
            return ByteArray(16).also { poly.doFinal(it, 0) }
        }
    }

    private fun putLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
    }

    private fun InputStream.readExactly(n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) return null
            read += r
        }
        return out
    }
}
