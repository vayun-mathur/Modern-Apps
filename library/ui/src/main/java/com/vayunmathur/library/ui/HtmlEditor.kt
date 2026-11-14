package com.vayunmathur.library.ui

import android.content.Context
import android.text.Editable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

@Composable
fun rememberHtmlEditorController(initialHtml: String = ""): HtmlEditorController =
    remember { HtmlEditorController(initialHtml) }

/** EditText backing the HTML editor; reports selection changes and handles Tab as input. */
class RichEditText(context: Context) : EditText(context) {
    var onSelectionChange: ((Int, Int) -> Unit)? = null
    var richController: HtmlEditorController? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            val shift = event?.isShiftPressed == true
            if (shift) richController?.indentDecrease() else richController?.indentIncrease()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun HtmlEditor(
    controller: HtmlEditorController,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val textColor = LocalContentColor.current.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val appliedVersion = remember { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            RichEditText(ctx).apply {
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                hint = placeholder
                gravity = Gravity.TOP or Gravity.START
                setHorizontallyScrolling(false)
                isSingleLine = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                this.richController = controller
                controller.editText = this
                onSelectionChange = { s, e ->
                    controller.selectionStart = s
                    controller.selectionEnd = e
                }
                setOnFocusChangeListener { _, hasFocus -> controller.focused = hasFocus }
                controller.updating = true
                setText(HtmlCompat.fromHtml(controller.html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                setSelection(text?.length ?: 0)
                controller.updating = false
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (!controller.updating && s != null) {
                            controller.commitHtml(controller.htmlSerializer(s))
                        }
                    }
                })
            }
        },
        update = { et ->
            val v = controller.setVersion
            if (v != appliedVersion.intValue) {
                val prevSel = et.selectionStart.coerceAtLeast(0)
                controller.updating = true
                et.setText(HtmlCompat.fromHtml(controller.html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                val newLen = et.text?.length ?: 0
                et.setSelection(prevSel.coerceIn(0, newLen))
                controller.updating = false
                appliedVersion.intValue = v
            }
        },
    )
}

@Composable
fun HtmlFormatToolbar(
    controller: HtmlEditorController,
    modifier: Modifier = Modifier,
) {
    EditorBottomBar(modifier = modifier, scrollable = true) {
        EditorBaseButtons(controller)
    }
}
