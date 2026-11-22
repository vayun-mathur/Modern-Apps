package com.vayunmathur.email.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.LabeledTextField

/**
 * Composer form pane: header/recipients, subject, HTML editor and the
 * attachment section. Extracted from [ComposerScreen] to keep that file
 * under the length limit. Behavior identical — only moved.
 */
@Composable
fun ComposerForm(
    padding: PaddingValues,
    accounts: List<EmailAccount>,
    fromAccount: EmailAccount?,
    showAccountPicker: Boolean,
    onOpenAccountPicker: () -> Unit,
    onDismissAccountPicker: () -> Unit,
    onSelectAccount: (EmailAccount) -> Unit,
    to: String,
    onToChange: (String) -> Unit,
    cc: String,
    onCcChange: (String) -> Unit,
    bcc: String,
    onBccChange: (String) -> Unit,
    showCcBcc: Boolean,
    onToggleCcBcc: () -> Unit,
    onPickContact: (Int) -> Unit,
    subject: String,
    onSubjectChange: (String) -> Unit,
    draftId: Long?,
    bodyController: EmailHtmlEditorController,
    context: Context,
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
) {
    // Detail pane form: letterboxed on expanded windows so fields keep a
    // readable measure on desktop.
    DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ComposerHeaderSection(
                accounts = accounts,
                fromAccount = fromAccount,
                showAccountPicker = showAccountPicker,
                onOpenAccountPicker = onOpenAccountPicker,
                onDismissAccountPicker = onDismissAccountPicker,
                onSelectAccount = onSelectAccount,
                to = to,
                onToChange = onToChange,
                cc = cc,
                onCcChange = onCcChange,
                bcc = bcc,
                onBccChange = onBccChange,
                showCcBcc = showCcBcc,
                onToggleCcBcc = onToggleCcBcc,
                onPickContact = onPickContact,
            )
            // The drafts row's subject morphs into this field. Null for a fresh compose, a reply or
            // a forward, none of which came from a draft.
            LabeledTextField(
                value = subject,
                onValueChange = onSubjectChange,
                label = stringResource(R.string.subject_label),
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Next,
                sharedTextKey = draftId?.let { "email-draft-subject-$it" },
            )

            com.vayunmathur.email.ui.composer.EmailHtmlEditor(
                controller = bodyController,
                placeholder = stringResource(R.string.body_label),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            ComposerAttachmentSection(
                context = context,
                bodyController = bodyController,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
            )
        }
    }
}
