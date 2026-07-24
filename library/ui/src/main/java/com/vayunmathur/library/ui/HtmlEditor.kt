package com.vayunmathur.library.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

class OrderedListSpan(var number: Int, private val gapWidth: Int = 48) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = gapWidth
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: Layout?,
    ) {
        if (!first) return
        val oldStyle = p.style
        p.style = Paint.Style.FILL
        val numText = "$number."
        val width = p.measureText(numText)
        val xPos = (x + gapWidth - width - 8).coerceAtLeast(x.toFloat())
        c.drawText(numText, xPos, baseline.toFloat(), p)
        p.style = oldStyle
    }
}

class IndentSpan(val level: Int) : LeadingMarginSpan.Standard(level * INDENT_PER_LEVEL) {
    companion object {
        const val INDENT_PER_LEVEL = 48
        const val MAX_LEVEL = 6
    }
}

fun Editable.forEachParagraph(selStartIn: Int, selEndIn: Int, action: (Int, Int) -> Unit) {
    val len = length
    if (len == 0) return
    val rawStart = minOf(selStartIn, selEndIn).coerceIn(0, len)
    val rawEnd = maxOf(selStartIn, selEndIn).coerceIn(0, len)
    val blockStart = lastIndexOf('\n', (rawStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val blockEnd = indexOf('\n', rawEnd).let { if (it < 0) len else it }
    var pos = blockStart
    while (pos <= blockEnd && pos < len) {
        val nextNl = indexOf('\n', pos).let { if (it < 0) len else it }
        if (nextNl > pos || pos < len) action(pos, nextNl)
        pos = nextNl + 1
        if (pos > blockEnd && nextNl == len) break
        if (pos > len) break
    }
}

fun Editable.paragraphsAll(): List<Pair<Int, Int>> {
    val list = mutableListOf<Pair<Int, Int>>()
    var pos = 0
    while (pos < length) {
        val next = indexOf('\n', pos).let { if (it < 0) length else it }
        list.add(pos to next)
        pos = next + 1
    }
    if (list.isEmpty() && length == 0) list.add(0 to 0)
    if (length > 0 && this[length - 1] == '\n') list.add(length to length)
    return list
}

/** Open so email module can subclass with CID images – minimal hook: public commitHtml(). */
open class HtmlEditorController(
    initialHtml: String = "",
    var htmlSerializer: (Spanned) -> String = { s -> serializeRich(s) },
) : EditorFormatter {

    var html by mutableStateOf(initialHtml)
        internal set

    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE,
        EditorFormat.STRIKETHROUGH, EditorFormat.BULLET, EditorFormat.ORDERED_LIST,
        EditorFormat.INDENT, EditorFormat.OUTDENT, EditorFormat.LINK,
    )

    override fun toggle(format: EditorFormat) {
        when (format) {
            EditorFormat.BOLD -> toggleBold()
            EditorFormat.ITALIC -> toggleItalic()
            EditorFormat.UNDERLINE -> toggleUnderline()
            EditorFormat.STRIKETHROUGH -> toggleStrikethrough()
            EditorFormat.BULLET -> toggleBullet()
            EditorFormat.ORDERED_LIST -> toggleOrderedList()
            EditorFormat.INDENT -> indentIncrease()
            EditorFormat.OUTDENT -> indentDecrease()
            EditorFormat.LINK -> {}
        }
    }

    override fun isActive(format: EditorFormat): Boolean {
        val e = editText?.text ?: return false
        @Suppress("UNUSED_EXPRESSION") html
        @Suppress("UNUSED_EXPRESSION") selectionStart
        @Suppress("UNUSED_EXPRESSION") selectionEnd
        if (e.isEmpty()) return false
        val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
        val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
        return when (format) {
            EditorFormat.BOLD -> e.isFullyCovered(start, end) { it is StyleSpan && (it.style and Typeface.BOLD) != 0 }
            EditorFormat.ITALIC -> e.isFullyCovered(start, end) { it is StyleSpan && (it.style and Typeface.ITALIC) != 0 }
            EditorFormat.UNDERLINE -> e.isFullyCovered(start, end) { it is UnderlineSpan }
            EditorFormat.STRIKETHROUGH -> e.isFullyCovered(start, end) { it is StrikethroughSpan }
            EditorFormat.BULLET -> e.hasParaSpanInSelection<BulletSpan>(start, end)
            EditorFormat.ORDERED_LIST -> e.hasParaSpanInSelection<OrderedListSpan>(start, end)
            EditorFormat.INDENT -> e.hasParaSpanInSelection<IndentSpan>(start, end)
            EditorFormat.OUTDENT -> false
            EditorFormat.LINK -> urlSpanAt(e, start, end) != null
        }
    }

    var selectionStart by mutableStateOf(0)
        internal set
    var selectionEnd by mutableStateOf(0)
        internal set

    var focused by mutableStateOf(false)
        internal set

    internal var setVersion by mutableStateOf(0)
    // Exposed for email subclass (different Gradle module) – minimal hook
    var updating = false
    var editText: EditText? = null

    fun setHtml(newHtml: String) {
        html = newHtml
        setVersion++
    }

    /** Single public entry point for external modules to set html state */
    fun commitHtml(value: String) {
        html = value
    }

    protected open fun refresh() {
        editText?.text?.let { html = htmlSerializer(it) }
    }

    open fun toggleBold() = toggleStyle(Typeface.BOLD)
    open fun toggleItalic() = toggleStyle(Typeface.ITALIC)
    open fun toggleUnderline() = toggleCharSpan({ UnderlineSpan() }) { it is UnderlineSpan }
    open fun toggleStrikethrough() = toggleCharSpan({ StrikethroughSpan() }) { it is StrikethroughSpan }

    private fun toggleStyle(style: Int) =
        toggleCharSpan({ StyleSpan(style) }) { it is StyleSpan && (it.style and style) != 0 }

    private fun toggleCharSpan(make: () -> Any, matches: (Any) -> Boolean) {
        val e = editText?.text ?: return
        val start = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        if (start >= end) return
        val overlapping = e.getSpans(start, end, Any::class.java).filter(matches)
        if (e.isFullyCovered(start, end, matches)) {
            overlapping.forEach { sp ->
                val ss = e.getSpanStart(sp); val se = e.getSpanEnd(sp)
                e.removeSpan(sp)
                if (ss < start) e.setSpan(make(), ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (se > end) e.setSpan(make(), end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            e.setSpan(make(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        refresh()
    }

    open fun toggleBullet() {
        val e = editText?.text ?: return
        val hasBullet = e.hasParaSpanInSelection<BulletSpan>(selStart(), selEnd())
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            e.getSpans(paraStart, paraEnd, OrderedListSpan::class.java).forEach { e.removeSpan(it) }
            val existing = e.getSpans(paraStart, paraEnd, BulletSpan::class.java)
            if (hasBullet) existing.forEach { e.removeSpan(it) }
            else if (existing.isEmpty()) e.setSpan(BulletSpan(24), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (!hasBullet) renumberOrderedLists(e)
        refresh()
    }

    open fun toggleOrderedList() {
        val e = editText?.text ?: return
        val hasOrdered = e.hasParaSpanInSelection<OrderedListSpan>(selStart(), selEnd())
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            e.getSpans(paraStart, paraEnd, BulletSpan::class.java).forEach { e.removeSpan(it) }
            val existing = e.getSpans(paraStart, paraEnd, OrderedListSpan::class.java)
            if (hasOrdered) existing.forEach { e.removeSpan(it) }
            else if (existing.isEmpty()) e.setSpan(OrderedListSpan(1), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        renumberOrderedLists(e)
        refresh()
    }

    open fun indentIncrease() {
        val e = editText?.text ?: return
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            val existing = e.getSpans(paraStart, paraEnd, IndentSpan::class.java).firstOrNull()
            val current = existing?.level ?: 0
            if (current >= IndentSpan.MAX_LEVEL) return@forEachParagraph
            existing?.let { e.removeSpan(it) }
            e.setSpan(IndentSpan(current + 1), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        refresh()
    }

    open fun indentDecrease() {
        val e = editText?.text ?: return
        e.forEachParagraph(selStart(), selEnd()) { paraStart, paraEnd ->
            if (paraEnd <= paraStart) return@forEachParagraph
            val existing = e.getSpans(paraStart, paraEnd, IndentSpan::class.java).firstOrNull() ?: return@forEachParagraph
            e.removeSpan(existing)
            val newLevel = existing.level - 1
            if (newLevel > 0) e.setSpan(IndentSpan(newLevel), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        refresh()
    }

    private fun renumberOrderedLists(e: Editable) {
        var counter = 0
        for ((paraStart, paraEnd) in e.paragraphsAll()) {
            if (paraEnd <= paraStart) { counter = 0; continue }
            val spans = e.getSpans(paraStart, paraEnd, OrderedListSpan::class.java)
            val has = spans.any { sp -> val ss = e.getSpanStart(sp); val se = e.getSpanEnd(sp); ss < paraEnd && se > paraStart }
            if (has) {
                counter++
                spans.forEach { e.removeSpan(it) }
                e.setSpan(OrderedListSpan(counter), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            } else counter = 0
        }
    }

    override fun linkContext(): LinkContext? = computeLinkContext()

    private fun computeLinkContext(): LinkContext? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val span = urlSpanAt(e, start, end)
        if (span != null) {
            val ss = e.getSpanStart(span).coerceIn(0, e.length)
            val se = e.getSpanEnd(span).coerceIn(0, e.length)
            return LinkContext(editing = true, text = e.substring(ss, se), url = span.url ?: "")
        }
        return if (start < end) LinkContext(editing = false, text = e.substring(start, end), url = "") else null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        val e = editText?.text ?: return
        if (url.isBlank()) return
        if (context.editing) {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            val span = urlSpanAt(e, start, end) ?: return
            val ss = e.getSpanStart(span); val se = e.getSpanEnd(span)
            e.removeSpan(span)
            updating = true
            e.replace(ss, se, text)
            updating = false
            e.setSpan(URLSpan(url), ss, ss + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(ss + text.length)
        } else {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            updating = true
            e.replace(start, end, text)
            updating = false
            val newEnd = start + text.length
            e.getSpans(start, newEnd, URLSpan::class.java).forEach { e.removeSpan(it) }
            e.setSpan(URLSpan(url), start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(newEnd)
        }
        refresh()
    }

    protected fun selStart() = (editText?.selectionStart ?: 0).coerceAtLeast(0)
    protected fun selEnd() = (editText?.selectionEnd ?: 0).coerceAtLeast(0)
}

@Composable
fun rememberHtmlEditorController(initialHtml: String = ""): HtmlEditorController =
    remember { HtmlEditorController(initialHtml) }

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

fun serializeRich(spanned: Spanned): String {
    if (spanned.isEmpty()) return ""
    val editable = spanned as? Editable ?: SpannableStringBuilder(spanned)
    val paras = editable.paragraphsAll()
    if (paras.isEmpty()) return ""

    data class ParaMeta(
        val start: Int, val end: Int,
        val bullet: Boolean, val orderedNum: Int?,
        val indentLevel: Int, val inlineHtml: String, val isEmpty: Boolean,
    )

    fun paraInlineHtml(start: Int, end: Int): String {
        if (start >= end) return ""
        val slice = SpannableStringBuilder()
        slice.append(editable.subSequence(start, end))
        val raw = HtmlCompat.toHtml(slice, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE).trim()
        var inner = raw.replace(Regex("^<p[^>]*>"), "").replace(Regex("</p>\\s*$"), "").trim()
        if (inner.isEmpty()) {
            val txt = editable.subSequence(start, end).toString()
            if (txt.isBlank()) return ""
            return android.text.TextUtils.htmlEncode(txt)
        }
        return inner
    }

    val metas = paras.map { (s, e) ->
        val bullet = editable.getSpans(s, e, BulletSpan::class.java).any { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }
        val orderedSpan = editable.getSpans(s, e, OrderedListSpan::class.java).firstOrNull { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }
        val indent = editable.getSpans(s, e, IndentSpan::class.java).firstOrNull { sp -> editable.getSpanStart(sp) < e && editable.getSpanEnd(sp) > s }?.level ?: 0
        ParaMeta(s, e, bullet, orderedSpan?.number, indent, paraInlineHtml(s, e), s >= e)
    }

    val out = StringBuilder()
    var currentList: String? = null
    fun closeList() { if (currentList != null) { out.append("</${currentList}>"); currentList = null } }

    for (meta in metas) {
        if (meta.isEmpty) {
            closeList()
            if (meta.indentLevel > 0) out.append("<div style=\"margin-left: ${meta.indentLevel * 24}px\"><br></div>") else out.append("<br>")
            continue
        }
        val inline = meta.inlineHtml.ifBlank { "<br>" }
        val indentedContent = if (meta.indentLevel > 0) "<div style=\"margin-left: ${meta.indentLevel * 24}px\">$inline</div>" else inline
        when {
            meta.bullet -> {
                if (currentList != "ul") { closeList(); out.append("<ul>"); currentList = "ul" }
                out.append("<li>$indentedContent</li>")
            }
            meta.orderedNum != null -> {
                if (currentList != "ol") { closeList(); out.append("<ol>"); currentList = "ol" }
                out.append("<li>$indentedContent</li>")
            }
            else -> {
                closeList()
                if (meta.indentLevel > 0) out.append(indentedContent) else out.append("<div>$inline</div>")
            }
        }
    }
    closeList()
    val result = out.toString()
    return if (result.isBlank()) HtmlCompat.toHtml(spanned, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) else result
}

internal fun urlSpanAt(e: Editable, start: Int, end: Int): URLSpan? {
    e.getSpans(start, maxOf(end, start), URLSpan::class.java).firstOrNull()?.let { return it }
    if (start == end && start > 0) e.getSpans(start - 1, start, URLSpan::class.java).firstOrNull()?.let { return it }
    return null
}

internal fun Editable.isFullyCovered(start: Int, end: Int, matches: (Any) -> Boolean): Boolean {
    if (start >= end) return false
    var pos = start
    while (pos < end) {
        val ok = getSpans(pos, pos + 1, Any::class.java).any { matches(it) && getSpanStart(it) <= pos && getSpanEnd(it) >= pos + 1 }
        if (!ok) return false
        pos++
    }
    return true
}

internal inline fun <reified T : Any> Editable.hasParaSpanInSelection(start: Int, end: Int): Boolean {
    var found = false
    forEachParagraph(start, end) { paraStart, paraEnd ->
        if (found) return@forEachParagraph
        if (paraEnd <= paraStart) return@forEachParagraph
        val spans = getSpans(paraStart, paraEnd, T::class.java)
        if (spans.any { getSpanStart(it) < paraEnd && getSpanEnd(it) > paraStart }) found = true
    }
    return found
}
