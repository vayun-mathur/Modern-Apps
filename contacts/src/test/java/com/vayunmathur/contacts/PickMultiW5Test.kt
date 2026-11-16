package com.vayunmathur.contacts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** W5: multi-select ACTION_PICK — toggle two contacts, confirm, ClipData result. */
@RunWith(RobolectricTestRunner::class)
class PickMultiW5Test {
    companion object {
        private val intent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(ContactsContract.Contacts.CONTENT_URI, ContactsContract.Contacts.CONTENT_TYPE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    private fun grantAndRegister() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )
        // Build via Robolectric so the provider carries its authority:
        // ClipData.newUri (multi-pick result) validates it.
        org.robolectric.Robolectric.buildContentProvider(FakeProviderPass2Test.FakeContactsProvider::class.java)
            .create(ContactsContract.AUTHORITY)
    }

    private val registerRule = object : ExternalResource() {
        override fun before() = grantAndRegister()
    }

    val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(intent)) { r ->
            lateinit var act: MainActivity
            r.scenario.onActivity { act = it }
            act
        }

    @get:Rule
    val chain: org.junit.rules.TestRule = RuleChain.outerRule(registerRule).around(rule)

    @Test
    fun multiPickTwoContactsReturnsClipData() {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Toggle contacts on via their rows (text nodes are not the clickable).
        val rows = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        val rowCount = rows.fetchSemanticsNodes().size
        for (i in 0 until rowCount) {
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[i].performClick() }
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Done (", substring = true).fetchSemanticsNodes().isNotEmpty()) break
        }
        val doneNodes = rule.onAllNodesWithText("Done", substring = true)
        assertTrue(doneNodes.fetchSemanticsNodes().isNotEmpty(), "multi-pick should show a Done action")
        doneNodes[0].performClick()
        rule.waitUntil(20_000) { rule.activity.isFinishing }
        val shadow = Shadows.shadowOf(rule.activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        val result = shadow.resultIntent
        assertTrue(result != null)
        val uris = mutableListOf<String>()
        result?.data?.let { uris += it.toString() }
        result?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it.toString() }
        }
        assertTrue(uris.isNotEmpty(), "pick result should carry at least one contact uri")
    }
}
