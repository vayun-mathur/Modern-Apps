package com.vayunmathur.files.ui
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.TextEditorViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.appBarScrollBehavior

class TextEditorActivity : ComponentActivity() {
    private val viewModel: TextEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        viewModel.load(uri)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                TextEditorScreen(uri, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(uri: Uri, viewModel: TextEditorViewModel) {
    val initialContent by viewModel.initialContent.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()
    val content = initialContent
    if (loadFailed) {
        // Never present a failed read as an editable empty file: saving from
        // here would overwrite the unread original with whatever is typed.
        AppScaffold(
            title = uri.lastPathSegment ?: stringResource(R.string.type_file),
            modifier = Modifier.imePadding(),
            scrollBehavior = appBarScrollBehavior(),
        ) { paddingValues ->
            Text(
                stringResource(R.string.editor_load_failed),
                modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp),
            )
        }
    } else if (content == null) {
        // Loading: render the bar only so the screen isn't blank during async read.
        AppScaffold(
            title = uri.lastPathSegment ?: stringResource(R.string.type_file),
            modifier = Modifier.imePadding(),
            scrollBehavior = appBarScrollBehavior(),
        ) { }
    } else {
        TextEditorLoaded(uri, content, viewModel)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorLoaded(uri: Uri, initialContent: String, viewModel: TextEditorViewModel) {
    val state = remember { TextFieldState(initialText = initialContent) }
    val saveFailed by viewModel.saveFailed.collectAsState()
    var isEditing by remember { mutableStateOf(false) }

    AppScaffold(
        title = uri.lastPathSegment ?: stringResource(R.string.type_file),
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(onClick = {
                if (isEditing && state.text.toString() != initialContent) {
                    viewModel.save(uri, state.text.toString())
                }
                isEditing = !isEditing
            }) {
                if (isEditing) if (initialContent == state.text.toString()) IconVisible() else IconSave() else IconEdit()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (saveFailed) {
                Text(
                    stringResource(R.string.editor_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                readOnly = !isEditing,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = TextFieldLineLimits.Default,
                scrollState = rememberScrollState()
            )
        }
    }
}
