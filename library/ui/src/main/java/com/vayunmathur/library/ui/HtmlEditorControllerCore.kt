package com.vayunmathur.library.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.widget.EditText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Core of [HtmlEditorController]: state plus the base-format operations
 * (bold/italic/underline/strikethrough, lists, indent).
 *
 * Split out so each file stays under the FileLength limit; the rich hooks live in
 * [HtmlEditorController], which is what the editor and the email subclass use.
 */
open class HtmlEditorControllerCore(
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

    var selectionStart by mutableIntStateOf(0)
        internal set
    var selectionEnd by mutableIntStateOf(0)
        internal set

    var focused by mutableStateOf(false)
        internal set

    var setVersion by mutableIntStateOf(0)
        internal set

    // Public accessors for cross-module email package
    val currentSelectionStart: Int get() = selectionStart
    val currentSelectionEnd: Int get() = selectionEnd
    val isFocused: Boolean get() = focused
    val currentSetVersion: Int get() = setVersion

    fun updateSelection(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    fun updateFocus(f: Boolean) {
        focused = f
    }

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

    protected fun toggleStyle(style: Int) =
        toggleCharSpan({ StyleSpan(style) }) { it is StyleSpan && (it.style and style) != 0 }

    protected fun toggleCharSpan(make: () -> Any, matches: (Any) -> Boolean) {
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

    protected fun clearCharSpans(matches: (Any) -> Boolean) {
        val e = editText?.text ?: return
        val start = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        if (start >= end) return
        val overlapping = e.getSpans(start, end, Any::class.java).filter(matches)
        overlapping.forEach { sp ->
            val ss = e.getSpanStart(sp); val se = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (ss < start) e.setSpan(sp, ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (se > end) e.setSpan(sp, end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    protected fun setCharSpan(make: () -> Any, matches: (Any) -> Boolean) {
        val e = editText?.text ?: return
        val start = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        if (start >= end) return
        // Remove existing matching spans in range (split preserved)
        val overlapping = e.getSpans(start, end, Any::class.java).filter(matches)
        overlapping.forEach { sp ->
            val ss = e.getSpanStart(sp); val se = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (ss < start) e.setSpan(sp, ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (se > end) e.setSpan(sp, end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        e.setSpan(make(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    protected fun selStart() = (editText?.selectionStart ?: 0).coerceAtLeast(0)
    protected fun selEnd() = (editText?.selectionEnd ?: 0).coerceAtLeast(0)
}
