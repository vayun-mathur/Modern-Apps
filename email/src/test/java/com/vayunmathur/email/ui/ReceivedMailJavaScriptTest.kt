package com.vayunmathur.email.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.MessageThreadActions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Received mail is untrusted, so the thread screen must render its HTML with
 * JavaScript off (HtmlText defaults to scripts on for its other callers). The
 * .eml viewer's counterpart lives in [EmlViewerSuccessTest], whose sandbox the
 * file-backed parse is reliable in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceivedMailJavaScriptTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private object NoopActions : MessageThreadActions {
        override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) = Unit
        override fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) = Unit
        override fun blockSender(from: String) = Unit
        override fun fetchBodyIfNeeded(message: EmailMessage) = Unit
        override suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> = emptyList()
        override suspend fun loadCidMap(context: Context, message: EmailMessage): Map<String, File> = emptyMap()
        override fun downloadAttachment(attachment: Attachment, onSuccess: (String) -> Unit, onError: (String) -> Unit) = Unit
        override fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) = Unit
        override fun exportEml(accountEmail: String, folderName: String, uid: Long, targetUri: Uri, onResult: (Boolean, String?) -> Unit) = Unit
    }

    private fun webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        rule.runOnUiThread {
            val q = ArrayDeque<View>()
            q.add(rule.activity.findViewById(android.R.id.content))
            while (q.isNotEmpty()) {
                val v = q.removeFirst()
                if (v is WebView) found.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
            }
        }
        return found
    }

    @Test
    fun `thread screen renders html mail with javascript off`() {
        val msg = EmailMessage(
            accountEmail = "me@x.com", folderName = "INBOX", id = 1L,
            subject = "Hi", from = "Eve <eve@x.com>", date = "Wed Nov 27 10:00",
            body = "<p>Hello</p><script>document.title='pwned'</script>", isHtml = true, isRead = true,
        )
        rule.setContent { MessageThreadScreen(messages = listOf(msg), actions = NoopActions) }
        rule.waitForIdle()
        val views = webViews()
        assertFalse(views.isEmpty(), "no WebView rendered")
        views.forEach { assertFalse(it.settings.javaScriptEnabled, "sender script would run on open") }
    }
}
