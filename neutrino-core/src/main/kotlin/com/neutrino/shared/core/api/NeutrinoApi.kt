package com.neutrino.shared.core.api

import com.neutrino.shared.core.keys.ArchivedKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * The Neutrino HTTP API, as every Android client uses it: the token plumbing, the account's keys,
 * and the handful of Drive endpoints any app needs to open or add an encrypted file (Calendar's
 * attachments are Drive files). Each app subclasses it for its own endpoints — Drive for sharing,
 * trash and versions, Calendar for `/api/v1/calendar`.
 *
 * Every call goes through one token path ([send]): a proactive refresh inside the skew window,
 * then on a 401 a forced refresh and exactly one retry. On iOS the same rule is repeated in each
 * service's `authorized(_:)`; here a call site cannot forget it.
 */
open class NeutrinoApi(val http: OkHttpClient, val auth: AuthClient) {

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    fun url(path: String): HttpUrl = "${auth.serverUrl}/api/v1$path".toHttpUrl()

    /** Absolute URL for a server-relative path such as a `coverThumbnailUrl`. */
    fun absolute(serverPath: String): HttpUrl = "${auth.serverUrl}$serverPath".toHttpUrl()

    /** Headers every request carries, beyond the bearer token. Calendar adds its client id. */
    protected open fun decorate(builder: Request.Builder): Request.Builder = builder

    /** How [send] rides out transient failures. Tests that script failures set [RetryPolicy.NONE]. */
    var retryPolicy: RetryPolicy = RetryPolicy.DEFAULT

    /**
     * Send with a bearer token; on 401 refresh and retry once. A transient failure — a dropped
     * connection, 429, 502–504 — is retried under [retryPolicy] (see its rules on which methods).
     * `build` runs per attempt so a body is never replayed half-consumed. The caller owns (and must
     * close) the returned response.
     */
    suspend fun send(build: () -> Request.Builder): Response {
        val idempotent = RetryPolicy.isIdempotent(build().build().method)
        var attempt = 1
        while (true) {
            val isLast = attempt >= retryPolicy.maxAttempts
            val wait = try {
                val response = sendAuthorized(build)
                if (isLast || !RetryPolicy.isRetryable(response.code, idempotent)) return response
                val retryAfter = RetryPolicy.retryAfterMs(response.header("Retry-After"))
                response.close()
                retryPolicy.delayBeforeRetry(attempt, retryAfter)
            } catch (e: ApiException.Network) {
                val cause = e.cause as? IOException ?: throw e
                if (isLast || !RetryPolicy.isTransient(cause, idempotent)) throw e
                retryPolicy.delayBeforeRetry(attempt)
            }
            delay(wait)
            attempt++
        }
    }

    private suspend fun sendAuthorized(build: () -> Request.Builder): Response {
        val first = http.newCall(decorate(build()).header("Authorization", "Bearer ${auth.accessToken()}").build()).await()
        if (first.code != 401) return first
        first.close()
        val retry = http.newCall(decorate(build()).header("Authorization", "Bearer ${auth.accessToken(force = true)}").build()).await()
        if (retry.code == 401) {
            retry.close()
            throw ApiException.SessionExpired()
        }
        return retry
    }

