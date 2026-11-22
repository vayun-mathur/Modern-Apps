package com.vayunmathur.email.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.ui.composer.EmailHtmlEditor
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.email.ui.composer.InlineAttachment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ComposerScreen(
    viewModel: EmailViewModel,
    initialTo: String = "",
    initialSubject: String = "",
    initialBody: String = "",
    inReplyTo: String? = null,
    references: String? = null,
    draftId: Long? = null,
    onBack: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccount by viewModel.selectedAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    var fromAccount by remember(selectedAccount, accounts) {
        mutableStateOf(selectedAccount ?: accounts.firstOrNull())
    }

    var to by remember { mutableStateOf(initialTo) }
    var cc by remember { mutableStateOf("") }
    var bcc by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(initialSubject) }
    val bodyController = remember { com.vayunmathur.email.ui.composer.EmailHtmlEditorController(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    var pickTarget by remember { mutableIntStateOf(0) }
    var pickTick by remember { mutableIntStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val email = result.data?.data?.let { contactEmail(context, it) }
            if (!email.isNullOrBlank()) {
                when (pickTarget) {
                    0 -> to = appendRecipient(to, email)
                    1 -> cc = appendRecipient(cc, email)
                    2 -> bcc = appendRecipient(bcc, email)
                }
                pickTick++
            }
        }
    }
    LaunchedEffect(pickTick) {
        if (pickTick > 0) {
            contactPicker.launch(
                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI)
            )
        }
    }
    val pickContact: (Int) -> Unit = { target ->
        pickTarget = target
        pickTick++
    }

    var currentDraftId by remember { mutableStateOf(draftId) }
    var draftLoaded by remember { mutableStateOf(draftId == null) }
    LaunchedEffect(draftId) {
        if (draftId != null) {
            viewModel.draftsActions.load(draftId)?.let { d ->
                to = d.to; cc = d.cc; bcc = d.bcc
                if (d.cc.isNotBlank() || d.bcc.isNotBlank()) showCcBcc = true
                subject = d.subject; bodyController.setHtml(d.body)
                accounts.firstOrNull { it.email == d.accountEmail }?.let { fromAccount = it }
            }
            draftLoaded = true
        }
    }

    var appliedSignature by remember { mutableStateOf("") }
    LaunchedEffect(fromAccount) {
        if (draftId != null) return@LaunchedEffect
        val block = signatureBlockHtml(fromAccount)
        if (block != appliedSignature) {
            val t = bodyController.html
            val newText = when {
                appliedSignature.isEmpty() -> t + block
                t.endsWith(appliedSignature) -> t.removeSuffix(appliedSignature) + block
                else -> t + block
            }
            bodyController.setHtml(newText)
            appliedSignature = block
        }
    }

    LaunchedEffect(fromAccount, draftLoaded) {
        val acc = fromAccount
        if (!draftLoaded || acc == null) return@LaunchedEffect
        snapshotFlow { listOf(to, cc, bcc, subject, bodyController.html) }
            .debounce(800)
            .collect {
                val hasContent = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() ||
                    subject.isNotBlank() || bodyController.html.isNotBlank()
                if (hasContent) {
                    viewModel.draftsActions.save(currentDraftId, acc.email, to, cc, bcc, subject, bodyController.html) { id ->
                        currentDraftId = id
                    }
                }
            }
    }

    // Clean up orphan inline images on text change
    LaunchedEffect(bodyController.html) {
        bodyController.inlineCleanupOrphans()
    }

    // Attachment launchers
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) attachments = attachments + uris
    }

    // Inline image pickers: use PickVisualMedia + fallback GetContent image/*
    val inlineFallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val name = uriName(context, uri)
            // Copy to cache/inline for stable uri
            val cached = copyInlineToCache(context, uri, name)
            val finalUri = cached ?: uri
            bodyController.insertInlineImage(context, finalUri, mime, name)
        }
    }

    // Try to use PickVisualMedia if available
    var visualMediaLauncher: Any? = null
    val pickVisualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val name = uriName(context, uri)
            val cached = copyInlineToCache(context, uri, name)
            val finalUri = cached ?: uri
            bodyController.insertInlineImage(context, finalUri, mime, name)
        }
    }

    val pickMultipleVisualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val name = uriName(context, uri)
                val cached = copyInlineToCache(context, uri, name)
                val finalUri = cached ?: uri
                bodyController.insertInlineImage(context, finalUri, mime, name)
            }
        }
    }

    AppScaffold(
        title = stringResource(R.string.compose),
        onNavigateBack = onBack,
        actions = {
            IconButton(onClick = { attachmentLauncher.launch("*/*") }) {
                IconAttachment()
            }
            IconButton(onClick = {
                // Prefer new photo picker
                try {
                    pickVisualLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    inlineFallbackLauncher.launch("image/*")
                }
            }) {
                com.vayunmathur.library.ui.IconImage()
            }
            Box {
                TextButton(onClick = { showSchedule = true }, enabled = fromAccount != null) {
                    Text(stringResource(R.string.later))
                }
                DropdownMenu(expanded = showSchedule, onDismissRequest = { showSchedule = false }) {
                    val schedule = { at: Long ->
                        showSchedule = false
                        fromAccount?.let { acc ->
                            viewModel.send.schedule(
                                account = acc, to = to, subject = subject,
                                body = bodyController.html,
                                asHtml = true,
                                cc = cc.ifBlank { null }, bcc = bcc.ifBlank { null },
                                attachments = attachments,
                                inlineImages = bodyController.toInlineAttachments(),
                                inReplyTo = inReplyTo,
                                references = references, scheduledAt = at,
                            ) { currentDraftId?.let { viewModel.draftsActions.delete(it) } }
                            AppMessages.show(resources.getString(R.string.scheduled))
                            onBack()
                        }
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_1_hour)) }, onClick = { schedule(System.currentTimeMillis() + 3_600_000L) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.this_evening_6_pm)) }, onClick = { schedule(scheduleTime(18, sameDay = true)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tomorrow_8_am)) }, onClick = { schedule(scheduleTime(8, sameDay = false)) })
                }
            }
            // Mirror securemail's ComposeScreen gate: Send needs at least
            // one recipient and a non-blank body (HTML stripped for the check).
            val hasRecipient = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank()
            val hasBody = bodyController.html.replace(Regex("<[^>]*>"), "").isNotBlank()
            IconButton(onClick = {
                val acc = fromAccount ?: return@IconButton
                sending = true
                viewModel.send.sendFrom(
                    account = acc,
                    to = to,
                    subject = subject,
                    body = bodyController.html,
                    asHtml = true,
                    cc = cc.ifBlank { null },
                    bcc = bcc.ifBlank { null },
                    attachments = attachments,
                    inlineImages = bodyController.toInlineAttachments(),
                    inReplyTo = inReplyTo,
                    references = references,
                    onSuccess = {
                        sending = false
                        currentDraftId?.let { viewModel.draftsActions.delete(it) }
                        AppMessages.show(resources.getString(R.string.message_sent))
                        onBack()
                    },
                    onError = { err ->
                        sending = false
                        AppMessages.show(resources.getString(R.string.saved_to_outbox, err))
                        onBack()
                    }
                )
            }, enabled = !sending && fromAccount != null && hasRecipient && hasBody) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else IconSend()
            }
        },
        bottomBar = {
            // Always show formatting toolbar? Per plan, only when focused, but now scrollable and more buttons
            if (bodyController.focused) {
                EmailComposerFormatToolbar(controller = bodyController,
                    onInsertImage = {
                        try {
                            pickVisualLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: Exception) {
                            inlineFallbackLauncher.launch("image/*")
                        }
                    })
            }
        },
    ) { padding ->
        ComposerForm(
            padding = padding,
            accounts = accounts,
            fromAccount = fromAccount,
            showAccountPicker = showAccountPicker,
            onOpenAccountPicker = { showAccountPicker = true },
            onDismissAccountPicker = { showAccountPicker = false },
            onSelectAccount = { fromAccount = it; showAccountPicker = false },
            to = to,
            onToChange = { to = it },
            cc = cc,
            onCcChange = { cc = it },
            bcc = bcc,
            onBccChange = { bcc = it },
            showCcBcc = showCcBcc,
            onToggleCcBcc = { showCcBcc = !showCcBcc },
            onPickContact = pickContact,
            subject = subject,
            onSubjectChange = { subject = it },
            draftId = draftId,
            bodyController = bodyController,
            context = context,
            attachments = attachments,
            onRemoveAttachment = { uri -> attachments = attachments - uri },
        )
    }
}
