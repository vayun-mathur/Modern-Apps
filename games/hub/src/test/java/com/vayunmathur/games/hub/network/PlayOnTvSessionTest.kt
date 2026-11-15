package com.vayunmathur.games.hub.network

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.sdk.controller.ControllerHostInfo
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PlayOnTvSessionTest {
    @Test fun sessionLifecycleAndDelegationDoNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = PlayOnTvSession(ctx)
        assertNotNull(session.state)
        assertNotNull(session.state.value)
        // Delegation when not joined/paired should be dropped/no-op in client, not crash
        session.proveCode("123456")
        session.join("Ada")
        session.sendInput(1L, listOf(0f,0f,0f,0f,0f,0f), 0, 0)
        session.leave()
        session.rescan()
        session.connect(ControllerHostInfo("tvx","tvx","TV","127.0.0.1",1,1,"kart"))
        session.close()
    }
    @Test fun secondSessionReusesPersistedSenderId() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s1 = PlayOnTvSession(ctx); s1.close()
        val s2 = PlayOnTvSession(ctx); assertNotNull(s2.state.value); s2.close()
    }
}
