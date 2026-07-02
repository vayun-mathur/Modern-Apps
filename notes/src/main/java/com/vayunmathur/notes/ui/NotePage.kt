package com.vayunmathur.notes.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.EditorBottomBar
import com.vayunmathur.library.ui.FormatIconButton
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.InkCanvasView
import com.vayunmathur.library.ui.OdfMarkdownEditorController
import com.vayunmathur.library.ui.OdfMarkdownEditorField
import com.vayunmathur.library.ui.OdfMarkdownEditorToolbar
import com.vayunmathur.library.ui.rememberOdfMarkdownEditorController
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.body
import com.vayunmathur.notes.data.randomBlockId
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.util.NoteImageStore
import com.vayunmathur.notes.util.NotesViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by notesViewModel.editableNote(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Local, editable copy of the note body: an ordered list of inline blocks
    // (text / image / ink). Every change is written back to [note], which persists
    // via the ViewModel's debounced upsert.
    val blocks = remember(noteID) { mutableStateListOf<NoteBlock>().apply { addAll(note.body().blocks) } }
    fun commit() { note = note.withBody(NoteBody(blocks.toList())) }

    // Which text block currently has focus, so new media is inserted next to it
    // and the formatting toolbar targets the right editor.
    var focusedBlockId by remember { mutableStateOf<String?>(null) }
    var activeController by remember { mutableStateOf<OdfMarkdownEditorController?>(null) }
    var editingInk by remember { mutableStateOf<NoteBlock.Ink?>(null) }

    fun focusedIndex(): Int? = blocks.indexOfFirst { it.id == focusedBlockId }.takeIf { it >= 0 }

    fun insertBlock(block: NoteBlock) {
        val at = focusedIndex()?.plus(1) ?: blocks.size
        blocks.add(at, block)
        // Keep a text block after inserted media so the user can keep typing below it.
        if (block !is NoteBlock.Text && (at + 1 > blocks.lastIndex || blocks[at + 1] !is NoteBlock.Text)) {
            blocks.add(at + 1, NoteBlock.Text("", randomBlockId()))
        }
        commit()
    }

    fun deleteBlock(block: NoteBlock) {
        val i = blocks.indexOfFirst { it.id == block.id }
        if (i < 0) return
        if (block is NoteBlock.Image) NoteImageStore.delete(context, block.fileName)
        blocks.removeAt(i)
        if (blocks.isEmpty()) blocks.add(NoteBlock.Text("", randomBlockId()))
        commit()
    }

    fun moveBlock(block: NoteBlock, delta: Int) {
        val i = blocks.indexOfFirst { it.id == block.id }
        val j = i + delta
        if (i < 0 || j < 0 || j > blocks.lastIndex) return
        val moved = blocks.removeAt(i)
        blocks.add(j, moved)
        commit()
    }

    fun importImage(uri: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { NoteImageStore.import(context, uri) }
            if (name != null) insertBlock(NoteBlock.Image(name))
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importImage(uri)
    }

    LaunchedEffect(notesViewModel) {
        notesViewModel.shareUris.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Note"))
        }
    }

    // The drawing editor takes over the whole screen while open.
    val ink = editingInk
    if (ink != null) {
        BackHandler { editingInk = null }
        InkEditor(
            initialStrokes = ink.strokes,
            onDone = { newStrokes ->
                val i = blocks.indexOfFirst { it.id == ink.id }
                if (i >= 0) {
                    blocks[i] = ink.copy(strokes = newStrokes)
                    commit()
                } else {
                    insertBlock(ink.copy(strokes = newStrokes))
                }
                editingInk = null
            },
            onCancel = { editingInk = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconNavigation { backStack.pop() } },
                actions = {
                    IconButton({
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("note", note.content)))
                        }
                    }) { IconCopy() }
                    IconButton({ notesViewModel.requestShare(note.title, note.content) }) { IconShare() }
                    IconButton(onClick = {
                        notesViewModel.delete(note)
                        backStack.pop()
                    }) { IconDelete() }
                },
            )
        },
        bottomBar = {
            // One horizontally-scrollable bar. While a text block is focused it shows
            // the markdown formatting buttons followed by the insert buttons; otherwise
            // it shows just the insert buttons. Everything lives in a single row.
            val insertButtons: @Composable RowScope.() -> Unit = {
                FormatIconButton(Icons.Filled.Image, stringResource(R.string.add_image)) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                FormatIconButton(Icons.Filled.Draw, stringResource(R.string.add_drawing)) {
                    editingInk = NoteBlock.Ink(emptyList(), id = randomBlockId())
                }
            }
            val controller = activeController
            if (controller != null && controller.focused) {
                OdfMarkdownEditorToolbar(controller, trailing = insertButtons)
            } else {
                EditorBottomBar(scrollable = true, content = insertButtons)
            }
        },
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            BasicTextField(
                note.title,
                { note = note.copy(title = it) },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = { innerTextField ->
                    Box {
                        if (note.title.isEmpty()) Text(
                            text = stringResource(R.string.title),
                            style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        innerTextField()
                    }
                },
            )

            blocks.toList().forEach { block ->
                key(block.id) {
                    when (block) {
                        is NoteBlock.Text -> {
                            val controller = rememberOdfMarkdownEditorController(initialMarkdown = block.markdown) { newMd ->
                                val i = blocks.indexOfFirst { it.id == block.id }
                                if (i >= 0) {
                                    blocks[i] = NoteBlock.Text(newMd, block.id)
                                    commit()
                                }
                            }
                            LaunchedEffect(controller.focused) {
                                if (controller.focused) {
                                    focusedBlockId = block.id
                                    activeController = controller
                                }
                            }
                            OdfMarkdownEditorField(controller = controller, modifier = Modifier.fillMaxWidth())
                        }

                        is NoteBlock.Image -> ImageBlock(
                            block = block,
                            file = NoteImageStore.fileFor(context, block.fileName),
                            onResize = { fraction ->
                                val i = blocks.indexOfFirst { it.id == block.id }
                                if (i >= 0) { blocks[i] = block.copy(widthFraction = fraction); commit() }
                            },
                            onMoveUp = { moveBlock(block, -1) },
                            onMoveDown = { moveBlock(block, 1) },
                            onDelete = { deleteBlock(block) },
                        )

                        is NoteBlock.Ink -> InkBlock(
                            block = block,
                            onEdit = { editingInk = block },
                            onMoveUp = { moveBlock(block, -1) },
                            onMoveDown = { moveBlock(block, 1) },
                            onDelete = { deleteBlock(block) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageBlock(
    block: NoteBlock.Image,
    file: File,
    onResize: (Float) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(block.widthFraction)
                .clip(RoundedCornerShape(8.dp)),
        )
        BlockControls(onMoveUp = onMoveUp, onMoveDown = onMoveDown, onDelete = onDelete) {
            // Step image width between quarter and full width.
            IconButton(onClick = { onResize((block.widthFraction - 0.25f).coerceAtLeast(0.25f)) }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.shrink))
            }
            IconButton(onClick = { onResize((block.widthFraction + 0.25f).coerceAtMost(1f)) }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.enlarge))
            }
        }
    }
}

@Composable
private fun InkBlock(
    block: NoteBlock.Ink,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val strokes = remember(block) { block.strokes.map { it.deserialize() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(block.heightDp.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable { onEdit() },
        ) {
            InkCanvasView(
                currentBrush = previewBrush,
                finishedStrokes = strokes,
                onStrokeFinished = {},
                enabled = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BlockControls(onMoveUp = onMoveUp, onMoveDown = onMoveDown, onDelete = onDelete)
    }
}

@Composable
private fun BlockControls(
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        extra()
        IconButton(onClick = onMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
        }
        IconButton(onClick = onMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
        }
        IconButton(onClick = onDelete) { IconDelete() }
    }
}

// A read-only ink preview needs some brush, but strokes carry their own; this is unused for drawing.
private val previewBrush by lazy {
    Brush.createWithColorIntArgb(StockBrushes.pressurePen(), 0xFF000000.toInt(), 6f, 0.1f)
}
