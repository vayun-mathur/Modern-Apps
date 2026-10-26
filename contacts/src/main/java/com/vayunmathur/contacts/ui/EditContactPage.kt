package com.vayunmathur.contacts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.initEditDraft
import com.vayunmathur.contacts.util.loadLastSelectedAccount
import com.vayunmathur.contacts.util.saveEditDraft
import com.vayunmathur.contacts.util.setLastSelectedAccount
import com.vayunmathur.contacts.util.updateEditDraft
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactPage(backStack: NavBackStack<Route>, viewModel: ContactViewModel, editRoute: Route.EditContact, onExit: () -> Unit = { backStack.pop() }) {
    val contactId = editRoute.contactId
    val context = LocalContext.current
    var saveError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val isNewContact = contactId == null

    // Initialize the VM draft for this contact. No-op on rotation (same key).
    LaunchedEffect(contactId) {
        // Reload the persisted save location first: after a process kill the
        // in-memory last-selected account is null, and seeding the draft before
        // this runs falls back to on-device despite the stored choice.
        if (isNewContact) {
            viewModel.loadLastSelectedAccount()
        }
        viewModel.initEditDraft(
            contactId = contactId,
            prefill = editRoute.prefill,
        )
    }
    val draft by viewModel.editDraft.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val simLabels by viewModel.simAccountLabels.collectAsStateWithLifecycle()

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val encoded = java.net.URLEncoder.encode(uri.toString(), "UTF-8")
            backStack.add(Route.CropPhoto(encoded))
        }
    }

    val currentDraft = draft ?: return
    val isSimAccount = currentDraft.accountType == SIM_ACCOUNT_TYPE
    // Markdown note editor backed by the shared ODF editor; stored content stays markdown.
    val noteController = key(contactId) {
        com.vayunmathur.library.ui.rememberOdfMarkdownEditorController(initialMarkdown = currentDraft.noteContent) { content ->
            viewModel.updateEditDraft { it.copy(noteContent = content) }
        }
    }
    DetailScaffold(
        title = if (isNewContact) stringResource(R.string.add_contact) else stringResource(R.string.edit_contact),
        onClose = { onExit() },
        actions = {
            Button(onClick = {
                if (isSaving) return@Button
                isSaving = true
                viewModel.saveEditDraft { ok, err ->
                    isSaving = false
                    if (ok) onExit() else saveError = err ?: context.getString(R.string.save_failed)
                }
            }) {
                Text(stringResource(UiR.string.save))
            }
        },
        bottomBar = {
            if (noteController.focused) {
                // EditorBottomBar already claims navigationBars ∪ ime itself —
                // no extra imePadding here or the bar floats 2x above the keys.
                com.vayunmathur.library.ui.OdfMarkdownEditorToolbar(noteController)
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        // Short (landscape) windows collapse the fixed spacers so the ~2000dp form
        // needs less scrolling; portrait keeps every gap verbatim.
        val gap = if (isCompactHeight()) 8.dp else 16.dp
        val smallGap = if (isCompactHeight()) 4.dp else 8.dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (saveError != null) {
                Text(saveError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(smallGap))
            }

            // Account chooser for new contacts (SIM accounts appear here as normal accounts)
            if (isNewContact) {
                EditContactAccountSection(currentDraft, accounts, simLabels) { name, type ->
                    viewModel.updateEditDraft { it.copy(accountName = name, accountType = type) }
                    viewModel.setLastSelectedAccount(name, type)
                }
                Spacer(Modifier.height(gap))
                if (isSimAccount) {
                    Text(
                        stringResource(R.string.sim_limited_fields_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(smallGap))
                }
            } else if (isSimAccount) {
                Text(
                    stringResource(R.string.sim_limited_fields_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(smallGap))
            }

            Spacer(Modifier.height(smallGap))
            if (!isSimAccount) {
                EditContactPhotoSection(
                    photo = currentDraft.photo?.photo,
                    viewModel = viewModel,
                    onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    removePhoto = {
                        viewModel.updateEditDraft { it.copy(photo = null) }
                    },
                    sharedKey = editRoute.contactId,
                )
                Spacer(Modifier.height(if (isCompactHeight()) 12.dp else 24.dp))
            }


            // Plain Column rather than FormSection: DetailScaffold already insets its content
            // horizontally, and the section's own padding on top of that made the name fields
            // narrower than every field below them.
            val id = editRoute.contactId
            EditContactNameSection(
                draft = currentDraft,
                contactId = id,
                isSimAccount = isSimAccount,
                onUpdate = { viewModel.updateEditDraft { _ -> it } },
            )

            // A contact always offers a mobile number and a home email, even when blank, so there is
            // always somewhere obvious to put the two values almost every contact has. Seeded here
            // rather than in the draft so it also applies to contacts saved before this existed.
            val mobileIndex = currentDraft.phoneNumbers.indexOfFirst { it.type == CDKPhone.TYPE_MOBILE }
            val homeEmailIndex = currentDraft.emails.indexOfFirst { it.type == CDKEmail.TYPE_HOME }
            LaunchedEffect(mobileIndex, homeEmailIndex) {
                if (mobileIndex < 0) {
                    viewModel.updateEditDraft {
                        it.copy(
                            phoneNumbers = it.phoneNumbers +
                                ContactDetail.default<PhoneNumber>().withType(CDKPhone.TYPE_MOBILE)
                        )
                    }
                }
                if (homeEmailIndex < 0) {
                    viewModel.updateEditDraft {
                        it.copy(
                            emails = it.emails +
                                ContactDetail.default<Email>().withType(CDKEmail.TYPE_HOME)
                        )
                    }
                }
            }

            Spacer(Modifier.height(gap))
            EditContactDetailGroups(
                draft = currentDraft,
                mobileIndex = mobileIndex,
                homeEmailIndex = homeEmailIndex,
                onUpdate = { viewModel.updateEditDraft { _ -> it } },
                isSimAccount = isSimAccount,
            )
            Spacer(Modifier.height(smallGap))

            // Preferred call/message method: SIM ADN rows have no Data rows to keep it.
            if (!isSimAccount) {
                EditContactCommPrefSection(
                    draft = currentDraft,
                    contactId = editRoute.contactId,
                    onUpdate = { viewModel.updateEditDraft { _ -> it } },
                )
                Spacer(Modifier.height(gap))
            }

            if (!isSimAccount) {
                Spacer(Modifier.height(gap))
                EditContactBirthday(backStack, currentDraft.birthday) { v ->
                    viewModel.updateEditDraft { it.copy(birthday = v) }
                }
                EditContactDateDetails(
                    backStack = backStack,
                    details = currentDraft.dates,
                    onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(dates = list) } },
                    icon = { DateDetailsAddIcon() },
                    options = listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER, CDKEvent.TYPE_CUSTOM)
                )
                Spacer(Modifier.height(gap))
                EditContactGenderSection(
                    draft = currentDraft,
                    contactId = editRoute.contactId,
                    onUpdate = { viewModel.updateEditDraft { _ -> it } },
                )
                Spacer(Modifier.height(gap))
                Text(
                    text = stringResource(UiR.string.note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                com.vayunmathur.library.ui.OdfMarkdownEditorField(
                    controller = noteController,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                Spacer(Modifier.height(gap))

                // Last, to match the detail page. Anything on both pages is in the same order on both, so
                // a value does not have to be hunted for after the morph, and nothing has to travel past
                // the whole form to reach its counterpart.
                val allGroups by viewModel.groups.collectAsStateWithLifecycle()
                EditContactGroupSection(
                    draft = currentDraft,
                    allGroups = allGroups,
                    contactId = editRoute.contactId,
                    onUpdate = { viewModel.updateEditDraft { _ -> it } },
                )
                Spacer(Modifier.height(gap))
            }

            Spacer(Modifier.height(gap))
        }
    }
}
