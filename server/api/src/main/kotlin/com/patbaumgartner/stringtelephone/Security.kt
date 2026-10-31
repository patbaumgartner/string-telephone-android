package com.patbaumgartner.stringtelephone

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    @Order(1)
    fun probeSecurity(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/health", "/ready", "/actuator/health", "/actuator/health/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    fun adminIdentity(env: Environment): InMemoryUserDetailsManager {
        val username = env.getProperty("st.admin.username").orEmpty()
        val password = env.getProperty("st.admin.password").orEmpty()
        require(username.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))) { "ADMIN_USERNAME is required and must be a valid username" }
        require(password.length >= 24 && password.none { it == '\r' || it == '\n' }) {
            "ADMIN_PASSWORD must be at least 24 characters and contain no line breaks"
        }
        val encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        return InMemoryUserDetailsManager(
            User.withUsername(username).password("{pbkdf2@SpringSecurity_v5_8}${encoder.encode(password)}").roles("ADMIN").build(),
        )
    }

    @Bean
    @Order(2)
    fun androidSecurity(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/v1/me", "/v1/contacts", "/v1/conversations/**", "/v1/groups/**", "/v1/media", "/v1/media/**", "/v1/ws")
            // These stateless routes authenticate with bearer headers, not browser session cookies.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    fun adminSecurity(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests {
            it.requestMatchers("/admin/login", "/admin/style.css", "/error").permitAll()
            it.anyRequest().hasRole("ADMIN")
        }.formLogin {
            it.loginPage("/admin/login").loginProcessingUrl("/admin/login")
                .defaultSuccessUrl("/admin/", true).failureUrl("/admin/login?error").permitAll()
        }.logout {
            it.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout")
                .invalidateHttpSession(true).deleteCookies("JSESSIONID")
        }.headers {
            it.contentSecurityPolicy { policy ->
                policy.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
            }
            it.cacheControl { }
        }
        return http.build()
    }
}
