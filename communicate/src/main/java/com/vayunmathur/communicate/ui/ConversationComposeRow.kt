package com.vayunmathur.communicate.ui

import androidx.core.net.toUri
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

@Composable
internal fun ComposeSmsRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<CommunicateAttachment>,
    onAttachMedia: () -> Unit,
    onAttachFile: () -> Unit,
    /** Null when the line has no polls, so the option is not offered rather than failing. */
    onCreatePoll: (() -> Unit)?,
    onShareContact: (() -> Unit)?,
    onRemoveAttachment: (CommunicateAttachment) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { attachment ->
                        SelectedAttachmentPreview(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.vayunmathur.library.ui.OverflowMenu(icon = { IconAttachment() }) {
                    Item(
                        text = stringResource(R.string.attach_media),
                        leadingIcon = { com.vayunmathur.library.ui.IconImage() },
                        onClick = onAttachMedia,
                    )
                    Item(
                        text = stringResource(R.string.attach_file),
                        leadingIcon = { IconAttachment() },
                        onClick = onAttachFile,
                    )
                    onCreatePoll?.let { create ->
                        Item(
                            text = stringResource(R.string.attach_poll),
                            leadingIcon = { com.vayunmathur.library.ui.IconPoll() },
                            onClick = create,
                        )
                    }
                    onShareContact?.let { share ->
                        Item(
                            text = stringResource(R.string.attach_contact),
                            leadingIcon = { com.vayunmathur.library.ui.IconPerson() },
                            onClick = share,
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = onSend, enabled = draft.isNotBlank() || attachments.isNotEmpty()) {
                    IconSend()
                }
            }
        }
    }
}

@Composable
internal fun SelectedAttachmentPreview(
    attachment: CommunicateAttachment,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 160.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.mimeType.startsWith("image/")) {
                AsyncImage(
                    model = attachment.contentUri.toUri(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                IconAttachment()
            }
            Spacer(Modifier.size(6.dp))
            Text(
                attachment.mimeType,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onRemove) {
                IconClose(Modifier.size(18.dp))
            }
        }
    }
}
