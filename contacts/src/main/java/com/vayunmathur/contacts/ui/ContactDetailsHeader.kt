package com.vayunmathur.contacts.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CommAction
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.CommPrefResolver
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactPlatforms
import com.vayunmathur.contacts.platform.PackageUtils
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.sharedContent

@Composable
fun ProfileHeader(contact: Contact, decodePhoto: ((String) -> android.graphics.Bitmap?)? = null) {
    // Wide windows (landscape phones, tablets) put avatar beside the name so the
    // header fits above the fold; portrait keeps the centered stack verbatim.
    if (isExpandedWidth() || isCompactHeight()) {
        ContactDetailsHeaderLandscape(contact, decodePhoto)
        return
    }
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        // No whole-block shared key. The avatar, each name part and the company pair up individually
        // with their counterparts in the list row and in the editor, and a container morph on top of
        // those would drag the same content to a second destination at the same time.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        ContactAvatar(
            contact = contact,
            decodePhoto = decodePhoto,
            modifier = Modifier
                .size(100.dp)
                .sharedContent("contact-avatar-${contact.id}"),
            initialsStyle = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.size(16.dp))

        // Composed from the structured name rather than the joined display name, so that each part can
        // travel to the field that owns it when this page morphs into the editor. A single Text could
        // only ever land in one of them.
        ProfileNameColumn(contact, startAligned = false)
    }
}
