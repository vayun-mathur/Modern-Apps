package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vayunmathur.library.ui.R

/** The base formatting actions every editor toolbar can offer. */
enum class EditorFormat { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, BULLET, ORDERED_LIST, INDENT, OUTDENT, LINK }

/**
 * Polymorphic contract for an editor's formatting toolbar. Markdown, HTML and
 * office (ODF) editors all implement the same base actions; *how* a format is
 * applied and displayed is left to each editor type. Each editor declares the
 * subset of [EditorFormat]s it [supported]s, reports per-format active state via
 * [isActive], and performs the change in [toggle]. Editors that support links
 * additionally implement [linkContext]/[applyLink].
 *
 * Render the mandated base buttons for any implementation with [EditorBaseButtons];
 * editor-type-specific extras (headings, alignment, insert menus, …) are appended
 * by each toolbar around that shared block.
 */
interface EditorFormatter {
    /** Which base buttons this editor exposes. */
    val supported: Set<EditorFormat>

    /** Whether the toolbar is currently actionable (something is selected/editable). */
    val enabled: Boolean get() = true

    /** Whether [format] is currently applied at the selection (drives highlight). */
    fun isActive(format: EditorFormat): Boolean = false

    /** Apply/remove [format] at the current selection. Not called for [EditorFormat.LINK]. */
    fun toggle(format: EditorFormat)

    /** Link state for the current selection/cursor, or null to disable the link button. */
    fun linkContext(): LinkContext? = null

    /** Create or edit a link with the given [text]/[url]. */
    fun applyLink(context: LinkContext, text: String, url: String) {}

    /** Remove the link at the current selection/cursor, keeping its text. */
    fun removeLink(context: LinkContext) {}
}

private val BASE_ORDER = listOf(
    EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE,
    EditorFormat.STRIKETHROUGH, EditorFormat.BULLET, EditorFormat.ORDERED_LIST,
    EditorFormat.OUTDENT, EditorFormat.INDENT,
)

private fun baseIcon(format: EditorFormat): Pair<ImageVector, String> = when (format) {
    EditorFormat.BOLD -> Icons.Filled.FormatBold to "Bold"
    EditorFormat.ITALIC -> Icons.Filled.FormatItalic to "Italic"
    EditorFormat.UNDERLINE -> Icons.Filled.FormatUnderlined to "Underline"
    EditorFormat.STRIKETHROUGH -> Icons.Filled.FormatStrikethrough to "Strikethrough"
    EditorFormat.BULLET -> Icons.AutoMirrored.Filled.FormatListBulleted to "Bulleted list"
    EditorFormat.ORDERED_LIST -> Icons.Filled.FormatListNumbered to "Numbered list"
    EditorFormat.INDENT -> Icons.AutoMirrored.Filled.FormatIndentIncrease to "Indent"
    EditorFormat.OUTDENT -> Icons.AutoMirrored.Filled.FormatIndentDecrease to "Outdent"
    EditorFormat.LINK -> Icons.Filled.Link to "Link"
}

/** A toolbar icon button that does not steal focus from the editor field. */
@Composable
fun FormatIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusProperties { canFocus = false }.size(40.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/**
 * Slot-based variant of [FormatIconButton] for toolbar buttons that render one of the
 * app's own `IconXyz()` composables (see `Icons.kt`) instead of a Material vector.
 */
@Composable
fun FormatIconButton(
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    // The slot renders an app IconXxx with no contentDescription parameter of its
    // own, so expose this button's description to accessibility services here.
    val description = contentDescription
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusProperties { canFocus = false }.size(40.dp)
            .semantics { this.contentDescription = description },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalContentColor provides
                if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        ) {
            icon()
        }
    }
}

/**
 * Renders the mandated base buttons (in a fixed order) for [formatter], showing
 * only those it [EditorFormatter.supported]s. Handles the link dialog for
 * editors that support [EditorFormat.LINK].
 */
@Composable
fun EditorBaseButtons(formatter: EditorFormatter) {
    for (format in BASE_ORDER) {
        if (format !in formatter.supported) continue
        val (icon, desc) = baseIcon(format)
        FormatIconButton(
            icon = icon,
            contentDescription = desc,
            active = formatter.isActive(format),
            enabled = formatter.enabled,
            onClick = { formatter.toggle(format) },
        )
    }
    if (EditorFormat.LINK in formatter.supported) {
        var showLink by remember { mutableStateOf(false) }
        val linkCtx = formatter.linkContext()
        FormatIconButton(
            icon = Icons.Filled.Link,
            contentDescription = stringResource(R.string.cd_link),
            active = linkCtx?.editing == true,
            enabled = formatter.enabled && linkCtx != null,
            onClick = { showLink = true },
        )
        if (showLink && linkCtx != null) {
            LinkDialog(
                context = linkCtx,
                onConfirm = { text, url -> formatter.applyLink(linkCtx, text, url); showLink = false },
                onDismiss = { showLink = false },
                onUnlink = if (linkCtx.editing) {
                    { formatter.removeLink(linkCtx); showLink = false }
                } else null,
            )
        }
    }
}

/**
 * Shared bottom-bar container for editor toolbars: a tonal [Surface] wrapping
 * a [FlowRow] grid. Place [EditorBaseButtons] plus any editor-specific extras
 * inside [content]; buttons wrap onto as many rows as they need, so nothing
 * scrolls.
 *
 * The [scrollable] parameter is accepted for source compatibility and ignored:
 * every toolbar now shows all its buttons. Kept (rather than removed) so the
 * call sites in notes, email, office and the markdown/html editors compile
 * unchanged.
 *
 * The bar rides above the keyboard: a Scaffold's `bottomBar` slot is measured
 * bare and pinned to the layout bottom by M3, so the bar claims
 * `navigationBars ∪ ime` itself — union, not sum, since a visible keyboard
 * already covers the navigation bar (same fix as `BottomNavBar` in `:library`).
 * Not a [BottomAppBar]: the grid needs wrap-content height, and M3 fixes that
 * bar to a single container height.
 *
 * Deliberately NOT removed by the Scaffold-owns-IME fix: this is used in the
 * `bottomBar` slot (notes, email), which sits outside the Scaffold content
 * wrapper that consumes insets — a bottom bar must still claim the union
 * itself, exactly like [BottomAppBar]. Using this inside Scaffold *content*
 * instead would now be a no-op (content consumes IME), not double-padding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorBottomBar(
    modifier: Modifier = Modifier,
    @Suppress("UnusedParameter") scrollable: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        tonalElevation = 3.dp,
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}
