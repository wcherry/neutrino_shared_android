package com.neutrino.shared.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.neutrino.shared.core.api.Session
import com.neutrino.shared.core.api.SessionStore
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.KeyringCodec
import com.neutrino.shared.core.keys.KeyringStore
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets at rest — the session and the keyring — sealed with an AES-256-GCM key that lives
 * in the Android Keystore (hardware-backed where the device has it) and never leaves it.
 *
 * The iOS apps use the Keychain with `…ThisDeviceOnly`; this is the Android equivalent: the key
 * cannot be exported, is excluded from backup with everything else (`data_extraction_rules.xml`),
 * and dies with the app. One file per item, `[12-byte IV][ciphertext+tag]`, written atomically.
 * Like iOS's `AfterFirstUnlock`, the key needs no user authentication, so photo backup can read the
 * token while the phone is locked; the app lock guards the UI, not this.
 *
 * Unlike iOS there is no cross-app keychain group, so each Neutrino Android app keeps its own copy
 * of the keyring. [alias] is the app's `NeutrinoAppConfig.keystoreAlias`: each app's own key, never
 * shared and never renamed (a new alias cannot open what the old one sealed).
 */
class SecureStore(context: Context, private val alias: String) {

    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val file = File(dir, "$name.bin")
        if (!file.exists()) return null
        return try {
            val data = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        } catch (e: Exception) {
            // A sealed item that will not open (the Keystore key was lost with a restore, say) is
            // gone for good; treating it as absent routes the user back through sign-in.
            Log.e(TAG, "could not open $name: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    fun set(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(File(dir, "$name.bin"))) throw java.io.IOException("could not save $name")
    }

    @Synchronized
    fun delete(name: String) {
        File(dir, "$name.bin").delete()
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/** The session, in the macOS/Windows clients' JSON shape. */
class SecureSessionStore(private val store: SecureStore) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    override fun load(): Session? = store.get(ITEM)?.let {
        runCatching { json.decodeFromString(Session.serializer(), it.decodeToString()) }.getOrNull()
    }
    override fun save(session: Session) = store.set(ITEM, json.encodeToString(Session.serializer(), session).toByteArray())
    override fun clear() = store.delete(ITEM)

    private companion object {
        const val ITEM = "session"
    }
}

/**
 * The keyring, per account, in the web's serialised form — the same JSON the pairing QR carries.
 * Sign-out keeps it, deliberately, so signing back in does not need the recovery kit.
 */
class SecureKeyringStore(private val store: SecureStore) : KeyringStore {
    private val cache = mutableMapOf<String, Keyring>()

    @Synchronized
    override fun load(userId: String): Keyring? = cache[userId] ?: store.get(item(userId))?.let {
        runCatching { KeyringCodec.decodeJson(it.decodeToString()) }.getOrNull()
            ?.takeIf { ring -> ring.userId == userId }
            ?.also { ring -> cache[userId] = ring }
    }

    @Synchronized
    override fun store(keyring: Keyring) {
        store.set(item(keyring.userId), KeyringCodec.encodeJson(keyring).toByteArray())
        cache[keyring.userId] = keyring
    }

    @Synchronized
    override fun remove(userId: String) {
        store.delete(item(userId))
        cache.remove(userId)
    }

    /** File names stay filesystem-safe whatever an id looks like. */
    private fun item(userId: String) = "keyring." + userId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}
