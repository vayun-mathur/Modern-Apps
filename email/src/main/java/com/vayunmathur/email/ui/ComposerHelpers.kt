package com.vayunmathur.email.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.toColorInt
import com.vayunmathur.email.R
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EditorBaseButtons
import com.vayunmathur.library.ui.EditorBottomBar
import com.vayunmathur.library.ui.FormatIconButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconFormatAlignCenter
import com.vayunmathur.library.ui.IconFormatAlignJustify
import com.vayunmathur.library.ui.IconFormatAlignLeft
import com.vayunmathur.library.ui.IconFormatAlignRight
import com.vayunmathur.library.ui.IconFormatClear
import com.vayunmathur.library.ui.IconFormatCode
import com.vayunmathur.library.ui.IconFormatColorFill
import com.vayunmathur.library.ui.IconFormatColorText
import com.vayunmathur.library.ui.IconFormatHorizontalRule
import com.vayunmathur.library.ui.IconFormatQuote
import com.vayunmathur.library.ui.IconFormatSize
import com.vayunmathur.library.ui.IconFormatTitle
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR

@Composable
internal fun EmailComposerFormatToolbar(
    controller: com.vayunmathur.email.ui.composer.EmailHtmlEditorController,
    onInsertImage: () -> Unit,
) {
    var headingMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var colorDialog by remember { mutableIntStateOf(0) } // 0 none, 1 fg, 2 bg
    var sizeMenu by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }

    val headingLevel = controller.getCurrentHeadingLevel()
    val headingLabel = when (headingLevel) {
        1 -> "H1"; 2 -> "H2"; 3 -> "H3"; else -> "Normal"
    }
    val alignCss = controller.getCurrentAlignment()
    val blockquoteActive = controller.isBlockquoteActive()
    val codeActive = controller.isInlineCodeActive()

    com.vayunmathur.library.ui.EditorBottomBar(modifier = Modifier, scrollable = true) {
        // Heading dropdown
        Box {
            TextButton(onClick = { headingMenu = true }) {
                Text(headingLabel)
                IconArrowDropDown()
            }
            DropdownMenu(expanded = headingMenu, onDismissRequest = { headingMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.normal)) }, onClick = { headingMenu = false; controller.toggleHeading(null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.heading_1)) }, onClick = { headingMenu = false; controller.toggleHeading(1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.heading_2)) }, onClick = { headingMenu = false; controller.toggleHeading(2) })
                DropdownMenuItem(text = { Text(stringResource(R.string.heading_3)) }, onClick = { headingMenu = false; controller.toggleHeading(3) })
            }
        }

        com.vayunmathur.library.ui.EditorBaseButtons(formatter = controller)

        HorizontalDivider(modifier = Modifier.height(24.dp).width(1.dp))

        // Alignment dropdown
        Box {
            val alignIcon: @Composable () -> Unit = when (alignCss) {
                "center" -> { { IconFormatAlignCenter() } }
                "right" -> { { IconFormatAlignRight() } }
                "justify" -> { { IconFormatAlignJustify() } }
                else -> { { IconFormatAlignLeft() } }
            }
            com.vayunmathur.library.ui.FormatIconButton(
                contentDescription = stringResource(R.string.alignment),
                active = alignCss != null,
                onClick = { alignMenu = true },
                icon = alignIcon,
            )
            DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.left)) }, leadingIcon = { IconFormatAlignLeft() },
                    onClick = { alignMenu = false; controller.setAlignment(null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.center)) }, leadingIcon = { IconFormatAlignCenter() },
                    onClick = { alignMenu = false; controller.setAlignment("center") }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.right)) }, leadingIcon = { IconFormatAlignRight() },
                    onClick = { alignMenu = false; controller.setAlignment("right") }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.justify)) }, leadingIcon = { IconFormatAlignJustify() },
                    onClick = { alignMenu = false; controller.setAlignment("justify") }
                )
            }
        }

        // Blockquote, code, hr, clear
        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.blockquote),
            active = blockquoteActive,
            onClick = { controller.toggleBlockquote() },
        ) { IconFormatQuote() }

        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.inline_code),
            active = codeActive,
            onClick = { controller.toggleInlineCode() },
        ) { IconFormatCode() }

        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.horizontal_rule),
            onClick = { controller.insertHorizontalRule() },
        ) { IconFormatHorizontalRule() }

        HorizontalDivider(modifier = Modifier.height(24.dp).width(1.dp))

        // Text color
        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.text_color),
            active = colorDialog == 1,
            onClick = { colorDialog = 1 },
        ) { IconFormatColorText() }

        // Highlight / background
        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.highlight),
            active = colorDialog == 2,
            onClick = { colorDialog = 2 },
        ) { IconFormatColorFill() }

        // Font size dropdown
        Box {
            com.vayunmathur.library.ui.FormatIconButton(
                contentDescription = stringResource(R.string.font_size),
                onClick = { sizeMenu = true },
            ) { IconFormatSize() }
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.small)) }, onClick = { sizeMenu = false; controller.setFontSizeFactor(0.9f) })
                DropdownMenuItem(text = { Text(stringResource(R.string.normal)) }, onClick = { sizeMenu = false; controller.setFontSizeFactor(null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.large)) }, onClick = { sizeMenu = false; controller.setFontSizeFactor(1.2f) })
                DropdownMenuItem(text = { Text(stringResource(R.string.extra_large)) }, onClick = { sizeMenu = false; controller.setFontSizeFactor(1.4f) })
            }
        }

        // Font family dropdown
        Box {
            com.vayunmathur.library.ui.FormatIconButton(
                contentDescription = stringResource(R.string.font_family),
                onClick = { fontMenu = true },
            ) { IconFormatTitle() }
            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.font_default)) }, onClick = { fontMenu = false; controller.setFontFamily(null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.sans)) }, onClick = { fontMenu = false; controller.setFontFamily("sans-serif") })
                DropdownMenuItem(text = { Text(stringResource(R.string.serif)) }, onClick = { fontMenu = false; controller.setFontFamily("serif") })
                DropdownMenuItem(text = { Text(stringResource(R.string.monospace)) }, onClick = { fontMenu = false; controller.setFontFamily("monospace") })
            }
        }

        HorizontalDivider(modifier = Modifier.height(24.dp).width(1.dp))

        // Clear formatting
        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.clear_formatting),
            onClick = { controller.clearFormatting() },
        ) { IconFormatClear() }

        // Image button
        com.vayunmathur.library.ui.FormatIconButton(
            contentDescription = stringResource(R.string.cd_insert_image),
            onClick = onInsertImage,
        ) { com.vayunmathur.library.ui.IconImage() }
    }

    // Color picker dialog
    if (colorDialog != 0) {
        val isForeground = colorDialog == 1
        EmailColorPickerDialog(
            title = if (isForeground) "Text color" else "Highlight",
            onDismiss = { colorDialog = 0 },
            onColorSelected = { colorInt ->
                if (isForeground) {
                    if (colorInt == null) controller.setTextColor(null) else controller.setTextColor(colorInt)
                } else {
                    if (colorInt == null) controller.setHighlight(null) else controller.setHighlight(colorInt)
                }
                colorDialog = 0
            }
        )
    }
}

