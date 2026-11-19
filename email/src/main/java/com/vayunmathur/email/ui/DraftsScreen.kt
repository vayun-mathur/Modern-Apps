package com.vayunmathur.email.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText

@Composable
fun DraftsScreen(
    viewModel: EmailViewModel,
    onBack: () -> Unit,
    onOpenDraft: (Long) -> Unit,
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle(emptyList())
    AppScaffold(
        title = stringResource(R.string.drafts),
        onNavigateBack = onBack,
    ) { padding ->
        if (drafts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_drafts),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(drafts, key = { it.id }) { d ->
                    ListItem(
                        content = {
                            Text(
                                d.subject.ifBlank { stringResource(R.string.no_subject) },
                                modifier = Modifier.sharedText("email-draft-subject-${d.id}"),
                            )
                        },
                        supportingContent = {
                            val prefix = if (d.to.isNotBlank()) "To: ${d.to}  " else ""
                            Text(prefix + d.body.replace("\n", " ").take(80), maxLines = 2)
                        },
                        modifier = Modifier.clickable { onOpenDraft(d.id) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.draftsActions.delete(d.id) }) {
                                com.vayunmathur.library.ui.IconDelete()
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
