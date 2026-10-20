package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import kotlinx.coroutines.delay

@Composable
internal fun PageHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    StringTelephoneIcons.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ErrorNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun InfoNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
internal fun CircleAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(54.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

/** Conversations shorter than this never need a scroll indicator. */
internal const val SCROLLBAR_MIN_ITEMS = 8

/** Thumb geometry in dp; the composable converts to pixels for its density. */
internal data class ScrollbarStyle(
    val thumbWidthDp: Float,
    val thumbMinHeightDp: Float,
    val edgePaddingDp: Float,
    val densityScale: Float,
)

/** Style at 1x density; the composable derives the real one from [LocalDensity]. */
internal val DefaultScrollbarStyle = ScrollbarStyle(
    thumbWidthDp = 4f,
    thumbMinHeightDp = 48f,
    edgePaddingDp = 3f,
    densityScale = 1f,
)

internal data class ScrollbarMetrics(
    val thumbHeightPx: Float,
    val thumbTopPx: Float,
    val fillsViewport: Boolean,
)

private const val MIN_THUMB_VIEWPORT_FRACTION = 0.08f

/**
 * Thumb geometry from [LazyListLayoutInfo] values: height from the
 * visible-to-total item ratio over the viewport, position from scroll
 * progress estimated over the first visible item's own height. Pure function
 * so the geometry rules are unit-testable; [style.densityScale] converts the
 * dp thresholds to pixels.
 *
 * [firstVisibleScrolledPx] is a non-negative magnitude: the topmost visible
 * item reports a zero or negative `offset` in a forward list, so the caller
 * passes `-firstVisibleItem.offset`, never the raw offset.
 */
internal fun scrollbarMetrics(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleScrolledPx: Int,
    firstVisibleItemSizePx: Int,
    viewportHeightPx: Float,
    canScrollForward: Boolean,
    style: ScrollbarStyle = DefaultScrollbarStyle,
): ScrollbarMetrics {
    require(totalItems > 0) { "totalItems must be positive" }
    require(viewportHeightPx > 0f) { "viewport height must be positive" }
    require(firstVisibleItemSizePx > 0) { "first visible item size must be positive" }
    // Never above the viewport: a thumb taller than its track would make
    // coerceIn(min, max) throw in the draw phase on a short viewport.
    val minHeightPx = maxOf(
        MIN_THUMB_VIEWPORT_FRACTION * viewportHeightPx,
        style.thumbMinHeightDp * style.densityScale,
    ).coerceAtMost(viewportHeightPx)
    val thumbHeight = (viewportHeightPx * visibleItems / totalItems)
        .coerceIn(minHeightPx, viewportHeightPx)
    val scrolledFraction = if (canScrollForward) {
        (firstVisibleScrolledPx.toFloat() / firstVisibleItemSizePx).coerceIn(0f, 1f)
    } else {
        // Pinned to the newest message: snap the thumb to the bottom.
        1f
    }
    val scrollable = (viewportHeightPx - thumbHeight).coerceAtLeast(0f)
    val top = (scrollable * scrolledFraction)
    return ScrollbarMetrics(
        thumbHeightPx = thumbHeight,
        thumbTopPx = top,
        fillsViewport = thumbHeight >= viewportHeightPx,
    )
}

/**
 * A thin scroll thumb on the trailing edge of the conversation, drawn from
 * the [LazyListState] layout info so it stays correct across day chips and
 * variable bubble heights. Appears while scrolling, lingers briefly, then
 * fades; conversations with fewer than [SCROLLBAR_MIN_ITEMS] rows never
 * show one. Decorative (no drag): the chat auto-pins to the newest message,
 * so a draggable handle would fight the auto-scroll. Sizes itself to a 20.dp
 * strip on the trailing edge spanning the list viewport, so it only needs
 * edge alignment from the caller rather than explicit sizing.
 *
 * [visibleOverride] forces the thumb on regardless of scroll state; it is a
 * seam for previews and screenshot tests, which cannot perform real scrolls.
 */
@Composable
internal fun ChatScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    visibleOverride: Boolean? = null,
) {
    val density = LocalDensity.current
    val thumbColor = StringTelephoneTheme.chat.meta
    // Resolved in composition, not per frame: density changes are rare.
    val style = remember(density) { DefaultScrollbarStyle.copy(densityScale = density.density) }
    val thumbWidthPx = with(density) { style.thumbWidthDp.dp.toPx() }
    val edgePaddingPx = with(density) { style.edgePaddingDp.dp.toPx() }

    // Show while scrolling; hide after a short linger, like the platform scrollbar.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(
        listState.isScrollInProgress,
        listState.layoutInfo.totalItemsCount,
        visibleOverride,
    ) {
        when {
            visibleOverride != null -> shown = visibleOverride
            listState.isScrollInProgress && listState.layoutInfo.totalItemsCount >= SCROLLBAR_MIN_ITEMS -> shown = true
            shown -> {
                delay(SCROLLBAR_LINGER_MS)
                shown = false
            }
        }
    }

    // The forced-on override skips the fade so static previews and screenshot
    // renders are deterministic: full alpha on first composition, no frames.
    val alpha = if (visibleOverride == true) {
        1f
    } else {
        animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(durationMillis = SCROLLBAR_FADE_MS),
            label = "chatScrollbarAlpha",
        ).value
    }
    if (alpha > 0f) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .width(20.dp)
                .alpha(alpha)
                .drawBehind {
                    val info = listState.layoutInfo
                    val visibleItems = info.visibleItemsInfo
                    if (info.totalItemsCount == 0 || size.height <= 0f || visibleItems.isEmpty()) return@drawBehind
                    val first = visibleItems.first()
                    // A zero-height row cannot carry progress; drawing on would
                    // trip the metrics preconditions, so skip this frame.
                    if (first.size <= 0) return@drawBehind
                    val metrics = scrollbarMetrics(
                        totalItems = info.totalItemsCount,
                        visibleItems = visibleItems.size,
                        // The topmost item offsets backwards in a forward list;
                        // the metrics consume a non-negative scroll magnitude.
                        firstVisibleScrolledPx = -first.offset,
                        firstVisibleItemSizePx = first.size,
                        viewportHeightPx = size.height,
                        canScrollForward = listState.canScrollForward,
                        style = style,
                    )
                    if (metrics.fillsViewport) return@drawBehind
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(
                            size.width - thumbWidthPx - edgePaddingPx,
                            metrics.thumbTopPx,
                        ),
                        size = Size(thumbWidthPx, metrics.thumbHeightPx),
                        cornerRadius = CornerRadius(thumbWidthPx / 2f),
                    )
                },
        )
    }
}

private const val SCROLLBAR_FADE_MS = 200
private const val SCROLLBAR_LINGER_MS = 600L
