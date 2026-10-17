package com.neutrino.shared.core.keys

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.Sodium
import kotlinx.serialization.Serializable

/**
 * The account's **retired** identity keys, as `GET /api/v1/drive/key-file` returns them.
 *
 * A device enrolling from a single keypair (key QR or key file) holds the account's *active* key
 * only, so on a rotated account it could read nothing written before the last rotation. Each
 * retired secret key on the server is `crypto_box_seal`ed to the keyring's active public key, so
 * the holder of the current identity can recover every identity before it, and nobody else can.
 * See `KeyFileService.swift` and `web/packages/e2e-crypto/src/keyFile.ts`.
 */
object ArchivedKeys {

    @Serializable
    data class ArchivedKey(
        /** The `user_public_keys.version` this entry unwraps to. */
        val keyVersion: Int,
        /** base64url sealed box over the retired secret key. */
        val encryptedKey: String,
        /** base64url public half, so an entry can be matched before it is unsealed. */
        val publicKey: String? = null,
    )

    @Serializable
    data class KeyFile(
        val userId: String,
        /** Ascending by `keyVersion`. */
        val keys: List<ArchivedKey>,
        val createdAt: String,
        val updatedAt: String,
    )

    data class Outcome(
        /** Versions added to this device by this pull. */
        val recovered: Int = 0,
        /** Versions the file carried that this device already had. */
        val alreadyHeld: Int = 0,
        /** Versions that would not open — damaged, or sealed to a key newer than the one held. */
        val unopenable: Int = 0,
        /**
         * True when this device's *active* version turns up in the file. The file holds retired
         * keys only, so the account has rotated since this key was issued and this device cannot
         * read anything written since; it needs a fresh key.
         */
        val activeIsStale: Boolean = false,
        /** The server answered 404: this account has no key file (it has never rotated). */
        val serverHasNoKeyFile: Boolean = false,
    )

    /**
     * What a fetched key file yields against a keyring: the entries to add, and what happened.
     * Versions already in the keyring are counted and skipped rather than re-unsealed.
     */
    fun plan(file: KeyFile, keyring: Keyring): Pair<Outcome, List<KeyringEntry>> {
        val active = keyring.active ?: return Outcome() to emptyList()
        var held = 0
        var unopenable = 0
        val recovered = mutableListOf<KeyringEntry>()
        for (key in file.keys) {
            if (keyring.entry(key.keyVersion) != null) {
                held++
                continue
            }
            val entry = open(key, active.secretKey, file.createdAt, file.updatedAt)
            if (entry == null) unopenable++ else recovered += entry
        }
        return Outcome(
            recovered = recovered.size,
            alreadyHeld = held,
            unopenable = unopenable,
            activeIsStale = file.keys.any { it.keyVersion >= active.version },
        ) to recovered
    }

    /** The keyring with the plan's entries merged in, retired. */
    fun apply(keyring: Keyring, entries: List<KeyringEntry>): Keyring =
        if (entries.isEmpty()) keyring
        else keyring.copy(entries = (keyring.entries + entries).sortedBy { it.version })

    /**
     * Open one archived key with the active secret key.
     *
     * Everything here came off the network: a secret of the wrong length, or a declared public
     * half that is not the secret's own, is refused rather than stored.
     */
    internal fun open(key: ArchivedKey, activeSecretKey: ByteArray, createdAt: String, retiredAt: String): KeyringEntry? {
        val sealed = B64.decode(key.encryptedKey) ?: return null
        val secret = Sodium.boxSealOpen(sealed, activeSecretKey) ?: return null
        if (secret.size != Sodium.KEY_BYTES) return null
        val derived = Sodium.publicKeyFromSecret(secret)
        if (key.publicKey != null && !(B64.decode(key.publicKey)?.contentEquals(derived) ?: false)) return null
        return KeyringEntry(key.keyVersion, secret, createdAt, retiredAt)
    }
}
