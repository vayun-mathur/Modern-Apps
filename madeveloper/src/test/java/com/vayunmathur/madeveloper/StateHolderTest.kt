package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.platform.MaDevUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for the main screen state holder defaults. */
class StateHolderTest {
    @Test fun defaultStateIsUnpairedAndDisconnected() {
        val state = MaDevUiState(pairingCode = "ABC234")
        assertEquals(false, state.connected)
        assertEquals("ABC234", state.pairingCode)
        assertEquals(0, state.pending.size)
    }

    @Test fun repoShortSha() {
        val repo = GitRepo("Modern-Apps", "https://git.maproj.org/Modern-Apps.git", null)
        assertEquals("Modern-Apps", repo.name)
    }
}
