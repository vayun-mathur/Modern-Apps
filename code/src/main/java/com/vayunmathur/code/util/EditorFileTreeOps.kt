package com.vayunmathur.code.util

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---- Folder handling ----
// Moved from EditorViewModel.kt (FileLength split); behavior identical.
// NOTE: CodeActions overrides (toggleNode/createFile/createFolder/renameNode/deleteNode)
// stay as members in EditorViewModel.kt — extensions cannot implement interface methods,
// and EditorScreen binds `actions = viewModel` as CodeActions.

/** Opens [dir] as the project root and loads its top level, persisting it for relaunch. */
fun EditorViewModel.openFolder(dir: File) {
    viewModelScope.launch {
        prefs.setFolderPath(dir.absolutePath)
        runCatching { loadFolder(dir) }
    }
}

fun EditorViewModel.closeFolder() {
    rootDir = null
    rootName = null
    nodes.clear()
    viewModelScope.launch { prefs.clearFolderPath() }
}

/** Loads [dir]'s top level into the tree (internal so the toggleNode override can reuse it). */
internal suspend fun EditorViewModel.loadFolder(dir: File) {
    require(dir.isDirectory) { "Not a directory: $dir" }
    val children = withContext(Dispatchers.IO) { FileFiles.listChildren(dir) }
    rootDir = dir
    rootName = dir.name
    nodes.clear()
    nodes.addAll(children.map { TreeNode(it, depth = 0) })
    refreshGit()
}

/** Removes the rows that are descendants of the row at [index] (depth strictly greater). */
internal fun EditorViewModel.removeDescendants(index: Int) {
    val depth = nodes[index].depth
    while (index + 1 < nodes.size && nodes[index + 1].depth > depth) {
        nodes.removeAt(index + 1)
    }
}

/**
 * Re-lists the children of a folder and rebuilds that subtree in [nodes]. Expansion state
 * and already-loaded descendant rows of immediate child folders are preserved by path.
 * [parentIndex] null refreshes the tree root.
 */
internal suspend fun EditorViewModel.refreshChildren(parentIndex: Int?) {
    val parentFile = if (parentIndex == null) rootDir ?: return
    else nodes.getOrNull(parentIndex)?.entry?.file ?: return
    val parentDepth = if (parentIndex == null) -1 else nodes[parentIndex].depth
    val childDepth = parentDepth + 1

    val blockStart = (parentIndex ?: -1) + 1
    val blockEnd = findBlockEnd(blockStart, parentDepth)
    val preserved = collectPreserved(blockStart, blockEnd, childDepth)

    val entries = withContext(Dispatchers.IO) { FileFiles.listChildren(parentFile) }
    val rebuilt = rebuildChildren(entries, preserved, childDepth)

    for (k in blockEnd - 1 downTo blockStart) nodes.removeAt(k)
    nodes.addAll(blockStart, rebuilt)
}

private fun EditorViewModel.findBlockEnd(blockStart: Int, parentDepth: Int): Int {
    var blockEnd = blockStart
    while (blockEnd < nodes.size && nodes[blockEnd].depth > parentDepth) blockEnd++
    return blockEnd
}

private class PreservedSubtree(
    val nodes: Map<String, TreeNode>,
    val children: Map<String, List<TreeNode>>,
)

// Preserve existing immediate children (and their loaded subtrees) by path.
private fun EditorViewModel.collectPreserved(
    blockStart: Int,
    blockEnd: Int,
    childDepth: Int,
): PreservedSubtree {
    val preservedNode = HashMap<String, TreeNode>()
    val preservedSubtree = HashMap<String, List<TreeNode>>()
    var i = blockStart
    while (i < blockEnd) {
        val child = nodes[i]
        if (child.depth != childDepth) {
            i++
            continue
        }
        val end = findChildEnd(i, blockEnd, childDepth)
        val key = child.entry.file.absolutePath
        preservedNode[key] = child
        preservedSubtree[key] = nodes.subList(i + 1, end).toList()
        i = end
    }
    return PreservedSubtree(preservedNode, preservedSubtree)
}

