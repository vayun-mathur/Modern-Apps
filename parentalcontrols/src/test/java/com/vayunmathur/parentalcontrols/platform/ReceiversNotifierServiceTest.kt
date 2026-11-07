package com.vayunmathur.parentalcontrols.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.parentalcontrols.notifications.LockNotifier
import com.vayunmathur.parentalcontrols.receiver.BedtimeReceiver
import com.vayunmathur.parentalcontrols.receiver.BootReceiver
import com.vayunmathur.parentalcontrols.receiver.LimitReachedReceiver
import com.vayunmathur.parentalcontrols.service.SupervisionMessengerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReceiversNotifierServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun receiverIntentBuilders() {
        val limit = LimitReachedReceiver.intent(ctx, "com.example.game")
        assertEquals("com.example.game", limit.getStringExtra(EXTRA_PACKAGE_NAME))
        assertEquals(LimitReachedReceiver::class.java.name, limit.component?.className)
        val bedtime = BedtimeReceiver.intent(ctx)
        assertEquals(BedtimeReceiver::class.java.name, bedtime.component?.className)
        assertEquals(
            "android.app.supervision.action.SUPERVISION_MESSENGER_SERVICE",
            SupervisionMessengerService.SUPERVISION_BIND_ACTION,
        )
    }

    @Test
    fun receiversIgnoreIrrelevantBroadcasts() {
        // Wrong action: boot receiver returns before doing any async work.
        BootReceiver().onReceive(ctx, Intent("com.example.WRONG_ACTION"))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_VIEW))
        // A limit callback without a package is dropped.
        LimitReachedReceiver().onReceive(ctx, Intent())
        assertFalse(LimitState(ctx).hasReached("com.example.game"))
    }

    @Test
    fun lockNotifierPostsAndCancelsPerReason() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        val before = shadow.allNotifications.size
        for (reason in Enforcer.BlockReason.entries) {
            LockNotifier.notifyLocked(ctx, reason, "com.example.game")
        }
        assertEquals(before + Enforcer.BlockReason.entries.size, shadow.allNotifications.size)
        assertTrue(shadow.allNotifications.all { it != null })
        LockNotifier.cancel(ctx, Enforcer.BlockReason.Bedtime)
        assertEquals(before + Enforcer.BlockReason.entries.size - 1, shadow.allNotifications.size)
        // A device-wide lock carries no package.
        LockNotifier.notifyLocked(ctx, Enforcer.BlockReason.DailyLimit, null)
        assertNotNull(shadow.allNotifications.lastOrNull())
    }

    @Test
    fun messengerServiceRepliesDeniedToNonSettingsCallers() {
        val controller = Robolectric.buildService(SupervisionMessengerService::class.java).create()
        val service = controller.get()
        val binder = service.onBind(Intent(ctx, SupervisionMessengerService::class.java))
        assertNotNull(binder)
        val client = Messenger(binder)

        val replies = mutableListOf<Message>()
        val latch = CountDownLatch(2)
        val replyThread = HandlerThread("reply-test").apply { start() }
        val replyMessenger = Messenger(object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                replies.add(Message.obtain(msg).also { it.data = msg.peekData() })
                latch.countDown()
            }
        })

        fun request(apiId: Int, uid: Int) {
            val msg = Message.obtain(null, apiId, 42, 0)
            msg.sendingUid = uid
            msg.replyTo = replyMessenger
            client.send(msg)
        }
        // The binder delivery stamps the caller's real uid, so neither a stranger uid nor a
        // forged system uid survives as Settings: both are refused with arg2 = 1, and the
        // api id / transaction id are echoed for correlation.
        request(SupervisionApis.IS_SUPERVISOR_ACCOUNT, 10_999)
        request(SupervisionApis.PREFERENCE_DATA, Process.SYSTEM_UID)
        assertTrue(latch.await(10, TimeUnit.SECONDS), "no replies: $replies")
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.arg2 == 1 })
        assertEquals(setOf(SupervisionApis.IS_SUPERVISOR_ACCOUNT, SupervisionApis.PREFERENCE_DATA), replies.map { it.what }.toSet())
        assertTrue(replies.all { it.arg1 == 42 })
        controller.destroy()
    }

    @Test
    fun messengerServiceAnswersSettingsUidForKnownAndUnknownApis() {
        // Register a fake Settings package whose uid is this process's uid; binder delivery
        // stamps the real calling uid, so the caller check then passes honestly.
        val pm = ctx.packageManager
        val settingsInfo = android.content.pm.PackageInfo().apply {
            packageName = "com.android.settings"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.android.settings"
                uid = Process.myUid()
            }
        }
        shadowOf(pm).installPackage(settingsInfo)
        val controller = Robolectric.buildService(SupervisionMessengerService::class.java).create()
        val service = controller.get()
        val client = Messenger(service.onBind(Intent()))

        val replies = mutableListOf<Message>()
        val latch = CountDownLatch(3)
        val replyThread = HandlerThread("reply-test-ok").apply { start() }
        val replyMessenger = Messenger(object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                replies.add(Message.obtain(msg).also { it.data = msg.peekData() })
                latch.countDown()
            }
        })
        fun request(apiId: Int) {
            val msg = Message.obtain(null, apiId, 5, 0)
            msg.replyTo = replyMessenger
            client.send(msg)
        }
        request(SupervisionApis.PREFERENCE_DATA)
        request(SupervisionApis.SUPPORTED_APPS)
        request(SupervisionApis.IS_SUPERVISOR_ACCOUNT)
        assertTrue(latch.await(10, TimeUnit.SECONDS), "replies=$replies")
        assertEquals(3, replies.size)
        assertTrue(replies.all { it.arg2 == 0 && it.arg1 == 5 })
        val supervisor = replies.first { it.what == SupervisionApis.IS_SUPERVISOR_ACCOUNT }
        assertFalse(supervisor.peekData()?.getBoolean("is_supervisor_account") ?: true)
        controller.destroy()
    }

    @Test
    fun messengerServiceUnknownApiAndNoReplyTo() {
        val controller = Robolectric.buildService(SupervisionMessengerService::class.java).create()
        val service = controller.get()
        val client = Messenger(service.onBind(Intent()))

        val replies = mutableListOf<Message>()
        val latch = CountDownLatch(1)
        val replyThread = HandlerThread("reply-test-2").apply { start() }
        val replyMessenger = Messenger(object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                replies.add(Message.obtain(msg))
                latch.countDown()
            }
        })
        // No replyTo: ignored, must not throw.
        client.send(Message.obtain(null, SupervisionApis.PREFERENCE_DATA, 1, 0))
        val unknown = Message.obtain(null, 999, 7, 0)
        unknown.sendingUid = Process.SYSTEM_UID
        unknown.replyTo = replyMessenger
        client.send(unknown)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(1, replies.single().arg2) // denied before the unknown-api dispatch
        assertEquals(7, replies.single().arg1)
        controller.destroy()
    }
}
