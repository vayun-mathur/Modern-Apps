package com.vayunmathur.files

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconVisible

class TextEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data!!
        setContent {
            DynamicTheme {
                TextEditorScreen(uri)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class) // Only needed if on older 1.7.x versions
@Composable
private fun TextEditorScreen(uri: Uri) {
    val context = LocalContext.current

    var initialContent by remember {
        mutableStateOf(context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: "")
    }

    // 1. Initialize the state with the file content
    val state = remember { TextFieldState(initialText = initialContent) }

    var isEditing by remember { mutableStateOf(false) }

    Scaffold(
        Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(uri.lastPathSegment ?: "File") },
                actions = {
                    IconButton(onClick = {
                        if (isEditing && state.text != initialContent) {
                            // Save Logic: Use the state.text buffer directly
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.bufferedWriter().write(state.text.toString())
                            }
                        }
                        isEditing = !isEditing
                    }) {
                        if (isEditing) if(initialContent == state.text) IconVisible() else IconSave() else IconEdit()
                    }
                }
            )
        }
    ) { paddingValues ->
        // 2. Wrap in a scrollable container that is NOT a LazyColumn
        // BasicTextField2 handles its own internal scrolling much better
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize() // This works correctly with BasicTextField2
        ) {
            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                readOnly = !isEditing,
                // 3. Set the text style to match your theme
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // 4. Enable efficient line-based scrolling
                lineLimits = TextFieldLineLimits.Default,
                scrollState = rememberScrollState()
            )
        }
    }
}