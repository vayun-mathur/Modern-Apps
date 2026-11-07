package com.vayunmathur.email.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmlViewerScreen] success path in its own sandbox: a parseable .eml
 * file on disk must render its subject (scaffold title and body), sender,
 * body text and attachment rows with the human size. Kept in its own
 * class (fresh Robolectric sandbox) rather than in
 * [EmlViewerScreenTest]: in that class this file-backed parse
 * intermittently never lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmlViewerSuccessTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun writeEml(name: String, content: String): File {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return File(context.cacheDir, name).apply { writeText(content) }
    }

    @Test
    fun `parseable eml renders subject sender body and attachment`() {
        val file = writeEml("sample.eml",
            """
            From: Dana Sender <dana@x.com>
            To: me@x.com
            Subject: Quarterly numbers
            Date: Wed, 17 Jul 2024 12:00:00 +0000
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="BB"

            --BB
            Content-Type: text/plain; charset="UTF-8"

            Hello from the eml body
            --BB
            Content-Type: application/pdf; name="report.pdf"
            Content-Disposition: attachment; filename="report.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --BB--
            """.trimIndent()
        )
        composeRule.setContent {
            EmlViewerScreen(uriString = Uri.fromFile(file).toString(), onBack = {})
        }
        // The scaffold title flips to the subject when the parse lands, a
        // frame before the loading flag clears and the detail column
        // composes: wait for a detail row, not just the title.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Dana Sender", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The subject renders as scaffold title (and again as the body
        // headline below the fold); assert it landed at all, plus the
        // detail rows in the unmerged tree below.
        assertTrue(
            composeRule.onAllNodesWithText("Quarterly numbers", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        // The detail rows live in the scrollable column below the fold and
        // merge into their row nodes, so assert them in the unmerged tree.
        composeRule.onNodeWithText("Dana Sender", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Hello from the eml body", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Attachments:", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("report.pdf", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Open", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `opening an attachment writes its bytes to the cache`() {
        val file = writeEml(
            "with-attachment.eml",
            """
            From: Dana Sender <dana@x.com>
            To: me@x.com
            Subject: With attachment
            Date: Wed, 17 Jul 2024 12:00:00 +0000
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="BB"

            --BB
            Content-Type: text/plain; charset="UTF-8"

            See attached
            --BB
            Content-Type: application/pdf; name="report.pdf"
            Content-Disposition: attachment; filename="report.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --BB--
            """.trimIndent(),
        )
        composeRule.setContent {
            EmlViewerScreen(uriString = Uri.fromFile(file).toString(), onBack = {})
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Open", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Open", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val written = File(context.cacheDir, "eml_attachments/report.pdf")
        assertTrue(written.exists(), "attachment bytes were not written to the cache")
        assertEquals("%PDF-1.4\n", written.readText())
    }

    @Test
    fun `html eml blocks remote images until the user loads them`() {
        val file = writeEml(
            "html.eml",
            """
            From: dana@x.com
            To: me@x.com
            Subject: Html mail
            Date: Wed, 17 Jul 2024 12:00:00 +0000
            MIME-Version: 1.0
            Content-Type: text/html; charset="UTF-8"

            <p>Hello html world</p><img src="https://tracker.example/pixel.png">
            """.trimIndent(),
        )
        composeRule.setContent {
            EmlViewerScreen(uriString = Uri.fromFile(file).toString(), onBack = {})
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Remote images blocked", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Remote images blocked", substring = true).assertExists()
        composeRule.onNodeWithText("Load images").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remote images blocked", substring = true).assertDoesNotExist()
    }
}
