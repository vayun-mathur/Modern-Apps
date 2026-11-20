package com.vayunmathur.library.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text

/**
 * The action button. [primary] fills with the app accent (one per screen: Play, Install);
 * secondary actions sit on a neutral tonal fill. Both go white on focus.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) scheme.primary else scheme.surfaceVariant,
            contentColor = if (primary) scheme.onPrimary else scheme.onSurface,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        if (icon != null) {
            Box(Modifier.size(ButtonDefaults.IconSize), contentAlignment = Alignment.Center) { TvIcon(icon) }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon-only button for transport controls and compact toolbars. */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        colors = IconButtonDefaults.colors(
            containerColor = scheme.surfaceVariant.copy(alpha = 0.7f),
            contentColor = scheme.onSurface,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
        ),
    ) {
        TvIcon(icon)
    }
}

/** Filter/selection chip (categories, sources, seasons). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = icon?.let { { TvIcon(it) } },
        colors = FilterChipDefaults.colors(
            containerColor = scheme.surfaceVariant.copy(alpha = 0.6f),
            contentColor = scheme.onSurface,
            selectedContainerColor = scheme.primary,
            selectedContentColor = scheme.onPrimary,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
            focusedSelectedContainerColor = scheme.inverseSurface,
            focusedSelectedContentColor = scheme.inverseOnSurface,
        ),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A full-width list/settings row. Leading/trailing slots get the icon colour bridge. */
@Composable
fun TvListRow(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    overline: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        overlineContent = overline?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = leading?.let { slot -> { TvIcon(slot) } },
        trailingContent = trailing?.let { slot -> { TvIcon(slot) } },
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        colors = ListItemDefaults.colors(
            containerColor = scheme.surface.copy(alpha = 0.6f),
            contentColor = scheme.onSurface,
            selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
            selectedContentColor = scheme.onSurface,
            focusedContainerColor = scheme.inverseSurface,
            focusedContentColor = scheme.inverseOnSurface,
            focusedSelectedContainerColor = scheme.inverseSurface,
            focusedSelectedContentColor = scheme.inverseOnSurface,
        ),
    )
}

/** A list row whose whole surface toggles, so the D-pad never has to land on a switch thumb. */
@Composable
fun TvSwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    TvListRow(
        headline = headline,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        supporting = supporting,
        leading = leading,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.onPrimary,
                    checkedTrackColor = scheme.primary,
                ),
            )
        },
    )
}
