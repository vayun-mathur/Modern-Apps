package com.vayunmathur.share.platform.receive

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.IncomingTransfer
import com.vayunmathur.share.platform.ReceivedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotifierProgressTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    class FakeTransfer(override val sessionHandle: Long) : IncomingTransfer {
        override val state = MutableStateFlow(ShareState.Handshaking)
        override val pendingFiles = MutableStateFlow(listOf(PendingFile("pic.jpg", 2048, "image/jpeg")))
        override val receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
        override val bytesReceived = MutableStateFlow(0L)
        override val expectedTotalBytes = MutableStateFlow(2048L)
        override val error = MutableStateFlow<String?>(null)
        override val displayName = "CameraPhone"
    }

    @Test
    fun `progress then done with image and generic files`() = runBlocking {
        val fake = FakeTransfer(555L)
        val incoming = MutableStateFlow<List<IncomingTransfer>>(listOf(fake))
        val retired = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = ShareReceiveNotifier(ctx, incoming, retire = { retired += it })
        try {
            notifier.start(scope)
            delay(200)
            fake.state.value = ShareState.Transferring
            fake.bytesReceived.value = 1024
            delay(400)
            fake.bytesReceived.value = 2048
            delay(200)
            fake.receivedFiles.value = listOf(
                ReceivedFile("pic.jpg", 2048, "image/jpeg", Uri.parse("content://x/pic.jpg")),
                ReceivedFile("doc.pdf", 100, "application/pdf", Uri.parse("content://x/doc.pdf")),
                ReceivedFile("mystery", 10, "application/octet-stream", Uri.parse("content://x/mystery")),
            )
            fake.state.value = ShareState.Completed
            delay(600)
            assertTrue(retired.contains(555L))
            // Removing from incoming after terminal also exercises onGone path for a fresh handle.
            val fake2 = FakeTransfer(556L)
            incoming.value = listOf(fake2)
            delay(300)
            fake2.state.value = ShareState.AwaitingAccept
            delay(400)
            incoming.value = emptyList()
            delay(400)
        } finally { notifier.stop(); scope.cancel() }
    }
}