private fun EditorViewModel.findChildEnd(start: Int, blockEnd: Int, childDepth: Int): Int {
    var j = start + 1
    while (j < blockEnd && nodes[j].depth > childDepth) j++
    return j
}

private fun rebuildChildren(
    entries: List<FileEntry>,
    preserved: PreservedSubtree,
    childDepth: Int,
): List<TreeNode> {
    val rebuilt = ArrayList<TreeNode>()
    for (entry in entries) {
        val key = entry.file.absolutePath
        val existing = preserved.nodes[key]
        if (existing == null) {
            rebuilt.add(TreeNode(entry, childDepth))
        } else {
            rebuilt.add(existing)
            rebuilt.addAll(preserved.children[key].orEmpty())
        }
    }
    return rebuilt
}

/** Resolves the create target directory: the tree root, or a directory row. */
internal fun EditorViewModel.parentFileFor(parentIndex: Int?): File? =
    if (parentIndex == null) rootDir else nodes.getOrNull(parentIndex)?.entry?.file

// ---- File operations (CodeActions bodies; overrides in EditorViewModel.kt delegate here) ----

internal fun EditorViewModel.createFileImpl(parentIndex: Int?, name: String) {
    val parent = parentFileFor(parentIndex) ?: return
    viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { FileFiles.createFile(parent, name) } ?: return@launch
        nodes.getOrNull(parentIndex ?: -1)?.expanded = true
        refreshChildren(parentIndex)
        openFile(file)
    }
}

internal fun EditorViewModel.createFolderImpl(parentIndex: Int?, name: String) {
    val parent = parentFileFor(parentIndex) ?: return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { FileFiles.createDirectory(parent, name) } ?: return@launch
        nodes.getOrNull(parentIndex ?: -1)?.expanded = true
        refreshChildren(parentIndex)
    }
}

internal fun EditorViewModel.renameNodeImpl(index: Int, newName: String) {
    val node = nodes.getOrNull(index) ?: return
    val oldFile = node.entry.file
    viewModelScope.launch {
        val newFile = withContext(Dispatchers.IO) { FileFiles.rename(oldFile, newName) } ?: return@launch
        val at = nodes.indexOf(node)
        if (at >= 0) {
            // A renamed directory's descendant paths shift; drop them so a re-expand re-lists.
            if (node.entry.isDirectory) removeDescendants(at)
            nodes[at] = TreeNode(FileEntry(newFile, newName, node.entry.isDirectory), node.depth)
        }
        // Repoint open tabs backed by the renamed file (or living under a renamed directory).
        val oldPath = oldFile.absolutePath
        val newPath = newFile.absolutePath
        for (tab in tabs) {
            val p = tab.file?.absolutePath ?: continue
            if (p == oldPath) {
                tab.file = newFile
                tab.name = newName
                tab.language = Language.fromFileName(newName)
            } else if (p.startsWith(oldPath + File.separator)) {
                tab.file = File(newPath + p.substring(oldPath.length))
            }
        }
        saveSession()
    }
}

internal fun EditorViewModel.deleteNodeImpl(index: Int) {
    val node = nodes.getOrNull(index) ?: return
    val file = node.entry.file
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { FileFiles.delete(file) }
        if (!ok) return@launch
        val at = nodes.indexOf(node)
        if (at >= 0) {
            removeDescendants(at)
            nodes.removeAt(at)
        }
        closeTabsUnder(file)
    }
}

/** Select-tab body; the override in EditorViewModel.kt delegates here. */
internal fun EditorViewModel.selectTabImpl(index: Int) {
    if (index in tabs.indices) {
        currentIndex = index
        if (secondaryIndex == index) secondaryIndex = null // never show the same tab in both panes
        focusedSecondary = false
        actions.dismissCompletions()
        saveSession()
        scheduleDiagnostics()
    }
}

