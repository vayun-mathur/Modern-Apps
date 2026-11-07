package com.vayunmathur.email.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.library.widgets.MaWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.email.R

class EmailWidget : MaWidget<List<EmailPreview>>() {

    companion object {
        private const val WIDGET_MESSAGE_COUNT = 100
        private const val WIDGET_PEEK_LEN = 50
    }

    // Widened from MaWidget's protected so unit tests can drive the data
    // layer directly; the framework only ever calls it via provideGlance.
    public override suspend fun loadData(context: Context, id: GlanceId): List<EmailPreview> {
        // Query the database directly for recent messages.
        // No read status indicators, so widget doesn't need to refresh on read changes.
        return withContext(Dispatchers.IO) {
            try {
                EmailRepository.get(context).getDatabase().queryDao().getRecentUnifiedPreview()
            } catch (ignored: Exception) {
                Log.error("EmailWidget", "DB fail", ignored)
                emptyList()
            }
        }
    }

    public override fun previewData(context: Context): List<EmailPreview> = listOf(
        EmailPreview(
            accountEmail = "alex@example.com", folderName = "INBOX", id = 1,
            subject = "Lunch tomorrow?", from = "Alex Johnson <alex@example.com>",
            date = "Jul 6", peekContent = "Are we still on for noon at the usual place?",
        ),
        EmailPreview(
            accountEmail = "priya@work.com", folderName = "INBOX", id = 2,
            subject = "Q3 report is ready", from = "Priya Patel <priya@work.com>",
            date = "Jul 5", peekContent = "I've attached the final numbers for review.",
        ),
        EmailPreview(
            accountEmail = "news@digest.com", folderName = "INBOX", id = 3,
            subject = "Your weekly digest", from = "Tech Weekly <news@digest.com>",
            date = "Jul 5", peekContent = "Top stories and updates from this week.",
        ),
    )

    @Composable
    public override fun Content(data: List<EmailPreview>) {
        EmailWidgetContent(data)
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun EmailWidgetContent(messages: List<EmailPreview>) {
        val ctx = LocalContext.current
        Scaffold(
            titleBar = {
                TitleBar(
                    startIcon = ImageProvider(com.vayunmathur.library.R.drawable.outline_inbox_24),
                    title = ctx.getString(R.string.unified_inbox_label),
                    actions = {
                        CircleIconButton(
                            imageProvider = ImageProvider(com.vayunmathur.library.R.drawable.edit_24px),
                            contentDescription = ctx.getString(R.string.compose),
                            onClick = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).apply {
                                putExtra("compose", true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }),
                            backgroundColor = null,
                            contentColor = GlanceTheme.colors.onSurface
                        )
                    },
                )
            },
            modifier = GlanceModifier.clickable(
                actionStartActivity(
                    Intent(LocalContext.current, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
            horizontalPadding = 0.dp
        ) {
            if (messages.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = ctx.getString(R.string.no_recent_emails),
                        style = TextStyle(color = GlanceTheme.colors.onBackground),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
                    items(messages.take(WIDGET_MESSAGE_COUNT)) { msg ->
                        EmailItem(msg)
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun EmailItem(msg: EmailPreview) {
        val barColor = accountColor(msg.accountEmail)

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = 1.dp)
                .background(GlanceTheme.colors.surface)
                .clickable(
                    actionStartActivity(
                        Intent(LocalContext.current, MainActivity::class.java).apply {
                            putExtra("accountEmail", msg.accountEmail)
                            putExtra("threadId", msg.threadId ?: msg.id.toString())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored Bar (account color)
            Box(
                modifier = GlanceModifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(ColorProvider(Color(barColor))),
            ) {}
            EmailItemBody(msg)
        }
    }

    @Composable
    private fun EmailItemBody(msg: EmailPreview) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            EmailItemHeaderRow(msg)
            Text(
                text = senderDisplayName(msg.from),
                style = TextStyle(
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            Text(
                text = msg.peekContent.take(WIDGET_PEEK_LEN),
                style = TextStyle(
                    fontSize = 12.sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun EmailItemHeaderRow(msg: EmailPreview) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = msg.subject,
                style = TextStyle(
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = msg.date.substringBefore(" "),
                style = TextStyle(
                    fontSize = 11.sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
            )
        }
    }
}
