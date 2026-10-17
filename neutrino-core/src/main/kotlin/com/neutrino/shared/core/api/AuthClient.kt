package com.neutrino.shared.core.api

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.Sodium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** What is persisted between launches. Never logged: [toString] hides the tokens. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Unix seconds. */
    val expiresAt: Double? = null,
) {
    fun expiresWithin(seconds: Long, now: Double = System.currentTimeMillis() / 1000.0): Boolean =
        expiresAt?.let { now + seconds >= it } ?: true

    override fun toString() = "Session(expiresAt=$expiresAt)"
}

/** Where the session lives — Keystore-encrypted storage on the device, memory in tests. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

sealed class AuthException(message: String) : Exception(message) {
    class InvalidCredentials : AuthException("Invalid email or password.")
    class TwoFactorRequired : AuthException("Enter the code from your authenticator app.")
    class InvalidTwoFactorCode : AuthException("That code was not accepted. Try the current one.")
    class StateMismatch : AuthException("Authorization failed — security check failed.")
    class MissingCode : AuthException("Authorization failed — no code returned.")
    class TokenExchangeFailed(detail: String) : AuthException("Token exchange failed: $detail")
    class EmailAlreadyRegistered : AuthException("That email already has an account. Sign in instead.")
    class RegistrationRejected(message: String) : AuthException(message)
    class Server(status: Int) : AuthException("Server error ($status). Please try again later.")
    class InvalidServerUrl : AuthException("That server address is not valid.")
}

/**
 * Signing in, and keeping a usable access token.
 *
 * The three-step PKCE flow every Neutrino native client uses (no browser):
 *
 * 1. `POST /api/v1/auth/login` → a short-lived session token, or `requiresTwoFactor`
 * 2. `GET /api/v1/oauth/authorize` with that bearer, redirects **not** followed → the code is read
 *    from the `Location` header
 * 3. `POST /api/v1/oauth/token` → the long-lived access + refresh pair
 *
 * Step 1 carries `X-Device-Name`, which is how the session row is named in the account's device
 * list. This is the single owner of the session: refreshes run under one lock, so two requests
 * that both find the token stale cannot both spend the refresh token (the server consumes it on
 * use, and the loser would be signed out).
 */
