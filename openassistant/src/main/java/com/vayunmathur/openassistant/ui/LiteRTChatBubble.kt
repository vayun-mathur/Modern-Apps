package com.vayunmathur.openassistant.ui

import android.content.ClipData
import android.content.Intent
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import com.vayunmathur.library.log.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.LocalTextStyle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.parseMarkdown
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.data.Message
import kotlinx.coroutines.launch

/**
 * One chat message bubble, split from LiteRTChatUi.kt to satisfy the
 * one-public-composable-per-file lint rule. Behavior identical — only moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBubble(message: Message, surface: A2uiCoreSurfaceModel? = null) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (isUser) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(if (message.imagePaths.isNotEmpty() || message.hasAudio) 4.dp else 12.dp)) {
                    message.imagePaths.forEach { path ->
                        AsyncImage(
                            ImageRequest.Builder(context)
                                .data(path)
                                .memoryCacheKey("chat-msg-$path")
                                .build(),
                            null,
                            Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (message.hasAudio) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconMic(tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.voice_message), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Normal, fontSize = 14.sp)
                    }
                    if (message.text.isNotBlank()) Text(message.text, Modifier.padding(8.dp, 4.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                }
            }
        } else if (isTool) {
            val linkRegex = remember { Regex("\\[(.*?)\\]\\((.*?)\\)") }
            val match = remember(message.text) { linkRegex.find(message.text) }
            val cleanText = remember(message.text, match) {
                if (match != null) message.text.replace(match.value, match.groups[1]!!.value)
                else message.text
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 350.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconInfo(Modifier.size(20.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            cleanText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
                        )
                    }
                    if (match != null) {
                        val url = match.groups[2]!!.value
                        val label = match.groups[1]!!.value
                        Spacer(Modifier.padding(top = 8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (expected: Exception) {
                                    Log.status("LiteRTChatUi", "Failed to open link: $url", expected)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        } else if (message.text.isNotBlank()) {
            Text(
                parseMarkdown(message.text, showMarkers = false),
                Modifier.padding(vertical = 4.dp, horizontal = 0.dp).padding(end = 100.dp),
                style = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 22.sp)
            )
        }
        if (surface != null && !isUser) {
            A2uiSurfaceView(surface, Modifier.padding(vertical = 4.dp))
        }
        if (message.text.isNotBlank() && !isTool) {
            IconButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) } },
                modifier = Modifier.size(32.dp).padding(top = 4.dp)
            ) {
                IconCopy(tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            }
        }
    }
}
