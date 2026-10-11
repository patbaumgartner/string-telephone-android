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

    val VolumeOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_volume_off)

    val VolumeUp: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_volume_up)

    val Stop: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)

    val GraphicEq: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_graphic_eq)

    val Headphones: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_headphones)

    val Call: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_call)

    val Message: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_message)
}
