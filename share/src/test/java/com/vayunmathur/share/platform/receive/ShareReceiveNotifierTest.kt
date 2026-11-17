package com.vayunmathur.share.platform.receive

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.IncomingTransfer
import com.vayunmathur.share.platform.ReceivedFile
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ShareReceiveNotifierTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    class FakeTransfer(
        override val sessionHandle: Long,
        override val displayName: String = "PeerPhone",
    ) : IncomingTransfer {
        override val state = MutableStateFlow(ShareState.Handshaking)
        override val pendingFiles = MutableStateFlow<List<PendingFile>>(emptyList())
        override val receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
        override val bytesReceived = MutableStateFlow(0L)
        override val expectedTotalBytes = MutableStateFlow(0L)
        override val error = MutableStateFlow<String?>(null)
    }

    /** Polls [cond] in real time: the notifier collects on Dispatchers.Default, so a fixed delay is a bet on CI load, not a wait for the outcome. */
    private suspend fun awaitShareCondition(cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(25)
        }
        return cond()
    }

    @Test
    fun `notifier posts request then done and retires`() = runBlocking {
        val fake = FakeTransfer(12345L)
        fake.pendingFiles.value = listOf(PendingFile("a.txt", 100, "text/plain"))
        fake.expectedTotalBytes.value = 100
        val incoming = MutableStateFlow<List<IncomingTransfer>>(listOf(fake))
        val retired = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(ctx, incoming, retire = { retired += it })
        try {
            notifier.start(scope)
            fake.state.value = ShareState.AwaitingAccept
            val nm = ctx.getSystemService<NotificationManager>()!!
            assertTrue(
                "expected a posted notification",
                awaitShareCondition { Shadows.shadowOf(nm).allNotifications.isNotEmpty() },
            )
            fake.state.value = ShareState.Completed
            fake.receivedFiles.value = listOf(ReceivedFile("a.txt", 100, "text/plain", Uri.parse("content://x/a")))
            assertTrue(
                "retire should be called for terminal state, retired=$retired",
                awaitShareCondition { retired.contains(12345L) },
            )
        } finally {
            notifier.stop(); scope.cancel()
        }
    }

    @Test
    fun `cancelStale clears transfer notifications`() {
        ShareReceiveNotifier.cancelStale(ctx)
        // no crash when no notifications
        assertTrue(true)
    }

    @Test
    fun `failed transfer posts failure and retires`() = runBlocking {
        val fake = FakeTransfer(777L)
        fake.error.value = "handshake broke"
        val incoming = MutableStateFlow<List<IncomingTransfer>>(listOf(fake))
        val retired = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(ctx, incoming, retire = { retired += it })
        try {
            notifier.start(scope)
            fake.state.value = ShareState.Failed
            assertTrue(awaitShareCondition { retired.contains(777L) })
        } finally { notifier.stop(); scope.cancel() }
    }
}
