package com.vayunmathur.library.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Text

/** Width-over-height aspect ratios for TV artwork: 16:9 landscape, 2:3 poster, square. */
object TvAspect {
    const val Landscape = 16f / 9f
    const val Poster = 2f / 3f
    const val Square = 1f
}

/**
 * The one card for media and catalog grids: image on top that scales and gets a white ring on
 * focus, title/subtitle underneath (outside the image, so text never sits on artwork). Fills
 * its grid cell unless [width] is given (for horizontal rows).
 *
 * @param image fills the card; use `ContentScale.Crop`. Draw nothing for a tinted placeholder.
 * @param progress 0..1 resume bar along the image bottom, `null` hides it.
 * @param badge short overlay text in the bottom-end corner (duration, "Installed", episode).
 */
@Composable
fun TvMediaCard(
    onClick: () -> Unit,
    image: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    aspectRatio: Float = TvAspect.Landscape,
    title: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    StandardCardContainer(
        modifier = modifier.cellWidth(width).onFocusChanged { if (it.hasFocus) onFocus?.invoke() },
        imageCard = { interactionSource ->
            Card(
                onClick = onClick,
                onLongClick = onLongClick,
                interactionSource = interactionSource,
                shape = CardDefaults.shape(shape),
                scale = CardDefaults.scale(focusedScale = 1.04f),
                colors = CardDefaults.colors(containerColor = scheme.surfaceVariant),
                border = CardDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, scheme.inverseSurface), shape = shape),
                ),
                glow = CardDefaults.glow(focusedGlow = Glow(scheme.primary.copy(alpha = 0.45f), 20.dp)),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio)) {
                    image()
                    if (badge != null) {
                        TvBadge(
                            text = badge,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            container = Color.Black.copy(alpha = 0.72f),
                            content = Color.White,
                        )
                    }
                    if (progress != null && progress > 0f) {
                        TvProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        },
        title = {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        subtitle = {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        },
    )
}

/** A thin accent progress bar, used along image bottoms and in download rows. */
@Composable
fun TvProgressStrip(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Small pill label. Defaults to the accent container; pass colours for overlays/warnings. */
@Composable
fun TvBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(container, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
