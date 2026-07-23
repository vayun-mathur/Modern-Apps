package com.vayunmathur.messages.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.util.ContactSuggestion
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.NewMediaPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose-new screen.
 *
 * Used for three entry points:
 *  1. Inbox FAB → empty form, user types a recipient + body.
 *  2. `smsto:` deep link → recipient prefilled, body optionally prefilled.
 *  3. Share-sheet target → body prefilled (text), media URIs prefilled.
 *
 * Source selection (Voice vs Messages) follows the rules the user
 * confirmed during planning:
 *  - Selected recipient phone has an existing thread on exactly one
 *    source → that source is auto-picked, segmented control hidden.
 *  - 0 or 2 existing sources → user must pick.
 *
 * When the user hits Send, we navigate into the resulting thread (so
 * back goes to inbox, not back to this screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
    db: MessagesDatabase,
    initialNumber: String?,
    initialBody: String?,
    initialMediaUris: List<Uri>,
    initialMime: String?,
    initialSource: MessageSource? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Recipient state. The selected recipient is the one chip we
    // commit to; the query field is what the user is currently typing.
    var query by remember { mutableStateOf(initialNumber.orEmpty()) }
    var selectedRecipient by remember { mutableStateOf<ContactSuggestion?>(null) }
    val suggestions = remember { mutableStateListOf<ContactSuggestion>() }
    // 150 ms debounce on the search. When initialSource is set, only
    // search device contacts (no server-side contact discovery).
    LaunchedEffect(query) {
        delay(150)
        suggestions.clear()
        if (initialSource != null) {
            suggestions.addAll(vm.searchDeviceContacts(query))
        } else {
            suggestions.addAll(vm.searchContacts(query))
        }
    }

    // Source selection state. When initialSource is set (from the FAB
    // source picker), skip the resolve and lock to that source.
    var availableSources by remember { mutableStateOf<Set<MessageSource>>(emptySet()) }
    var chosenSource by remember { mutableStateOf<MessageSource?>(initialSource) }
    LaunchedEffect(selectedRecipient) {
        if (initialSource != null) {
            chosenSource = initialSource
            return@LaunchedEffect
        }
        val phone = selectedRecipient?.phoneE164 ?: return@LaunchedEffect
        availableSources = vm.resolveSourcesForNumber(phone)
        chosenSource = when {
            availableSources.size == 1 -> availableSources.single()
            selectedRecipient?.source != null && availableSources.isEmpty() ->
                selectedRecipient?.source
            else -> null
        }
    }

    // Body + media state.
    var draft by remember { mutableStateOf(initialBody.orEmpty()) }
    val media = remember { mutableStateListOf<Uri>().apply { addAll(initialMediaUris) } }
    var sending by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(media::add) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New conversation") },
                navigationIcon = { IconNavigation(backStack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // Recipient row: chip (when selected) OR live search field.
            if (selectedRecipient != null) {
                SelectedRecipientChip(
                    recipient = selectedRecipient!!,
                    onClear = {
                        selectedRecipient = null
                        availableSources = emptySet()
                        chosenSource = null
                    },
                )
                // Source picker — hidden when initialSource is locked.
                if (initialSource != null) {
                    Text(
                        "Sending via ${labelFor(initialSource)}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    SourcePicker(
                        available = availableSources,
                        chosen = chosenSource,
                        onChange = { chosenSource = it },
                    )
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Name or phone number") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(
                        suggestions.distinctBy { (it.phoneE164 ?: it.displayName) + "|" + (it.source?.name ?: "device") },
                        key = { (it.phoneE164 ?: it.displayName) + "|" + (it.source?.name ?: "device") },
                    ) { sug ->
                        SuggestionRow(sug) {
                            selectedRecipient = sug
                            query = sug.displayName
                        }
                    }
                }
            }

            // Media preview row — horizontal scroller of thumbs.
            if (media.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(media.toList(), key = { it.toString() }) { uri ->
                        MediaThumb(uri = uri, onRemove = { media.remove(uri) })
                    }
                }
            }

            if (selectedRecipient != null) {
                Spacer(Modifier.weight(1f))

                // Compose row reuses the conversation-screen pattern.
                ComposeRow(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sending = sending,
                    onSend = {
                        val source = chosenSource
                        val recipient = selectedRecipient?.phoneE164 ?: query.trim().takeIf { it.isNotEmpty() }
                        when {
                            recipient == null -> scope.launch {
                                snackbar.showSnackbar("Pick a recipient first")
                            }
                            source == null -> scope.launch {
                                snackbar.showSnackbar("Choose a source (Voice or Messages)")
                            }
                            // Voice supports image MMS only. Anything else
                            // (video / audio / file) goes through gmessages
                            // only. We check the first attachment's mime to
                            // decide; mixed attachments are out of scope.
                            else -> scope.launch {
                                val mediaPart = media.firstOrNull()?.let { vm.readUri(it) }
                                if (source == MessageSource.VOICE &&
                                    mediaPart != null &&
                                    !mediaPart.mime.startsWith("image/")
                                ) {
                                    snackbar.showSnackbar(
                                        "Voice supports image MMS only — pick Messages, or remove the attachment."
                                    )
                                    return@launch
                                }
                                sending = true
                                vm.sendNewMessage(
                                    source = source,
                                    recipients = listOf(recipient),
                                    body = draft.trim().takeIf { it.isNotEmpty() },
                                    media = mediaPart,
                                ) { newConvId ->
                                    sending = false
                                    if (newConvId == null) {
                                        scope.launch { snackbar.showSnackbar("Send failed") }
                                    } else {
                                        backStack.pop()
                                        backStack.add(Route.Conversation(newConvId))
                                    }
                                }
                            }
                        }
                    },
                    onAttach = {
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(
    available: Set<MessageSource>,
    chosen: MessageSource?,
    onChange: (MessageSource) -> Unit,
) {
    // Hide entirely when the source is forced (exactly one existing
    // thread). Show a tiny "via …" label instead so the user knows.
    if (available.size == 1) {
        val s = available.single()
        Text(
            "Sending via ${labelFor(s)}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    val options = MessageSource.values().toList()
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        options.forEachIndexed { idx, source ->
            SegmentedButton(
                selected = chosen == source,
                onClick = { onChange(source) },
                shape = SegmentedButtonDefaults.itemShape(idx, options.size),
            ) {
                Text(labelFor(source))
            }
        }
    }
}

@Composable
private fun SelectedRecipientChip(
    recipient: ContactSuggestion,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            if (recipient.avatarUrl != null) {
                AsyncImage(
                    model = recipient.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        recipient.displayName.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(recipient.displayName, fontWeight = FontWeight.SemiBold)
            recipient.phoneE164?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onClear) { IconClose() }
    }
}

@Composable
private fun SuggestionRow(suggestion: ContactSuggestion, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(32.dp),
        ) {
            if (suggestion.avatarUrl != null) {
                AsyncImage(
                    model = suggestion.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(suggestion.displayName.take(1).uppercase(), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(suggestion.displayName)
            suggestion.phoneE164?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        suggestion.source?.let { src ->
            Text(
                labelFor(src),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MediaThumb(uri: Uri, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(uri.lastPathSegment ?: "attachment", modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) { IconClose() }
    }
}

private fun labelFor(source: MessageSource): String = when (source) {
    MessageSource.MESSAGES_WEB -> "Messages"
    MessageSource.VOICE -> "Voice"
    MessageSource.TELEGRAM -> "Telegram"
    MessageSource.SIGNAL -> "Signal"
    MessageSource.WHATSAPP -> "WhatsApp"
    MessageSource.MESSENGER -> "Messenger"
    MessageSource.INSTAGRAM -> "Instagram"
}
