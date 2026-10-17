package com.neutrino.shared.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One Neutrino Android app's identity: the Android counterpart of `NeutrinoAppConfig` in
 * `neutrino_shared_ios`.
 *
 * **Every value is load-bearing on an installed device.** [oauthClientId] must exist in the
 * server's `oauth_clients` table (root CLAUDE.md, rule 3) or sign-in fails opaquely. [prefix] names
 * this app's preferences and [keystoreAlias] its Keystore key, so two co-installed apps never read
 * each other's session (rule 4); changing either for a shipped app signs its users out and orphans
 * the keyring it holds. The shipped values live in [NeutrinoApps].
 */
data class NeutrinoAppConfig(
    /** `drive`, `calendar`: the iOS config's `slug`. */
    val slug: String,
    /** "Neutrino Drive" — the suffix of the device name and the sign-in screen's title. */
    val displayName: String,
    /** What the app holds, for copy: "files", "events". */
    val contentNoun: String,
    /** `nd`, `ncal`: the namespace of this app's preferences. Never change or reuse one. */
    val prefix: String,
    val oauthClientId: String,
    /** The Android Keystore alias sealing this app's secrets. Never change one. */
    val keystoreAlias: String = "neutrino.$slug.secrets.v1",
    val defaultServerUrl: String = DEFAULT_SERVER_URL,
) {
    /**
     * The device name as a header value the server can read.
     *
     * The server reads `X-Device-Name` with actix's `to_str()`, which rejects any non-ASCII byte
     * and then records no name at all — so "Pixel — Neutrino Drive" would list as "Unknown device"
     * (OkHttp refuses to send it anyway). Dashes and quotes are folded to ASCII, accents stripped,
     * and anything left that is not printable ASCII dropped.
     */
    fun headerSafeDeviceName(name: String): String {
        val folded = name.replace('\u2014', '-').replace('\u2013', '-')
            .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
        val stripped = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFKD)
            .filter { it in ' '..'~' }
            .replace(Regex("\\s+"), " ").trim()
        return stripped.ifEmpty { "Android - $displayName" }
    }

    /** `https://example.com/` → `https://example.com`; a bare host gets `https://`. */
    fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return defaultServerUrl
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    companion object {
        /** Shared by every native client; the flow never leaves the process, so it disambiguates nothing. */
        const val OAUTH_REDIRECT_URI = "neutrino://oauth/callback"
        const val DEFAULT_SERVER_URL = "https://www.getneutrino.app"
        const val DEVICE_NAME_HEADER = "X-Device-Name"
    }
}

/**
 * The shipped Android apps. Kept here rather than in each app, as iOS keeps `NeutrinoAppConfig+Apps`,
 * so a test can check that no two collide.
 */
object NeutrinoApps {
    /** Seeded by `neutrino/migrations/00141_oauth__*`. Drive's values predate this package and are kept as shipped. */
    val drive = NeutrinoAppConfig(
        slug = "drive",
        displayName = "Neutrino Drive",
        contentNoun = "files",
        prefix = "nd",
        oauthClientId = "neutrino-android",
    )

    /** Seeded by `neutrino/migrations/00142_oauth__*`. The `ncal` prefix matches the iOS Calendar app's. */
    val calendar = NeutrinoAppConfig(
        slug = "calendar",
        displayName = "Neutrino Calendar",
        contentNoun = "events",
        prefix = "ncal",
        oauthClientId = "neutrino-calendar-android",
    )

    val all: List<NeutrinoAppConfig> = listOf(drive, calendar)
}

/** Every failure a request can end in, with text a person can act on. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotSignedIn : ApiException("You are not signed in.")
    class SessionExpired : ApiException("Your session has expired. Please sign in again.")
    class Network(cause: IOException) : ApiException("A network error occurred. Please check your connection.", cause)
    class Http(val status: Int, val code: String?, val serverMessage: String?, body: String) :
        ApiException(serverMessage?.takeIf { it.isNotBlank() } ?: "Server error ($status).") {
        val body: String = body.take(500)
    }
    class Decode(detail: String) : ApiException("Failed to read the server's response: $detail")

    /** Worth sending again later: a transport error, 408, 429 or a 5xx. */
    val isTransient: Boolean
        get() = this is Network || (this is Http && (status >= 500 || status == 429 || status == 408))

    val httpStatus: Int? get() = (this as? Http)?.status
}

val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Suspends on an OkHttp call, cancelling it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(ApiException.Network(e))
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Neutrino wraps errors as `{"error": {"code", "message"}}`; some older handlers answer `{"message"}`. */
fun httpError(status: Int, body: String): ApiException.Http {
    val parsed = runCatching { apiJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val error = runCatching { parsed?.get("error")?.jsonObject }.getOrNull()
    val code = runCatching { error?.get("code")?.jsonPrimitive?.content }.getOrNull()
    val message = runCatching {
        (error?.get("message") ?: parsed?.get("message"))?.jsonPrimitive?.content
    }.getOrNull()
    return ApiException.Http(status, code, message, body)
}

/**
 * Dates as the server sends them.
 *
 * File and folder DTOs serialise a zone-less `NaiveDateTime` (`2026-01-02T03:04:05.123456`), which
 * is UTC; the version DTO sends RFC 3339 with an offset. Both are accepted.
 */
object DriveDate {
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** RFC 3339 in UTC — the shape the server's `parse_import_timestamp` pins to an instant. */
    fun wire(instant: Instant): String = instant.atOffset(ZoneOffset.UTC).withNano(0).toString()
        .let { if (it.endsWith("Z")) it else it.replace("+00:00", "Z") }
}
