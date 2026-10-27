package com.patbaumgartner.android.stringtelephone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Loads a (possibly authenticated) remote image into an [Image] composable
 * without pulling in a full image-loading library. Falls back to nothing while
 * loading or on failure, so a missing network never breaks the chat.
 */
@Composable
fun RemoteImage(
    url: String,
    token: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // Screenshot previews only: a pre-decoded bitmap rendered synchronously,
    // because Robolectric dispatches compose effects of later activities on a
    // stale looper, which would make the fetch non-deterministic. Null in
    // production, where the network path below always runs.
    RemoteImagePreview?.let { preset ->
        Image(bitmap = preset.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
        return
    }
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url, token) {
        if (url.isBlank()) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).apply {
                    token?.let { header("Authorization", "Bearer $it") }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
        }
        bitmap = result
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}

/** Test-only seam: when set, [RemoteImage] renders this bitmap instead of fetching. */
internal var RemoteImagePreview: android.graphics.Bitmap? = null
