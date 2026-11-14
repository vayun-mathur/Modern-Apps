package com.vayunmathur.library.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * The full-information card a phone list row becomes on TV: leading art, title, a byline,
 * a couple of lines of description, a badge row and an optional trailing status/progress -
 * everything a phone search result shows. Fills its grid cell; lay them out 3 across in a
 * [TvGrid] so a screen shows a dozen at once.
 *
 * @param badges source/tier/installed pills, rendered in a row under the text.
 * @param progress 0..1 bar along the card bottom (downloads, installs, resume); `null` hides it.
 */
@Composable
fun TvInfoCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    subtitle: String? = null,
    description: String? = null,
    descriptionLines: Int = 2,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    leadingSize: Dp = 64.dp,
    badges: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.cellWidth(width).onFocusChanged { if (it.hasFocus) onFocus?.invoke() },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = scheme.surface,
            contentColor = scheme.onSurface,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
        ),
    ) {
        Column {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                if (leading != null) {
                    Box(
                        Modifier
                            .size(leadingSize)
                            .clip(MaterialTheme.shapes.small)
                            .background(scheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                        content = leading,
                    )
                }
                TvInfoCardText(
                    title = title,
                    subtitle = subtitle,
                    description = description,
                    descriptionLines = descriptionLines,
                    hasLeading = leading != null,
                    badges = badges,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Box(Modifier.padding(start = 10.dp)) { TvIcon(trailing) }
                }
            }
            if (progress != null) TvProgressStrip(progress)
        }
    }
}

/** Fixed width when given, otherwise fill the grid cell / parent. */
internal fun Modifier.cellWidth(width: Dp): Modifier =
    if (width == Dp.Unspecified) fillMaxWidth() else width(width)

/** Title, subtitle, description and badge column of a [TvInfoCard]. */
@Composable
private fun TvInfoCardText(
    title: String,
    subtitle: String?,
    description: String?,
    descriptionLines: Int,
    hasLeading: Boolean,
    badges: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(start = if (hasLeading) 14.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.86f),
                maxLines = descriptionLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badges != null) {
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = badges,
            )
        }
    }
}
