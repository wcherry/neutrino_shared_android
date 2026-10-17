package com.neutrino.shared.core.ooxml

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

sealed class ZipException(message: String) : Exception(message) {
    /** The bytes are not a zip at all — no end-of-central-directory record. */
    class NotAnArchive : ZipException("This file is not a zip archive.")
    /** The central directory names an entry the file does not contain. */
    class CorruptEntry(name: String) : ZipException("The archive entry $name is damaged.")
    /** An entry uses a compression method other than store or deflate. */
    class UnsupportedCompression(name: String) : ZipException("The archive entry $name uses an unsupported compression method.")
}

/**
 * The zip container an OOXML package is: read whole into memory, written whole out of it. The
 * Kotlin half of `ZipArchive.swift`.
 *
 * What is implemented is exactly what OOXML uses — stored and deflated entries, no encryption, no
 * zip64, no spanning — on the JDK's own `Inflater`/`Deflater` in raw (no zlib header) mode, which is
 * what a zip entry holds.
 *
 * **Deterministic.** Two writes of the same parts produce byte-identical archives: entries keep the
 * order they were added in and every header carries the same fixed DOS timestamp. The offline cache
 * compares stored bytes to decide whether a document changed, so an archive that embedded the
 * current time would make every no-op save look like an edit.
 */
class ZipArchive() {

    private val parts = LinkedHashMap<String, ByteArray>()

    /** Part paths in the order they appear in the archive. */
    val names: List<String> get() = parts.keys.toList()

