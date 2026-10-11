package com.patbaumgartner.stringtelephone

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverters
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class WireConfiguration : WebMvcConfigurer {
    override fun configureMessageConverters(builder: HttpMessageConverters.ServerBuilder) {
        builder.withKotlinSerializationJsonConverter(KotlinSerializationJsonHttpMessageConverter(Json { ignoreUnknownKeys = true }))
    }
}
