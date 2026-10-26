package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.expandFromLine
import com.vayunmathur.library.util.sharedContainer

/**
 * Name fields for the edit form.
 *
 * Non-SIM accounts get split prefix/first/middle/last/suffix plus nickname and company.
 * SIM ADN rows store a single name, so SIM accounts get one joint name field instead.
 *
 * One key per name part, so each piece of the header lands in the field that owns it. These
 * go to sharedTextKey, not to a modifier: keyed on the field the header's text would grow
 * to the whole box and then snap: keyed on the inner text, text pairs with text.
 * The SIM joint field reuses the firstname key so the header's single name part morphs into it.
 */
@Composable
fun EditContactNameSection(
    draft: ContactViewModel.ContactDraft,
    contactId: Long?,
    isSimAccount: Boolean,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isSimAccount) {
            val jointName = listOfNotNull(
                draft.namePrefix.ifEmpty { null },
                draft.firstName.ifEmpty { null },
                draft.middleName.ifEmpty { null },
                draft.lastName.ifEmpty { null },
                draft.nameSuffix.ifEmpty { null },
            ).joinToString(" ")
            LabeledTextField(
                value = jointName,
                onValueChange = { v ->
                    onUpdate(
                        draft.copy(
                            namePrefix = "",
                            firstName = v,
                            middleName = "",
                            lastName = "",
                            nameSuffix = "",
                        )
                    )
                },
                label = stringResource(R.string.name),
                keyboardType = KeyboardType.PersonName,
                sharedTextKey = contactId?.let { "contact-firstname-$it" },
                modifier = Modifier.fillMaxWidth().expandFromLine(),
            )
            return@Column
        }
        // Expanded tablets get two name fields per row; phones (including landscape,
        // where the two-pane detail is narrow) keep the single-column stack verbatim.
        if (isExpandedWidth()) {
            EditContactNameGrid(draft, contactId, onUpdate)
            return@Column
        }
        LabeledTextField(
            value = draft.firstName,
            onValueChange = { v -> onUpdate(draft.copy(firstName = v)) },
            label = stringResource(R.string.first_name),
            keyboardType = KeyboardType.PersonName,
            sharedTextKey = contactId?.let { "contact-firstname-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
            leadingIcon = {
                NamePrefixChooser(draft.namePrefix, null) { v ->
                    onUpdate(draft.copy(namePrefix = v))
                }
            },
        )
        LabeledTextField(
            value = draft.middleName,
            onValueChange = { v -> onUpdate(draft.copy(middleName = v)) },
            label = stringResource(R.string.middle_name),
            keyboardType = KeyboardType.PersonName,
            sharedTextKey = contactId?.let { "contact-middlename-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
        )
        LabeledTextField(
            value = draft.lastName,
            onValueChange = { v -> onUpdate(draft.copy(lastName = v)) },
            label = stringResource(R.string.last_name),
            keyboardType = KeyboardType.PersonName,
            sharedTextKey = contactId?.let { "contact-lastname-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
            trailingIcon = {
                NameSuffixChooser(draft.nameSuffix, null) { v ->
                    onUpdate(draft.copy(nameSuffix = v))
                }
            },
        )
        LabeledTextField(
            value = draft.nickname,
            onValueChange = { v -> onUpdate(draft.copy(nickname = v)) },
            label = stringResource(R.string.nickname),
            sharedTextKey = contactId?.let { "contact-nickname-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
        )
        LabeledTextField(
            value = draft.company,
            onValueChange = { v -> onUpdate(draft.copy(company = v)) },
            label = stringResource(R.string.company),
            sharedTextKey = contactId?.let { "contact-company-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
        )
    }
}

/**
 * Two-column name grid for expanded windows: first/middle, last/nickname, company
 * full-width. Same fields, keys and order as the portrait stack — only paired.
 */
@Composable
private fun EditContactNameGrid(
    draft: ContactViewModel.ContactDraft,
    contactId: Long?,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledTextField(
                value = draft.firstName,
                onValueChange = { v -> onUpdate(draft.copy(firstName = v)) },
                label = stringResource(R.string.first_name),
                keyboardType = KeyboardType.PersonName,
                sharedTextKey = contactId?.let { "contact-firstname-$it" },
                modifier = Modifier.weight(1f).expandFromLine(),
                leadingIcon = {
                    NamePrefixChooser(draft.namePrefix, null) { v ->
                        onUpdate(draft.copy(namePrefix = v))
                    }
                },
            )
            LabeledTextField(
                value = draft.middleName,
                onValueChange = { v -> onUpdate(draft.copy(middleName = v)) },
                label = stringResource(R.string.middle_name),
                keyboardType = KeyboardType.PersonName,
                sharedTextKey = contactId?.let { "contact-middlename-$it" },
                modifier = Modifier.weight(1f).expandFromLine(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledTextField(
                value = draft.lastName,
                onValueChange = { v -> onUpdate(draft.copy(lastName = v)) },
                label = stringResource(R.string.last_name),
                keyboardType = KeyboardType.PersonName,
                sharedTextKey = contactId?.let { "contact-lastname-$it" },
                modifier = Modifier.weight(1f).expandFromLine(),
                trailingIcon = {
                    NameSuffixChooser(draft.nameSuffix, null) { v ->
                        onUpdate(draft.copy(nameSuffix = v))
                    }
                },
            )
            LabeledTextField(
                value = draft.nickname,
                onValueChange = { v -> onUpdate(draft.copy(nickname = v)) },
                label = stringResource(R.string.nickname),
                sharedTextKey = contactId?.let { "contact-nickname-$it" },
                modifier = Modifier.weight(1f).expandFromLine(),
            )
        }
        LabeledTextField(
            value = draft.company,
            onValueChange = { v -> onUpdate(draft.copy(company = v)) },
            label = stringResource(R.string.company),
            sharedTextKey = contactId?.let { "contact-company-$it" },
            modifier = Modifier.fillMaxWidth().expandFromLine(),
        )
    }
}

/**
 * The prefix/suffix picker that sits inside the first/last name fields.
 *
 * Formatted like the type picker on a phone or email row - a borderless button with its current value
 * and a caret - because that is what it is: a small enumerated choice attached to a field. It used to
 * be an [AssistChip], which read as a tappable object floating in the field rather than as part of it.
 * [placeholder] is shown while nothing is chosen, since an empty label reads as a stray caret.
 */
@Composable
internal fun NameAffixChooser(
    value: String,
    placeholder: String,
    options: List<String>,
    sharedKey: Any?,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(UiR.string.none)
    androidx.compose.foundation.layout.Box(
        if (sharedKey == null) Modifier else Modifier.sharedContainer(sharedKey)
    ) {
        TextButton(onClick = { expanded = true }) {
            Text(value.ifEmpty { placeholder })
            IconArrowDropDown()
        }
        DropdownMenu(expanded, { expanded = false }) {
            // The clear option is listed by its localized name but stores an empty affix, so
            // the contact does not end up with the word "None" as its title.
            (listOf(none) + options).forEach { option ->
                val affix = if (option == none) "" else option
                SelectableDropdownMenuItem(
                    selected = value == affix,
                    onClick = {
                        onValueChange(affix)
                        expanded = false
                    },
                    text = { Text(option) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
