package com.vayunmathur.messages.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.LocalSnackbarHostState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageAttachment
import com.vayunmathur.messages.data.MessageDirection
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessageState
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.data.Reaction
import com.vayunmathur.messages.util.CameraCapture
import com.vayunmathur.messages.util.FindFamilyLocation
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.ReactionAction
import com.vayunmathur.messages.util.PollView
import com.vayunmathur.messages.util.pollFromServiceData
import com.vayunmathur.messages.util.isMessageRequest
import com.vayunmathur.messages.util.mediaCapabilities
import com.vayunmathur.messages.util.participantNames
import com.vayunmathur.messages.util.displayTitle
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
    db: MessagesDatabase,
    conversationId: String,
) {
    val conversation by db.conversationDao().observe(conversationId).collectAsState(initial = null)
    val messages by db.messageDao().observeForConversation(conversationId)
        .collectAsState(initial = emptyList())

    LaunchedEffect(conversationId) {
        vm.markRead(conversationId)
        vm.fetchMessages(conversationId)
    }

    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var reactingTo by remember { mutableStateOf<Message?>(null) }

    // Compose's PickVisualMedia uses the Android system photo picker
    // (Photos / Files on the user's device) — no READ_EXTERNAL_STORAGE
    // permission needed because the result is a tightly-scoped grant.
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            sending = true
            vm.sendMedia(
                conversationId = conversationId,
                uri = uri,
                caption = draft.trim().takeIf { it.isNotEmpty() },
            ) { _ ->
                sending = false
                draft = ""
            }
        }
    }

    val context = LocalContext.current
    val caps = remember(conversation?.source) {
        conversation?.source?.mediaCapabilities() ?: emptySet()
    }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showCameraFallback by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    // Read a picked/captured URI and send it as media, carrying the
    // current draft as the caption (then clear it). Shared by the photo
    // picker, file picker, and both camera paths.
    fun sendUri(uri: Uri) {
        sending = true
        vm.sendMedia(
            conversationId = conversationId,
            uri = uri,
            caption = draft.trim().takeIf { it.isNotEmpty() },
        ) { _ ->
            sending = false
            draft = ""
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) sendUri(uri) }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        val uri = pendingCameraUri
        if (success && uri != null) sendUri(uri)
        pendingCameraUri = null
    }

    fun startCamera() {
        if (CameraCapture.hasCameraApp(context)) {
            val file = CameraCapture.newPhotoFile(context)
            val uri = CameraCapture.uriFor(context, file)
            pendingCameraUri = uri
            takePhoto.launch(uri)
        } else {
            // No camera app handles ACTION_IMAGE_CAPTURE — fall back to
            // the built-in CameraX capture screen.
            showCameraFallback = true
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted: Boolean ->
        if (granted) startCamera()
        else scope.launch { snackbar?.showSnackbar("Camera permission denied") }
    }

    fun requestCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val url = FindFamilyLocation.parseResult(result.data)
        if (url != null) {
            sending = true
            vm.sendLocation(conversationId, url) { _ -> sending = false }
        } else {
            scope.launch { snackbar?.showSnackbar("Couldn't create location link") }
        }
    }

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConvAvatar(conversation)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(
                                conversation?.displayTitle() ?: "Conversation",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            conversation?.let {
                                val subtitle = if (it.isGroup) {
                                    val names = it.participantNames()
                                    when {
                                        names.isNotEmpty() -> names.joinToString(", ")
                                        it.participantCount > 0 -> "${it.participantCount} participants"
                                        else -> "Group"
                                    }
                                } else {
                                    it.conversationType ?: ""
                                }
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        subtitle,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    val conv = conversation
                    if (conv != null && !conv.isGroup && conv.peerPhoneE164 != null) {
                        val ctx = LocalContext.current
                        IconButton(onClick = {
                            val phone = conv.peerPhoneE164
                            val lookupUri = Uri.withAppendedPath(
                                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                                Uri.encode(phone)
                            )
                            val contactId: Long? = ctx.contentResolver.query(
                                lookupUri,
                                arrayOf(ContactsContract.PhoneLookup._ID),
                                null, null, null
                            )?.use { cursor: Cursor ->
                                if (cursor.moveToFirst()) cursor.getLong(0) else null
                            }
                            val editIntent = if (contactId != null) {
                                Intent(Intent.ACTION_EDIT).apply {
                                    data = ContentUris.withAppendedId(
                                        ContactsContract.Contacts.CONTENT_URI, contactId
                                    )
                                }
                            } else {
                                Intent(Intent.ACTION_INSERT).apply {
                                    type = ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                                    conv.peerName?.let {
                                        putExtra(ContactsContract.Intents.Insert.NAME, it)
                                    }
                                }
                            }
                            ctx.startActivity(editIntent)
                        }) {
                            IconEdit()
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (conversation?.isMessageRequest() == true) {
                    MessageRequestBar(
                        onAccept = { vm.acceptMessageRequest(conversationId) },
                        onBlock = {
                            vm.blockConversation(conversationId) { ok ->
                                if (ok) backStack.pop()
                            }
                        },
                        onDelete = {
                            vm.deleteConversation(conversationId) { ok ->
                                if (ok) backStack.pop()
                            }
                        },
                    )
                }
                ComposeRow(
                draft = draft,
                onDraftChange = { newDraft ->
                    draft = newDraft
                    // Fire a typing notification on each edit (gmessages
                    // only — voice has no typing endpoint). Cheap: it's
                    // a fire-and-forget on a coroutine.
                    if (conversation?.source in setOf(
                            MessageSource.MESSAGES_WEB,
                            MessageSource.TELEGRAM,
                            MessageSource.SIGNAL,
                        )
                    ) {
                        vm.sendTyping(conversationId)
                    }
                },
                sending = sending,
                onSend = {
                    val toSend = draft.trim()
                    if (toSend.isEmpty()) return@ComposeRow
                    sending = true
                    vm.send(conversationId, toSend) { _ -> sending = false }
                    draft = ""
                },
                onAttach = {
                    showAttachSheet = true
                },
                )
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Loading messages…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Walk the message list once, emitting (day-divider, message)
            // pairs so the UI can render them inline. Cheaper than a
            // groupBy on every recomposition.
            //
            // The LazyColumn below uses reverseLayout = true so item 0
            // sits at the visual bottom — the standard messenger pattern
            // (sparse threads pack to the bottom, new messages slot in
            // without a jumpy scroll-to-end). To get that visual order
            // we feed it the chronological list REVERSED: newest first.
            val items = remember(messages) { buildItems(messages).asReversed() }
            val isGroup = conversation?.isGroup == true
            val canReact = conversation?.source in setOf(
                MessageSource.MESSAGES_WEB,
                MessageSource.TELEGRAM,
                MessageSource.SIGNAL,
                MessageSource.WHATSAPP,
                MessageSource.MESSENGER,
                MessageSource.INSTAGRAM,
            )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    when (item) {
                        is ChatItem.DayDivider -> item(key = "day-${item.dayKey}") {
                            DayDivider(item.label)
                        }
                        is ChatItem.Msg -> item(key = item.message.id) {
                            MessageBubble(
                                msg = item.message,
                                showSender = isGroup && item.showSender,
                                isFirstInRun = item.isFirstInRun,
                                isLastInRun = item.isLastInRun,
                                onLongPress = if (canReact) {
                                    { reactingTo = item.message }
                                } else null,
                                onPollVote = { options ->
                                    vm.sendPollVote(item.message.id, options)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press picker for reactions (gmessages only). Tapping an
    // emoji fires SEND_REACTION (ADD). The relay echoes the result
    // through MESSAGE_UPDATES which arrives via the long-poll and
    // re-renders the message bubble with the new reactionsJson.
    reactingTo?.let { target ->
        ReactionPickerDialog(
            onPick = { emoji ->
                vm.sendReaction(target.id, emoji, ReactionAction.ADD)
                reactingTo = null
            },
            onDismiss = { reactingTo = null },
        )
    }

    if (showAttachSheet) {
        AttachmentSheet(
            capabilities = caps,
            onPhoto = {
                showAttachSheet = false
                pickImage.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onCamera = {
                showAttachSheet = false
                requestCamera()
            },
            onFile = {
                showAttachSheet = false
                pickFile.launch(arrayOf("*/*"))
            },
            onPoll = {
                showAttachSheet = false
                showPollDialog = true
            },
            onLocation = {
                showAttachSheet = false
                showLocationDialog = true
            },
            onDismiss = { showAttachSheet = false },
        )
    }

    if (showPollDialog) {
        PollDialog(
            onCreate = { question, options, allowMultiple ->
                showPollDialog = false
                sending = true
                vm.sendPoll(conversationId, question, options, allowMultiple) { _ ->
                    sending = false
                }
            },
            onDismiss = { showPollDialog = false },
        )
    }

    if (showLocationDialog) {
        LocationDurationDialog(
            defaultName = conversation?.peerName ?: "Shared location",
            onConfirm = { name, expiryMillis ->
                showLocationDialog = false
                if (FindFamilyLocation.isAvailable(context)) {
                    locationLauncher.launch(FindFamilyLocation.buildIntent(name, expiryMillis))
                } else {
                    scope.launch { snackbar?.showSnackbar("Location sharing unavailable") }
                }
            },
            onDismiss = { showLocationDialog = false },
        )
    }

    if (showCameraFallback) {
        CameraCaptureScreen(
            onCaptured = { uri ->
                showCameraFallback = false
                sendUri(uri)
            },
            onDismiss = { showCameraFallback = false },
        )
    }
}

/** Render units we feed to LazyColumn. */
private sealed interface ChatItem {
    data class DayDivider(
        val label: String,
        /** Unique per calendar day so the LazyColumn key won't collide
         *  on a thread that spans multiple Fridays. */
        val dayKey: Long,
    ) : ChatItem
    data class Msg(
        val message: Message,
        /** Show the sender name above this bubble (groups only). */
        val showSender: Boolean,
        /** First message in a contiguous run from the same sender —
         *  used to give the bubble a square corner at the join point. */
        val isFirstInRun: Boolean,
        val isLastInRun: Boolean,
    ) : ChatItem
}

private fun buildItems(messages: List<Message>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var lastDayKey: Long = Long.MIN_VALUE
    var lastSenderKey: String? = null
    var lastDirection: MessageDirection? = null
    messages.forEachIndexed { idx, m ->
        val dayKey = startOfDayEpoch(m.timestamp)
        if (dayKey != lastDayKey) {
            out += ChatItem.DayDivider(dayLabel(m.timestamp), dayKey)
            lastDayKey = dayKey
            // Day-divider always breaks a sender run.
            lastSenderKey = null
            lastDirection = null
        }
        // Coalesce by a stable sender key (senderId when present, else the
        // display name) so two group members sharing a name don't merge.
        val senderKey = m.senderId ?: m.senderName
        val nextMessage = messages.getOrNull(idx + 1)
        val sameRunAsPrev = senderKey == lastSenderKey && m.direction == lastDirection
        val sameRunAsNext = nextMessage != null &&
            (nextMessage.senderId ?: nextMessage.senderName) == senderKey &&
            nextMessage.direction == m.direction &&
            startOfDayEpoch(nextMessage.timestamp) == dayKey
        out += ChatItem.Msg(
            message = m,
            showSender = !sameRunAsPrev && m.direction == MessageDirection.INCOMING,
            isFirstInRun = !sameRunAsPrev,
            isLastInRun = !sameRunAsNext,
        )
        lastSenderKey = senderKey
        lastDirection = m.direction
    }
    return out
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

private fun parseAttachments(json: String?): List<MessageAttachment> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.decodeFromString(ListSerializer(MessageAttachment.serializer()), json)
    }.getOrNull().orEmpty()
}

private fun isPlaceholderBody(body: String): Boolean {
    val t = body.trim()
    return t.startsWith("[") && t.endsWith("]") && t.length <= 20
}

/** Render one inline attachment inside a message bubble. */
@Composable
private fun AttachmentView(att: MessageAttachment) {
    val ctx = LocalContext.current
    val media = att.url ?: att.previewUrl
    val ratio = if (att.width > 0 && att.height > 0) {
        att.width.toFloat() / att.height.toFloat()
    } else null

    fun open(url: String?) {
        val target = url ?: return
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
    }

    when (att.attachmentType) {
        "image", "sticker" -> {
            if (media != null) {
                val isSticker = att.attachmentType == "sticker"
                AsyncImage(
                    model = media,
                    contentDescription = att.title ?: att.attachmentType,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = if (isSticker) 140.dp else 260.dp)
                        .let { m -> if (ratio != null) m.aspectRatio(ratio) else m }
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        "video" -> {
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .let { m -> if (ratio != null) m.aspectRatio(ratio) else m }
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickableSafe { open(att.url ?: att.previewUrl) },
                contentAlignment = Alignment.Center,
            ) {
                if (att.previewUrl != null || att.url != null) {
                    AsyncImage(
                        model = att.previewUrl ?: att.url,
                        contentDescription = att.title ?: "video",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconPlay(tint = Color.White)
            }
        }
        "share" -> {
            ShareCard(att, onClick = { open(att.actionUrl ?: att.url) })
        }
        "audio" -> {
            AttachmentChip(label = att.fileName ?: "Voice message") { open(att.url) }
        }
        else -> {
            AttachmentChip(label = att.fileName ?: "Attachment") { open(att.url) }
        }
    }
}

@Composable
private fun ShareCard(att: MessageAttachment, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .padding(4.dp)
            .combinedClickableSafe(onClick),
    ) {
        Column {
            att.previewUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = att.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                )
            }
            Text(
                att.title ?: att.actionUrl ?: "Shared link",
                modifier = Modifier.padding(10.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AttachmentChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(10.dp)
            .combinedClickableSafe(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAttachment()
        Text(label, modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp)
    }
}

/** clickable that also works inside the long-press bubble without needing
 *  the experimental combinedClickable ceremony at each call site. */
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    showSender: Boolean,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    onLongPress: (() -> Unit)? = null,
    onPollVote: (List<String>) -> Unit = {},
) {
    val isOutgoing = msg.direction == MessageDirection.OUTGOING
    val bubbleColor = if (isOutgoing) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    // Tail corner shrinks when this is the last bubble in a sender run.
    val corner = 18.dp
    val joinedCorner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (isOutgoing || isFirstInRun) corner else joinedCorner,
        topEnd = if (!isOutgoing || isFirstInRun) corner else joinedCorner,
        bottomStart = if (isOutgoing || isLastInRun) corner else joinedCorner,
        bottomEnd = if (!isOutgoing || isLastInRun) corner else joinedCorner,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInRun) 6.dp else 0.dp),
        horizontalAlignment = alignment,
    ) {
        if (showSender && !msg.senderName.isNullOrBlank()) {
            Text(
                msg.senderName,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        val attachments = remember(msg.mediaJson) { parseAttachments(msg.mediaJson) }
        val poll = remember(msg.serviceData) { pollFromServiceData(msg.serviceData) }
        // Suppress a bare "[Image]"/"[Attachment]" placeholder body once the
        // real media is available; keep genuine captions. Poll messages render
        // their own body, so suppress the text there too.
        val showBody = poll == null && msg.body.isNotBlank() &&
            !(attachments.isNotEmpty() && isPlaceholderBody(msg.body))
        Surface(
            color = bubbleColor,
            contentColor = textColor,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(min = 32.dp)
                .let { mod ->
                    if (onLongPress != null) {
                        mod.combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress,
                        )
                    } else mod
                },
        ) {
            Column {
                attachments.forEach { AttachmentView(it) }
                if (poll != null) {
                    PollBody(poll, onVote = onPollVote)
                }
                if (showBody) {
                    Text(
                        msg.body,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 15.sp,
                    )
                }
            }
        }
        ReactionsRow(msg.reactionsJson, alignToEnd = isOutgoing)
        if (isLastInRun) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text(
                    formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isOutgoing) {
                    val stateLabel = when (msg.state) {
                        MessageState.PENDING -> " · " + stringResource(R.string.message_state_pending)
                        MessageState.FAILED -> " · " + stringResource(R.string.message_state_failed)
                        MessageState.SENT, MessageState.DELIVERED -> ""
                    }
                    if (stateLabel.isNotEmpty()) {
                        Text(
                            stateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.state == MessageState.FAILED)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal reaction picker. The seven emoji shown match Google Messages'
 * built-in reaction palette (LIKE / LOVE / LAUGH / SURPRISED / SAD /
 * ANGRY / DISLIKE) but we send them as raw unicode — the relay
 * normalizes to the EmojiType enum server-side.
 */
@Composable
private fun MessageRequestBar(
    onAccept: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "This sender isn't in your contacts.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBlock) { Text("Block") }
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAccept) {
                    Text("Accept", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val emojis = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDC4E")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                emojis.forEach { e ->
                    TextButton(onClick = { onPick(e) }) {
                        Text(e, fontSize = 22.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PollBody(poll: PollView, onVote: (List<String>) -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .widthIn(min = 200.dp),
    ) {
        Text(poll.question, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        poll.options.forEach { opt ->
            val count = poll.counts[opt] ?: 0
            val selected = opt in poll.myVotes
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable {
                        val newVotes = when {
                            // Single-choice polls: tap replaces (tap again to clear).
                            poll.selectable <= 1 -> if (selected) emptyList() else listOf(opt)
                            selected -> (poll.myVotes - opt).toList()
                            else -> (poll.myVotes + opt).toList()
                        }
                        onVote(newVotes)
                    },
            ) {
                Text(if (selected) "◉" else "○", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(opt, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (poll.totalVoters > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                "${poll.totalVoters} vote${if (poll.totalVoters == 1) "" else "s"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionsRow(json: String?, alignToEnd: Boolean) {
    if (json.isNullOrBlank()) return
    val reactions = remember(json) {
        runCatching {
            Json.decodeFromString(ListSerializer(Reaction.serializer()), json)
        }.getOrNull().orEmpty()
    }
    if (reactions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = if (alignToEnd) Arrangement.End else Arrangement.Start,
    ) {
        reactions.forEach { r ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.emoji, fontSize = 13.sp)
                    if (r.count > 1) {
                        Text(
                            "  ${r.count}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConvAvatar(conversation: Conversation?) {
    val photo = conversation?.avatarUrl
    val isGroup = conversation?.isGroup == true
    val name = conversation?.peerName
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> AsyncImage(
                model = photo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            isGroup -> Text(
                "…",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            else -> {
                val initials = (name ?: "?").trim().take(2).uppercase()
                Text(
                    initials,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

/** Epoch-ms at the local-time start of the calendar day [ts] falls in.
 *  Unique per calendar day, used as a stable LazyColumn key. */
private fun startOfDayEpoch(ts: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** "Today", "Yesterday", "Wednesday", or a full date for older messages. */
private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val that = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val yesterday = (Calendar.getInstance()).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    ) return "Yesterday"
    val sixDaysAgo = (Calendar.getInstance()).apply { add(Calendar.DAY_OF_YEAR, -6) }
    if (that.after(sixDaysAgo)) {
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ts))
    }
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
}
