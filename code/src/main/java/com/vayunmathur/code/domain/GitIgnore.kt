package com.vayunmathur.code.domain
import com.vayunmathur.code.platform.EditorViewModel
/**
 * A small, pure `.gitignore` matcher used to hide ignored files from project search and
 * quick-open (alongside the hard-coded [EditorViewModel.SKIP_DIRS]).
 *
 * It supports the common subset of the gitignore spec: comments (`#`) and blank lines, `*`
 * and `?` wildcards, `**` across directories, `/` anchoring (a leading or interior slash
 * anchors to the project root), directory-only patterns (trailing `/`) and negation (`!`).
 * Rules are applied in file order with last-match-wins, so a later `!pattern` can re-include a
 * path an earlier rule ignored. It deliberately does not implement every corner of the real
 * spec (e.g. escaping oddities), which is fine for a client-side "hide the noise" filter.
 */
class GitIgnore internal constructor(private val rules: List<Rule>) {

    internal class Rule(
        val regex: Regex,
        val negate: Boolean,
        val dirOnly: Boolean,
        val anchored: Boolean,
    ) {
        fun matches(prefix: String, prefixIsDir: Boolean): Boolean {
            if (dirOnly && !prefixIsDir) return false
            return if (anchored) regex.matches(prefix) else regex.matches(prefix.substringAfterLast('/'))
        }
    }

    /**
     * True if [relativePath] (relative to the project root, `/`-separated) is ignored. [isDir]
     * marks whether the entry itself is a directory. Every ancestor directory of the path is
     * also tested, so a file under an ignored directory is reported ignored too.
     */
    fun isIgnored(relativePath: String, isDir: Boolean): Boolean {
        if (rules.isEmpty()) return false
        val norm = relativePath.replace('\\', '/').trim('/')
        if (norm.isEmpty()) return false
        val segs = norm.split('/')
        var ignored = false
        for (i in 1..segs.size) {
            val prefix = segs.subList(0, i).joinToString("/")
            val prefixIsDir = if (i < segs.size) true else isDir
            for (rule in rules) {
                if (rule.matches(prefix, prefixIsDir)) ignored = !rule.negate
            }
        }
        return ignored
    }

    companion object {
        val EMPTY = GitIgnore(emptyList())
    }
}

/** Parses the text of a `.gitignore` file into a [GitIgnore] matcher. */
fun parseGitIgnore(text: String): GitIgnore {
    val rules = ArrayList<GitIgnore.Rule>()
    for (raw in text.lineSequence()) {
        parseRule(raw)?.let { rules.add(it) }
    }
    return GitIgnore(rules)
}

private fun parseRule(raw: String): GitIgnore.Rule? {
    var line = raw.trimEnd()
    if (line.isEmpty() || line.startsWith("#")) return null

    var negate = false
    if (line.startsWith("!")) {
        negate = true
        line = line.substring(1)
    }
    // An escaped leading '#' or '!' is a literal.
    if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.substring(1)

    var dirOnly = false
    if (line.endsWith("/")) {
        dirOnly = true
        line = line.dropLast(1)
    }
    if (line.isEmpty()) return null

    val anchored = line.contains('/')
    val pat = if (line.startsWith("/")) line.substring(1) else line
    if (pat.isEmpty()) return null
    return GitIgnore.Rule(globToRegex(pat), negate, dirOnly, anchored)
}

/** Translates a gitignore glob into a [Regex] matching a whole path segment (or full path). */
private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when (c) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    // "**" spans directories. A "/**/" segment also matches zero directories.
                    val slashBefore = i > 0 && glob[i - 1] == '/'
                    val slashAfter = i + 2 < glob.length && glob[i + 2] == '/'
                    if (slashBefore && slashAfter) {
                        // Emitted "/" already; consume the trailing "/" and allow zero-or-more dirs.
                        sb.append("(.*/)?")
                        i += 2 // skip second '*' and the following '/'
                    } else {
                        sb.append(".*")
                        i++ // skip second '*'
                    }
                } else {
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex(sb.toString())
}
