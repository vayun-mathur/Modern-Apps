package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.provider.VoicemailContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.VoicemailEntryTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * W1 wave 8: voicemail screen with carrier rows (expand + transcript),
 * and the new-message picker's line selector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave8Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var vmProvider: VoicemailEntryTest.FakeVoicemailProvider

    @BeforeTest
    fun setUp() {
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        vmProvider = Robolectric.setupContentProvider(
            VoicemailEntryTest.FakeVoicemailProvider::class.java, VoicemailContract.AUTHORITY,
        )
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )
        val roleManager = context.getSystemService(RoleManager::class.java)
        val shadow = Shadow.extract<ShadowRoleManager>(roleManager)
        shadow.addHeldRole(RoleManager.ROLE_DIALER)
        shadow.addAvailableRole(RoleManager.ROLE_DIALER)
    }

    @AfterTest
    fun tearDown() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        Shadow.extract<ShadowRoleManager>(roleManager).removeHeldRole(RoleManager.ROLE_DIALER)
    }

    @Test
    fun `voicemail screen expands a carrier row to its transcript`() {
        vmProvider.rows = listOf(
            arrayOf<Any?>(1L, "+14155550102", 1_785_566_000_000L, 42L, 1, "audio/amr", "call me back", 0, null),
            arrayOf<Any?>(2L, "+14155550103", 1_785_565_000_000L, 9L, 0, null, null, 1, null),
        )
        rule.setContent { VoicemailScreen() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("+14155550102").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("+14155550102").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("call me back").assertIsDisplayed()
    }

    @Test
    fun `picker line selector switches the compose line`() {
        FakeContactsState.phoneRows = listOf(
            mapOf(
                android.provider.ContactsContract.CommonDataKinds.Phone._ID to 1L,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice",
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                android.provider.ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        val composed = CopyOnWriteArrayList<Pair<LineChoice, String>>()
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.Sim(-1, "SIM"), LineChoice.WhatsApp),
                onDismiss = {},
                onCompose = { choice, address -> composed += choice to address },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty()
        }
        // Open the line selector (shows the current SIM label) and pick WhatsApp.
        rule.onNodeWithText("SIM").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("WhatsApp").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Alice").performClick()
        assertEquals(listOf(LineChoice.WhatsApp to "+14155550102"), composed.toList())
        assertTrue(composed.isNotEmpty())
    }
}
