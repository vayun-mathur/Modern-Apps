package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.expandFromLine

/**
 * Gender picker for the edit form: a read-only field opening a dropdown
 * (Female/Male/Other/Clear), plus a custom-text field while Other is picked.
 *
 * Shaped like [EditContactBirthday] (read-only field + trailing action + full-row
 * tap target); the menu itself mirrors [NameAffixChooser] (localized options, a
 * clear entry that stores empty rather than the word "None").
 */
@Composable
fun EditContactGenderSection(
    draft: ContactViewModel.ContactDraft,
    contactId: Long?,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    val options = stringArrayResource(R.array.genders).toList()
    val codes = listOf(Gender.FEMALE, Gender.MALE, Gender.OTHER)
    val currentLabel = codes.indexOf(draft.genderCode)
        .takeIf { it >= 0 }?.let { options.getOrNull(it) }.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(UiR.string.none)

    fun select(code: String) {
        onUpdate(
            draft.copy(
                genderCode = code,
                genderText = if (code == Gender.OTHER) draft.genderText else "",
            )
        )
        expanded = false
    }

    Column(Modifier.fillMaxWidth()) {
        // The menu lives in the field's Box so it anchors to the field, mirroring
        // the button-anchored menu in NameAffixChooser.
        Box {
            LabeledTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                label = stringResource(R.string.gender),
                sharedTextKey = contactId?.let { "contact-gender-$it" },
                modifier = Modifier.fillMaxWidth().expandFromLine(),
                trailingIcon = {
                    // Clear is offered inside the menu; the trailing button only opens it.
                    // A RemoveCircle here would fight the menu's own clear entry.
                    IconButton(onClick = { expanded = true }) {
                        IconArrowDropDown()
                    }
                },
            )
            // The field is read-only and opens the menu, so the whole row is the tap
            // target - except the trailing button, which needs its own clicks.
            Row(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { expanded = true }
                )
                Spacer(Modifier.width(GenderMenuButtonWidth))
            }
            DropdownMenu(expanded, { expanded = false }) {
                (listOf(none) + options).forEach { option ->
                    val code = if (option == none) "" else codes.getOrNull(options.indexOf(option)).orEmpty()
                    SelectableDropdownMenuItem(
                        selected = draft.genderCode == code,
                        onClick = { select(code) },
                        text = { Text(option) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }
        if (draft.genderCode == Gender.OTHER) {
            Spacer(Modifier.height(12.dp))
            LabeledTextField(
                value = draft.genderText,
                onValueChange = { v -> onUpdate(draft.copy(genderText = v)) },
                label = stringResource(R.string.gender_custom_hint),
                sharedTextKey = contactId?.let { "contact-gender-text-$it" },
                modifier = Modifier.fillMaxWidth().expandFromLine(),
                trailingIcon = {
                    if (draft.genderText.isNotBlank()) {
                        IconButton(onClick = { onUpdate(draft.copy(genderText = "")) }) {
                            IconRemoveCircle()
                        }
                    }
                },
            )
        }
    }
}

private val GenderMenuButtonWidth = 48.dp

/**
 * Localized display for a stored gender: the picker label, with the custom
 * free text in parentheses for Other. Shared by the edit field and the detail
 * row so both pages agree on the wording.
 */
internal fun genderDisplayText(code: String, text: String, options: List<String>): String {
    val label = when (code) {
        Gender.FEMALE -> options.getOrNull(0)
        Gender.MALE -> options.getOrNull(1)
        Gender.OTHER -> options.getOrNull(2)
        else -> null
    }.orEmpty()
    if (label.isBlank()) return text.trim()
    return if (code == Gender.OTHER && text.isNotBlank()) "$label (${text.trim()})" else label
}