@Composable
private fun EmailColorPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onColorSelected: (Int?) -> Unit,
) {
    val palette = remember {
        listOf(
            null to "Default",
            android.graphics.Color.BLACK to "Black",
            "#D32F2F".toColorInt() to "Red",
            "#1976D2".toColorInt() to "Blue",
            "#388E3C".toColorInt() to "Green",
            "#F57C00".toColorInt() to "Orange",
            "#7B1FA2".toColorInt() to "Purple",
            "#00796B".toColorInt() to "Teal",
            "#455A64".toColorInt() to "Gray",
            "#FFEB3B".toColorInt() to "Yellow",
            "#FFCDD2".toColorInt() to "Light Red",
            "#BBDEFB".toColorInt() to "Light Blue",
            "#C8E6C9".toColorInt() to "Light Green",
            "#FFF9C4".toColorInt() to "Light Yellow",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // chunk into rows of 4
                palette.chunked(4).forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowItems.forEach { (colorInt, label) ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        color = if (colorInt == null) MaterialTheme.colorScheme.surfaceVariant
                                        else Color(colorInt),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .clickable { onColorSelected(colorInt) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (colorInt == null) {
                                    IconClose(modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}

internal fun copyInlineToCache(context: android.content.Context, uri: Uri, name: String): Uri? {
    return try {
        val dir = java.io.File(context.cacheDir, "inline").also { it.mkdirs() }
        val safeName = name.replace(Regex("[/\\\\]"), "_").ifBlank { "image_${System.currentTimeMillis()}.jpg" }
        val outFile = java.io.File(dir, "${System.currentTimeMillis()}-$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    } catch (_: Exception) { null }
}


