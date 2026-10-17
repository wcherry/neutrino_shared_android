package com.neutrino.shared.core

import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.NeutrinoAppConfig
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.api.Session
import com.neutrino.shared.core.api.SessionStore
import com.neutrino.shared.core.crypto.B64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.UUID

/**
 * An in-memory Neutrino server that stores real ciphertext, so the client's crypto runs end to
 * end in tests with no network. Implements only what the Android client calls, with the server's
 * real response shapes.
 */
class FakeServer {
    val server = MockWebServer()
    val userId = "user-0001"
    val email = "a@example.com"
    val password = "correct horse"
    var requireTotp: String? = null
    var accessTokenCounter = 0
    /** Tokens the server will reject with 401 (to exercise refresh). */
    val revokedAccessTokens = mutableSetOf<String>()
    var refreshTokenValid = true
    /** When false, uploads ignore the sealed key in the body (an older server). */
    var acceptsKeyInBody = true

    data class Published(val publicKey: String, val version: Int)
    val publishedKeys = mutableMapOf<String, MutableList<Published>>()
    data class StoredFile(
        val id: String, val name: String, val folderId: String?, val mimeType: String, val blob: ByteArray,
        val encryptedMetadata: String, val thumbnail: String?, val importSource: String?, val createdAt: String?,
    )
    val files = mutableMapOf<String, StoredFile>()
    /** id → (name, parent id; null is the root). */
    val folders = mutableMapOf<String, Pair<String, String?>>()
    /** fileId → userId → (sealed, version) */
    val keyRefs = mutableMapOf<String, MutableMap<String, Pair<String, Int>>>()
    var keyFile: String? = null
    val permissions = mutableListOf<Triple<String, String, String>>() // fileId, userId, role
    val users = mutableMapOf(userId to email)
    val requestLog = mutableListOf<String>()
    /** Part names of the last upload, in order. */
    var lastUploadParts = listOf<String>()

    private val json = Json { ignoreUnknownKeys = true }

