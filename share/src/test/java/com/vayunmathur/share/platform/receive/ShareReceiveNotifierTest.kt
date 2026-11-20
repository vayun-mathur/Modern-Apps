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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
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

    // Terminal signals, not polls: `retire` is the notifier's own
    // callback (fired after the terminal notification posts), and
    // `onNotificationPosted` is its post seam. Awaiting the channel /
    // deferred below is resumed by the notifier's work on
    // Dispatchers.Default; the old helper polled a condition against a
    // 15s System.currentTimeMillis deadline, betting on CI load.

    @Test
    fun `notifier posts request then done and retires`() = runBlocking {
        val fake = FakeTransfer(12345L)
        fake.pendingFiles.value = listOf(PendingFile("a.txt", 100, "text/plain"))
        fake.expectedTotalBytes.value = 100
        val incoming = MutableStateFlow<List<IncomingTransfer>>(listOf(fake))
        val retired = Channel<Long>(Channel.UNLIMITED)
        val requestPosted = kotlinx.coroutines.CompletableDeferred<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(
            ctx, incoming,
            retire = { retired.trySend(it) },
            onNotificationPosted = { handle ->
                if (!requestPosted.isCompleted) requestPosted.complete(handle)
            },
        )
        try {
            notifier.start(scope)
            fake.state.value = ShareState.AwaitingAccept
            // Suspend until the request notification has actually posted:
            // only then is it safe to advance the state, because the
            // notifier conflates and could otherwise skip the request.
            assertEquals(12345L, requestPosted.await())
            val nm = ctx.getSystemService<NotificationManager>()!!
            assertTrue(
                "expected a posted notification",
                Shadows.shadowOf(nm).allNotifications.isNotEmpty(),
            )
            fake.state.value = ShareState.Completed
            fake.receivedFiles.value = listOf(ReceivedFile("a.txt", 100, "text/plain", Uri.parse("content://x/a")))
            // Retire fires after the Done notification posts, so this
            // receive is a wait on the terminal state itself.
            assertEquals(
                "retire should be called for terminal state",
                12345L, retired.receive(),
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
        val retired = Channel<Long>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(ctx, incoming, retire = { retired.trySend(it) })
        try {
            notifier.start(scope)
            fake.state.value = ShareState.Failed
            assertEquals(777L, retired.receive())
        } finally { notifier.stop(); scope.cancel() }
    }
}
