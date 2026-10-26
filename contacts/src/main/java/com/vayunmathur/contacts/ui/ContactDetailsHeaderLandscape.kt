package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContent
import com.vayunmathur.library.util.sharedText

/**
 * Horizontal header for wide windows: avatar beside the name instead of stacked
 * above it, so the header fits in ~120dp of vertical space instead of ~230dp.
 * Same shared-element keys as the portrait stack, only arrangement differs.
 */
@Composable
fun ContactDetailsHeaderLandscape(
    contact: Contact,
    decodePhoto: ((String) -> android.graphics.Bitmap?)? = null,
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        ContactAvatar(
            contact = contact,
            decodePhoto = decodePhoto,
            modifier = Modifier
                .size(72.dp)
                .sharedContent("contact-avatar-${contact.id}"),
            initialsStyle = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.size(16.dp))
        ProfileNameColumn(contact, startAligned = true, modifier = Modifier.weight(1f))
    }
}

/** Name/nickname/company block shared by both header orientations. */
@Composable
internal fun ProfileNameColumn(
    contact: Contact,
    startAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Composed from the structured name rather than the joined display name, so that each part can
        // travel to the field that owns it when this page morphs into the editor. A single Text could
        // only ever land in one of them.
        val name = contact.name
        val parts = listOfNotNull(
            name.namePrefix.takeIf { it.isNotBlank() }?.let { "nameprefix" to it },
            name.firstName.takeIf { it.isNotBlank() }?.let { "firstname" to it },
            name.middleName.takeIf { it.isNotBlank() }?.let { "middlename" to it },
            name.lastName.takeIf { it.isNotBlank() }?.let { "lastname" to it },
            name.nameSuffix.takeIf { it.isNotBlank() }?.let { "namesuffix" to it },
        )
        FlowRow(
            // Full width so the arrangement always has room to centre within. Without it the row
            // shrinks to its content and relies on the parent Column to centre it, which silently
            // stops working once a name is wide enough to fill the width - the point at which each
            // wrapped line needs centring most.
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (startAligned) {
                Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.Start)
            } else {
                Arrangement.spacedBy(
                    6.dp,
                    androidx.compose.ui.Alignment.CenterHorizontally,
                )
            },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (slot, label) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.sharedText("contact-$slot-${contact.id}"),
                )
            }
        }
        if (contact.nickname.nickname.isNotBlank()) {
            Text(
                text = contact.nickname.nickname,
                style = MaterialTheme.typography.titleMedium,
                textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.sharedText("contact-nickname-${contact.id}"),
            )
        }
        Text(
            text = contact.org.company,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.sharedText("contact-company-${contact.id}")
        )
    }
}
