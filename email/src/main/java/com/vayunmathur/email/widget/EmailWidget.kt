package com.vayunmathur.email.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.library.widgets.DynamicThemeGlance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmailWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val messages = withContext(Dispatchers.IO) {
            try {
                EmailDatabase.getInstance(context).emailDao().getRecentUnifiedMessages()
            } catch (e: Exception) {
                emptyList()
            }
        }

        provideContent {
            DynamicThemeGlance(context) {
                EmailWidgetContent(messages)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun EmailWidgetContent(messages: List<EmailMessage>) {
        Scaffold(
            titleBar = {
                TitleBar(
                    startIcon = ImageProvider(com.vayunmathur.library.R.drawable.outline_inbox_24),
                    title = "Unified Inbox",
                    actions = {
                        CircleIconButton(
                            imageProvider = ImageProvider(com.vayunmathur.library.R.drawable.edit_24px),
                            contentDescription = "Compose",
                            onClick = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).apply {
                                putExtra("compose", true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }),
                            backgroundColor = null,
                            contentColor = ColorProvider(Color.White)
                        )
                    },
                )
            },
            modifier = GlanceModifier.clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })),
            horizontalPadding = 0.dp
        ) {
            if (messages.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No recent emails", style = TextStyle(color = GlanceTheme.colors.onBackground))
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
                    items(messages.take(10)) { msg ->
                        EmailItem(msg)
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun EmailItem(msg: EmailMessage) {
        val accountColor = EmailAccount(msg.accountEmail, "", "").getColor()
        
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = 1.dp)
                .background(GlanceTheme.colors.surface)
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).apply {
                    putExtra("accountEmail", msg.accountEmail)
                    putExtra("threadId", msg.threadId ?: msg.id.toString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored Bar
            Box(
                modifier = GlanceModifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(ColorProvider(Color(accountColor)))
            ) {}
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = msg.subject,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = GlanceTheme.colors.onSurface
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = msg.date.substringBefore(" "),
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = GlanceTheme.colors.onSurfaceVariant
                        )
                    )
                }
                Text(
                    text = msg.from.substringBefore("<").trim(),
                    style = TextStyle(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = GlanceTheme.colors.onSurface
                    ),
                    maxLines = 1
                )
                Text(
                    text = msg.body?.take(50) ?: "",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = GlanceTheme.colors.onSurfaceVariant
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
