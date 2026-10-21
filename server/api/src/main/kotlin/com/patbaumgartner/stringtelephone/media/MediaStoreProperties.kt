package com.patbaumgartner.stringtelephone.media

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import jakarta.validation.constraints.NotBlank

@ConfigurationProperties("st.media")
data class MediaStoreProperties(
    @field:NotBlank @DefaultValue("garage") val bucket: String = "garage",
    @field:NotBlank @DefaultValue("http://garage:3900") val endpoint: String = "http://garage:3900",
    @DefaultValue("garage") val region: String = "garage",
    @field:NotBlank val accessKey: String = "",
    @field:NotBlank val secretKey: String = "",
    @DefaultValue("true") val pathStyleAccess: Boolean = true,
    @DefaultValue("false") val enabled: Boolean = false,
) {
    val configured: Boolean get() = enabled && accessKey.isNotBlank() && secretKey.isNotBlank()
}
