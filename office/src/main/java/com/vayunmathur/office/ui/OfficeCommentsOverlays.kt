package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.CommentDialog
import com.vayunmathur.office.ui.HeaderFooterDialog
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.insertComment
import com.vayunmathur.office.platform.setFooterText
import com.vayunmathur.office.platform.setHeaderText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.odf.OdfContentBlock

import com.vayunmathur.office.R
import com.vayunmathur.office.platform.resolveComment
/**
 * Comments + tracked-changes overlays (split from OfficeDocumentOverlays.kt for file length).
 * Behavior identical, call sites unchanged.
 */

/** Comments list dialog. */
@Composable
internal fun CommentsOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    listState: LazyListState,
    scope: CoroutineScope,
) {
    if (!state.showComments) return
    val td = document as? OdfDocument.TextDocument
    val comments = remember(document) {
        buildList {
            td?.content?.forEachIndexed { bi, block ->
                if (block is OdfContentBlock.Paragraph) block.paragraph.spans.forEachIndexed { si, span ->
                    span.annotation?.let { add(Triple(bi, si, it)) }
                }
            }
        }
    }
    AlertDialog(onDismissRequest = { state.showComments = false }, title = { Text(stringResource(
        R.string.comments_1,
        comments.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (comments.isEmpty()) Text(stringResource(R.string.no_comments_yet_use_insert_comment_to_ad))
                comments.forEach { (bi, si, ann) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            stringResource(
                                R.string.annotation_author_date,
                                ann.author ?: stringResource(R.string.anonymous),
                                ann.date?.let { " · $it" } ?: "",
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            ann.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } },
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { scope.launch { listState.animateScrollToItem(bi.coerceIn(
                                0,
                                (td?.content?.size ?: 1) - 1)) } }) { Text(stringResource(R.string.go_to)) }
                            TextButton(onClick = { viewModel.resolveComment(
                                bi,
                                si) }) { Text(stringResource(R.string.resolve)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.showComments = false }) { Text(stringResource(R.string.close_search)) }
        },
    )
}

/** Comment + changes + page-setup + header/footer overlays. */
@Composable
internal fun CommentOverlaysHost(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    focusedPara: Int,
    listState: LazyListState,
    scope: CoroutineScope,
) {
    if (state.showComment) CommentDialog(
        onAdd = { author, text -> if (focusedPara >= 0) viewModel.insertComment(focusedPara, author, text) },
        onDismiss = { state.showComment = false })
    CommentsOverlays(state, document, viewModel, listState, scope)
    ChangesOverlays(state, document, viewModel)
    PageSetupOverlays(state, document, viewModel)
    if (state.showHeaderFooter) {
        val td = document as? OdfDocument.TextDocument
        HeaderFooterDialog(
            initialHeader = td?.headerParagraphs?.joinToString("\n") { p ->
                p.spans.joinToString("") { it.text }
            } ?: "",
            initialFooter = td?.footerParagraphs?.joinToString("\n") { p ->
                p.spans.joinToString("") { it.text }
            } ?: "",
            onSave = { h, f -> viewModel.setHeaderText(h); viewModel.setFooterText(f) },
            onDismiss = { state.showHeaderFooter = false }
        )
    }
}
