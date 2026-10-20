package com.patbaumgartner.stringtelephone

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * One PostgreSQL container per test JVM (JUnit 4 has no extension wiring;
 * Testcontainers' Ryuk reaps the container when the JVM exits). Each test
 * subject gets its own database created from the container's default one, so
 * Spring contexts stay as isolated as the former per-test SQLite files were.
 *
 * Spring-context tests consume [springArgs]: Spring Boot's own DataSource and
 * Flyway auto-configuration then behaves exactly as in production. Tests
 * without a context build a migrated pool directly through [newJdbcClient].
 */
object TestPostgres {
    private val container: PostgreSQLContainer<*> = PostgreSQLContainer(
        DockerImageName.parse("postgres:18-alpine"),
    ).apply {
        withDatabaseName("stringtelephone")
        withUsername("stringtelephone")
        withPassword("stringtelephone-test")
        start()
    }

    /** A throwaway database name for one independently isolated test subject. */
    private fun freshDatabaseName(): String {
        val name = "st_test_" + UUID.randomUUID().toString().replace("-", "")
        // CREATE DATABASE takes a literal identifier, not a bind parameter.
        require(name.matches(Regex("[a-z0-9_]+"))) { "unsafe generated database name" }
        container.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    /** `spring.datasource.*` properties pointing at a fresh database. */
    fun springArgs(): Map<String, String> {
        val name = freshDatabaseName()
        return mapOf(
            "spring.datasource.url" to "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$name",
            "spring.datasource.username" to container.username,
            "spring.datasource.password" to container.password,
        )
    }

    /**
     * A migrated database behind a JdbcClient for tests that run no Spring
     * context. JUnit 4 builds a new test instance per method, so pools are
     * capped at one connection and closed when the JVM exits rather than
     * exhausting the container's connection limit.
     */
    fun newJdbcClient(): JdbcClient {
        val url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${freshDatabaseName()}"
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = container.username
                password = container.password
                // Flyway borrows more than one connection while migrating, so
                // one is not enough; small bounds keep per-method pools cheap.
                minimumIdle = 1
                maximumPoolSize = 4
            },
        )
        pools += dataSource
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        return JdbcClient.create(dataSource)
    }

    private val pools = mutableListOf<HikariDataSource>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { pools.forEach { it.close() } })
    }
}
