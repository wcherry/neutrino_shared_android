package com.neutrino.shared.core.keys

/**
 * The keyring on paper — with nothing stored server-side, the only copy that survives losing every
 * device.
 *
 * The encoding must match `web/packages/e2e-crypto/src/recoveryKit.ts` exactly, since a kit
 * printed on the web is typed in here. A compact binary frame in Crockford base32:
 *
 * ```
 * byte 0      magic 'N' (0x4E)
 * byte 1      format version (1)
 * byte 2      entry count
 * per entry   version (2 bytes, big-endian) | secret key (32) | flags (1)
 *             flags bit 0 = retired
 * ```
 *
 * Timestamps are deliberately absent; restored entries are stamped with the moment of restore.
 */
object RecoveryKit {

    /** Crockford base32 — no I, L, O or U. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP_SIZE = 4
    private const val GROUPS_PER_LINE = 8
    private const val MAGIC: Byte = 0x4E
    private const val FORMAT_VERSION: Byte = 1
    private const val SECRET_KEY_BYTES = 32
    private const val ENTRY_BYTES = 2 + SECRET_KEY_BYTES + 1
    private const val FLAG_RETIRED = 0x01

    sealed class KitException(message: String) : Exception(message) {
        class NotAKit : KitException("This does not look like a Neutrino recovery kit.")
        class UnsupportedVersion(v: Int) : KitException("This recovery kit uses format version $v, which this app does not understand.")
        class Incomplete : KitException("This recovery kit is incomplete — some characters are missing.")
        class Damaged : KitException("This recovery kit is damaged — it does not name exactly one current key.")
        class UnexpectedCharacter(c: Char) : KitException("This recovery kit contains an unexpected character: $c")
    }

    /**
     * Fold the common misreadings back before decoding: someone copying off paper writes O for 0
     * and l for 1 regardless of what the alphabet says.
     */
    fun normalize(text: String): String = buildString {
        for (c in text.uppercase()) {
            if (c.isWhitespace() || c == '-') continue
            append(
                when (c) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    'U' -> 'V'
                    else -> c
                },
            )
        }
    }

    internal fun encodeBase32(bytes: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1)
        }
        // Pad the trailing partial group with zero bits rather than dropping it — key material.
        if (bits > 0) out.append(ALPHABET[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    internal fun decodeBase32(text: String): ByteArray {
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in text) {
            val index = ALPHABET.indexOf(c)
            if (index < 0) throw KitException.UnexpectedCharacter(c)
            value = (value shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xff)
                bits -= 8
            }
            value = value and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    /** Render a keyring as the printable kit, grouped in fours and wrapped. */
    fun export(keyring: Keyring): String {
        val frame = java.io.ByteArrayOutputStream()
        frame.write(MAGIC.toInt())
        frame.write(FORMAT_VERSION.toInt())
        frame.write(keyring.entries.size)
        for (e in keyring.entries) {
            frame.write((e.version ushr 8) and 0xff)
            frame.write(e.version and 0xff)
            frame.write(e.secretKey)
            frame.write(if (e.isActive) 0 else FLAG_RETIRED)
        }
        val encoded = encodeBase32(frame.toByteArray())
        return encoded.chunked(GROUP_SIZE).chunked(GROUPS_PER_LINE).joinToString("\n") { it.joinToString("-") }
    }

    /**
     * Rebuild a keyring from a printed kit. `userId` comes from the signed-in session — the kit
     * holds key material only.
     */
    fun import(text: String, userId: String): Keyring {
        val normalized = normalize(text)
        if (normalized.isEmpty()) throw KitException.NotAKit()
        val bytes = decodeBase32(normalized)
        if (bytes.size < 3 || bytes[0] != MAGIC) throw KitException.NotAKit()
        if (bytes[1] != FORMAT_VERSION) throw KitException.UnsupportedVersion(bytes[1].toInt() and 0xff)
        val count = bytes[2].toInt() and 0xff
        if (bytes.size < 3 + count * ENTRY_BYTES) throw KitException.Incomplete()
        val now = nowIso8601()
        val entries = (0 until count).map { i ->
            val off = 3 + i * ENTRY_BYTES
            val version = ((bytes[off].toInt() and 0xff) shl 8) or (bytes[off + 1].toInt() and 0xff)
            val secret = bytes.copyOfRange(off + 2, off + 2 + SECRET_KEY_BYTES)
            val retired = (bytes[off + 2 + SECRET_KEY_BYTES].toInt() and FLAG_RETIRED) != 0
            KeyringEntry(version, secret, now, if (retired) now else null)
        }
        if (entries.count { it.isActive } != 1) throw KitException.Damaged()
        if (entries.map { it.version }.toSet().size != entries.size) throw KitException.Damaged()
        return Keyring(userId, entries.sortedBy { it.version })
    }

    /** True if `text` could plausibly be a kit, for deciding which input field to accept. */
    fun looksLikeKit(text: String): Boolean {
        val n = normalize(text)
        return n.length >= 60 && n.all { it in ALPHABET }
    }
}