    fun token(sub: String = userId): String {
        accessTokenCounter++
        val payload = B64.encode("""{"sub":"$sub","email":"$email","n":$accessTokenCounter}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeServer) {
                requestLog += "${request.method} ${request.path}"
                handle(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** The OAuth clients the server knows: every shipped Android app's (`oauth_clients`). */
    val registeredClients = NeutrinoApps.all.map { it.oauthClientId }.toMutableSet()

    /**
     * Routes an app's own tests add (Calendar's `/api/v1/calendar` ones), tried after the shared ones
     * for an authenticated request; `null` falls through to 404. Given the request and the caller's
     * user id.
     */
    var extraRoutes: ((RecordedRequest, String) -> MockResponse?)? = null

    fun client(store: SessionStore = MemorySessionStore(), config: NeutrinoAppConfig = NeutrinoApps.drive): NeutrinoApi =
        client(store, config) { http, auth -> NeutrinoApi(http, auth) }

    fun <A : NeutrinoApi> client(
        store: SessionStore = MemorySessionStore(),
        config: NeutrinoAppConfig = NeutrinoApps.drive,
        make: (OkHttpClient, AuthClient) -> A,
    ): A {
        val http = OkHttpClient()
        val auth = AuthClient(config, http, store, url) { "Test Phone — ${config.displayName}" }
        return make(http, auth)
    }

    fun ok(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    fun status(code: Int, message: String = ""): MockResponse =
        MockResponse().setResponseCode(code).setBody("""{"error":{"code":"E$code","message":"$message"}}""")


    private fun bodyJson(r: RecordedRequest): JsonObject = json.parseToJsonElement(r.body.readUtf8()).jsonObject

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.requestUrl!!.encodedPath
        when {
            path == "/api/v1/auth/login" -> {
                val b = bodyJson(r)
                if (r.getHeader("X-Device-Name")?.startsWith("Test Phone - Neutrino ") != true) return status(400, "bad device name")
                if (b["email"]!!.jsonPrimitive.content != email || b["password"]!!.jsonPrimitive.content != password) return status(401)
                val totp = requireTotp
                if (totp != null) {
                    val given = b["totpCode"]?.jsonPrimitive?.content ?: return ok("""{"requiresTwoFactor":true}""")
                    if (given != totp) return status(401)
                }
                return ok("""{"accessToken":"session-token","requiresTwoFactor":false}""")
            }
            path == "/api/v1/oauth/authorize" -> {
                if (r.getHeader("Authorization") != "Bearer session-token") return status(401)
                val u = r.requestUrl!!
                if (u.queryParameter("client_id") !in registeredClients) return status(400, "unknown client")
                val state = u.queryParameter("state")
                return MockResponse().setResponseCode(302)
                    .setHeader("Location", "neutrino://oauth/callback?code=the-code&state=$state")
            }
            path == "/api/v1/oauth/token" -> {
                val form = r.body.readUtf8().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                if (form["client_id"] !in registeredClients) return status(400)
                when (form["grant_type"]) {
                    "authorization_code" -> if (form["code"] != "the-code" || form["code_verifier"].isNullOrEmpty()) return status(400)
                    "refresh_token" -> if (!refreshTokenValid) return status(400, "invalid_grant")
                    else -> return status(400)
                }
                return ok("""{"access_token":"${token()}","refresh_token":"r-$accessTokenCounter","expires_in":900,"token_type":"Bearer"}""")
            }
            path == "/api/v1/auth/register" -> return ok("{}")
        }

        // Everything else needs a valid bearer.
        val bearer = r.getHeader("Authorization")?.removePrefix("Bearer ") ?: return status(401)
        if (bearer in revokedAccessTokens || !bearer.startsWith("eyJ")) return status(401)
        val caller = json.parseToJsonElement(B64.decode(bearer.split('.')[1])!!.decodeToString())
            .jsonObject["sub"]!!.jsonPrimitive.content

        Regex("/api/v1/auth/users/([^/]+)/public-key").matchEntire(path)?.let { m ->
            val k = publishedKeys[m.groupValues[1]]?.lastOrNull() ?: return status(404)
            return ok("""{"userId":"${m.groupValues[1]}","publicKey":"${k.publicKey}","version":${k.version}}""")
        }
        if (path == "/api/v1/auth/keys" && r.method == "POST") {
            val pk = bodyJson(r)["publicKey"]!!.jsonPrimitive.content
            val list = publishedKeys.getOrPut(caller) { mutableListOf() }
            if (list.lastOrNull()?.publicKey != pk) list += Published(pk, list.size + 1)
            return ok("""{"userId":"$caller","publicKey":"$pk","version":${list.last().version}}""")
        }
        if (path == "/api/v1/auth/users/lookup") {
            val e = r.requestUrl!!.queryParameter("email")
            val id = users.entries.firstOrNull { it.value == e }?.key ?: return status(404)
            return ok("""{"id":"$id","email":"$e","name":"Someone"}""")
        }
        if (path == "/api/v1/drive/key-file") return keyFile?.let(::ok) ?: status(404)
        if (path == "/api/v1/drive/files/upload") return upload(r, caller)
        Regex("/api/v1/drive/files/([^/]+)/key").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (id !in files) return status(404)
            return when (r.method) {
                "GET" -> keyRefs[id]?.get(caller)?.let { (s, v) -> ok("""{"fileId":"$id","userId":"$caller","encryptedFileKey":"$s","keyVersion":$v}""") } ?: status(404)
                "PUT" -> {
                    val b = bodyJson(r)
                    keyRefs.getOrPut(id) { mutableMapOf() }[caller] =
                        b["encryptedFileKey"]!!.jsonPrimitive.content to b["keyVersion"]!!.jsonPrimitive.content.toInt()
                    ok("{}")
                }
                else -> status(405)
            }
        }
        Regex("/api/v1/drive/files/([^/]+)/key/share").matchEntire(path)?.let { m ->
            val b = bodyJson(r)
            val recipient = b["recipientId"]!!.jsonPrimitive.content
            if (permissions.none { it.first == m.groupValues[1] && it.second == recipient }) return status(400, "RECIPIENT_NO_ACCESS")
            keyRefs.getOrPut(m.groupValues[1]) { mutableMapOf() }[recipient] =
                b["encryptedFileKey"]!!.jsonPrimitive.content to b["keyVersion"]!!.jsonPrimitive.content.toInt()
            return ok("{}")
        }
        Regex("/api/v1/drive/files/([^/]+)/permissions").matchEntire(path)?.let { m ->
            val b = bodyJson(r)
            val uid = b["userId"]!!.jsonPrimitive.content
            val role = b["role"]!!.jsonPrimitive.content
            permissions += Triple(m.groupValues[1], uid, role)
            return ok("""{"id":"p1","userId":"$uid","userEmail":"${b["userEmail"]!!.jsonPrimitive.content}","userName":"","role":"$role"}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/metadata").matchEntire(path)?.let { m ->
            val f = files[m.groupValues[1]] ?: return status(404)
            return ok(fileJson(f).toString())
        }
        Regex("/api/v1/drive/files/([^/]+)").matchEntire(path)?.let { m ->
            if (r.method == "DELETE") return if (files.remove(m.groupValues[1]) != null) ok("{}") else status(404)
            val f = files[m.groupValues[1]] ?: return status(404)
            return MockResponse().setResponseCode(200).setBody(Buffer().write(f.blob))
        }
        if (path == "/api/v1/drive/folders" && r.method == "POST") {
            val b = bodyJson(r)
            val id = UUID.randomUUID().toString()
            folders[id] = b["name"]!!.jsonPrimitive.content to b["parentId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            return ok(buildJsonObject { put("id", id); put("name", folders[id]!!.first); put("parentId", folders[id]!!.second) }.toString())
        }
        Regex("/api/v1/drive/folders/([^/]+)").matchEntire(path)?.let { m ->
            val folder = m.groupValues[1].takeIf { it != caller }
            val listed = files.values.filter { it.folderId == folder }
            return ok(buildJsonObject {
                put("folder", kotlinx.serialization.json.JsonNull)
                put("folders", buildJsonArray {
                    folders.filter { it.value.second == folder }.forEach { (id, f) -> add(buildJsonObject { put("id", id); put("name", f.first); put("parentId", f.second) }) }
                })
                put("files", buildJsonArray { listed.forEach { add(fileJson(it)) } })
            }.toString())
        }
        extraRoutes?.invoke(r, caller)?.let { return it }
        return status(404, "no route ${r.method} $path")
    }

    private fun fileJson(f: StoredFile) = buildJsonObject {
        put("id", f.id); put("name", f.name); put("folderId", f.folderId); put("sizeBytes", f.blob.size)
        put("mimeType", f.mimeType); put("isStarred", false)
        put("createdAt", f.createdAt ?: "2026-01-01T00:00:00"); put("updatedAt", "2026-01-02T03:04:05.123456")
        put("encryptedMetadata", f.encryptedMetadata); put("contentVersion", 1)
        put("coverThumbnailUrl", if (f.thumbnail != null) "/api/v1/drive/files/${f.id}/thumbnail?v=1" else null)
        if (acceptsKeyInBody) put("importSource", f.importSource)
    }

    private fun upload(r: RecordedRequest, caller: String): MockResponse {
        val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=")
        val fields = mutableMapOf<String, String>()
        val names = mutableListOf<String>()
        var blob: ByteArray? = null
        var fileName = ""
        var mime = ""
        MultipartReader(r.body, boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"]!!
                val name = Regex("name=\"([^\"]+)\"").find(disposition)!!.groupValues[1]
                names += name
                if (name == "file") {
                    fileName = Regex("filename=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
                    mime = part.headers["Content-Type"] ?: ""
                    blob = part.body.readByteArray()
                    break // the real server stops reading at the file part
                } else {
                    fields[name] = part.body.readUtf8()
                }
            }
        }
        lastUploadParts = names
        val id = UUID.randomUUID().toString()
        val f = StoredFile(
            id, fileName, fields["folder_id"], mime, blob ?: return status(400, "no file"),
            fields["encrypted_metadata"] ?: return status(400, "no metadata"), fields["thumbnail_b64"],
            if (acceptsKeyInBody) fields["import_source"] else null, fields["created_at"],
        )
        files[id] = f
        if (acceptsKeyInBody && fields["encrypted_file_key"] != null) {
            keyRefs.getOrPut(id) { mutableMapOf() }[caller] = fields["encrypted_file_key"]!! to fields["key_version"]!!.toInt()
        }
        return ok(fileJson(f).toString())
    }

    fun shutdown() = server.shutdown()
}

class MemorySessionStore(var session: Session? = null) : SessionStore {
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}
