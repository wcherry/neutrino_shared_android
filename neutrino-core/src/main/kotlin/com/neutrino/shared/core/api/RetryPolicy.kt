package com.neutrino.shared.core.api

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.random.Random

/**
 * When, and how often, a request that failed for a reason unrelated to the request itself is sent
 * again — the Android counterpart of `RetryPolicy` in neutrino_shared_ios, with the same rules.
 *
 * A dropped connection, a DNS blip, a proxy answering 502 while the server restarts: each is gone a
 * second later, and showing it as "Sync failed" makes a hiccup look like an outage. [NeutrinoApi.send]
 * retries them a few times with growing delays before any caller sees them.
 *
 * **Only idempotent requests are retried on an ambiguous failure.** A timeout or a reset does not
 * say whether the server acted. Repeating a GET is harmless; repeating a save is not — the first
 * may have landed, and the repeat would come back as a content-version conflict for the user's own
 * edit. A non-idempotent request is retried only when the server cannot have seen it (the
 * connection was never made) or said it did not act (429, 503).
 */
data class RetryPolicy(
    /** Total attempts, the first included. 1 means never retry. */
    val maxAttempts: Int = 3,
    /** The wait before the first retry; each later one doubles it. */
    val baseDelayMs: Long = 500,
    /** No wait — computed or asked for with `Retry-After` — is longer than this. */
    val maxDelayMs: Long = 8_000,
    /** How far each wait is spread, as a fraction of itself, so clients do not reconnect in lockstep. */
    val jitter: Double = 0.2,
) {
    /**
     * The wait before retry number [retry] (1 for the first). The server's `Retry-After` wins, still
     * capped. [random] is in `0..1` and injected so the curve is testable.
     */
    fun delayBeforeRetry(retry: Int, retryAfterMs: Long? = null, random: Double = Random.nextDouble()): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0, maxDelayMs)
        val backoff = minOf(baseDelayMs * 2.0.pow((retry - 1).coerceAtLeast(0)), maxDelayMs.toDouble())
        val spread = backoff * jitter.coerceIn(0.0, 1.0) * (random * 2 - 1)
        return (backoff + spread).toLong().coerceIn(0, maxDelayMs)
    }

    companion object {
        /** Three attempts over about a second and a half. */
        val DEFAULT = RetryPolicy()

        val NONE = RetryPolicy(maxAttempts = 1)

        /**
         * GET, HEAD and OPTIONS. PUT and DELETE are idempotent by the letter of HTTP but not here:
         * a save carries an `expectedContentVersion` its own first attempt bumps.
         */
        fun isIdempotent(method: String): Boolean = method.uppercase() in setOf("GET", "HEAD", "OPTIONS")

        /** Whether a request that got no response at all is worth another attempt. */
        fun isTransient(error: IOException, idempotent: Boolean): Boolean = when (error) {
            // Never connected: the server cannot have acted, whatever the method.
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
            // Connected, then timed out: the server may have acted.
            is SocketTimeoutException -> idempotent
            // OkHttp reports a cancelled call as a bare InterruptedIOException("Canceled").
            is InterruptedIOException -> false
            // Resets, unexpected end of stream: connected, so ambiguous.
            else -> idempotent && error.message?.contains("Canceled", ignoreCase = true) != true
        }

        /** Whether a status says "try again shortly" rather than "this request is wrong". */
        fun isRetryable(status: Int, idempotent: Boolean): Boolean = when (status) {
            429, 503 -> true
            408, 502, 504 -> idempotent
            else -> false
        }

        /** `Retry-After` in milliseconds: seconds, or an HTTP date. */
        fun retryAfterMs(header: String?, now: Instant = Instant.now()): Long? {
            val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            val date = runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
                ?: return null
            return Duration.between(now, date).toMillis().coerceAtLeast(0)
        }
    }
}
