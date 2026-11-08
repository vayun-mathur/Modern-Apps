package com.vayunmathur.contacts.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.util.VcfUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Display name with optional nickname suffix (e.g. "Jane Doe (Janey)"). */
@Composable
fun contactDisplayName(contact: Contact): String =
    if (contact.nickname.value.isNotBlank())
        stringResource(R.string.name_nickname_format, contact.name.value, contact.nickname.value)
    else contact.name.value

/** Groups (with non-blank names) that [contact] belongs to, from [allGroups]. */
fun contactGroupsOf(contact: Contact, allGroups: List<ContactGroup>): List<ContactGroup> =
    allGroups.filter { group ->
        contact.details.groups.any { it.groupId == group.id } && group.name.trim().isNotEmpty()
    }

/**
 * Circular avatar: decoded photo (off the main thread) or colored initials fallback.
 *
 * [decodePhoto] is the ViewModel's cached decoder, passed as a function so this stays
 * usable from a stateless screen. Only the photo keys the decode — the decoder itself is
 * effectively constant, and keying on it would restart the decode on every recomposition.
 */
@Composable
fun ContactAvatar(
    contact: Contact,
    decodePhoto: ((String) -> Bitmap?)?,
    modifier: Modifier = Modifier,
    initialsStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    /** Overridable so a caller can morph it - see the press feedback on a contact row. */
    shape: Shape = CircleShape,
) {
    val photoBase64 = contact.photo?.photo
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, key1 = photoBase64) {
        value = if (photoBase64 != null) {
            withContext(Dispatchers.IO) { decodePhoto?.invoke(photoBase64) }
        } else null
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        val bmp = avatarBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.contact_photo_description, contact.name.value),
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(getAvatarColor(contact.id), shape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.value.firstOrNull()?.uppercase() ?: "",
                    color = Color.White,
                    style = initialsStyle,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Exports [contacts] to a VCF cache file and fires a share chooser. Runs I/O on [scope]. */
fun shareContactsAsVcf(
    scope: CoroutineScope,
    context: Context,
    contacts: List<Contact>,
    filename: String,
    chooserTitle: String,
    groupNames: Map<Long, String> = emptyMap(),
) {
    scope.launch(Dispatchers.IO) {
        val vcfFile = File(context.cacheDir, filename)
        vcfFile.outputStream().use { out -> VcfUtils.exportContacts(contacts, out, groupNames) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", vcfFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ExternalIntents.launch(context, Intent.createChooser(intent, chooserTitle))
    }
}
