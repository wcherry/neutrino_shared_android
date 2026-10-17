package com.neutrino.shared.android.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The optional app lock and the gate on key-sensitive actions — iOS's `BiometricAuthService`.
 *
 * Fingerprint/face or the device PIN, whichever the phone has. When the lock is on, the app locks
 * at launch and after being in the background longer than [GRACE_MILLIS], and its window is marked
 * secure so the recents screen shows no file names or events. The keyring itself is protected by the
 * Keystore either way; this guards the UI.
 */
object AppLock {
    const val GRACE_MILLIS = 30_000L
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt = 0L
    private var lastUnlock = 0L

    fun isAvailable(context: Context) =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun lockOnLaunch(enabled: Boolean) {
        if (enabled) _locked.value = true
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
    }

    fun onForeground(enabled: Boolean) {
        if (enabled && backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > GRACE_MILLIS) _locked.value = true
        backgroundedAt = 0
    }

    fun unlocked() {
        _locked.value = false
        lastUnlock = System.currentTimeMillis()
    }

    /** One prompt. `true` when the user authenticated, or when the phone has no lock to ask for. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean {
        if (!isAvailable(activity)) return true
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { subtitle?.let(::setSubtitle) }
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    /**
     * The gate on removing or revealing the key: skipped within a minute of unlocking the app, as
     * iOS skips it within its grace period.
     */
    suspend fun authorizeKeyAccess(activity: FragmentActivity, title: String): Boolean {
        if (System.currentTimeMillis() - lastUnlock < 60_000) return true
        return authenticate(activity, title).also { if (it) lastUnlock = System.currentTimeMillis() }
    }
}
