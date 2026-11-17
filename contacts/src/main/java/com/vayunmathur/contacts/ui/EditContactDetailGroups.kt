package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_MAX_EMAILS
import com.vayunmathur.contacts.data.SIM_MAX_PHONE_NUMBERS
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.FormDetailGroup
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

/**
 * Phone, email, and address [FormDetailGroup]s for the edit form.
 *
 * A contact always offers a mobile number and a home email, even when blank, so there is
 * always somewhere obvious to put the two values almost every contact has.
 *
 * SIM accounts store only a single name, number, and email, so they get one plain
 * phone field and one plain email field with no type selectors or add-more buttons.
 */
@Composable
fun EditContactDetailGroups(
    draft: ContactViewModel.ContactDraft,
    mobileIndex: Int,
    homeEmailIndex: Int,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
    isSimAccount: Boolean = false,
) {
    // The flag slot reserves leading space even when empty, so only offer it once at
    // least one number resolves to a country; otherwise local/empty fields gain a gap.
    // Hoisted above the SIM early-return so remember stays unconditional.
    val showCountryFlags = remember(draft.phoneNumbers) {
        draft.phoneNumbers.any { getCountryFlagEmoji(it.value).isNotBlank() }
    }
    if (isSimAccount) {
        // A SIM stores one number and one email; drop extras that arrived with a
        // draft seeded for a non-SIM account (e.g. account switched mid-edit).
        LaunchedEffect(draft.phoneNumbers.size, draft.emails.size) {
            if (draft.phoneNumbers.size > SIM_MAX_PHONE_NUMBERS ||
                draft.emails.size > SIM_MAX_EMAILS
            ) {
                onUpdate(
                    draft.copy(
                        phoneNumbers = draft.phoneNumbers.take(SIM_MAX_PHONE_NUMBERS),
                        emails = draft.emails.take(SIM_MAX_EMAILS),
                    )
                )
            }
        }
        SimContactFields(draft, onUpdate)
        return
    }
    val phoneCtx = LocalContext.current
    FormDetailGroup(
        items = draft.phoneNumbers,
        label = stringResource(R.string.phone),
        addLabel = stringResource(R.string.add_phone),
        typeOptions = listOf(CDKPhone.TYPE_MOBILE, CDKPhone.TYPE_HOME, CDKPhone.TYPE_WORK, CDKPhone.TYPE_OTHER, CDKPhone.TYPE_CUSTOM),
        value = { it.value },
        onValueChange = { idx, v -> onUpdate(draft.copy(phoneNumbers = draft.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withValue(v) })) },
        typeLabel = { it.typeString(phoneCtx) },
        optionLabel = { opt -> ContactDetail.default<PhoneNumber>().withType(opt).typeString(phoneCtx) },
        onTypeChange = { idx, opt -> onUpdate(draft.copy(phoneNumbers = draft.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withType(opt) })) },
        onRemove = { idx -> onUpdate(draft.copy(phoneNumbers = draft.phoneNumbers.toMutableList().also { l -> l.removeAt(idx) })) },
        onAdd = { onUpdate(draft.copy(phoneNumbers = draft.phoneNumbers + ContactDetail.default<PhoneNumber>())) },
        currentType = { it.type },
        keyboardType = KeyboardType.Phone,
        isCustom = { it.type == CDKPhone.TYPE_CUSTOM },
        customLabel = { it.label },
        onLabelChange = { idx, v -> onUpdate(draft.copy(phoneNumbers = draft.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) })) },
        customLabelText = stringResource(R.string.custom_label),
        customPlaceholder = stringResource(R.string.enter_custom_label),
        leadingIcon = if (showCountryFlags) {
            { item ->
                val flag = remember(item.value) { getCountryFlagEmoji(item.value) }
                if (flag.isNotBlank()) {
                    Text(flag)
                }
            }
        } else {
            null
        },
        addIcon = { IconCall() },
        // Pairs each field with the read-only row on the detail page. A row the user has just
        // added has no id yet and no counterpart, so it is left unkeyed.
        sharedKey = { it.id.takeIf { id -> id > 0 }?.let { id -> "contact-phone-$id" } },
        isMandatory = { it == mobileIndex },
        expandOnEnter = true,
    )

    val emailCtx = LocalContext.current
    FormDetailGroup(
        items = draft.emails,
        label = stringResource(UiR.string.email),
        addLabel = stringResource(R.string.add_email),
        typeOptions = listOf(CDKEmail.TYPE_HOME, CDKEmail.TYPE_WORK, CDKEmail.TYPE_OTHER, CDKEmail.TYPE_MOBILE, CDKEmail.TYPE_CUSTOM),
        value = { it.value },
        onValueChange = { idx, v -> onUpdate(draft.copy(emails = draft.emails.toMutableList().also { l -> l[idx] = l[idx].withValue(v) })) },
        typeLabel = { it.typeString(emailCtx) },
        optionLabel = { opt -> ContactDetail.default<Email>().withType(opt).typeString(emailCtx) },
        onTypeChange = { idx, opt -> onUpdate(draft.copy(emails = draft.emails.toMutableList().also { l -> l[idx] = l[idx].withType(opt) })) },
        onRemove = { idx -> onUpdate(draft.copy(emails = draft.emails.toMutableList().also { l -> l.removeAt(idx) })) },
        onAdd = { onUpdate(draft.copy(emails = draft.emails + ContactDetail.default<Email>())) },
        currentType = { it.type },
        keyboardType = KeyboardType.Email,
        isCustom = { it.type == CDKEmail.TYPE_CUSTOM },
        customLabel = { it.label },
        onLabelChange = { idx, v -> onUpdate(draft.copy(emails = draft.emails.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) })) },
        customLabelText = stringResource(R.string.custom_label),
        customPlaceholder = stringResource(R.string.enter_custom_label),
        addIcon = { IconMail() },
        sharedKey = { it.id.takeIf { id -> id > 0 }?.let { id -> "contact-email-$id" } },
        isMandatory = { it == homeEmailIndex },
        expandOnEnter = true,
    )

    val addressCtx = LocalContext.current
    FormDetailGroup(
        items = draft.addresses,
        label = stringResource(R.string.addresses),
        addLabel = stringResource(R.string.add_address),
        typeOptions = listOf(CDKStructuredPostal.TYPE_HOME, CDKStructuredPostal.TYPE_WORK, CDKStructuredPostal.TYPE_OTHER, CDKStructuredPostal.TYPE_CUSTOM),
        value = { it.value },
        onValueChange = { idx, v -> onUpdate(draft.copy(addresses = draft.addresses.toMutableList().also { l -> l[idx] = l[idx].withValue(v) })) },
        typeLabel = { it.typeString(addressCtx) },
        optionLabel = { opt -> ContactDetail.default<Address>().withType(opt).typeString(addressCtx) },
        onTypeChange = { idx, opt -> onUpdate(draft.copy(addresses = draft.addresses.toMutableList().also { l -> l[idx] = l[idx].withType(opt) })) },
        onRemove = { idx -> onUpdate(draft.copy(addresses = draft.addresses.toMutableList().also { l -> l.removeAt(idx) })) },
        onAdd = { onUpdate(draft.copy(addresses = draft.addresses + ContactDetail.default<Address>())) },
        currentType = { it.type },
        isCustom = { it.type == CDKStructuredPostal.TYPE_CUSTOM },
        customLabel = { it.label },
        onLabelChange = { idx, v -> onUpdate(draft.copy(addresses = draft.addresses.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) })) },
        customLabelText = stringResource(R.string.custom_label),
        customPlaceholder = stringResource(R.string.enter_custom_label),
        addIcon = { IconLocationOn() },
        expandOnEnter = true,
    )
}

