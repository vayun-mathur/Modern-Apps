package com.vayunmathur.files.platform

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Debounce applied before a keystroke starts a recursive walk. */
internal const val SEARCH_DEBOUNCE_MS = 250L

/** Deepest level below the search root that is still listed (the root itself is level 0). */
internal const val SEARCH_MAX_DEPTH = 8

/** Hard stop on flattened results, so a huge tree cannot flood the listing. */
internal const val SEARCH_MAX_RESULTS = 500

/** One recursive-search result: the item plus where it lives, for the row subtitle. */
data class FilesSearchHit(
    val item: FileBrowserItem,
    val parentSubtitle: String,
)

/** Splits [query] into lowercase match tokens; blank queries have none (and match all). */
internal fun searchTokens(query: String): List<String> =
    query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * The listing's name match, tokenized: every whitespace-separated token must be
 * contained in [name] (case-insensitive), so "report pdf" narrows instead of
 * needing the exact phrase. A single token is exactly the old substring match.
 */
internal fun searchMatches(name: String, query: String): Boolean {
    val tokens = searchTokens(query)
    return tokens.isEmpty() || tokens.all { name.contains(it, ignoreCase = true) }
}

/**
 * Where [item] lives under [root], as a row subtitle: the parent folder's path
 * relative to [root] (e.g. "Docs/2024"), or [root]'s own name for items sitting
 * directly in it. Zip entries without a real file derive it from the inner path.
 */
internal fun parentSubtitle(root: File, item: FileBrowserItem): String {
    val real = item.realFile
    if (real != null) {
        val parent = real.parentFile ?: return root.name
        if (parent.absolutePath == root.absolutePath) return root.name.ifEmpty { "/" }
        val relative = runCatching { parent.relativeTo(root).path }.getOrDefault("")
        return relative.ifEmpty { parent.name }
    }
    val inner = item.zipInnerPath ?: return ""
    return inner.substringBeforeLast('/', "")
}

/**
 * Enumerates one directory exactly as the browser does, flattened. This is the
 * only enumeration the walk uses — no new storage backend.
 */
internal fun listDirChildren(dir: File, showHidden: Boolean): List<FileBrowserItem> {
    val (dirs, files) = listRealDirItems(dir, showHidden)
    return dirs + files
}

/**
 * Recursive search under [root] only (the folder the user is browsing — never
 * the whole device). Breadth-first, so shallower hits come first; matching
 * folders are both listed and descended into. Stops descending at
 * [maxDepth] levels, stops collecting at [maxResults], skips directories
 * already visited (symlink cycles), and cooperatively cancels via
 * [ensureActive] between directories, so a superseded query (collectLatest)
 * abandons the walk promptly. Enumeration goes through [listChildren],
 * which defaults to the browser's own [listRealDirItems].
 */
internal suspend fun recursiveSearch(
    root: File,
    query: String,
    showHidden: Boolean = false,
    maxDepth: Int = SEARCH_MAX_DEPTH,
    maxResults: Int = SEARCH_MAX_RESULTS,
    listChildren: (File) -> List<FileBrowserItem> = { listDirChildren(it, showHidden) },
): List<FilesSearchHit> {
    if (searchTokens(query).isEmpty() || maxResults <= 0) return emptyList()
    val hits = mutableListOf<FilesSearchHit>()
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<Pair<File, Int>>()
    visited += canonicalKey(root)
    queue.addLast(root to 0)
    while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (dir, depth) = queue.removeFirst()
        if (depth >= maxDepth) continue
        val children = listChildren(dir)
        currentCoroutineContext().ensureActive()
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val childFile = child.realFile ?: continue
            // Scope guard: nothing outside the browsed root is ever reported.
            if (!isWithin(root, childFile)) continue
            if (searchMatches(child.name, query)) {
                hits += FilesSearchHit(child, parentSubtitle(root, child))
                if (hits.size >= maxResults) return hits
            }
            if (child.isDirectory && visited.add(canonicalKey(childFile))) {
                queue.addLast(childFile to depth + 1)
            }
        }
    }
    return hits
}

private fun canonicalKey(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

private fun isWithin(root: File, file: File): Boolean {
    val rootPath = canonicalKey(root)
    val path = canonicalKey(file)
    return path == rootPath || path.startsWith(rootPath + File.separator)
}