    /**
     * Throws [ApiException.Http] for anything but 2xx; returns the body text otherwise. The body is
     * read on [Dispatchers.IO]: a coroutine resumes on its caller's dispatcher, which on Android is
     * often the main thread, where reading a socket throws `NetworkOnMainThreadException`.
     */
    suspend fun text(build: () -> Request.Builder): String = withContext(Dispatchers.IO) {
        send(build).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, body)
            body
        }
    }

    suspend fun <T> json(serializer: KSerializer<T>, build: () -> Request.Builder): T {
        val body = text(build)
        return try {
            apiJson.decodeFromString(serializer, body)
        } catch (e: Exception) {
            throw ApiException.Decode("${e.message?.take(200)}: ${body.take(200)}")
        }
    }

    /** `null` on 404, for endpoints where absence is an answer rather than a failure. */
    suspend fun <T> jsonOrNull(serializer: KSerializer<T>, build: () -> Request.Builder): T? = try {
        json(serializer, build)
    } catch (e: ApiException.Http) {
        if (e.status == 404) null else throw e
    }

    fun get(path: String): () -> Request.Builder = { Request.Builder().url(url(path)).get() }
    fun get(url: HttpUrl): () -> Request.Builder = { Request.Builder().url(url).get() }
    fun method(method: String, path: String, body: JsonElement? = null): () -> Request.Builder = {
        val requestBody = body?.toString()?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)
        Request.Builder().url(url(path)).method(method, requestBody)
    }

    /** A JSON body already encoded — for a queued write replayed exactly as it was first sent. */
    fun method(method: String, path: String, rawJson: String?): () -> Request.Builder = {
        val requestBody = rawJson?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)
        Request.Builder().url(url(path)).method(method, requestBody)
    }

    // ── Folders and files ───────────────────────────────────────────────────────────────────────

    /** The drive root's folder id — the signed-in user's id, read off the token's `sub`. */
    suspend fun rootFolderId(): String {
        auth.accessToken() // fresh before its claims are read
        return auth.userId ?: throw ApiException.NotSignedIn()
    }

    suspend fun folder(folderId: String?, limit: Int? = null, offset: Int? = null, orderByName: Boolean = false): FolderContentsDto {
        val id = folderId ?: rootFolderId()
        val u = url("/drive/folders/$id").newBuilder().apply {
            limit?.let { addQueryParameter("limit", it.toString()) }
            offset?.let { addQueryParameter("offset", it.toString()) }
            if (orderByName) {
                addQueryParameter("orderBy", "name")
                addQueryParameter("direction", "asc")
            }
        }.build()
        val listing = json(FolderContentsDto.serializer(), get(u))
        return listing.copy(files = listing.files.filter { it.deletedAt.isNullOrEmpty() })
    }

    suspend fun fileMetadata(fileId: String): FileDto = json(FileDto.serializer(), get("/drive/files/$fileId/metadata"))

    suspend fun createFolder(name: String, parentId: String?): FolderDto = json(
        FolderDto.serializer(),
        method("POST", "/drive/folders", buildJsonObject { put("name", name); put("parentId", parentId) }),
    )

    /** Moves a file to the Drive trash. */
    suspend fun trashFile(fileId: String) {
        text(method("DELETE", "/drive/files/$fileId"))
    }

    // ── Keys ────────────────────────────────────────────────────────────────────────────────────

    /** The caller's sealed DEK for a file, or `null` when the file has no key ref (404). */
    suspend fun fileKey(fileId: String): FileKeyDto? = jsonOrNull(FileKeyDto.serializer(), get("/drive/files/$fileId/key"))

    suspend fun setFileKey(fileId: String, sealed: String, keyVersion: Int) {
        text(method("PUT", "/drive/files/$fileId/key", buildJsonObject {
            put("encryptedFileKey", sealed); put("keyVersion", keyVersion)
        }))
    }

    /** The user's **active** published key, or `null` when they have published none (404). */
    suspend fun publishedKey(userId: String): PublicKeyDto? =
        jsonOrNull(PublicKeyDto.serializer(), get("/auth/users/$userId/public-key"))

    /** `POST /auth/keys` is append-only: publishing a different key is a rotation. */
    suspend fun publishKey(publicKey: String): PublicKeyDto =
        json(PublicKeyDto.serializer(), method("POST", "/auth/keys", buildJsonObject { put("publicKey", publicKey) }))

    /** The account's retired keys, or `null` when it has none stored (it never rotated). */
    suspend fun keyFile(): ArchivedKeys.KeyFile? = jsonOrNull(ArchivedKeys.KeyFile.serializer(), get("/drive/key-file"))

    // ── Upload / download plumbing ──────────────────────────────────────────────────────────────

    /** Opens a blob for streaming. The caller closes the response. */
    suspend fun openBlob(fileId: String, versionId: String? = null): Response {
        val path = if (versionId == null) "/drive/files/$fileId" else "/drive/files/$fileId/versions/$versionId/download"
        val response = send(get(path))
        if (!response.isSuccessful) {
            val body = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
            response.close()
            throw httpError(response.code, body)
        }
        return response
    }

    suspend fun upload(body: RequestBody): FileDto =
        json(FileDto.serializer()) { Request.Builder().url(url("/drive/files/upload")).post(body) }

    // ── Account ─────────────────────────────────────────────────────────────────────────────────

    suspend fun profile(): ProfileDto = json(ProfileDto.serializer(), get("/auth/me"))

    suspend fun lookupUser(email: String): UserDto? = jsonOrNull(
        UserDto.serializer(),
        get(url("/auth/users/lookup").newBuilder().addQueryParameter("email", email).build()),
    )

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
