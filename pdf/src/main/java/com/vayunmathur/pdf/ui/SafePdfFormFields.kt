package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CheckboxDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.platform.SafeFormField
import com.vayunmathur.pdf.platform.SafePdfDocument
import com.vayunmathur.pdf.platform.setCheckbox
import com.vayunmathur.pdf.platform.setChoiceField
import com.vayunmathur.pdf.platform.setTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Interactive form fields positioned over the page. */
@Composable
internal fun FormFieldOverlay(
    fields: List<SafeFormField>,
    ch: Float,
    scale: Float,
    document: SafePdfDocument,
    index: Int,
    scope: CoroutineScope,
    onEdited: () -> Unit,
) {
    val density = LocalDensity.current
    for (field in fields) {
        val leftDp = with(density) { (field.x0 * scale).toDp() }
        val topDp = with(density) { (ch - field.y1 * scale).toDp() }
        val wDp = with(density) { ((field.x1 - field.x0) * scale).toDp() }
        val hDp = with(density) { ((field.y1 - field.y0) * scale).toDp() }

        when (field.type) {
            0 -> { // text field
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x332196F3)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setChoiceField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            1 -> { // checkbox / button
                var checked by remember(field.id, field.checked) { mutableStateOf(field.checked) }
                Box(Modifier.padding(start = leftDp, top = topDp).size(wDp, hDp)) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            scope.launch { document.setCheckbox(index, field.id, it); onEdited() }
                        },
                        // The page behind this is a fixed white sheet, so the
                        // control cannot follow the theme's on-surface roles.
                        colors = CheckboxDefaults.colors(
                            uncheckedColor = Color(0xFF49454F),
                            checkedColor = Color(0xFF1F6FC0),
                            checkmarkColor = Color.White,
                        ),
                    )
                }
            }
            2 -> { // choice / dropdown (editable combo): edit the value inline
                var text by remember(field.id, field.value) { mutableStateOf(field.value) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x3300B0FF)),
                    singleLine = true,
                )
                DisposableEffect(field.id) {
                    onDispose {
                        if (text != field.value) {
                            scope.launch { document.setTextField(index, field.id, text); onEdited() }
                        }
                    }
                }
            }
            3 -> { // signature / other: show a tappable placeholder
                Box(
                    Modifier
                        .padding(start = leftDp, top = topDp)
                        .size(wDp, hDp)
                        .background(Color(0x22000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.sign),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF49454F),
                    )
                }
            }
        }
    }
}
