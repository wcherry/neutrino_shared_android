package com.neutrino.shared.core.keys

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.Sodium
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Every Curve25519 identity keypair this account has held, numbered.
 *
 * A single identity cannot be rotated: the moment it changes, every DEK on the server is sealed to
 * a key nobody holds any more. So the identity is a list, and `file_key_refs.key_version` names
 * the entry a given DEK needs.
 *
 * - **read**: resolve the file's key version against this keyring, open with that entry
 * - **write**: seal to the *active* entry and record its version
 *
 * The wire format is shared byte for byte with `web/packages/e2e-crypto/src/keyring.ts`, the
 * Swift `Keyring.swift` and the Windows `keyring.rs`. Two properties are load-bearing:
 *
 * - entries carry the secret key only; the public half is *derived* on read, so a damaged payload
 *   cannot produce a pair whose halves disagree;
 * - `retiredAt` is null on exactly one entry, and decoding rejects anything else.
 *
 * Nothing here is ever sent to the server (`neutrino/agent_docs/client-only-key-architecture.md`).
 */
class KeyringEntry(
    /** 1-based, matching `user_public_keys.version` on the server. */
    val version: Int,
    val secretKey: ByteArray,
    val createdAt: String,
    /** `null` while this is the active entry. */
    val retiredAt: String?,
) {
    val publicKey: ByteArray = Sodium.publicKeyFromSecret(secretKey)
    val isActive: Boolean get() = retiredAt == null

    fun retired(at: String) = KeyringEntry(version, secretKey, createdAt, at)

    override fun equals(other: Any?): Boolean =
        other is KeyringEntry && version == other.version && secretKey.contentEquals(other.secretKey) &&
            createdAt == other.createdAt && retiredAt == other.retiredAt

    override fun hashCode(): Int = version * 31 + secretKey.contentHashCode()

    /** Never prints the secret. */
    override fun toString(): String =
        "KeyringEntry(version=$version, publicKey=${B64.encode(publicKey)}, secretKey=<redacted>, retiredAt=$retiredAt)"
}

data class Keyring(val userId: String, /** Ascending by version. Exactly one is active. */ val entries: List<KeyringEntry>) {

    /** The entry new work is sealed to. */
    val active: KeyringEntry? get() = entries.firstOrNull { it.isActive }

    /** The entry a file's DEK was sealed to, or `null` if this device lacks it. */
    fun entry(version: Int): KeyringEntry? = entries.firstOrNull { it.version == version }

    /** "version 3" for a single key, "versions 1–3 (current: 3)" for a rotated account. */
    fun describe(): String {
        val active = active ?: return "unknown"
        val lowest = entries.first()
        return if (entries.size > 1) {
            "versions ${lowest.version}–${active.version} (current: ${active.version})"
        } else {
            "version ${active.version}"
        }
    }

    companion object {
        /**
         * Adopt a bare keypair as a single-entry keyring.
         *
         * `version` is the version the key actually is — a rotated account's v3 key filed under
         * v1 would make every file written since the rotation fail to open.
         */
        fun fromKeyPair(userId: String, secretKey: ByteArray, version: Int): Keyring =
            Keyring(userId, listOf(KeyringEntry(maxOf(version, 1), secretKey, nowIso8601(), null)))

        /**
         * Union two copies of one account's keyring — as iOS's `KeyringStore.merge` does.
         *
         * `null` when they disagree (same version, different secret): two identities claiming one
         * number has no safe automatic answer. The active entry of the result is the highest
         * version present; everything below it is retired, keeping any retirement time it had.
         */
        fun merge(a: Keyring, b: Keyring): Keyring? {
            if (a.userId != b.userId) return null
            val byVersion = sortedMapOf<Int, KeyringEntry>()
            for (entry in a.entries + b.entries) {
                val existing = byVersion[entry.version]
                if (existing == null) {
                    byVersion[entry.version] = entry
                } else {
                    if (!existing.secretKey.contentEquals(entry.secretKey)) return null
                    if (entry.createdAt < existing.createdAt) byVersion[entry.version] = entry
                }
            }
            val newest = byVersion.lastKey() ?: return null
            val now = nowIso8601()
            val entries = byVersion.values.map { e ->
                when {
                    e.version == newest -> KeyringEntry(e.version, e.secretKey, e.createdAt, null)
                    e.retiredAt == null -> e.retired(now)
                    else -> e
                }
            }
            return Keyring(a.userId, entries)
        }
    }
}

sealed class KeyringException(message: String) : Exception(message) {
    class UnrecognisedFormat : KeyringException("This is not a Neutrino key.")
    class Empty : KeyringException("That key is empty.")
    class BadSecretKeyLength(version: Int) : KeyringException("Key version $version is damaged.")
    class DuplicateVersions : KeyringException("That key has duplicate versions and cannot be used.")
    class NotExactlyOneActive(count: Int) : KeyringException(
        if (count == 0) "That key names no current version."
        else "That key names $count current versions; it should name exactly one.",
    )
    class WrongAccount : KeyringException("That key belongs to a different account.")
    class NoKeyring : KeyringException(
        "This device has no encryption key. Restore your recovery kit, or pair with a device that already has the key.",
    )
    class MissingVersion(val version: Int) : KeyringException(
        "This file needs encryption key version $version, which this device does not have. Restore your recovery kit, or pair with a device that has it.",
    )
}

/**
 * What the pairing QR and this device's secret store carry. Field names are fixed by the web
 * implementation; do not rename them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class SerializedKeyring(val v: Int, val userId: String, val entries: List<SerializedEntry>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class SerializedEntry(
    val version: Int,
    /** base64url secret key. No public half — it is derived on read. */
    val sk: String,
    val createdAt: String,
    /** Written as an explicit `null` for the active entry, as the web does; missing reads as null (the Swift shape). */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val retiredAt: String? = null,
)

object KeyringCodec {
    private const val FORMAT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    fun encodeJson(keyring: Keyring): String = json.encodeToString(
        SerializedKeyring.serializer(),
        SerializedKeyring(
            FORMAT_VERSION,
            keyring.userId,
            keyring.entries.map { SerializedEntry(it.version, B64.encode(it.secretKey), it.createdAt, it.retiredAt) },
        ),
    )

    /**
     * Rebuild a keyring, validating as it goes. Everything this parses arrived off paper, a QR code
     * or disk, so a wrong key length, a duplicate version or no current entry is rejected here
     * rather than surfacing later as files that will not open.
     */
    fun decodeJson(text: String): Keyring {
        val payload = try {
            json.decodeFromString(SerializedKeyring.serializer(), text)
        } catch (_: Exception) {
            throw KeyringException.UnrecognisedFormat()
        }
        if (payload.v != FORMAT_VERSION) throw KeyringException.UnrecognisedFormat()
        if (payload.entries.isEmpty()) throw KeyringException.Empty()
        val entries = payload.entries.map { e ->
            val sk = B64.decode(e.sk)
            if (sk == null || sk.size != Sodium.KEY_BYTES) throw KeyringException.BadSecretKeyLength(e.version)
            KeyringEntry(e.version, sk, e.createdAt, e.retiredAt)
        }.sortedBy { it.version }
        if (entries.zipWithNext().any { (a, b) -> a.version == b.version }) throw KeyringException.DuplicateVersions()
        val active = entries.count { it.isActive }
        if (active != 1) throw KeyringException.NotExactlyOneActive(active)
        return Keyring(payload.userId, entries)
    }
}

/** ISO-8601 UTC with milliseconds, the shape `new Date().toISOString()` produces on the web. */
fun nowIso8601(): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
