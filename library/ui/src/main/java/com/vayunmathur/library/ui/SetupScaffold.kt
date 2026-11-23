package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/** A button in a [SetupScaffold]'s footer. */
data class SetupAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * The scaffold for a setup step - one screen of a linear flow that explains itself and
 * offers one obvious way forward. Pairing an eSIM, granting a permission, finishing a
 * first run.
 *
 * [AppScaffold] and [DetailScaffold] both assume a titled bar over content the user came
 * looking for. A setup step is the opposite: the heading *is* the content, so the title
 * moves into the body at [displaySmall] with the bar left bare for the back button, and
 * the actions anchor to the bottom where a thumb already is.
 *
 * The proportions follow the platform's own setup screens (icon, large title, supporting
 * text, footer buttons) but the metrics are this repo's [Spacing] scale rather than
 * SetupDesign's, so a step sits next to the rest of an app instead of looking like it was
 * lifted out of another one.
 *
 * [primaryAction] is the way forward and renders filled; [secondaryAction] is the escape
 * hatch and renders as text. Pass neither for a screen the user cannot act on yet - a
 * progress step, say - and the footer disappears rather than showing a dead button.
 *
 * On wide windows the scrolling column is centred and capped at
 * [DesktopContentMaxWidth]; pass [fullBleed] = true only for a step whose
 * content genuinely needs the full window width.
 */
@Composable
fun SetupScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    primaryAction: SetupAction? = null,
    secondaryAction: SetupAction? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    fullBleed: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) = AppScaffold(
    title = "",
    modifier = modifier,
    onNavigateBack = onNavigateBack,
    onClose = onClose,
    scrollBehavior = scrollBehavior,
    bottomBar = { SetupFooter(primaryAction, secondaryAction) },
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
) { pad ->
    SetupColumn(pad, title, subtitle, icon, fullBleed, content)
}

/** [SetupScaffold] for a screen that owns a back stack, wiring the back button to it. */
@Composable
fun <T : NavKey> SetupScaffold(
    title: String,
    backStack: NavBackStack<T>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    primaryAction: SetupAction? = null,
    secondaryAction: SetupAction? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    fullBleed: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) = SetupScaffold(
    title = title,
    modifier = modifier,
    subtitle = subtitle,
    icon = icon,
    onNavigateBack = { backStack.pop() },
    primaryAction = primaryAction,
    secondaryAction = secondaryAction,
    scrollBehavior = scrollBehavior,
    fullBleed = fullBleed,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    content = content,
)

@Composable
private fun SetupColumn(
    pad: PaddingValues,
    title: String,
    subtitle: String?,
    icon: (@Composable () -> Unit)?,
    fullBleed: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Centre the scrolling column at a readable measure on wide windows;
    // fullBleed steps keep the whole width.
    val contentMaxWidth: Dp = if (fullBleed) Dp.Infinity else DesktopContentMaxWidth
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = paneContentMargin())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (icon != null) {
                Spacer(Modifier.padding(top = Spacing.sm))
                icon()
            }
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The gap between the heading block and whatever the step actually asks for.
            Spacer(Modifier.padding(top = Spacing.sm))
            content()
            Spacer(Modifier.padding(bottom = Spacing.xl))
        }
    }
}

@Composable
private fun SetupFooter(primary: SetupAction?, secondary: SetupAction?) {
    if (primary == null && secondary == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paneContentMargin(), vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondary != null) {
            TextButton(onClick = secondary.onClick, enabled = secondary.enabled) {
                Text(secondary.label)
            }
        }
        if (primary != null) {
            Button(onClick = primary.onClick, enabled = primary.enabled) {
                Text(primary.label)
            }
        }
    }
}
