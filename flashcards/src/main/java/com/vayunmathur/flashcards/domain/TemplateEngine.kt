package com.vayunmathur.flashcards.domain

/**
 * Renders Anki-style card templates into the markdown shown on a card's front and
 * back. Pure Kotlin (no Android dependency) so it is directly unit-testable.
 *
 * Templates ([qfmt]/[afmt]) are markdown with `{{…}}` placeholders:
 * - `{{Field}}` — substitute a field value (unknown field → empty).
 * - `{{FrontSide}}` — in the answer format, the fully-rendered front.
 * - `{{#Field}}…{{/Field}}` — keep the section only if the field is present.
 * - `{{^Field}}…{{/Field}}` — keep the section only if the field is absent.
 * - `{{cloze:Field}}` — render the cloze deletions in a field (see [renderCloze]).
 *
 * [clozeOrd] is the active cloze index (0-based; cloze number − 1) for cloze note
 * types, or null for standard note types.
 */
object TemplateEngine {
    private val clozeRegex = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val clozeFieldRegex = Regex("""\{\{cloze:([^}]+)\}\}""")
    private val typeRegex = Regex("""\{\{type:([^}]+)\}\}""")

    /** Bound on nested-conditional passes; templates deeper than this are left as-is. */
    private const val MAX_CONDITIONAL_PASSES = 100
    private val sectionRegex = Regex(
        """\{\{([#^])([^}]+)\}\}((?:(?!\{\{[#^/]).)*?)\{\{/([^}]+)\}\}""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The field name a `{{type:Field}}` marker in [qfmt] targets, or null if none. */
    fun typeField(qfmt: String): String? =
        typeRegex.find(qfmt)?.groupValues?.get(1)?.trim()

    /** Returns the rendered (frontMarkdown, backMarkdown) pair. */
    fun render(
        qfmt: String,
        afmt: String,
        fields: Map<String, String>,
        clozeOrd: Int? = null,
    ): Pair<String, String> {
        val front = renderSide(qfmt, fields, clozeOrd, frontSide = null)
        val back = renderSide(afmt, fields, clozeOrd, frontSide = front)
        return front to back
    }

    private fun renderSide(
        fmt: String,
        fields: Map<String, String>,
        clozeOrd: Int?,
        frontSide: String?,
    ): String {
        var text = processConditionals(fmt, fields, clozeOrd)
        text = clozeFieldRegex.replace(text) { match ->
            val fieldName = match.groupValues[1].trim()
            renderCloze(fields[fieldName] ?: "", clozeOrd, isBack = frontSide != null)
        }
        // `{{type:Field}}`: blank on the front (the UI shows an input); the field
        // value on the back (the UI also shows a typed-vs-actual diff).
        text = typeRegex.replace(text) { match ->
            if (frontSide == null) "" else fields[match.groupValues[1].trim()] ?: ""
        }
        if (frontSide != null) {
            text = text.replace("{{FrontSide}}", frontSide)
        }
        text = substituteFields(text, fields)
        return text.trim()
    }

    /** Resolves `{{#Field}}`/`{{^Field}}` sections, innermost first (nesting-aware). */
    private fun processConditionals(fmt: String, fields: Map<String, String>, clozeOrd: Int?): String {
        var text = fmt
        var guard = 0
        while (guard++ < MAX_CONDITIONAL_PASSES) {
            val match = sectionRegex.find(text) ?: break
            val kind = match.groupValues[1]
            val name = match.groupValues[2].trim()
            val body = match.groupValues[3]
            val present = fieldPresent(name, fields, clozeOrd)
            val keep = if (kind == "#") present else !present
            text = text.replaceRange(match.range, if (keep) body else "")
        }
        return text
    }

    private fun fieldPresent(name: String, fields: Map<String, String>, clozeOrd: Int?): Boolean {
        val value = fields[name] ?: return false
        if (clozeOrd != null && clozeRegex.containsMatchIn(value)) {
            val active = clozeOrd + 1
            return clozeRegex.findAll(value).any { it.groupValues[1].toIntOrNull() == active }
        }
        return value.isNotBlank()
    }

    /**
     * Renders the cloze deletions in [text]. The active cloze ([clozeOrd] + 1) is
     * masked as `[hint]`/`[…]` on the front and shown as `**answer**` on the back;
     * every other cloze reveals its plain answer.
     */
    private fun renderCloze(text: String, clozeOrd: Int?, isBack: Boolean): String {
        val active = clozeOrd?.plus(1)
        return clozeRegex.replace(text) { match ->
            val number = match.groupValues[1].toIntOrNull()
            val answer = match.groupValues[2]
            val hint = match.groupValues[3]
            if (number == active) {
                if (isBack) "**$answer**" else "[" + hint.ifBlank { "…" } + "]"
            } else {
                answer
            }
        }
    }

    private fun substituteFields(text: String, fields: Map<String, String>): String {
        return Regex("""\{\{([^}]+)\}\}""").replace(text) { match ->
            val key = match.groupValues[1].trim()
            fields[key] ?: ""
        }
    }
}
