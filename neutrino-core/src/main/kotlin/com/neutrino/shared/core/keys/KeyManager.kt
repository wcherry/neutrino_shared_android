package com.neutrino.shared.core.keys

import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.PublicKeyDto
import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.Sodium

/**
 * Where this device keeps keyrings: Keystore-encrypted storage on Android, memory in tests.
 *
 * Keyed by user id, because a phone can be signed out of one account and into another, and the
 * second account must never be handed the first one's identity.
 */
interface KeyringStore {
    fun load(userId: String): Keyring?
    fun store(keyring: Keyring)
    fun remove(userId: String)
}

class InMemoryKeyringStore : KeyringStore {
    private val rings = mutableMapOf<String, Keyring>()
    override fun load(userId: String) = synchronized(rings) { rings[userId] }
    override fun store(keyring: Keyring) = synchronized(rings) { rings[keyring.userId] = keyring }
    override fun remove(userId: String) = synchronized(rings) { rings.remove(userId); Unit }
}

sealed class KeyException(message: String) : Exception(message) {
    class NoEncryptionKey : KeyException("No encryption key on this device. Open Settings › Encryption to add one.")
    class StaleEncryptionKey : KeyException(
        "This device's encryption key is no longer your account's key, so uploads are paused. " +
            "Open Settings › Encryption, remove the key and add your current one.",
    )
    class AlreadyPublished(noun: String = "files") : KeyException(
        "This account already has an encryption key. Bring it to this device with your recovery kit, by pairing, " +
            "or with the key code shown on the web — creating a new one here would leave your existing $noun unreadable.",
    )
    class DeviceAlreadyHasKey : KeyException("This device already holds an encryption key.")
    class KitDoesNotMatchAccount : KeyException(
        "This recovery kit holds a different key from the one your account uses now. It may belong to another " +
            "account, or date from before your key was replaced.",
    )
    class AccountPublishesNoKey : KeyException(
        "Your account has published no encryption key, so there is nothing for this kit to restore. Set up a new key instead.",
    )
    class Conflict : KeyException("That key disagrees with the key already on this device about a key version.")
}

/**
 * Answers "may a new file be sealed to this device's key, and under which version?"
 *
 * The device's active key is compared with the account's **published** active key before anything
 * is sealed. A device holding a key that was since replaced elsewhere would otherwise seal files
 * that open on this phone and nowhere else (Neutrino Drive iOS did this to 374 photos — see
 * `DeviceKeyGuard.swift`). One small GET, cached for ten minutes against the exact key it checked.
 */
class DeviceKeyCheck(private val api: NeutrinoApi, private val keys: KeyringStore) {

    private data class Cached(val publicKey: String, val version: Int, val at: Long)

    @Volatile
    private var cached: Cached? = null

    /** The published version of this device's active key; throws when there is none or it is stale. */
    suspend fun sealingVersion(): Pair<KeyringEntry, Int> {
        val userId = api.auth.userId ?: throw ApiException.NotSignedIn()
        val active = keys.load(userId)?.active ?: throw KeyException.NoEncryptionKey()
        val pk = B64.encode(active.publicKey)
        cached?.let { if (it.publicKey == pk && System.currentTimeMillis() - it.at < CACHE_MILLIS) return active to it.version }
        val status = status(active, api.publishedKey(userId))
        if (status !is Status.Current) {
            cached = null
            throw KeyException.StaleEncryptionKey()
        }
        cached = Cached(pk, status.version, System.currentTimeMillis())
        return active to status.version
    }

    fun forget() {
        cached = null
    }

    sealed class Status {
        data class Current(val version: Int) : Status()
        data class Stale(val published: PublicKeyDto) : Status()
        object Unpublished : Status()
    }

    /** Compared as bytes — a key file may carry the standard alphabet where the directory does not. */
    suspend fun check(): Status? {
        val userId = api.auth.userId ?: return null
        val active = keys.load(userId)?.active ?: return null
        return status(active, api.publishedKey(userId))
    }

    private fun status(active: KeyringEntry, published: PublicKeyDto?): Status = when {
        published == null -> Status.Unpublished
        B64.decode(published.publicKey)?.contentEquals(active.publicKey) == true -> Status.Current(published.version)
        else -> Status.Stale(published)
    }

