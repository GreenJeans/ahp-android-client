package dev.ahpclient.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionTokenTest {
    // Vectors generated with Node: createHash('sha256').update(id).digest('base64url'), plus the 'a' prefix rule.
    @Test
    fun `matches the Node implementation`() {
        assertEquals("vGNgMhnpBq2JWi9nMZV3-w4cHTRvpEqKOzJMyv1sMjs", ConnectionToken.derive("test-tunnel-1"))
        assertEquals("cCw9aND4FsPb7xIgD9wixpUcIcLG1aMpqt7sU9fYzwY", ConnectionToken.derive("test-tunnel-2"))
        assertEquals("LXEWQrcmsEQBYnyp-6wy9chTD7GQPMTbAiWHF5IaSIE", ConnectionToken.derive("x"))
    }

    @Test
    fun `prefixes a when the digest would start with a dash`() {
        // sha256("dash-69") in base64url starts with '-'
        assertEquals("a-CS_Pj9ywJIBAa2gzrf2WVE8l41It0l-V-CQ_iAir0Y", ConnectionToken.derive("dash-69"))
    }

    @Test
    fun `is url safe and unpadded`() {
        val token = ConnectionToken.derive("any-id")

        // 32 bytes encode to 43 base64url characters; the dash rule can add one
        assertTrue(token.length in 43..44, "length ${token.length}")
        assertTrue(token.none { it == '=' || it == '+' || it == '/' })
    }
}
