package com.vayunmathur.launcher.ui

// SCAFFOLD SEARCH EXCEPTION: bottom-sheet-local widget picker content hosted by a ModalBottomSheet, not a scaffold screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.platform.LocalIconLoader
import com.vayunmathur.launcher.platform.WidgetEntry
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The widget picker, grouped by the app that provides the widgets.
 *
 * Grouped rather than one flat alphabetical list because that is how people look for a
 * widget — by the app it belongs to, not by whatever the provider happens to call itself.
 *
 * The body of a sheet rather than a screen, and not a nav destination: Launcher3's widget picker
 * comes up over the workspace from the same options popup that a long press on the wallpaper
 * opens. The [com.vayunmathur.library.ui.ModalBottomSheet] that hosts this is in
 * [HomeContent] — hosting it there is what keeps this composable renderable in a `@Preview`,
 * since a sheet puts its content in a window Layoutlib does not draw.
 */
@Composable
fun WidgetPickerContent(
    state: WidgetPickerUiState,
    actions: WidgetPickerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CommonSearchBar(
            value = state.query,
            onValueChange = actions::setWidgetQuery,
            placeholder = "Search widgets",
            padding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        )

        if (state.groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = EMPTY_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.loading) "Loading widgets" else "No widgets available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            state.groups.forEach { group ->
                item(key = group.appLabel) {
                    SettingsSection(title = group.appLabel) {
                        group.widgets.forEach { widget ->
                            WidgetRow(widget, onClick = { actions.addWidget(widget) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(widget: WidgetEntry, onClick: () -> Unit) {
    val loader = LocalIconLoader.current
    val preview by produceState<ImageBitmap?>(initialValue = null, widget.provider, loader) {
        value = withContext(Dispatchers.IO) {
            loader.widgetPreview(widget.provider, widget.profileSerial)
        }
    }

    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val resolved = preview
        if (resolved != null) {
            Image(
                bitmap = resolved,
                contentDescription = widget.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(PREVIEW_SIZE),
            )
        } else {
            // Providers that ship only a previewLayout have no preview image to load.
            Box(modifier = Modifier.size(PREVIEW_SIZE), contentAlignment = Alignment.Center) {
                IconWidgets()
            }
        }
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(widget.label.ifBlank { widget.provider }, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (widget.description.isBlank()) {
                    "${widget.spanX} x ${widget.spanY}"
                } else {
                    "${widget.spanX} x ${widget.spanY} - ${widget.description}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PREVIEW_SIZE = 56.dp

/** Tall enough that "Loading widgets" is not a one-line sheet that then jumps to full height. */
private val EMPTY_HEIGHT = 160.dp
