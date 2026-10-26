package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.patbaumgartner.android.stringtelephone.R

/**
 * The Material icons this app draws, vendored as vector drawables. `material-icons-extended` is
 * frozen at 1.7.8 while the rest of Compose moves on, and it shipped thousands of icons to use
 * a handful. The drawables were generated from that library, so the geometry is unchanged.
 */
internal object StringTelephoneIcons {

    val ArrowBack: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back)

    val PersonOutline: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_person_outline)

    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)

    val MoreHoriz: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_more_horiz)

    val MicOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic_off)

    val Mic: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic)

    val Stop: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)

    val Speaker: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_speaker)

    val Bluetooth: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_bluetooth)

    val Headphones: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_headphones)

    val Call: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_call)

    val Message: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_message)

    val Send: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_send)

    val Done: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_done)

    /** Double tick: the recipient read the message (ADR-0010). */
    val DoneAll: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_done_all)

    val Schedule: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_schedule)

    val ErrorOutline: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_error_outline)

    val Mood: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_mood)

    val Reply: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_reply)

    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_close)

    val Delete: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_delete)

    /** Placeholder while a chat image loads (Phase 6). */
    val Image: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_image)

    /** Play button of a voice bubble (Phase 11). */
    val Play: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_play_arrow)

    /** Pause button of a playing voice bubble (Phase 11). */
    val Pause: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_pause)

    /** Hands-free recording lock (Phase 11). */
    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock)
}
