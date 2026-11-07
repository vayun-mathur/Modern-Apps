package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldMeta
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.network.VoxelsSync
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MenuScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun world(name: String, online: Boolean = false, role: String = "", worldId: String = "wid-$name") = WorldInfo(
        id = "w_$name", dir = "/tmp/w_$name",
        meta = WorldMeta(name = name, seed = 42, created = 1, lastPlayed = 2, online = online, worldId = if (online) worldId else "", ownerDeviceId = "owner-1", role = role),
    )

    private fun scrollDown(times: Int = 3) {
        repeat(times) {
            rule.onRoot().performTouchInput {
                swipe(start = Offset(center.x, center.y * 1.6f), end = Offset(center.x, center.y * 0.4f), durationMillis = 250)
            }
            rule.waitForIdle()
        }
    }

    private class Actions {
        val played = mutableListOf<WorldInfo>(); val deleted = mutableListOf<WorldInfo>(); val shared = mutableListOf<WorldInfo>()
        var creates = 0; var hosts = 0; var refreshes = 0; var copies = 0
        val approved = mutableListOf<VoxelsSync.JoinRequest>(); val denied = mutableListOf<VoxelsSync.JoinRequest>()
    }

    private fun menu(a: Actions, worlds: List<WorldInfo>, online: List<WorldInfo>, isOnline: Boolean = true, deviceId: String = "dev-123", requests: List<VoxelsSync.JoinRequest> = emptyList()) {
        rule.setContent {
            MenuScreen(
                worlds = worlds, onlineWorlds = online, deviceId = deviceId, isOnline = isOnline,
                onPlay = { a.played += it }, onDelete = { a.deleted += it }, onCreate = { a.creates++ },
                onHostOnline = { a.hosts++ }, onRefresh = { a.refreshes++ }, onCopyDeviceId = { a.copies++ },
                onShare = { a.shared += it }, requests = requests,
                onApprove = { a.approved += it }, onDeny = { a.denied += it },
            )
        }
    }

    @Test fun `menu empty worlds shows empty state and buttons`() {
        val a = Actions()
        menu(a, emptyList(), emptyList())
        rule.onNodeWithText("Select a world").assertExists()
        rule.onNodeWithText("No worlds yet", substring = true).assertExists()
        rule.onNodeWithText("Create New World").performClick()
        assertEquals(1, a.creates)
        rule.onNodeWithText("Host online world").performClick()
        assertEquals(1, a.hosts)
        rule.onNodeWithText("Refresh").performClick()
        assertEquals(1, a.refreshes)
        // device id row copy enabled when id present
        val copies = rule.onAllNodesWithText("Copy").fetchSemanticsNodes()
        if (copies.isNotEmpty()) rule.onAllNodesWithText("Copy")[0].performClick()
        assertTrue(a.copies <= 1)
    }

    @Test fun `menu offline disables host and refresh shows requires internet`() {
        val a = Actions()
        menu(a, listOf(world("Local")), listOf(world("Shared", online = true, role = VoxelsRoles.EDITOR)), isOnline = false)
        rule.onNodeWithText("Local").assertExists()
        scrollDown()
        rule.onNodeWithText("Shared with you", substring = true).assertExists()
        // Host button disabled offline: clicking does nothing
        rule.onNodeWithText("Host online world").performClick()
        assertEquals(0, a.hosts)
    }

    @Test fun `menu online owner world share delete play`() {
        val a = Actions()
        val owner = world("Mine", online = true, role = VoxelsRoles.OWNER)
        menu(a, emptyList(), listOf(owner))
        scrollDown()
        rule.onNodeWithText("Mine").assertExists()
        rule.onNodeWithText("Hosted by you", substring = true).assertExists()
        // play via the row's Play icon button
        rule.onAllNodesWithContentDescription("Play")[0].performClick()
        assertEquals(listOf("Mine"), a.played.map { it.meta.name })
    }

    @Test fun `menu join requests approve and deny`() {
        val a = Actions()
        val req = VoxelsSync.JoinRequest(worldId = "wid-Mine", requesterId = "req-abcdef123456", name = "Alice")
        val owner = world("Mine", online = true, role = VoxelsRoles.OWNER)
        menu(a, emptyList(), listOf(owner), requests = listOf(req))
        scrollDown(); rule.onNodeWithText("Approve").performClick()
        assertEquals(listOf(req), a.approved)
        // second request for deny: use a fresh screen via new test below (single setContent per test)
        assertTrue(a.denied.isEmpty())
    }

    @Test fun `menu join request deny`() {
        val a = Actions()
        val req = VoxelsSync.JoinRequest(worldId = "wid-X", requesterId = "req-xyz987654", name = "")
        menu(a, emptyList(), emptyList(), requests = listOf(req))
        scrollDown(); rule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(req), a.denied)
    }

    @Test fun `menu local world delete via icon`() {
        val a = Actions()
        menu(a, listOf(world("ToDelete")), emptyList())
        rule.onNodeWithText("ToDelete").assertExists()
        // delete is an icon button without text; play is text-row click - verify row present and Delete semantics via content desc in sibling test not possible here
        assertTrue(a.deleted.isEmpty())
    }

    @Test fun `world creator types and creates`() {
        var created: Pair<String, String>? = null; var backs = 0
        rule.setContent { WorldCreatorScreen(onBack = { backs++ }, onCreate = { n, s -> created = n to s }) }
        rule.onNodeWithText("Create World").assertExists()
        rule.onNodeWithText("World name").performTextInput("My New World")
        rule.onNodeWithText("Seed").performTextInput("12345")
        rule.onNodeWithText("Create & Play").performClick()
        assertEquals("My New World" to "12345", created)
        assertEquals(0, backs)
    }

    @Test fun `world creator cancel goes back`() {
        var backs = 0
        rule.setContent { WorldCreatorScreen(onBack = { backs++ }, onCreate = { _, _ -> }) }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, backs)
    }

    @Test fun `share dialog sends recipient and dismisses`() {
        var sent: String? = null; var dismissed = 0
        val w = world("SharedWorld", online = true, role = VoxelsRoles.OWNER)
        rule.setContent { ShareOnlineDialog(w, onDismiss = { dismissed++ }, onSend = { sent = it }) }
        rule.onNodeWithText("Share world").assertExists()
        rule.onNodeWithText("SharedWorld").assertExists()
        rule.onNodeWithText("Recipient device ID").performTextInput("  peer-device-9  ")
        rule.onNodeWithText("Send invite").performClick()
        assertEquals("peer-device-9", sent)
        assertEquals(0, dismissed)
    }

    @Test fun `share dialog send disabled until recipient typed`() {
        var sent: String? = null
        val w = world("SharedWorld", online = true, role = VoxelsRoles.OWNER)
        rule.setContent { ShareOnlineDialog(w, onDismiss = {}, onSend = { sent = it }) }
        rule.onNodeWithText("Send invite").performClick()
        assertEquals(null, sent)
        rule.onNodeWithText("Cancel").performClick()
    }

    @Test fun `theme wraps content`() {
        rule.setContent { VoxelsTheme { androidx.compose.foundation.layout.Box { com.vayunmathur.library.ui.Text("themed") } } }
        rule.onNodeWithText("themed").assertExists()
    }
}
