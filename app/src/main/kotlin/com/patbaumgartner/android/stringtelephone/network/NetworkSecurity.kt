package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.BuildConfig
import com.patbaumgartner.android.stringtelephone.core.ServerUrlValidation
import com.patbaumgartner.android.stringtelephone.core.validateServerUrl
import okhttp3.OkHttpClient

internal fun requireAllowedServerUrl(url: String, development: Boolean = BuildConfig.DEBUG) {
    val validated = validateServerUrl(url)
    require(validated is ServerUrlValidation.Valid && (development || validated.secure)) {
        "Production connections require a valid HTTPS server URL"
    }
}

// Also disallow HTTPS -> HTTP redirects; URL validation alone only covers the first hop.
internal fun OkHttpClient.withTransportSecurity(): OkHttpClient = newBuilder()
    .followSslRedirects(false)
    .build()
