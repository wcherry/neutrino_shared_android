package com.neutrino.shared.core.crypto

import java.util.Base64

/**
 * base64 helpers shared with every other Neutrino client.
 *
 * The server and the web write base64url without padding; older key bundles use standard base64
 * with it. Decoding therefore accepts both, exactly like `Data(base64URLEncoded:)` on iOS and
 * `b64::decode` in the Windows client.
 */
object B64 {

    /** base64url, no padding — the form the server and the web store. */
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Standard base64 with padding — only for the upload thumbnail, which the server expects so. */
    fun encodeStandard(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Accepts base64url or standard base64, padded or not. `null` for anything else. */
    fun decode(s: String): ByteArray? {
        val normalized = buildString(s.length) {
            for (c in s.trim()) {
                when (c) {
                    '=' -> Unit
                    '+' -> append('-')
                    '/' -> append('_')
                    else -> append(c)
                }
            }
        }
        return try {
            Base64.getUrlDecoder().decode(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
