package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.games.voxels.domain.NetFailure
import com.vayunmathur.games.voxels.domain.NetStatus
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class OnlineHudTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `peer ids are read from the engine json`() {
        assertEquals(setOf("a", "b"), peerIds("""[{"device":"a","x":0},{"device":"b"}]"""))
        assertEquals(emptySet(), peerIds("junk"))
    }

    @Test fun `a fresh join says connecting, not connection lost`() {
        rule.setContent { OnlineHud(NetStatus.Connecting, "[]", ownerDevice = "host", isHost = false) }
        rule.onNodeWithText("Connecting…").assertExists()
        rule.onNodeWithText("Connection lost — waiting to reconnect…").assertDoesNotExist()
    }

    @Test fun `joiners see whether the host is here`() {
        rule.setContent { OnlineHud(NetStatus.Connected, """[{"device":"host"}]""", ownerDevice = "host", isHost = false) }
        rule.onNodeWithText("Host is here").assertExists()
        rule.onNodeWithText("2 online").assertExists()
    }

    @Test fun `failures say why`() {
        rule.setContent { OnlineHud(NetStatus.Failed(NetFailure.BAD_INVITE), "[]", ownerDevice = "h", isHost = false) }
        rule.onNodeWithText("This world's invite is damaged — ask the host to invite you again.").assertExists()
    }
}