    /** Reads an archive from `bytes`. */
    constructor(bytes: ByteArray) : this() {
        val directoryStart = centralDirectoryOffset(bytes) ?: throw ZipException.NotAnArchive()
        var cursor = directoryStart
        while (cursor + 46 <= bytes.size && readUInt32(bytes, cursor) == 0x02014B50L) {
            val method = readUInt16(bytes, cursor + 10)
            val compressedSize = readUInt32(bytes, cursor + 20).toInt()
            val uncompressedSize = readUInt32(bytes, cursor + 24).toInt()
            val nameLength = readUInt16(bytes, cursor + 28)
            val extraLength = readUInt16(bytes, cursor + 30)
            val commentLength = readUInt16(bytes, cursor + 32)
            val localOffset = readUInt32(bytes, cursor + 42).toInt()
            if (cursor + 46 + nameLength > bytes.size) throw ZipException.NotAnArchive()
            val name = String(bytes, cursor + 46, nameLength, Charsets.UTF_8)
            cursor += 46 + nameLength + extraLength + commentLength

            // A directory entry has no content of its own; the parts below it carry their full path.
            if (name.endsWith("/")) continue
            parts[name] = contents(name, bytes, localOffset, method, compressedSize, uncompressedSize)
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    fun data(name: String): ByteArray? = parts[name]

    /** One part decoded as UTF-8, which is what every XML part in a package is. */
    fun text(name: String): String? = parts[name]?.let { String(it, Charsets.UTF_8) }

    operator fun contains(name: String): Boolean = parts.containsKey(name)

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /** Adds or replaces a part. A replaced part keeps its position. */
    fun set(name: String, data: ByteArray) {
        parts[name] = data
    }

    fun set(name: String, text: String) = set(name, text.toByteArray(Charsets.UTF_8))

    fun remove(name: String) {
        parts.remove(name)
    }

    /**
     * Serialises the archive. Every part is deflated unless that makes it larger (a JPEG, say), when
     * it is stored. `[Content_Types].xml` is written first: the OPC specification requires it.
     */
    fun serialized(): ByteArray {
        val ordered = names.toMutableList()
        val typesIndex = ordered.indexOf(OoxmlPackage.CONTENT_TYPES_PART)
        if (typesIndex > 0) {
            ordered.removeAt(typesIndex)
            ordered.add(0, OoxmlPackage.CONTENT_TYPES_PART)
        }

        val output = ByteArrayOutputStream()
        val directory = ByteArrayOutputStream()
        var count = 0

        for (name in ordered) {
            val plain = parts[name] ?: continue
            val crc = CRC32().apply { update(plain) }.value
            val deflated = deflate(plain)
            val stored = deflated.size >= plain.size
            val payload = if (stored) plain else deflated
            val method = if (stored) 0 else 8
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val localOffset = output.size()

            output.u32(0x04034B50)              // local file header signature
            output.u16(20)                      // version needed to extract
            output.u16(0)                       // flags
            output.u16(method)
            output.u16(DOS_TIME)
            output.u16(DOS_DATE)
            output.u32(crc)
            output.u32(payload.size.toLong())
            output.u32(plain.size.toLong())
            output.u16(nameBytes.size)
            output.u16(0)                       // extra field length
            output.write(nameBytes)
            output.write(payload)

            directory.u32(0x02014B50)           // central directory header signature
            directory.u16(20)                   // version made by
            directory.u16(20)                   // version needed
            directory.u16(0)                    // flags
            directory.u16(method)
            directory.u16(DOS_TIME)
            directory.u16(DOS_DATE)
            directory.u32(crc)
            directory.u32(payload.size.toLong())
            directory.u32(plain.size.toLong())
            directory.u16(nameBytes.size)
            directory.u16(0)                    // extra
            directory.u16(0)                    // comment
            directory.u16(0)                    // disk number
            directory.u16(0)                    // internal attributes
            directory.u32(0)                    // external attributes
            directory.u32(localOffset.toLong())
            directory.write(nameBytes)
            count++
        }

        val directoryOffset = output.size()
        val directoryBytes = directory.toByteArray()
        output.write(directoryBytes)
        output.u32(0x06054B50)                  // end of central directory signature
        output.u16(0)                           // this disk
        output.u16(0)                           // disk with the directory
        output.u16(count)
        output.u16(count)
        output.u32(directoryBytes.size.toLong())
        output.u32(directoryOffset.toLong())
        output.u16(0)                           // comment length
        return output.toByteArray()
    }

    companion object {
        /** MS-DOS 1980-01-01 00:00:00, the earliest timestamp the format can express. */
        private const val DOS_TIME = 0
        private const val DOS_DATE = 0x0021

        /** A zip's local file header: the cheapest way to reject bytes that cannot be a package. */
        fun looksLikeArchive(data: ByteArray): Boolean =
            data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() && data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

        private fun contents(name: String, bytes: ByteArray, localHeaderOffset: Int, method: Int, compressedSize: Int, uncompressedSize: Int): ByteArray {
            if (localHeaderOffset < 0 || localHeaderOffset + 30 > bytes.size || readUInt32(bytes, localHeaderOffset) != 0x04034B50L) {
                throw ZipException.CorruptEntry(name)
            }
            // The local header's own name and extra lengths say where the bytes start; they may differ
            // from the directory's.
            val nameLength = readUInt16(bytes, localHeaderOffset + 26)
            val extraLength = readUInt16(bytes, localHeaderOffset + 28)
            val start = localHeaderOffset + 30 + nameLength + extraLength
            if (compressedSize < 0 || start + compressedSize > bytes.size) throw ZipException.CorruptEntry(name)
            val payload = bytes.copyOfRange(start, start + compressedSize)
            return when (method) {
                0 -> payload
                8 -> inflate(payload, uncompressedSize, name)
                else -> throw ZipException.UnsupportedCompression(name)
            }
        }

        private fun deflate(bytes: ByteArray): ByteArray {
            if (bytes.isEmpty()) return ByteArray(0)
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream(bytes.size / 2 + 64)
                val buffer = ByteArray(16 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buffer)
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private fun inflate(bytes: ByteArray, expected: Int, name: String): ByteArray {
            if (bytes.isEmpty()) return ByteArray(0)
            val inflater = Inflater(true)
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream(if (expected > 0) expected else bytes.size * 4)
                val buffer = ByteArray(16 * 1024)
                var fedPadding = false
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    out.write(buffer, 0, n)
                    if (n == 0) {
                        if (inflater.needsDictionary()) throw ZipException.CorruptEntry(name)
                        if (inflater.needsInput()) {
                            // Raw inflate can ask for one byte past the stream (see `Inflater`'s
                            // `nowrap` note); a second request means the entry is truncated.
                            if (fedPadding) throw ZipException.CorruptEntry(name)
                            inflater.setInput(ByteArray(1))
                            fedPadding = true
                        }
                    }
                }
                return out.toByteArray()
            } catch (e: java.util.zip.DataFormatException) {
                throw ZipException.CorruptEntry(name)
            } finally {
                inflater.end()
            }
        }

        /** Scans back for the end-of-central-directory record, at most a 64 KiB comment from the end. */
        private fun centralDirectoryOffset(bytes: ByteArray): Int? {
            if (bytes.size < 22) return null
            val earliest = maxOf(0, bytes.size - 22 - 65535)
            var index = bytes.size - 22
            while (index >= earliest) {
                if (readUInt32(bytes, index) == 0x06054B50L) {
                    val offset = readUInt32(bytes, index + 16)
                    return if (offset <= bytes.size) offset.toInt() else null
                }
                index--
            }
            return null
        }

        private fun readUInt16(bytes: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 2 > bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readUInt32(bytes: ByteArray, offset: Int): Long {
            if (offset < 0 || offset + 4 > bytes.size) return 0
            return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        private fun ByteArrayOutputStream.u16(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
        }

        private fun ByteArrayOutputStream.u32(value: Long) {
            write((value and 0xFF).toInt())
            write(((value shr 8) and 0xFF).toInt())
            write(((value shr 16) and 0xFF).toInt())
            write(((value shr 24) and 0xFF).toInt())
        }
    }
}
