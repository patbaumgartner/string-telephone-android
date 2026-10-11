package com.patbaumgartner.android.stringtelephone.di

import com.patbaumgartner.android.stringtelephone.BuildConfig

/** Version shown in Settings so a report can name the exact build. */
data class BuildInfo(val versionName: String, val commit: String) {
    companion object {
        val current = BuildInfo(BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT)
    }
}
