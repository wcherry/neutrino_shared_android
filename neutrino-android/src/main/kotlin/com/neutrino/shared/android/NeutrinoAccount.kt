package com.neutrino.shared.android

import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import com.neutrino.shared.android.storage.SecureKeyringStore
import com.neutrino.shared.android.storage.SecureSessionStore
import com.neutrino.shared.android.storage.SecureStore
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.NeutrinoAppConfig
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.provider.Settings as SystemSettings

/**
 * One app's account on this device: its preferences, its Keystore-sealed session and keyring, the
 * HTTP client and the key manager. Built once per process by the app (in `Application.onCreate`,
 * since a background job can start the process with no screen), and handed to the shared screens
 * through [LocalNeutrinoAccount].
 *
 * Everything is namespaced by [config]: preferences under `<prefix>.settings`, secrets under its
 * Keystore alias. Two Neutrino apps on one phone never see each other's session — nor, unlike the
 * iOS apps' shared Keychain group, each other's keyring: each app holds its own copy.
 *
 * [A] is the app's own API, a subclass of [NeutrinoApi] with its endpoints.
 */
class NeutrinoAccount<A : NeutrinoApi>(
    context: Context,
    val config: NeutrinoAppConfig,
    /** "NeutrinoDrive-Android/1.0.0": the app's half of the User-Agent. */
    userAgentProduct: String,
    apiFactory: (OkHttpClient, AuthClient) -> A,
) {
    val context: Context = context.applicationContext
    val settings = AccountSettings(this.context, config)
    private val secure = SecureStore(this.context, config.keystoreAlias)
    val keyringStore = SecureKeyringStore(secure)

    private val userAgent = "$userAgentProduct (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})".filter { it in ' '..'~' }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Whole-file bodies: a large upload or download is one request.
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    val auth = AuthClient(config, http, SecureSessionStore(secure), settings.serverUrl) { settings.deviceName }
    val api: A = apiFactory(http, auth)
    val keyCheck = DeviceKeyCheck(api, keyringStore)
    val keys = KeyManager(api, keyringStore, keyCheck)

    private val _signedIn = MutableStateFlow(auth.isSignedIn)
    val signedIn: StateFlow<Boolean> = _signedIn

    /** Bumped whenever the keyring changes, so screens re-read whether a key is present. */
    val keyringVersion = MutableStateFlow(0)

    /** Run on sign-out, whether the user chose it or the server ended the session: the app forgets its data here. */
    private val signOutHooks = mutableListOf<() -> Unit>()

    init {
        auth.onSignedOut = {
            _signedIn.value = false
            signOutHooks.forEach { it() }
        }
    }

    fun onSignOut(hook: () -> Unit) {
        signOutHooks += hook
    }

    fun onSignedIn() {
        _signedIn.value = true
    }

    fun signOut() {
        auth.logout()
        _signedIn.value = false
        signOutHooks.forEach { it() }
    }

    fun setServerUrl(url: String) {
        settings.serverUrl = url
        auth.serverUrl = settings.serverUrl
    }

    fun keyringChanged() {
        keyringVersion.value++
    }
}

/** The account the shared screens act on; provided by each app around its content. */
val LocalNeutrinoAccount = staticCompositionLocalOf<NeutrinoAccount<*>> { error("No NeutrinoAccount provided") }

/**
 * The preferences every app has, in the app's own `<prefix>.settings` file under `<prefix>.` keys —
 * the names Drive shipped with (`nd.server_host`, …), so nothing moves for an installed Drive. An
 * app keeps its other preferences in the same file.
 */
class AccountSettings(private val context: Context, private val config: NeutrinoAppConfig) {

    val prefs = context.getSharedPreferences("${config.prefix}.settings", Context.MODE_PRIVATE)
    private val keyServer = "${config.prefix}.server_host"
    private val keyDeviceName = "${config.prefix}.device_name"
    private val keyAppLock = "${config.prefix}.app_lock"

    var serverUrl: String
        get() = prefs.getString(keyServer, null) ?: config.defaultServerUrl
        set(value) = prefs.edit().putString(keyServer, config.normalizeServerUrl(value)).apply()

    /**
     * How this device appears in the account's session list: "<device> - Neutrino Calendar", as the
     * iOS apps name themselves (`DeviceIdentity`), unless the user picked a name.
     */
    var customDeviceName: String?
        get() = prefs.getString(keyDeviceName, null)
        set(value) = prefs.edit().putString(keyDeviceName, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    val deviceName: String
        get() = customDeviceName ?: "${systemDeviceName()} - ${config.displayName}"

    private fun systemDeviceName(): String =
        SystemSettings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val _appLock = MutableStateFlow(prefs.getBoolean(keyAppLock, false))
    val appLock: StateFlow<Boolean> = _appLock
    fun setAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(keyAppLock, enabled).apply()
        _appLock.value = enabled
    }
}
