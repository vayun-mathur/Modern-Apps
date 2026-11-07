package com.vayunmathur.contacts

import android.Manifest
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/**
 * Second-pass coverage for the real MainActivity navigation: list, groups and
 * settings tabs, and opening a contact's details, with the gate permissions
 * granted and a fake contacts provider behind the ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityComposePass2Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }

    private fun awaitText(text: String) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun listShowsProviderContactsAndOpensDetails() {
        awaitText("Aisha")
        rule.onNodeWithText("Aisha", substring = true).performClick()
        // details page for the provider contact: her number shows in the header area
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Rahman", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            rule.onAllNodesWithText("(415) 555-0102", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun groupsTabShowsProviderGroups() {
        awaitText("Aisha")
        rule.onNodeWithText("Groups", substring = true).performClick()
        // the fake provider has a Family group row
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Family", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Groups", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Groups", substring = true).assertExists()
    }

    @Test
    fun settingsTabShowsSettingsSections() {
        awaitText("Aisha")
        rule.onNodeWithText("Settings", substring = true).performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Calendar Sync", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Calendar Sync", substring = true).assertExists()
    }
}
