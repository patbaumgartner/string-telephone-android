package com.patbaumgartner.stringtelephone

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.server.Compression
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Duration

// Pooling, the JdbcClient bean and Flyway migrations all come from Spring Boot
// auto-configuration: spring.datasource.* provides the HikariCP DataSource and
// flyway applies db/migration before the context serves traffic (ADR-0008).
// Wiring stays explicit @Bean methods (no component scanning) by design; the
// configuration properties are the typed, validated seam to the environment.
@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(PbxProperties::class, TlsProperties::class, AuthProperties::class)
class StringTelephoneServer {
    @Bean
    fun users(jdbcClient: JdbcClient) = Users(jdbcClient)

    @Bean
    fun healthProbe(jdbcClient: JdbcClient) = HealthProbe(jdbcClient)

    @Bean
    fun rateLimiter(jdbcClient: JdbcClient, auth: AuthProperties) =
        RateLimiter(jdbcClient, maxAttempts = auth.rateLimitAttempts)

    @Bean
    fun wsHub(users: Users, presence: Presence) = WsHub(users, presence)

    @Bean
    fun messages(jdbcClient: JdbcClient, hub: WsHub) = Messages(jdbcClient, hub::broadcastNewMessage)

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PbxGateway::class)
    fun pbxGateway(properties: PbxProperties): PbxGateway = AsteriskGateway(
        Path.of(properties.directory),
        properties.host,
        properties.port,
        properties.username,
        properties.password,
        properties.requireTls,
    )

    @Bean
    fun accounts(
        jdbcClient: JdbcClient,
        tx: TransactionTemplate,
        users: Users,
        pbx: PbxGateway,
        hub: WsHub,
    ) = AccountService(jdbcClient, tx, users, pbx, hub)

    @Bean
    fun presence(pbx: PbxGateway, hub: WsHub, users: Users) = Presence(pbx, hub, users)

    /**
     * Boundary for background scheduling: the PBX reconciler and the presence
     * broadcaster are independent and must not queue behind one slow AMI call.
     * Shutdown waits only briefly, so a hung PBX cannot block a redeploy.
     */
    @Bean
    fun taskScheduler(): org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler =
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("st-sched-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(5)
        }

    /**
     * Boundary for request-scoped background work (account lifecycle history).
     * Bounded and cheap to drain; a full queue fails the write, never the reply.
     */
    @Bean(name = ["applicationTaskExecutor", "taskExecutor"])
    fun taskExecutor(): org.springframework.core.task.TaskExecutor =
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 4
            queueCapacity = 64
            setThreadNamePrefix("st-async-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(5)
        }

    // Explicitly minimal gzip: the admin page is the only compressible body and
    // this must never turn into a BREACH-style secret-length oracle for the
    // credential responses, so it is stated rather than left to defaults.
    @Bean
    fun compressionCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.setCompression(Compression().apply {
                setEnabled(true)
                setMimeTypes(arrayOf("text/html", "text/css", "text/plain"))
                setMinResponseSize(org.springframework.util.unit.DataSize.ofKilobytes(2))
            })
        }

    /** The one place a new transaction is allowed to be opened. */
    @Bean
    fun transactions(dataSource: javax.sql.DataSource): TransactionTemplate =
        TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).apply {
            propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED
            isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = 10
        }
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(StringTelephoneServer::class.java).run(*args)
}
