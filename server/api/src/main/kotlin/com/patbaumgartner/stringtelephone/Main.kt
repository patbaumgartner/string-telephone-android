package com.patbaumgartner.stringtelephone

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

// Pooling, the JdbcClient bean and Flyway migrations all come from Spring Boot
// auto-configuration: spring.datasource.* provides the HikariCP DataSource and
// flyway applies db/migration before the context serves traffic (ADR-0008).
@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class StringTelephoneServer {
    @Bean
    fun users(jdbcClient: JdbcClient) = Users(jdbcClient)

    @Bean
    fun wsHub() = WsHub()

    @Bean
    fun messages(jdbcClient: JdbcClient, hub: WsHub) = Messages(jdbcClient, hub::broadcastNewMessage)

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PbxGateway::class)
    fun pbxGateway(env: Environment): PbxGateway = AsteriskGateway(
        Path.of(env.getProperty("st.pbx.directory", "data/pbx")),
        env.getProperty("st.pbx.host", "asterisk"),
        env.getProperty("st.pbx.port", Int::class.java, 5038),
        env.getProperty("st.pbx.username", "stringtelephone"),
        requireNotNull(env.getProperty("st.pbx.password")?.takeIf { it.isNotBlank() }) { "PBX_AMI_PASSWORD is required" },
        env.getProperty("st.pbx.require-tls", Boolean::class.java, false),
    )

    @Bean
    fun accounts(jdbcClient: JdbcClient, tx: TransactionTemplate, users: Users, pbx: PbxGateway, hub: WsHub) =
        AccountService(jdbcClient, tx, users, pbx, hub)

    @Bean
    fun presence(pbx: PbxGateway, hub: WsHub, users: Users) = Presence(pbx, hub, users)
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(StringTelephoneServer::class.java).run(*args)
}
