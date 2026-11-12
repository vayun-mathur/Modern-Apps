package com.vayunmathur.contacts.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.SimDroppedField
import com.vayunmathur.contacts.util.computeSimLoss
import com.vayunmathur.contacts.util.transferContacts
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Hosts [MoveToAccountDialog] for [contactIds]: resolves source accounts and
 * SIM data-loss, runs the transfer, and reports the result via messenger.
 * [onTransferred] fires when at least one contact moved (before [onDismiss]).
 */
@Composable
fun MoveDialogHost(
    viewModel: ContactViewModel,
    contactIds: List<Long>,
    onDismiss: () -> Unit,
    onTransferred: () -> Unit = {},
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val simLabels by viewModel.simAccountLabels.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val restriction by viewModel.newContactRestriction.collectAsStateWithLifecycle()

    val toMove = remember(contacts, contactIds) {
        contacts.filter { it.id in contactIds }
    }
    var selectedAccount by remember(contactIds) { mutableStateOf<ContactAccount?>(null) }

    val sourceAccounts = remember(toMove) {
        toMove.mapTo(mutableSetOf()) {
            ContactAccount(it.accountName.orEmpty(), it.accountType.orEmpty())
        }
    }
    val isSimDest = selectedAccount?.let { isSimAccountType(it.type) } == true
    val simLoss = remember(toMove, isSimDest) {
        if (!isSimDest) emptyList()
        else toMove.flatMapTo(mutableSetOf()) { computeSimLoss(it) }.toList()
    }

    MoveToAccountDialog(
        contactCount = toMove.size.coerceAtLeast(1),
        accounts = accounts,
        sourceAccounts = sourceAccounts,
        simLabels = simLabels,
        selectedAccount = selectedAccount,
        simLossLabels = simLoss.map { it.localized() },
        restriction = restriction,
        onSelectAccount = { selectedAccount = it },
        onClearSelection = { selectedAccount = null },
        onConfirm = {
            val dest = selectedAccount ?: return@MoveToAccountDialog
            val ids = toMove.map { it.id }.ifEmpty { contactIds }
            // Set when the provider refused the destination (#916); explains the failure.
            var rejection: String? = null
            viewModel.transferContacts(ids, dest.name, dest.type, onRejected = { rejection = it }) { moved, failed, _ ->
                // Resources (not composable stringResource): this callback runs
                // outside composition on the main thread.
                val message = when {
                    moved > 0 && failed == 0 ->
                        context.resources.getQuantityString(R.plurals.move_success, moved, moved)
                    moved > 0 ->
                        context.getString(R.string.move_partial_failure, moved, failed)
                    else -> rejection ?: context.getString(R.string.move_failed)
                }
                messenger.show(message)
                if (moved > 0) onTransferred()
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SimDroppedField.localized(): String = stringResource(
    when (this) {
        SimDroppedField.PHOTO -> R.string.move_dropped_photo
        SimDroppedField.EXTRA_PHONES -> R.string.move_dropped_extra_phones
        SimDroppedField.EXTRA_EMAILS -> R.string.move_dropped_extra_emails
        SimDroppedField.ADDRESSES -> R.string.move_dropped_addresses
        SimDroppedField.DATES -> R.string.move_dropped_dates
        SimDroppedField.ORGANIZATION -> R.string.move_dropped_organization
        SimDroppedField.NOTE -> R.string.move_dropped_note
        SimDroppedField.NICKNAME -> R.string.move_dropped_nickname
        SimDroppedField.GENDER -> R.string.move_dropped_gender
        SimDroppedField.COMM_PREF -> R.string.move_dropped_comm_pref
        SimDroppedField.GROUPS -> R.string.move_dropped_groups
        SimDroppedField.RINGTONE -> R.string.move_dropped_ringtone
        SimDroppedField.FAVORITE -> R.string.move_dropped_favorite
    }
)
