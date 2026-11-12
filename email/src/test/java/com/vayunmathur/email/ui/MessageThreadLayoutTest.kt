package com.vayunmathur.email.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.MessageThreadActions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout guarantees behind the #900 fix ("email pane scrolling broken" in
 * portrait): the open message's HTML body is a self-scrolling WebView that
 * fills the pane, never a wrap-content WebView inside a vertically scrolling
 * list, and threads with several messages open one at a time from a strip.
 *
 * Portrait phone geometry, the configuration the bug was reported in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
class MessageThreadLayoutTest {

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

    /** A long, fixed-width (600px) newsletter: the shape of mail #900 was reported on. */
    private val newsletter = buildString {
        append("<table width=\"600\" style=\"width:600px\" align=\"center\">")
        repeat(200) { append("<tr><td><p>Paragraph $it of a long newsletter.</p></td></tr>") }
        append("</table>")
    }

    private fun msg(uid: Long, body: String, isHtml: Boolean = false, isRead: Boolean = true) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = uid,
        subject = "Weekly digest", from = "News <news@x.com>", date = "Wed Nov 27 10:00",
        body = body, isHtml = isHtml, isRead = isRead,
    )

    private fun webView(): WebView {
        var found: WebView? = null
        rule.runOnUiThread {
            val q = ArrayDeque<View>()
            q.add(rule.activity.findViewById(android.R.id.content))
            while (q.isNotEmpty() && found == null) {
                val v = q.removeFirst()
                if (v is WebView) found = v
                if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
            }
        }
        return assertNotNull(found, "no WebView in the hierarchy")
    }

    @Test
    fun `html body is not inside any vertically scrolling container`() {
        rule.setContent { MessageThreadScreen(messages = listOf(msg(1L, newsletter, isHtml = true)), actions = NoopActions) }
        rule.waitForIdle()
        rule.onNodeWithTag(MESSAGE_HTML_BODY_TAG).assert(!hasAnyAncestor(hasScrollAction()))
    }

    @Test
    fun `html body fills the pane and scrolls itself instead of wrapping its content`() {
        rule.setContent { MessageThreadScreen(messages = listOf(msg(1L, newsletter, isHtml = true)), actions = NoopActions) }
        rule.waitForIdle()
        val root = rule.onRoot().getBoundsInRoot()
        val body = rule.onNodeWithTag(MESSAGE_HTML_BODY_TAG).getBoundsInRoot()
        val rootHeight = root.bottom - root.top
        val bodyHeight = body.bottom - body.top
        // Header, banner, footer and the app bar take the rest; the body gets the
        // bulk of a portrait screen regardless of what the WebView says its
        // content height is (0 under Robolectric).
        assertTrue(bodyHeight > rootHeight * 0.4f, "body $bodyHeight of root $rootHeight")
        assertTrue(body.bottom <= root.bottom, "body must end on screen: $body vs $root")
        val view = webView()
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.height)
        assertTrue(view.isVerticalScrollBarEnabled, "the WebView owns its scrolling")
    }

    @Test
    fun `html body loads with fit-to-width css`() {
        rule.setContent { MessageThreadScreen(messages = listOf(msg(1L, newsletter, isHtml = true)), actions = NoopActions) }
        rule.waitForIdle()
        val data = org.robolectric.Shadows.shadowOf(webView()).lastLoadDataWithBaseURL?.data.orEmpty()
        assertTrue(data.contains("overflow-wrap: anywhere"), data.take(2000))
        assertTrue(data.contains("user-scalable=yes"), data.take(500))
    }

    @Test
    fun `single message thread shows no strip`() {
        rule.setContent { MessageThreadScreen(messages = listOf(msg(1L, "Only one")), actions = NoopActions) }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
    }

    @Test
    fun `thread opens on its first unread message and the strip switches messages`() {
        rule.setContent {
            MessageThreadScreen(
                messages = listOf(
                    msg(1L, "Body one"),
                    msg(2L, "Body two", isRead = false),
                    msg(3L, "Body three", isRead = false),
                ),
                actions = NoopActions,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Body two").assertIsDisplayed()
        rule.onNodeWithText("Body one").assertDoesNotExist()
        val rows = rule.onAllNodes(isSelectable())
        rows[1].assertIsSelected()
        rows[0].assertIsNotSelected()
        rows[2].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Body three").assertIsDisplayed()
        rule.onNodeWithText("Body two").assertDoesNotExist()
        rule.onAllNodes(isSelectable())[2].assertIsSelected()
    }

    @Test
    fun `html message in a multi-message thread still fills the pane below the strip`() {
        rule.setContent {
            MessageThreadScreen(
                messages = listOf(msg(1L, "Earlier note"), msg(2L, newsletter, isHtml = true)),
                actions = NoopActions,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(MESSAGE_HTML_BODY_TAG).assert(!hasAnyAncestor(hasScrollAction()))
        val root = rule.onRoot().getBoundsInRoot()
        val body = rule.onNodeWithTag(MESSAGE_HTML_BODY_TAG).getBoundsInRoot()
        assertTrue(body.bottom - body.top > (root.bottom - root.top) * 0.25f, "body $body of root $root")
    }
}