class AuthClient(
    val config: NeutrinoAppConfig,
    private val http: OkHttpClient,
    private val store: SessionStore,
    serverUrl: String,
    private val deviceName: () -> String,
) {
    @Volatile
    var serverUrl: String = config.normalizeServerUrl(serverUrl)
        set(value) {
            field = config.normalizeServerUrl(value)
        }

    /** Called when the session ended underneath the app (refresh token rejected). */
    @Volatile
    var onSignedOut: (() -> Unit)? = null

    private val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val lock = Mutex()

    @Volatile
    private var session: Session? = store.load()

    val isSignedIn: Boolean get() = session != null

    /** The signed-in user's id — also the id of their drive root folder. */
    val userId: String? get() = session?.let { AccessTokenClaims.userId(it.accessToken) }
    val email: String? get() = session?.let { AccessTokenClaims.email(it.accessToken) }

    /** The current token without refreshing — for the web viewer's token injection. */
    val currentAccessToken: String? get() = session?.accessToken

    // ── Sign-in ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Run the full sign-in. An account with two-factor answers step 1 with [AuthException.TwoFactorRequired];
     * the caller asks for the code and calls again with [totpCode]. Once a code is in play, a 401 means the
     * code was wrong, not the password.
     */
    suspend fun login(email: String, password: String, totpCode: String? = null) {
        val sessionToken = step1Login(email.trim(), password, totpCode?.trim()?.takeIf { it.isNotEmpty() })
        val verifier = B64.encode(Sodium.randomBytes(32))
        val state = UUID.randomUUID().toString()
        val code = step2Authorize(sessionToken, FileCrypto.pkceChallenge(verifier), state)
        val tokens = postToken(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to NeutrinoAppConfig.OAUTH_REDIRECT_URI,
            "client_id" to config.oauthClientId,
        )
        adopt(tokens)
    }

    /**
     * Create an account and sign straight into it. `POST /auth/register` only writes the user row,
     * so the session comes from the ordinary three steps — which is also what registers this device.
     */
    suspend fun register(name: String, email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name.trim())
            put("email", email.trim())
            put("password", password)
        }.toString().toRequestBody(JSON)
        val response = http.newCall(Request.Builder().url(url("/api/v1/auth/register")).post(body).build()).await()
        response.use {
            val text = it.body?.string().orEmpty()
            when {
                it.isSuccessful -> Unit
                it.code == 409 -> throw AuthException.EmailAlreadyRegistered()
                it.code == 400 -> throw AuthException.RegistrationRejected(
                    httpError(400, text).serverMessage ?: "Please check your details and try again.",
                )
                else -> throw AuthException.Server(it.code)
            }
        }
        login(email, password)
    }

    /** Clears the session. The keyring is deliberately kept — signing back in should not need the recovery kit. */
    fun logout() {
        session = null
        store.clear()
    }

    private suspend fun step1Login(email: String, password: String, totpCode: String?): String = withContext(Dispatchers.IO) {
        // `totpCode` is omitted rather than sent empty: an empty string is a code that is wrong.
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            if (totpCode != null) put("totpCode", totpCode)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(url("/api/v1/auth/login"))
            .header(NeutrinoAppConfig.DEVICE_NAME_HEADER, config.headerSafeDeviceName(deviceName()))
            .post(body)
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val json = runCatching { apiJson.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: throw AuthException.Server(response.code)
                    val token = json["accessToken"]?.jsonPrimitive?.content
                    if (!token.isNullOrEmpty()) return@withContext token
                    if (json["requiresTwoFactor"]?.jsonPrimitive?.content == "true") throw AuthException.TwoFactorRequired()
                    throw AuthException.Server(response.code)
                }
                response.code == 401 ->
                    throw if (totpCode != null) AuthException.InvalidTwoFactorCode() else AuthException.InvalidCredentials()
                response.code == 429 -> throw AuthException.RegistrationRejected("Too many sign-in attempts. Wait a minute and try again.")
                else -> throw AuthException.Server(response.code)
            }
        }
    }

    private suspend fun step2Authorize(sessionToken: String, challenge: String, state: String): String = withContext(Dispatchers.IO) {
        val authorize = url("/api/v1/oauth/authorize").newBuilder()
            .addQueryParameter("client_id", config.oauthClientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", NeutrinoAppConfig.OAUTH_REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
        val request = Request.Builder().url(authorize).header("Authorization", "Bearer $sessionToken").build()
        noRedirect.newCall(request).await().use { response ->
            if (response.code !in 300..399) {
                throw AuthException.TokenExchangeFailed(response.body?.string()?.take(300) ?: "HTTP ${response.code}")
            }
            // The location is a custom scheme (`neutrino://…`), which HttpUrl cannot parse — read
            // the query by hand.
            val location = response.header("Location") ?: throw AuthException.MissingCode()
            val query = parseQuery(location)
            val code = query["code"] ?: throw AuthException.MissingCode()
            // Checked before the code is used: a mismatched state is not an answer to this request.
            if (query["state"] != state) throw AuthException.StateMismatch()
            code
        }
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val refresh_token: String,
        val expires_in: Long,
    )

    private suspend fun postToken(vararg fields: Pair<String, String>): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url("/api/v1/oauth/token")).post(form).build()
        http.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> runCatching { apiJson.decodeFromString(TokenResponse.serializer(), text) }
                    .getOrElse { throw AuthException.TokenExchangeFailed(it.message ?: "bad response") }
                // 4xx is the server refusing the grant. 5xx says nothing about whether the
                // refresh token is still good, so it must not end the session.
                response.code in 400..499 -> throw AuthException.TokenExchangeFailed("HTTP ${response.code}: ${text.take(200)}")
                else -> throw AuthException.Server(response.code)
            }
        }
    }

    private fun adopt(tokens: TokenResponse) {
        val new = Session(
            tokens.access_token,
            tokens.refresh_token,
            System.currentTimeMillis() / 1000.0 + tokens.expires_in,
        )
        store.save(new)
        session = new
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A token to put on a request, refreshed first if it expires within a minute (or when [force]:
     * the server already answered 401).
     *
     * Only a refresh token the server *rejects* ends the session. A refresh that fails because the
     * network is down leaves a still-valid token in use — signing someone out over a transient
     * error would be its own bug.
     */
    suspend fun accessToken(force: Boolean = false): String = lock.withLock {
        val current = session ?: throw ApiException.NotSignedIn()
        if (!force && !current.expiresWithin(REFRESH_SKEW_SECONDS)) return current.accessToken
        val refresh = current.refreshToken
            ?: return if (current.expiresWithin(0)) throw ApiException.SessionExpired() else current.accessToken
        try {
            val tokens = postToken(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to config.oauthClientId,
            )
            adopt(tokens)
            session!!.accessToken
        } catch (e: AuthException.TokenExchangeFailed) {
            session = null
            store.clear()
            onSignedOut?.invoke()
            throw ApiException.SessionExpired()
        } catch (e: Exception) {
            if (current.expiresWithin(0)) throw e else current.accessToken
        }
    }

    private fun url(path: String) =
        "$serverUrl$path".toHttpUrlOrNull() ?: throw AuthException.InvalidServerUrl()

    companion object {
        const val REFRESH_SKEW_SECONDS = 60L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun parseQuery(location: String): Map<String, String> {
            val query = location.substringAfter('?', "").substringBefore('#')
            return query.split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
            }
        }
    }
}

/**
 * Reads the claims the client needs out of an access token. Not a verification — the server
 * checks the signature on every request. Load-bearing for more than identity: **a user's root
 * folder id is their user id**, so listing the drive root is `GET /drive/folders/{sub}`.
 */
object AccessTokenClaims {
    private fun claims(token: String) = runCatching {
        val payload = token.split('.')[1]
        apiJson.parseToJsonElement(B64.decode(payload)!!.decodeToString()).jsonObject
    }.getOrNull()

    fun userId(token: String): String? = claims(token)?.get("sub")?.jsonPrimitive?.content
    fun email(token: String): String? = runCatching { claims(token)?.get("email")?.jsonPrimitive?.content }.getOrNull()
}
