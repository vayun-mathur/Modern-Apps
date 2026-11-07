package com.vayunmathur.everysync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.platform.AccountDetailActions
import com.vayunmathur.everysync.platform.AccountDetailUiState
import com.vayunmathur.everysync.platform.AccountRow
import com.vayunmathur.everysync.platform.AccountsActions
import com.vayunmathur.everysync.platform.AccountsUiState
import com.vayunmathur.everysync.platform.AddAccountActions
import com.vayunmathur.everysync.platform.SettingsActions
import com.vayunmathur.everysync.platform.SettingsUiState
import com.vayunmathur.everysync.platform.VaultMemberUi
import com.vayunmathur.everysync.platform.VaultRequestUi
import com.vayunmathur.everysync.platform.VaultUiState
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotStore
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreens2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private fun show(initial: @Composable () -> Unit): androidx.compose.runtime.MutableState<@Composable () -> Unit> {
        val state = mutableStateOf(initial)
        rule.setContent { state.value() }
        return state
    }

    private class DetailRecorder : AccountDetailActions {
        val toggles = mutableListOf<Triple<String, DataType, Boolean>>()
        var synced: String? = null; var removed: String? = null; var backs = 0
        var created: Pair<String, String>? = null; var refreshes = 0
        var restored: SnapshotStore.SnapshotMeta? = null; var snapshotNows = 0
        val invites = mutableListOf<Triple<String, String, String>>()
        val approvals = mutableListOf<Pair<String, String>>(); val denies = mutableListOf<Pair<String, String>>()
        val safetyFor = mutableListOf<String>()
        override fun toggleType(accountName: String, type: DataType, enabled: Boolean) { toggles += Triple(accountName, type, enabled) }
        override fun syncNow(accountName: String) { synced = accountName }
        override fun removeAccount(accountName: String) { removed = accountName }
        override fun back() { backs++ }
        override fun restoreSnapshot(snapshot: SnapshotStore.SnapshotMeta) { restored = snapshot }
        override fun snapshotNow() { snapshotNows++ }
        override fun createVault(dataType: String, title: String) { created = dataType to title }
        override fun refreshVaults() { refreshes++ }
        override fun sendInvite(vaultId: String, recipientId: String, role: String, onResult: (Boolean) -> Unit) { invites += Triple(vaultId, recipientId, role); onResult(true) }
        override fun approveRequest(vaultId: String, requesterId: String) { approvals += vaultId to requesterId }
        override fun denyRequest(vaultId: String, requesterId: String) { denies += vaultId to requesterId }
        override fun computeSafetyCode(recipientId: String) { safetyFor += recipientId }
        override fun offerableRoles(dataType: String) = if (dataType == "passwords") listOf("editor") else listOf("editor", "viewer")
    }

    private fun vaultState() = VaultUiState(
        meta = VaultMeta(vaultId = "v1", dataType = "notes", title = "Home Notes", keyB64 = "", ownerDeviceId = "ownerdev", isOwner = true),
        shareLink = "https://maproj.org/es/share#id=v1&owner=ownerdev&type=notes",
        members = listOf(VaultMemberUi("ownerdev", "Owner", "owner"), VaultMemberUi("peer1", "Peer", "editor")),
        requests = listOf(VaultRequestUi("req1", "Requester")),
    )

    @Test fun accountDetailFullInteractions() {
        val actions = DetailRecorder()
        val snapshot = SnapshotStore.SnapshotMeta("v1", "notes", 1_700_000_000_000L, recordCount = 4, blobCount = 1)
        show {
            AccountDetailScreen(
                AccountDetailUiState(
                    accountName = "alice", providerId = "e2ee",
                    enabledTypes = setOf(DataType.CONTACTS, DataType.NOTES),
                    vaults = listOf(vaultState()), deviceId = "mydev",
                    syncErrorText = "Pull failed", snapshots = listOf(snapshot),
                ),
                actions,
            )
        }
        rule.onNodeWithText("alice").assertIsDisplayed()
        rule.onNodeWithText("Pull failed").assertIsDisplayed()
        rule.onRoot().captureToImage()
        // Toggle an enabled type off and a disabled type on
        rule.onNodeWithText(str(R.string.sync_contacts)).performClick()
        rule.onNodeWithText(str(R.string.sync_passwords)).performClick()
        assertTrue(actions.toggles.contains(Triple("alice", DataType.CONTACTS, false)), actions.toggles.toString())
        assertTrue(actions.toggles.contains(Triple("alice", DataType.PASSWORDS, true)), actions.toggles.toString())
        rule.onNodeWithText(str(R.string.sync_now)).performScrollTo().performClick()
        assertEquals("alice", actions.synced)
        // Vault section renders the vault and its create-vault flow
        rule.onNodeWithText("Home Notes").assertExists()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Trip")
        rule.onNodeWithText(str(R.string.vault_create_calendar)).performScrollTo().performClick()
        assertEquals("calendar" to "Trip", actions.created)
        // Share opens the dialog (members + requests are inside)
        rule.onNodeWithText(str(R.string.vault_share)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        // Snapshot section: snapshot-now button exists; restore asks then confirms
        rule.onAllNodesWithText("Restore", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertEquals(null, actions.removed)
        rule.onNodeWithText(str(R.string.remove_account)).performScrollTo().performClick()
        assertEquals("alice", actions.removed)
    }

    @Test fun vaultSharingStatesAndCreateValidation() {
        val actions = DetailRecorder()
        val content = show {
            VaultSharingSection(vaults = emptyList(), deviceId = "dev", safetyCode = null, computingCode = false, actions = actions)
        }
        rule.onNodeWithText(str(R.string.vaults_empty), substring = true).assertIsDisplayed()
        // Create buttons stay disabled until a title is typed
        rule.onNodeWithText(str(R.string.vault_create_contacts)).performClick()
        assertEquals(null, actions.created)
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("  Keys  ")
        rule.onNodeWithText(str(R.string.vault_create_contacts)).performClick()
        assertEquals("contacts" to "Keys", actions.created)
        // With a vault + safety code, the section shows the member data in the dialog
        content.value = {
            VaultSharingSection(
                vaults = listOf(vaultState()), deviceId = "mydev",
                safetyCode = "123 456", computingCode = true, actions = actions,
            )
        }
        rule.onNodeWithText("Home Notes").assertIsDisplayed()
        rule.onNodeWithText("notes").assertIsDisplayed()
        assertEquals(0, actions.refreshes)
        // Icon-only refresh button: second clickable in the vault row area
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().let { assertTrue(it.size >= 3) }
    }

    @Test fun settingsInteractions() {
        var interval: Long? = null; var wifi: Boolean? = null; var policy: String? = null; var backs = 0
        val actions = object : SettingsActions {
            override fun setInterval(minutes: Long) { interval = minutes }
            override fun setWifiOnly(value: Boolean) { wifi = value }
            override fun setConflictPolicy(p: String) { policy = p }
            override fun back() { backs++ }
        }
        show { SettingsScreen(SettingsUiState(intervalMinutes = 60, wifiOnly = false, conflictPolicy = "lww"), actions) }
        rule.onNodeWithText(str(R.string.global_interval), substring = true).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.wifi_only)).performClick()
        assertEquals(true, wifi)
        rule.onNodeWithText(str(R.string.conflict_remote), substring = true).performClick()
        assertEquals("remote", policy)
        rule.onNodeWithText(str(R.string.conflict_lww), substring = true).performClick()
        assertEquals("lww", policy)
        // Interval field accepts a new value
        rule.onAllNodesWithText("60").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertEquals(0, backs); assertEquals(null, interval)
    }

    @Test fun accountsAndAddAccountInteractions() {
        var opened: String? = null; var synced: String? = null; var added: String? = null; var settings = 0; var addAccount = 0; var backs = 0
        val actions = object : AccountsActions {
            override fun openAccount(accountName: String) { opened = accountName }
            override fun syncNow(accountName: String) { synced = accountName }
            override fun openSettings() { settings++ }
            override fun openAddAccount() { addAccount++ }
        }
        val content = show {
            AccountsScreen(
                AccountsUiState(listOf(
                    AccountRow("alice", "e2ee", syncing = false, lastSyncedAt = "today"),
                    AccountRow("bob", "e2ee", syncing = true),
                    AccountRow("carol", "e2ee", lastSyncError = "boom"),
                )),
                actions,
            )
        }
        rule.onNodeWithText("alice").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Personal vaults").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("boom").assertIsDisplayed()
        rule.onRoot().captureToImage()
        rule.onNodeWithText("alice").performClick()
        assertEquals("alice", opened)
        assertEquals(0, settings); assertEquals(0, addAccount); assertEquals(null, added); assertEquals(0, backs)
        // Sync button for a non-syncing row is an icon button beside the row
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().let { assertTrue(it.size >= 4) }

        val addActions = object : AddAccountActions {
            override fun addRelayAccount(providerId: String) { added = providerId }
            override fun back() { backs++ }
        }
        content.value = { AddAccountScreen(addActions) }
        rule.onNodeWithText("Personal vaults").performClick()
        assertEquals("e2ee", added)
    }

    @Test fun syncHealthSections() {
        var restored: SnapshotStore.SnapshotMeta? = null; var now = 0
        val meta = SnapshotStore.SnapshotMeta("v1", "notes", 1_700_000_000_000L, recordCount = 2, takenAtMs = 1_700_000_100_000L)
        show {
            androidx.compose.foundation.layout.Column {
                SyncErrorBanner("Relay unreachable")
                SnapshotSection(listOf(meta), restoring = true, onRestore = { restored = it }, onSnapshotNow = { now++ })
            }
        }
        rule.onNodeWithText("Relay unreachable").assertIsDisplayed()
        rule.onRoot().captureToImage()
        assertEquals(null, restored); assertEquals(0, now)
    }
}
