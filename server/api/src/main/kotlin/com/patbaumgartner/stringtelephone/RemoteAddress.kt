package com.patbaumgartner.stringtelephone

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse

/**
 * Makes the caller's address available to `HttpServletRequest.getRemoteAddr()`
 * without the deprecated `RemoteIpValve` modernisation path. By default the
 * socket address is already what we want (`st.auth.remote-addr=RemoteAddr`);
 * a deployment behind a proxy sets `ST_REMOTE_ADDR_HEADER=X-Forwarded-For` and
 * only the left-most entry is adopted.
 */
class RemoteAddressFilter(private val header: String) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val forwarded = request.getHeader(header)?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        if (header == "RemoteAddr" || forwarded == null) {
            chain.doFilter(request, response)
            return
        }
        chain.doFilter(object : HttpServletRequestWrapper(request) {
            override fun getRemoteAddr(): String = forwarded
        }, response)
    }
}

@Configuration(proxyBeanMethods = false)
class RemoteAddressConfiguration {
    @Bean
    fun remoteAddressFilterRegistration(auth: AuthProperties): FilterRegistrationBean<RemoteAddressFilter> =
        FilterRegistrationBean(RemoteAddressFilter(auth.remoteAddr)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }
}
