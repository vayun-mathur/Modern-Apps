package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.office.R

@Composable
internal fun SlideElementTextField(key: String, initial: String, label: String, onChange: (String) -> Unit) {
    var tfv by remember(key) { mutableStateOf(TextFieldValue(initial)) }
    TextField(
        value = tfv,
        onValueChange = { tfv = it; onChange(it.text) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun DrawingView(doc: OdfDocument.Drawing) {
    if (doc.pages.isEmpty()) { Text(
        stringResource(R.string.empty_drawing),
        modifier = Modifier.padding(16.dp)); return }
    var currentPage by remember { mutableIntStateOf(0) }
    // A live merge may replace the drawing with fewer pages while paged to the end.
    currentPage = currentPage.coerceIn(0, doc.pages.size - 1)
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp)) { item { SlideCard(doc.pages[currentPage]) } }
        if (doc.pages.size > 1) Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0) { Text(stringResource(R.string.prev)) }
                Text(
                    stringResource(R.string.page_of, currentPage + 1, doc.pages.size),
                    style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { if (currentPage < doc.pages.size - 1) currentPage++ },
                    enabled = currentPage < doc.pages.size - 1) { Text(stringResource(UiR.string.next)) }
            }
        }
    }
}
