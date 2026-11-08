package com.vayunmathur.games.voxels.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.data.WorldManager
import com.vayunmathur.games.voxels.network.FakeVoxelsRelay
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.network.VoxelsSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MenuActivity online flows against [FakeVoxelsRelay]: hosting, share-by-id
 * invites, inbound join-request approve/deny, and join-link handling.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class MenuActivityOnlineTest {
    private val composeRule = createAndroidComposeRule<MenuActivity>()
    @get:Rule val rule = RuleChain.outerRule(SeedRule()).around(composeRule)
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    companion object {
        const val OWNED_WORLD_ID = "owned-world-123"
        const val REQUESTER = "requester-device-42"
    }

    private class SeedRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<Context>()
                FakeVoxelsRelay.reset()
                FakeVoxelsRelay.install()
                runBlocking { VoxelsSync.init(ctx) }
                val myId = VoxelsSync.deviceId
                if (myId.isNotBlank()) {
                    FakeVoxelsRelay.keys[myId] = Base64.encode(VoxelsSync.publicBundle)
                }
                // a peer whose bundle the fake relay can serve (our own bundle: sealable)
                FakeVoxelsRelay.keys[REQUESTER] = Base64.encode(VoxelsSync.publicBundle)
                FakeVoxelsRelay.keys["owner-remote"] = Base64.encode(VoxelsSync.publicBundle)
                // pretend validated internet so Host/online affordances enable
                runCatching {
                    com.vayunmathur.library.util.ConnectivityMonitor.start(ctx)
                    val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
                    val caps = android.net.NetworkCapabilities()
                    caps.javaClass.getMethod("addCapability", Int::class.javaPrimitiveType).invoke(caps, android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    caps.javaClass.getMethod("addCapability", Int::class.javaPrimitiveType).invoke(caps, android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    caps.javaClass.getMethod("addTransportType", Int::class.javaPrimitiveType).invoke(caps, android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    val network = org.robolectric.shadows.ShadowNetwork.newInstance(7)
                    org.robolectric.Shadows.shadowOf(cm).networkCallbacks.forEach { it.onCapabilitiesChanged(network, caps) }
                }
                WorldManager.listWorlds(ctx).forEach { WorldManager.deleteWorld(ctx, it.id) }
                // an owned online world + a sealed join request in our inbox for it
                val keyB64 = Base64.encode(VoxelsSync.newWorldKey())
                WorldManager.createOnlineWorld(ctx, "Owned Online", 9, OWNED_WORLD_ID, keyB64, myId, 1_700_000_100_000)
                val reqJson = Json.encodeToString(
                    VoxelsSync.JoinRequest.serializer(),
                    VoxelsSync.JoinRequest(worldId = OWNED_WORLD_ID, requesterId = REQUESTER, name = "Alice"),
                )
                runBlocking {
                    VoxelsSync.appendRaw(
                        "inbox:$myId",
                        listOf(Base64.encode(VoxelsSync.seal(VoxelsSync.publicBundle, reqJson.encodeToByteArray()))),
                    )
                }
                base.evaluate()
            }
        }
    }

    private fun scrollDown(times: Int = 3) {
        repeat(times) {
            composeRule.onRoot().performTouchInput {
                swipe(start = Offset(center.x, center.y * 1.6f), end = Offset(center.x, center.y * 0.4f), durationMillis = 250)
            }
            composeRule.waitForIdle()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `approve join request sends invite and marks handled`() {
        scrollDown(4)
        awaitText("Alice")
        composeRule.onNodeWithText("Approve").performClick()
        // invite sealed into the requester's inbox + roster appended + handled persisted
        val deadline = System.currentTimeMillis() + 8000
        var handled = false
        while (System.currentTimeMillis() < deadline) {
            handled = WorldManager.handledRequests(ctx).contains("$OWNED_WORLD_ID|$REQUESTER")
            if (handled) break
            Thread.sleep(50)
        }
        assertTrue(handled, "handled=${WorldManager.handledRequests(ctx)}")
        assertTrue(FakeVoxelsRelay.logs["inbox:$REQUESTER"].orEmpty().isNotEmpty())
        assertTrue(FakeVoxelsRelay.logs["members:$OWNED_WORLD_ID"].orEmpty().isNotEmpty())
    }

    @Test fun `host online creates online world and starts game`() {
        composeRule.onNodeWithText("Host online world").performClick()
        val deadline = System.currentTimeMillis() + 8000
        var hosted: com.vayunmathur.games.voxels.data.WorldInfo? = null
        while (System.currentTimeMillis() < deadline) {
            hosted = WorldManager.onlineWorlds(ctx).firstOrNull { it.meta.worldId != OWNED_WORLD_ID }
            if (hosted != null) break
            composeRule.waitForIdle()
            Thread.sleep(50)
        }
        assertNotNull(hosted)
        assertEquals(VoxelsRoles.OWNER, hosted.meta.role)
        val started = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(true, started.getBooleanExtra("online", false))
        assertEquals(hosted.meta.worldId, started.getStringExtra("world_id"))
        assertEquals(1, started.getIntExtra("net_role", -1))
    }

    @Test fun `share owned world by device id sends invite`() {
        scrollDown()
        awaitText("Owned Online")
        // share icon on the owned online row (only share affordance on screen)
        val shares = composeRule.onAllNodesWithContentDescription("Share").fetchSemanticsNodes()
        assertTrue(shares.isNotEmpty())
        composeRule.onAllNodesWithContentDescription("Share")[0].performClick()
        awaitText("Share world")
        composeRule.onNodeWithText("Recipient device ID").performTextInput(REQUESTER)
        composeRule.onNodeWithText("Send invite").performClick()
        val deadline = System.currentTimeMillis() + 8000
        var sent = false
        while (System.currentTimeMillis() < deadline) {
            sent = FakeVoxelsRelay.logs["inbox:$REQUESTER"].orEmpty().isNotEmpty()
            if (sent) break
            Thread.sleep(50)
        }
        assertTrue(sent)
    }

    @Test fun `cold start join link sends join request`() {
        // A tapped voxels://join link with the app closed launches MenuActivity via a VIEW
        // intent (cold start → onCreate, not onNewIntent). The join request must still go out.
        val joinIntent = Intent(ctx, MenuActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("voxels://join/cold-world-9?owner=owner-remote")
        }
        // Scenario (not a bare Robolectric controller): a bare controller's Compose content
        // never gets its initial composition under Robolectric, so the join effect would
        // never run. The rule's waitForIdle pumps the scenario activity's frames.
        val scenario = androidx.test.core.app.ActivityScenario.launch<MenuActivity>(joinIntent)
        val deadline = System.currentTimeMillis() + 10000
        var sent = false
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            sent = FakeVoxelsRelay.logs["inbox:owner-remote"].orEmpty().isNotEmpty()
            if (sent) break
            Thread.sleep(50)
        }
        scenario.close()
        assertTrue(sent, "inbox:owner-remote=${FakeVoxelsRelay.logs["inbox:owner-remote"]}")
    }
}