/** Close-tab body; the override in EditorViewModel.kt delegates here. */
internal fun EditorViewModel.closeTabImpl(index: Int) {
    if (index !in tabs.indices) return
    val removingCurrent = index == currentIndex
    tabs.removeAt(index)
    currentIndex = when {
        tabs.isEmpty() -> -1
        index < currentIndex -> currentIndex - 1
        removingCurrent -> index.coerceAtMost(tabs.lastIndex)
        else -> currentIndex
    }
    secondaryIndex = secondaryIndex?.let { s ->
        when {
            s == index -> null
            s > index -> s - 1
            else -> s
        }
    }?.takeIf { it in tabs.indices && it != currentIndex }
    if (secondaryIndex == null) focusedSecondary = false
    saveSession()
}

/** Closes any open tab whose file is [file] or lives beneath it (for a deleted directory). */
internal fun EditorViewModel.closeTabsUnder(file: File) {
    val target = file.absolutePath
    val prefix = target + File.separator
    for (i in tabs.indices.reversed()) {
        val p = tabs[i].file?.absolutePath ?: continue
        if (p == target || p.startsWith(prefix)) actions.closeTab(i)
    }
}

// ---- Tab open ----

/** Opens [file] in a tab (focusing it if already open), reading its text off the main thread. */
fun EditorViewModel.openFile(file: File) {
    addRecentFile(file.absolutePath)
    val key = file.absolutePath
    val existing = tabs.indexOfFirst { it.key == key }
    if (existing >= 0) {
        currentIndex = existing
        scheduleDiagnostics()
        return
    }
    viewModelScope.launch {
        val tab = makeFileTab(file) ?: return@launch
        tabs.add(tab)
        currentIndex = tabs.lastIndex
        saveSession()
        scheduleDiagnostics()
    }
}

fun EditorViewModel.openExternal(uri: Uri) {
    if (uri.scheme == "file") {
        uri.path?.let { openFile(File(it)) }
        return
    }
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val key = uri.toString()
    val existing = tabs.indexOfFirst { it.key == key }
    if (existing >= 0) {
        currentIndex = existing
        return
    }
    viewModelScope.launch {
        val displayName = withContext(Dispatchers.IO) { FileFiles.queryDisplayName(context, uri) } ?: "untitled"
        val text = withContext(Dispatchers.IO) {
            runCatching { FileFiles.readTextFromUri(context, uri) }.getOrDefault("")
        }
        tabs.add(
            OpenTab(
                file = null,
                externalUri = uri,
                readOnly = true,
                initialName = displayName,
                initialText = text,
                language = Language.fromFileName(displayName),
            )
        )
        currentIndex = tabs.lastIndex
        saveSession()
    }
}

/** Reads + decodes [file] off the main thread into a fully-populated tab (null on read failure). */
internal suspend fun EditorViewModel.makeFileTab(file: File, name: String = file.name): OpenTab? {
    val loaded = loadFile(file) ?: return null
    return OpenTab(
        file = file,
        initialName = name,
        initialText = loaded.decoded.text,
        language = Language.fromFileName(name),
    ).apply {
        charset = loaded.decoded.charset
        lineEnding = loaded.decoded.lineEnding
        hadBom = loaded.decoded.hadBom
        diskModified = loaded.modified
        diskLength = loaded.length
        foldStateByPath[file.absolutePath]?.let { foldedHeaders = it }
    }
}

/**
 * Reads [file]'s bytes and decodes them, capturing the on-disk snapshot.
 *
 * Returns null on any read failure: callers must not fabricate an empty buffer, because a tab
 * (or a reload) built on empty text can later be saved straight over the real file.
 */
internal suspend fun EditorViewModel.loadFile(file: File): LoadedFile? = withContext(Dispatchers.IO) {
    runCatching {
        LoadedFile(TextEncoding.decode(FileFiles.readBytes(file)), file.lastModified(), file.length())
    }.getOrNull()
}
