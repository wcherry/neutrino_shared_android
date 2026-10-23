package com.neutrino.shared.core.api

import com.neutrino.shared.core.FakeServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class RetryTest {

    // ── The policy ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun delayDoublesFromTheBaseAndCaps() {
        val policy = RetryPolicy(maxAttempts = 6, baseDelayMs = 500, maxDelayMs = 3_000, jitter = 0.0)
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L), (1..4).map { policy.delayBeforeRetry(it) })
    }

    @Test
    fun jitterStaysWithinItsFraction() {
        val policy = RetryPolicy(baseDelayMs = 1_000, jitter = 0.2)
        assertEquals(800L, policy.delayBeforeRetry(1, random = 0.0))
        assertEquals(1_200L, policy.delayBeforeRetry(1, random = 1.0))
    }

    @Test
    fun retryAfterWinsButIsCapped() {
        val policy = RetryPolicy(maxDelayMs = 8_000)
        assertEquals(4_000L, policy.delayBeforeRetry(1, retryAfterMs = 4_000))
        assertEquals(8_000L, policy.delayBeforeRetry(1, retryAfterMs = 600_000))
    }

    @Test
    fun onlyReadsAreIdempotent() {
        assertTrue(RetryPolicy.isIdempotent("GET"))
        assertTrue(RetryPolicy.isIdempotent("head"))
        // A save's expectedContentVersion makes a repeated PUT look like somebody else's edit.
        listOf("PUT", "POST", "PATCH", "DELETE").forEach { assertFalse(it, RetryPolicy.isIdempotent(it)) }
    }

    @Test
    fun transportErrors() {
        assertTrue(RetryPolicy.isTransient(UnknownHostException(), idempotent = false))
        assertTrue(RetryPolicy.isTransient(ConnectException(), idempotent = false))
        assertTrue(RetryPolicy.isTransient(SocketTimeoutException(), idempotent = true))
        assertFalse(RetryPolicy.isTransient(SocketTimeoutException(), idempotent = false))
        assertTrue(RetryPolicy.isTransient(IOException("unexpected end of stream"), idempotent = true))
        assertFalse(RetryPolicy.isTransient(IOException("unexpected end of stream"), idempotent = false))
        assertFalse(RetryPolicy.isTransient(IOException("Canceled"), idempotent = true))
    }

    @Test
    fun retryableStatuses() {
        listOf(429, 503).forEach { assertTrue("$it", RetryPolicy.isRetryable(it, idempotent = false)) }
        listOf(408, 502, 504).forEach {
            assertTrue("$it", RetryPolicy.isRetryable(it, idempotent = true))
            assertFalse("$it", RetryPolicy.isRetryable(it, idempotent = false))
        }
        listOf(200, 400, 401, 404, 409, 500).forEach { assertFalse("$it", RetryPolicy.isRetryable(it, idempotent = true)) }
    }

    @Test
    fun retryAfterReadsSecondsAndHttpDates() {
        assertEquals(7_000L, RetryPolicy.retryAfterMs("7"))
        val now = Instant.ofEpochSecond(1_000_000_000) // Sun, 09 Sep 2001 01:46:40 GMT
        assertEquals(10_000L, RetryPolicy.retryAfterMs("Sun, 09 Sep 2001 01:46:50 GMT", now))
        assertNull(RetryPolicy.retryAfterMs(null))
        assertNull(RetryPolicy.retryAfterMs("soon"))
    }

    // ── NeutrinoApi.send ─────────────────────────────────────────────────────────────────────────

    private lateinit var fake: FakeServer
    private lateinit var api: NeutrinoApi
    /** Responses for `/api/v1/test`, in order; once empty it answers 200. */
    private val script = ArrayDeque<MockResponse>()
    private var hits = 0

    @Before
    fun setUp() {
        fake = FakeServer()
        fake.extraRoutes = { request, _ ->
            if (request.requestUrl!!.encodedPath == "/api/v1/test") {
                hits++
                script.removeFirstOrNull() ?: fake.ok("{}")
            } else null
        }
        // OkHttp's own connection-failure retry is off, so these tests see only this layer's.
        api = fake.client { http, auth -> NeutrinoApi(http.newBuilder().retryOnConnectionFailure(false).build(), auth) }
        api.retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5, jitter = 0.0)
        runBlocking { api.auth.login(fake.email, fake.password) }
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun dropped() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    @Test
    fun aReadRidesOutABlip() = runBlocking {
        script += listOf(dropped(), fake.status(502))
        assertEquals("{}", api.text(api.get("/test")))
        assertEquals(3, hits)
    }

    @Test
    fun aReadGivesUpAfterTheLastAttempt() = runBlocking {
        script += listOf(fake.status(503), fake.status(503), fake.status(503))
        try {
            api.text(api.get("/test")); fail()
        } catch (e: ApiException.Http) {
            assertEquals(503, e.status)
        }
        assertEquals(3, hits)
    }

    @Test
    fun aSaveWhoseConnectionDroppedIsNotRepeated() = runBlocking {
        script += dropped()
        try {
            api.text(api.method("PUT", "/test", rawJson = "{}")); fail()
        } catch (_: ApiException.Network) {
        }
        assertEquals(1, hits)
    }

    @Test
    fun aSaveTheServerDeclinedIsRepeated() = runBlocking {
        script += fake.status(429).setHeader("Retry-After", "0")
        assertEquals("{}", api.text(api.method("POST", "/test", rawJson = "{}")))
        assertEquals(2, hits)
    }

    @Test
    fun aClientErrorIsNotRetried() = runBlocking {
        script += fake.status(404)
        try {
            api.text(api.get("/test")); fail()
        } catch (e: ApiException.Http) {
            assertEquals(404, e.status)
        }
        assertEquals(1, hits)
    }
}
