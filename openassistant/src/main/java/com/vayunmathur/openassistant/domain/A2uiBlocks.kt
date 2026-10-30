package com.vayunmathur.openassistant.domain

const val A2UI_FENCE_OPEN = "```a2ui"
private const val FENCE_CLOSE = "```"

/** A reply split into the prose to show and the raw JSON of its first A2UI block, if any. */
data class A2uiSplit(val visible: String, val json: String?)

/**
 * Cuts every ```` ```a2ui ```` block out of [text]; the first complete one becomes [A2uiSplit.json].
 *
 * An unclosed block hides everything after its opener. With [streaming], a trailing fragment
 * that could still grow into the opener (` ``` `, ` ```a2 `) is hidden too, so the fence never
 * flashes on screen while it is being written.
 */
fun splitA2ui(text: String, streaming: Boolean = true): A2uiSplit {
    val out = StringBuilder()
    var json: String? = null
    var at = 0
    while (at < text.length) {
        val open = text.indexOf(A2UI_FENCE_OPEN, at)
        if (open < 0) {
            out.append(text, at, text.length)
            break
        }
        out.append(text, at, open)
        val bodyStart = open + A2UI_FENCE_OPEN.length
        val close = text.indexOf(FENCE_CLOSE, bodyStart)
        if (close < 0) {
            at = text.length
            break
        }
        if (json == null) json = text.substring(bodyStart, close).trim().takeIf { it.isNotEmpty() }
        at = close + FENCE_CLOSE.length
    }
    if (streaming) trimPartialOpener(out)
    return A2uiSplit(out.toString().trim(), json)
}

/** [text] with [json] restored as a fenced block, as the model originally wrote it. */
fun withA2uiBlock(text: String, json: String?): String =
    if (json == null) text else "$text\n$A2UI_FENCE_OPEN\n$json\n$FENCE_CLOSE".trimStart()

private fun trimPartialOpener(out: StringBuilder) {
    val tick = out.lastIndexOf("`")
    if (tick < 0) return
    val start = generateSequence(tick) { i -> (i - 1).takeIf { it >= 0 && out[it] == '`' } }.last()
    val tail = out.substring(start)
    if (tail.length < A2UI_FENCE_OPEN.length && A2UI_FENCE_OPEN.startsWith(tail)) out.setLength(start)
}
