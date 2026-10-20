package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * Fixed-window rate limiter for unauthenticated token guessing, shared through
 * PostgreSQL so every instance of the API enforces the same budget.
 *
 * A single upsert both records the attempt and reports the window's running
 * total; the counting window is keyed by
 * `floor(nowMillis / windowMillis)`, so no cleanup job is needed — expired
 * rows are simply never read again and are pruned opportunistically.
 */
class RateLimiter(
    private val db: JdbcClient,
    private val window: Duration = Duration.ofMinutes(1),
    private val maxAttempts: Int = 30,
) {
    /**
     * Records one attempt for [key] and returns false once the window budget is
     * exhausted. The caller must treat false as "reject without work".
     */
    fun allow(key: String): Boolean {
        val windowMillis = window.toMillis()
        val bucket = System.currentTimeMillis() / windowMillis
        val attempts = db.sql(
            """
            INSERT INTO auth_attempts(bucket,key,attempts) VALUES(?,?,1)
            ON CONFLICT(bucket,key) DO UPDATE SET attempts=auth_attempts.attempts+1
            RETURNING attempts
            """.trimIndent(),
        ).params(bucket, key).query(Int::class.java).single()
        return attempts <= maxAttempts
    }
}

/**
 * The caller's address for rate-limiting purposes. `HttpServletRequest` has no
 * reliable client-address accessor in this stack, so the socket address is
 * used: with the documented loopback/private-network deployment there is no
 * proxy in front of the API. Revisit if a reverse proxy is ever added.
 */
fun remoteAddressOf(host: String?): String = host?.takeIf { it.isNotBlank() } ?: "unknown"
