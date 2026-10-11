package com.patbaumgartner.stringtelephone

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Path

@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class StringTelephoneServer {
    @Bean
    fun database(env: Environment) = Database(env.getProperty("st.database", "data/stringtelephone.db"))

    @Bean
    fun users(db: Database) = Users(db)

    @Bean
    fun wsHub() = WsHub()

    @Bean
    fun messages(db: Database, hub: WsHub) = Messages(db, hub::broadcastNewMessage)

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PbxGateway::class)
    fun pbxGateway(env: Environment): PbxGateway = AsteriskGateway(
        Path.of(env.getProperty("st.pbx.directory", "data/pbx")),
        env.getProperty("st.pbx.host", "asterisk"),
        env.getProperty("st.pbx.port", Int::class.java, 5038),
        env.getProperty("st.pbx.username", "stringtelephone"),
        requireNotNull(env.getProperty("st.pbx.password")?.takeIf { it.isNotBlank() }) { "PBX_AMI_PASSWORD is required" },
    )

    @Bean
    fun accounts(db: Database, users: Users, pbx: PbxGateway, hub: WsHub) = AccountService(db, users, pbx, hub)
}

fun main(args: Array<String>) {
    SpringApplicationBuilder(StringTelephoneServer::class.java).run(*args)
}
