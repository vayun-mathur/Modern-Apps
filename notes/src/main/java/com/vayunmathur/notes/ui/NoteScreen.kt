package com.vayunmathur.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import com.vayunmathur.library.util.SharedEditableText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EditorBottomBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.FormatIconButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OdfMarkdownEditorController
import com.vayunmathur.library.ui.OdfMarkdownEditorField
import com.vayunmathur.library.ui.OdfMarkdownEditorToolbar
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberOdfMarkdownEditorController
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ink.deserialize
import com.vayunmathur.notes.R
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.randomBlockId
import com.vayunmathur.notes.platform.NoteActions
import com.vayunmathur.notes.platform.NoteImageStore
import com.vayunmathur.notes.platform.NoteUiState
import com.vayunmathur.notes.ui.components.ImageBlock
import com.vayunmathur.notes.ui.components.InkBlock
import com.vayunmathur.notes.ui.components.NoteTagEditor

/**
 * The note editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` â€” see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(state: NoteUiState, actions: NoteActions, sharedTextKey: Any? = null) {
    val context = LocalContext.current

    // Which text block currently has focus, so new media is inserted next to it
    // and the formatting toolbar targets the right editor.
    var focusedBlockId by remember { mutableStateOf<String?>(null) }
    var activeController by remember { mutableStateOf<OdfMarkdownEditorController?>(null) }

    // The controller for each text block, so tapping the empty space below the note
    // can focus the last one and drop the caret at its end.
    val textControllers = remember { mutableStateMapOf<String, OdfMarkdownEditorController>() }
    fun focusNoteEnd() {
        val lastTextId = state.blocks.lastOrNull { it is NoteBlock.Text }?.id ?: return
        textControllers[lastTextId]?.requestFocusAtEnd()
    }
    val tapBelowInteraction = remember { MutableInteractionSource() }

    DetailScaffold(
        title = {},
        onNavigateBack = { actions.back() },
        actions = {
            IconButton({ actions.copyNote() }) { IconCopy() }
            IconButton({ actions.shareNote() }) { IconShare() }
            IconButton({ actions.deleteNote() }) { IconDelete() }
        },
        bottomBar = {
            // One horizontally-scrollable bar. While a text block is focused it shows
            // the markdown formatting buttons followed by the insert buttons; otherwise
            // it shows just the insert buttons. Everything lives in a single row.
            val insertButtons: @Composable RowScope.() -> Unit = {
                FormatIconButton(stringResource(R.string.add_image), onClick = {
                    actions.addImage(focusedBlockId)
                }) { IconImage() }
                FormatIconButton(stringResource(R.string.add_drawing), onClick = {
                    actions.addDrawing(focusedBlockId)
                }) { IconDraw() }
            }
            val controller = activeController
            if (controller != null && controller.focused) {
                OdfMarkdownEditorToolbar(controller, trailing = insertButtons)
            } else {
                EditorBottomBar(scrollable = true, content = insertButtons)
            }
        },
        scrollBehavior = appBarScrollBehavior(),
        fillHeight = true,
    ) {
        BasicTextField(
                state.title,
                { actions.setTitle(it) },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.title.isEmpty()) Text(
                            text = stringResource(R.string.title),
                            style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        SharedEditableText(
                            key = sharedTextKey,
                            value = state.title,
                            style = MaterialTheme.typography.headlineMedium,
                        ) { innerTextField() }
                    }
                },
            )

            NoteTagEditor(
                tags = state.tags,
                knownTags = state.knownTags,
                onAdd = { actions.addTag(it) },
                onRemove = { actions.removeTag(it) },
                modifier = Modifier.padding(vertical = 4.dp),
            )

            state.blocks.forEach { block ->
                key(block.id) {
                    when (block) {
                        is NoteBlock.Text -> {
                            val controller = rememberOdfMarkdownEditorController(initialMarkdown = block.markdown) { newMd ->
                                actions.setBlockMarkdown(block.id, newMd)
                            }
                            LaunchedEffect(controller.focused) {
                                if (controller.focused) {
                                    focusedBlockId = block.id
                                    activeController = controller
                                }
                            }
                            DisposableEffect(controller) {
                                textControllers[block.id] = controller
                                onDispose { textControllers.remove(block.id) }
                            }
                            OdfMarkdownEditorField(controller = controller, modifier = Modifier.fillMaxWidth())
                        }

                        is NoteBlock.Image -> ImageBlock(
                            block = block,
                            file = NoteImageStore.fileFor(context, block.fileName),
                            onResize = { fraction -> actions.resizeImage(block.id, fraction) },
                            onMoveUp = { actions.moveBlock(block.id, -1) },
                            onMoveDown = { actions.moveBlock(block.id, 1) },
                            onDelete = { actions.deleteBlock(block.id) },
                        )

                        is NoteBlock.Ink -> InkBlock(
                            block = block,
                            onEdit = { actions.editDrawing(block) },
                            onMoveUp = { actions.moveBlock(block.id, -1) },
                            onMoveDown = { actions.moveBlock(block.id, 1) },
                            onDelete = { actions.deleteBlock(block.id) },
                        )
                    }
                }
            }

            // Empty space below the last block. The note has no visible boundary, so
            // let a tap anywhere under it drop the caret at the end of the note.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(
                        interactionSource = tapBelowInteraction,
                        indication = null,
                    ) { focusNoteEnd() }
            )
    }
}
