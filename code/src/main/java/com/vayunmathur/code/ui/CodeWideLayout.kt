package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded layout for the code editor: file tree beside the editor, never list-detail.
 *
 * Phone keeps the tree in a navigation drawer ([EditorScreen]). On Expanded the same
 * two pieces sit side by side permanently, with the terminal as a fractional bottom
 * panel inside the editor column. All splits are weights so they scale with the window.
 *
 * Slots only: the caller reuses [FileTreePane], the editor surface and the terminal
 * content unchanged.
 */
@Composable
fun CodeWideLayout(
    tree: @Composable (Modifier) -> Unit,
    editor: @Composable (Modifier) -> Unit,
    terminal: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    showTerminal: Boolean = true,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.weight(0.28f).fillMaxHeight()) {
            tree(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.72f).fillMaxHeight()) {
            Box(Modifier.weight(0.72f).fillMaxWidth()) {
                editor(Modifier.fillMaxSize())
            }
            if (showTerminal) {
                Box(Modifier.weight(0.28f).fillMaxWidth()) {
                    terminal(Modifier.fillMaxSize())
                }
            }
        }
    }
}
