package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Cheap database reachability probe for the readiness endpoint
 * (`/ready`, `/actuator/health`). `/health` stays a pure process check: a probe
 * that fences traffic on the database must be able to fail.
 */
class HealthProbe(private val db: JdbcClient) {
    fun isReachable(): Boolean =
        try {
            db.sql("SELECT 1").query(Int::class.java).single() == 1
        } catch (_: RuntimeException) {
            false
        }
}
