package com.vayunmathur.code.platform
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.vayunmathur.code.data.FileFiles
import com.vayunmathur.code.platform.CodeTreeActions
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.TreeNode

/** [CodeTreeActions] implementation, delegating tree storage to the ViewModel. */
internal class EditorTreeActions(private val vm: EditorViewModel) : CodeTreeActions {

    override fun toggleNode(index: Int) {
        val node = vm.nodes.getOrNull(index) ?: return
        if (!node.entry.isDirectory) {
            vm.openFile(node.entry.file)
            return
        }

        if (node.expanded) {
            node.expanded = false
            vm.removeDescendants(index)
        } else {
            node.expanded = true
            node.loading = true
            vm.scope.launch {
                val children = withContext(Dispatchers.IO) { FileFiles.listChildren(node.entry.file) }
                node.loading = false
                // The row may have been collapsed again while loading; only insert if still open.
                val at = vm.nodes.indexOf(node)
                if (at >= 0 && node.expanded) {
                    vm.nodes.addAll(at + 1, children.map { TreeNode(it, node.depth + 1) })
                }
            }
        }
    }

    override fun createFile(parentIndex: Int?, name: String) = vm.createFileImpl(parentIndex, name)

    override fun createFolder(parentIndex: Int?, name: String) = vm.createFolderImpl(parentIndex, name)

    override fun renameNode(index: Int, newName: String) = vm.renameNodeImpl(index, newName)

    override fun deleteNode(index: Int) = vm.deleteNodeImpl(index)

    override fun openPath(path: String) = vm.openFile(File(path))

    override fun refreshProjectFiles() = vm.refreshProjectFilesImpl()
}