    companion object {
        const val CACHE_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * Giving this device an identity, and keeping it whole.
 *
 * Every way a key can arrive — the web's key code (PIN QR), a key file, the recovery kit, pairing —
 * ends in [adopt], which merges the new keyring with anything already held and then pulls the
 * account's retired keys from the key file. [provision] mints the account's *first* key, and only
 * for an account that has published none: `POST /auth/keys` on an account with a key is a rotation,
 * and every DEK sealed to the old key would need re-sealing by a device that holds it.
 */
class KeyManager(private val api: NeutrinoApi, private val store: KeyringStore, private val keyCheck: DeviceKeyCheck) {

    private fun userId() = api.auth.userId ?: throw ApiException.NotSignedIn()

    fun keyring(): Keyring? = api.auth.userId?.let(store::load)

    val hasKey: Boolean get() = keyring()?.active != null

    /**
     * Store `incoming`, merged with this device's keyring for the account.
     *
     * A merge conflict — same version, different secret — means the device held a key the account
     * no longer uses; the key the user just brought is the one they meant, so it replaces the old.
     */
    suspend fun adopt(incoming: Keyring): ArchivedKeys.Outcome {
        if (incoming.userId != userId()) throw KeyringException.WrongAccount()
        val existing = store.load(incoming.userId)
        store.store(existing?.let { Keyring.merge(it, incoming) } ?: incoming)
        keyCheck.forget()
        return pullKeyFile()
    }

    suspend fun adoptBundle(bundle: KeyBundle): ArchivedKeys.Outcome = adopt(bundle.toKeyring(userId()))

    suspend fun acceptPairing(response: Pairing.Response, session: Pairing.Session): ArchivedKeys.Outcome =
        adopt(Pairing.accept(response, session, userId()))

    /**
     * Fetch the key file and merge whatever this device's active key opens. Safe to call
     * repeatedly — launch-time top-ups find nothing on a healthy install.
     */
    suspend fun pullKeyFile(): ArchivedKeys.Outcome {
        val keyring = keyring() ?: throw KeyException.NoEncryptionKey()
        val file = api.keyFile() ?: return ArchivedKeys.Outcome(serverHasNoKeyFile = true)
        val (outcome, entries) = ArchivedKeys.plan(file, keyring)
        if (entries.isNotEmpty()) store.store(ArchivedKeys.apply(keyring, entries))
        return outcome
    }

    /**
     * Adopt the keyring a printed kit carries, after checking it belongs to this account.
     *
     * The kit names no account, so the binding is checked: its active key must be the one the
     * account publishes. Where the kit and the directory number the same key differently, the
     * directory wins — `file_key_refs.key_version` uses its numbering.
     */
    suspend fun restoreRecoveryKit(text: String): Keyring {
        val userId = userId()
        var keyring = RecoveryKit.import(text, userId)
        val active = keyring.active ?: throw RecoveryKit.KitException.Damaged()
        val published = api.publishedKey(userId)
        if (published != null) {
            if (B64.decode(published.publicKey)?.contentEquals(active.publicKey) != true) throw KeyException.KitDoesNotMatchAccount()
            if (published.version != active.version) {
                keyring = keyring.copy(entries = keyring.entries.map {
                    if (it.isActive) KeyringEntry(published.version, it.secretKey, it.createdAt, null) else it
                }.sortedBy { it.version })
            }
        } else {
            // A kit exists only because a key was once published; an empty directory means that
            // publish never landed. Repairing it is unambiguous only for a never-rotated keyring.
            if (active.version != 1 || keyring.entries.size != 1) throw KeyException.AccountPublishesNoKey()
            api.publishKey(B64.encode(active.publicKey))
        }
        adopt(keyring)
        return keyring
    }

    /** True when a key could be minted here: none on the device, none published. */
    suspend fun canProvision(): Boolean {
        if (hasKey) return false
        // A state that could not be fetched is not an empty one.
        return runCatching { api.publishedKey(userId()) == null }.getOrDefault(false)
    }

    /**
     * Mint the account's identity, store it, publish the public half, and return the recovery kit
     * — shown once, never stored, the user's only backup.
     */
    suspend fun provision(): String {
        val userId = userId()
        if (hasKey) throw KeyException.DeviceAlreadyHasKey()
        if (api.publishedKey(userId) != null) throw KeyException.AlreadyPublished()
        val pair = Sodium.boxKeyPair()
        // Stored before it is published: publishing first would advertise a key a failed write
        // means this device cannot use.
        store.store(Keyring.fromKeyPair(userId, pair.secretKey, 1))
        val published = try {
            api.publishKey(B64.encode(pair.publicKey))
        } catch (e: Exception) {
            store.remove(userId)
            throw e
        }
        val keyring = Keyring.fromKeyPair(userId, pair.secretKey, published.version)
        store.store(keyring)
        keyCheck.forget()
        return RecoveryKit.export(keyring)
    }

    /** Forget this device's key. It survives only on paired devices and the printed kit. */
    fun remove() {
        api.auth.userId?.let(store::remove)
        keyCheck.forget()
    }
}

/** The keypair that opens a DEK sealed to `version`, trying the named version first. */
fun Keyring.openDek(sealed: String, namedVersion: Int): Pair<ByteArray, Int>? {
    val ordered = entries.filter { it.version == namedVersion } +
        entries.filter { it.version != namedVersion }.sortedByDescending { it.version }
    for (entry in ordered) {
        FileCrypto.openSealedDek(sealed, entry.secretKey)?.let { return it to entry.version }
    }
    return null
}
