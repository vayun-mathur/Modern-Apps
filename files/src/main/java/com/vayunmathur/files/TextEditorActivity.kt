package com.vayunmathur.files

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.util.pop

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(uri: Uri) {
    val context = LocalContext.current
    // have an edit / view mode
    // when saving, write to file
    var originalContent by remember { mutableStateOf(context.contentResolver.openInputStream(uri)?.use {
        it.bufferedReader().readText()
    } ?: "") }
    var content by remember { mutableStateOf(originalContent) }
    val fileName = uri.lastPathSegment!!
    var isEditing by remember { mutableStateOf(true) }

    Scaffold(topBar = {
        TopAppBar({ Text(fileName) }, actions = {
            IconButton({
                if(isEditing && content != originalContent) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.bufferedWriter().write(content)
                    }
                    originalContent = content
                }
                isEditing = !isEditing
            }) {
                if(isEditing) (if(content == originalContent) IconVisible() else IconSave()) else IconEdit()
            }
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(horizontal = 16.dp)) {
            BasicTextField(
                content,
                { content = it },
                Modifier.fillMaxSize(),
                readOnly = !isEditing,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                cursorBrush = SolidColor(LocalContentColor.current),
                decorationBox = { innerTextField ->
                    Box() {
                        if (content.isEmpty()) Text(
                            text = "Start typing here...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        innerTextField()
                    }
                }
            )
        }
    }
}