/**
 * Single phone + email fields for SIM accounts. Plain [OutlinedTextField]s rather than
 * [FormDetailGroup]s: one value each, no type selector, no add-more button.
 */
@Composable
internal fun SimContactFields(
    draft: ContactViewModel.ContactDraft,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    val phone = draft.phoneNumbers.take(SIM_MAX_PHONE_NUMBERS).firstOrNull()
    val email = draft.emails.take(SIM_MAX_EMAILS).firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = phone?.value.orEmpty(),
            onValueChange = { v ->
                val updated = if (phone != null) {
                    draft.phoneNumbers.toMutableList().also { l -> l[0] = l[0].withValue(v) }
                } else {
                    listOf(ContactDetail.default<PhoneNumber>().withType(CDKPhone.TYPE_MOBILE).withValue(v))
                }
                onUpdate(draft.copy(phoneNumbers = updated))
            },
            label = { Text(stringResource(R.string.phone)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email?.value.orEmpty(),
            onValueChange = { v ->
                val updated = if (email != null) {
                    draft.emails.toMutableList().also { l -> l[0] = l[0].withValue(v) }
                } else {
                    listOf(ContactDetail.default<Email>().withType(CDKEmail.TYPE_HOME).withValue(v))
                }
                onUpdate(draft.copy(emails = updated))
            },
            label = { Text(stringResource(UiR.string.email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun getCountryFlagEmoji(phoneNumber: String): String {
    if (phoneNumber.isBlank()) return ""
    val phoneUtil = PhoneNumberUtil.getInstance()
    return try {
        val numberProto = phoneUtil.parse(phoneNumber, "")
        val regionCode = phoneUtil.getRegionCodeForNumber(numberProto) ?: return ""
        if (regionCode.length < 2) return ""
        val firstLetter = Character.codePointAt(regionCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(regionCode, 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (_: Exception) {
        ""
    }
}
