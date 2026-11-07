package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.VideoFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto module: [AutoViewModel] — the phone status screen's flow mirror. It
 * never writes: every exposed flow re-emits [AutoSessionState], starting
 * from the documented defaults until a collector subscribes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoViewModelTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        AutoSessionState.onDisconnected()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        AutoSessionState.onDisconnected()
    }

    private var store = ViewModelStore()

    private fun viewModel(): AutoViewModel {
        store.clear()
        store = ViewModelStore()
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                AutoViewModel() as T
        })[AutoViewModel::class.java]
    }

    @Test
    fun `defaults mirror the disconnected session`() {
        val vm = viewModel()
        assertEquals(AutoConnectionState.Disconnected, vm.connection.value)
        assertNull(vm.video.value)
        assertNull(vm.focusMode.value)
        assertEquals(VideoFocus.NONE, vm.videoFocus.value)
        assertEquals(false, vm.inputAllowed.value)
        assertEquals(0L, vm.framesSent.value)
        assertEquals(0L, vm.acksSeen.value)
        assertEquals(0L, vm.ackMismatches.value)
        assertEquals(0.0, vm.encodedFps.value)
        assertEquals(0.0, vm.ackFps.value)
        assertNull(vm.lastAckAgeMs.value)
        assertNull(vm.lastAckAt.value)
        assertNull(vm.lastAckSeq.value)
        assertNull(vm.avgEncodeLatencyUs.value)
        assertEquals(0L, vm.encoderDrains.value)
        assertEquals(false, vm.surfaceValid.value)
        assertNull(vm.nowPlaying.value)
        assertEquals(0L, vm.threadsPosted.value)
        assertEquals(0L, vm.messagesPosted.value)
        assertEquals(0L, vm.repliesReceived.value)
        assertEquals(0L, vm.touchesInjected.value)
        assertEquals(0L, vm.keysInjected.value)
        assertEquals(0L, vm.scrollsInjected.value)
        assertEquals(0L, vm.inputDropped.value)
        assertEquals(emptyMap(), vm.sinkStatus.value)
        assertEquals(0L, vm.audioBytesSent.value)
        assertEquals(0L, vm.musicBytesCaptured.value)
        assertEquals(0L, vm.micTurns.value)
        assertEquals(0L, vm.micAcks.value)
        assertEquals(0L, vm.ttsSpoken.value)
        assertNull(vm.credentialDaysLeft.value)
        assertNull(vm.sessionStartedAt.value)
        store.clear()
    }

    @Test
    fun `an active session flows through to the viewmodel`() {
        AutoSessionState.onSocketAccepted()
        AutoSessionState.onActive("Test Car")
        val vm = viewModel()
        // stateIn(WhileSubscribed) only subscribes upstream while collected:
        // a collector must be active before .value reflects the session.
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.connection.collect() }
        try {
            assertEquals(AutoConnectionState.Projecting("Test Car"), vm.connection.value)
        } finally {
            job.cancel()
            store.clear()
        }
    }
}
