package com.vayunmathur.notes.domain

/**
 * Pure tag rules for notes (#913). No Android, no Room: the editor, the list,
 * the repository's rename/delete and the relay receive path all call these, so
 * every way a tag can enter the database produces the same normalised form.
 *
 * Tags compare case-insensitively everywhere ([tagKey]); the first spelling a
 * note was given is the one it keeps.
 */

/** Longest tag, in UTF-16 chars, after whitespace is collapsed. */
const val MAX_TAG_LENGTH = 40

/** Most tags one note can carry; extras are dropped from the end. */
const val MAX_TAGS_PER_NOTE = 20

private val whitespaceRun = Regex("\\s+")

/** Identity used to compare tags: "Work", "work" and "WORK" are the same tag. */
fun tagKey(tag: String): String = tag.lowercase()

/**
 * One tag in canonical form: trimmed, internal whitespace runs collapsed to a
 * single space, cut to [MAX_TAG_LENGTH] (never splitting a surrogate pair).
 * Null when nothing is left.
 */
fun normalizeTag(raw: String): String? {
    val collapsed = raw.trim().replace(whitespaceRun, " ")
    if (collapsed.isEmpty()) return null
    var cut = collapsed.take(MAX_TAG_LENGTH)
    if (cut.length < collapsed.length && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd().ifEmpty { null }
}

/**
 * A note's tag list in canonical form: each tag normalised ([normalizeTag]),
 * empties dropped, case-insensitive duplicates removed keeping the first
 * spelling, and at most [MAX_TAGS_PER_NOTE] kept. Order is preserved.
 */
fun normalizeTags(raw: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>(minOf(raw.size, MAX_TAGS_PER_NOTE))
    for (tag in raw) {
        val clean = normalizeTag(tag) ?: continue
        if (seen.add(tagKey(clean))) out += clean
        if (out.size == MAX_TAGS_PER_NOTE) break
    }
    return out
}

/** True when [tags] contains [tag], ignoring case. */
fun hasTag(tags: List<String>, tag: String): Boolean {
    val key = tagKey(tag)
    return tags.any { tagKey(it) == key }
}

/**
 * The spelling to store for user input [raw]: normalised, and if a tag with the
 * same key already exists in [known] that spelling wins, so typing "Work" when
 * "work" exists does not split one tag in two. Null for blank input.
 */
fun canonicalTag(raw: String, known: List<String>): String? {
    val clean = normalizeTag(raw) ?: return null
    val key = tagKey(clean)
    return known.firstOrNull { tagKey(it) == key } ?: clean
}

/** [tags] with [tag] removed (case-insensitive); unchanged when absent. */
fun withoutTag(tags: List<String>, tag: String): List<String> {
    val key = tagKey(tag)
    return tags.filterNot { tagKey(it) == key }
}

/**
 * [tags] with [from] renamed to [to], in place. When the note already carries
 * [to] (any case) the two merge: [from] is dropped and the existing spelling
 * stays where it was. Unchanged when [from] is absent or [to] is blank.
 */
fun withTagRenamed(tags: List<String>, from: String, to: String): List<String> {
    val target = normalizeTag(to) ?: return tags
    val fromKey = tagKey(from)
    if (tags.none { tagKey(it) == fromKey }) return tags
    // normalizeTags keeps the first spelling per key, so an existing [to] that
    // sits before [from] wins the merge, and a later one is dropped.
    return normalizeTags(tags.map { if (tagKey(it) == fromKey) target else it })
}

/** A tag and how many notes carry it. */
data class TagCount(val name: String, val count: Int)

/**
 * Every tag across [tagLists] (one list per note) with its note count, most
 * used first, then by name. Tags group case-insensitively; the displayed
 * spelling is the one most notes use (ties go to the first seen).
 */
fun tagCounts(tagLists: List<List<String>>): List<TagCount> {
    // key -> (spelling -> count), insertion-ordered so ties resolve to the first seen.
    val byKey = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    for (tags in tagLists) {
        for (tag in tags.distinctBy(::tagKey)) {
            val spellings = byKey.getOrPut(tagKey(tag)) { LinkedHashMap() }
            spellings[tag] = (spellings[tag] ?: 0) + 1
        }
    }
    return byKey.values
        .map { spellings ->
            val name = spellings.entries.maxBy { it.value }.key
            TagCount(name, spellings.values.sum())
        }
        .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { tagKey(it.name) }.thenBy { it.name })
}

/**
 * Autocomplete for the tag field: [known] tags (already most-used first) that
 * contain [query] (case-insensitive; prefix matches first), minus those the
 * note already has, capped at [limit]. A blank query suggests the top tags.
 */
fun suggestTags(known: List<String>, query: String, exclude: List<String>, limit: Int): List<String> {
    val excluded = exclude.mapTo(HashSet(), ::tagKey)
    val q = tagKey(query.trim())
    val candidates = known.filter { tagKey(it) !in excluded && tagKey(it).contains(q) }
    val (prefix, rest) = candidates.partition { tagKey(it).startsWith(q) }
    return (prefix + rest).filterNot { q.isNotEmpty() && tagKey(it) == q }.take(limit)
}
