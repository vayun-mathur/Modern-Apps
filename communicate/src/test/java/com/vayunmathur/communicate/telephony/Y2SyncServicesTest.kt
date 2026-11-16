package com.vayunmathur.communicate.telephony

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import com.vayunmathur.communicate.data.rcs.buildIsComposingBody
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.domain.RingerMode
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Y2 wave-1: sync-service lifecycles (`RcsSyncServiceKt`,
 * `GoogleVoiceSyncServiceKt`, `SignalSyncServiceKt`, `WhatsAppSyncServiceKt`),
 * the call ringer (`CallRingerKt` / `RingEnvironmentReaderKt`) and the SMS
 * deliver receiver (`SmsDeliverReceiverKt`).
 *
 * Services run under `Robolectric.setupService` with no stored credentials,
 * so the asynchronous credential checks shut them down again — that shutdown
 * is the behavioural assert, alongside the synchronous START_* contracts.
 * The ringer asserts through the Vibrator/AudioManager shadows; the SMS
 * receiver is fed a real GSM PDU so the full insert path runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y2SyncServicesTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var rcsDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        rcsDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = rcsDb
        RingLine.entries.forEach(CallRinger::stop)
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @AfterTest
    fun tearDown() {
        RingLine.entries.forEach(CallRinger::stop)
        RcsDatabase.overrideForTest = null
        rcsDb.close()
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    // ---- companion start/stop intents ----

    @Test
    fun `all sync service companions issue foreground intents with stop actions`() {
        GoogleVoiceSyncService.start(app)
        var intent = Shadows.shadowOf(app).nextStartedService
        assertEquals(GoogleVoiceSyncService::class.java.name, intent.component?.className)
        assertNull(intent.action)
        GoogleVoiceSyncService.stop(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals("com.vayunmathur.communicate.googlevoice.STOP_SYNC", intent.action)

        SignalSyncService.start(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals(SignalSyncService::class.java.name, intent.component?.className)
        SignalSyncService.stop(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals("com.vayunmathur.communicate.signal.STOP_SYNC", intent.action)

        WhatsAppSyncService.start(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals(WhatsAppSyncService::class.java.name, intent.component?.className)
        WhatsAppSyncService.stop(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals("com.vayunmathur.communicate.whatsapp.STOP_SYNC", intent.action)

        RcsSyncService.start(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals(RcsSyncService::class.java.name, intent.component?.className)
        RcsSyncService.stop(app)
        intent = Shadows.shadowOf(app).nextStartedService
        assertEquals("com.vayunmathur.communicate.rcs.STOP_SYNC", intent.action)
    }

    // ---- Google Voice ----

    @Test
    fun `google voice service stops on the stop action and binds nothing`() {
        val service = Robolectric.setupService(GoogleVoiceSyncService::class.java)
        assertNull(service.onBind(Intent()))
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.googlevoice.STOP_SYNC" }, 0, 1,
        )
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        service.onDestroy()
    }

    @Test
    fun `google voice service without credentials shuts itself down`() {
        val service = Robolectric.setupService(GoogleVoiceSyncService::class.java)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        // Channels exist from the synchronous foreground entry.
        val nm = app.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).notificationChannels.map { it.id }.any { it.contains("gv") },
            "channels=${Shadows.shadowOf(nm).notificationChannels.map { it.id }}")
        awaitTrue { Shadows.shadowOf(service).isStoppedBySelf }
        service.onTimeout(1, 0)
        service.onDestroy()
    }

    // ---- Signal / WhatsApp ----

    @Test
    fun `signal service without credentials shuts itself down`() {
        val service = Robolectric.setupService(SignalSyncService::class.java)
        assertNull(service.onBind(Intent()))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        awaitTrue { Shadows.shadowOf(service).isStoppedBySelf }
        service.onTimeout(1, 0)
        service.onDestroy()
    }

    @Test
    fun `whatsApp service without credentials shuts itself down`() {
        val service = Robolectric.setupService(WhatsAppSyncService::class.java)
        assertNull(service.onBind(Intent()))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        awaitTrue { Shadows.shadowOf(service).isStoppedBySelf }
        service.onTimeout(1, 0)
        service.onDestroy()
    }

    @Test
    fun `signal and whatsApp stop actions are not sticky`() {
        val signal = Robolectric.setupService(SignalSyncService::class.java)
        assertEquals(
            Service.START_NOT_STICKY,
            signal.onStartCommand(
                Intent().apply { action = "com.vayunmathur.communicate.signal.STOP_SYNC" }, 0, 1,
            ),
        )
        assertTrue(Shadows.shadowOf(signal).isStoppedBySelf)
        signal.onDestroy()

        val wa = Robolectric.setupService(WhatsAppSyncService::class.java)
        assertEquals(
            Service.START_NOT_STICKY,
            wa.onStartCommand(
                Intent().apply { action = "com.vayunmathur.communicate.whatsapp.STOP_SYNC" }, 0, 1,
            ),
        )
        assertTrue(Shadows.shadowOf(wa).isStoppedBySelf)
        wa.onDestroy()
    }

    @Test
    fun `signal incoming and identity notifications post to the manager`() {
        val service = Robolectric.setupService(SignalSyncService::class.java)
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        val before = nm.allNotifications.size
        val incoming = SignalSyncService::class.java
            .getDeclaredMethod("showIncomingNotification", SignalEvent.IncomingMessage::class.java)
            .apply { isAccessible = true }
        incoming.invoke(
            service,
            SignalEvent.IncomingMessage(
                conversationId = "+14155550102", messageId = "s1", body = "signal hello",
                peerName = "Alice", peerPhone = "+14155550102", timestamp = 123L,
            ),
        )
        assertTrue(nm.allNotifications.size > before, "incoming notification posted")

        val identity = SignalSyncService::class.java
            .getDeclaredMethod("showIdentityChangeNotification", SignalEvent.IdentityKeyChanged::class.java)
            .apply { isAccessible = true }
        val event = SignalEvent.IdentityKeyChanged(
            conversationId = "+14155550102", peerAci = "aci-1",
            newIdentityKeyHex = "aabb", timestamp = 456L,
        )
        identity.invoke(service, event)
        assertNotNull(nm.getNotification("+14155550102", "aci-1".hashCode()))
        service.onDestroy()
    }

    @Test
    fun `whatsApp incoming notification posts for one-to-one and group`() {
        val service = Robolectric.setupService(WhatsAppSyncService::class.java)
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        val show = WhatsAppSyncService::class.java
            .getDeclaredMethod("showIncomingNotification", WhatsAppEvent.IncomingMessage::class.java)
            .apply { isAccessible = true }
        val before = nm.allNotifications.size
        show.invoke(
            service,
            WhatsAppEvent.IncomingMessage(
                conversationId = "14155550102@s.whatsapp.net", messageId = "w1", body = "wa hello",
                peerName = "Bob", peerPhone = "+14155550102", timestamp = 1L,
            ),
        )
        show.invoke(
            service,
            WhatsAppEvent.IncomingMessage(
                conversationId = "120207@g.us", messageId = "w2", body = "group hello",
                peerName = "Crew", peerPhone = null, timestamp = 2L, senderName = "Alice",
            ),
        )
        assertTrue(nm.allNotifications.size > before, "whatsApp notifications posted")
        service.onDestroy()
    }

    // ---- RCS ----

    @Test
    fun `rcs service stop action shuts down and non-stop goes sticky`() {
        val stopper = Robolectric.setupService(RcsSyncService::class.java)
        assertNull(stopper.onBind(Intent()))
        assertEquals(
            Service.START_NOT_STICKY,
            stopper.onStartCommand(
                Intent().apply { action = "com.vayunmathur.communicate.rcs.STOP_SYNC" }, 0, 7,
            ),
        )
        assertTrue(Shadows.shadowOf(stopper).isStoppedBySelf)
        stopper.onDestroy()

        val sticky = Robolectric.setupService(RcsSyncService::class.java)
        assertEquals(Service.START_STICKY, sticky.onStartCommand(Intent("com.example.OTHER"), 0, 8))
        sticky.onTimeout(1, 0)
        sticky.shutdown()
        assertTrue(Shadows.shadowOf(sticky).isStoppedBySelf)
        sticky.onDestroy()
    }

    @Test
    fun `rcs sync notification text changes with registration state`() {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        service.updateSyncNotification(RcsRegistrationState.Available)
        val available = nm.getNotification(4723)
        assertNotNull(available)
        service.updateSyncNotification(RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription))
        val unavailable = nm.getNotification(4723)
        assertNotNull(unavailable)
        val availableText = available.extras.getCharSequence("android.text")?.toString()
        val unavailableText = unavailable.extras.getCharSequence("android.text")?.toString()
        assertTrue(!availableText.isNullOrBlank(), "available text=$availableText")
        assertTrue(!unavailableText.isNullOrBlank(), "unavailable text=$unavailableText")
        assertFalse(availableText == unavailableText, "states must read differently: $availableText")
        service.updateSyncNotification(RcsRegistrationState.Disabled)
        service.updateSyncNotification(RcsRegistrationState.Unknown)
        service.onDestroy()
    }

    @Test
    fun `rcs msrp typing false clears and ft-shaped text stays a message`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val session = RcsSession(
            dialogId = "d-y2", callId = "call-y2", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550004001@rcs", conversationId = "+15550004001",
        )
        service.handleMsrpChunk(
            "+15550004001", session, "application/im-iscomposing+xml",
            buildIsComposingBody(true).toByteArray(),
        )
        assertEquals(
            true,
            com.vayunmathur.communicate.data.rcs.RcsSessionManager.typing.value["+15550004001"],
        )
        service.handleMsrpChunk(
            "+15550004001", session, "application/im-iscomposing+xml",
            buildIsComposingBody(false).toByteArray(),
        )
        assertEquals(
            false,
            com.vayunmathur.communicate.data.rcs.RcsSessionManager.typing.value["+15550004001"],
        )
        // IMDN for an unknown message id is absorbed by the tracker, never cached.
        val imdn = """
            <?xml version="1.0"?>
            <imdn xmlns="urn:ietf:params:xml:ns:imdn">
              <message-id>unknown-y2</message-id>
              <status><negative-delivery/></status>
            </imdn>
        """.trimIndent()
        service.handleMsrpChunk("+15550004001", session, "message/imdn+xml", imdn.toByteArray())
        assertTrue(rcsDb.cachedMessageDao().getForConversation("+15550004001").isEmpty())
        service.onDestroy()
    }

    @Test
    fun `rcs msrp connection to a dead path binds nothing`() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val session = RcsSession(
            dialogId = "d-dead", callId = "call-dead", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550004002@rcs", conversationId = "+15550004002",
            msrpRemotePath = "msrp://127.0.0.1:9/dead;tcp",
        )
        service.syncMsrpConnection("+15550004002", session, "msrp://127.0.0.1:9/dead;tcp")
        assertTrue(service.msrpConnections.isEmpty(), "no live connection to a dead path")
        assertFalse(service.boundPaths.containsKey("+15550004002"))
        service.onDestroy()
    }

    // ---- CallRinger + RingEnvironmentReader ----

    private val vibrator: Vibrator get() = app.getSystemService(Vibrator::class.java)!!
    private val audio: AudioManager get() = app.getSystemService(AudioManager::class.java)!!

    @Test
    fun `ringer replaces a call-waiting ring with a full ring when the other call ends`() {
        val waiting = RingRequest(RingLine.InApp, "call-1", number = null, callWaiting = true)
        CallRinger.start(app, waiting)
        assertFalse(Shadows.shadowOf(vibrator).isVibrating) // call-waiting: tone only, no vibration
        // Same call, no longer waiting: this is a different ring and must vibrate.
        CallRinger.start(app, waiting.copy(callWaiting = false))
        assertTrue(Shadows.shadowOf(vibrator).isVibrating)
        // Silencing from another line leaves this line's ring alone.
        CallRinger.silence(RingLine.Sim)
        assertTrue(Shadows.shadowOf(vibrator).isVibrating)
        CallRinger.silence(RingLine.InApp)
        assertFalse(Shadows.shadowOf(vibrator).isVibrating)
        CallRinger.stop(RingLine.InApp)
    }

    @Test
    fun `asIncomingRinger delegates ring and stop to the shared ringer`() {
        val ringer = CallRinger.asIncomingRinger(app)
        ringer.ring(RingRequest(RingLine.GoogleVoice, "gv-1", number = null, callWaiting = false))
        assertTrue(Shadows.shadowOf(vibrator).isVibrating)
        ringer.stop(RingLine.Sim) // wrong line: no-op
        assertTrue(Shadows.shadowOf(vibrator).isVibrating)
        ringer.stop(RingLine.GoogleVoice)
        assertFalse(Shadows.shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `ring environment maps the ringer switch and mirrors call waiting`() {
        val normal = RingEnvironmentReader.read(
            app, RingRequest(RingLine.Sim, "k1", number = null, callWaiting = false),
        )
        assertEquals(RingerMode.Normal, normal.conditions.ringerMode)
        assertFalse(normal.conditions.otherCallLive)
        assertNull(normal.customRingtone)

        val waiting = RingEnvironmentReader.read(
            app, RingRequest(RingLine.Sim, "k1", number = null, callWaiting = true),
        )
        assertTrue(waiting.conditions.otherCallLive)

        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(
            RingerMode.Silent,
            RingEnvironmentReader.read(app, RingRequest(RingLine.Sim, "k1", null, false)).conditions.ringerMode,
        )
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(
            RingerMode.Vibrate,
            RingEnvironmentReader.read(app, RingRequest(RingLine.Sim, "k1", null, false)).conditions.ringerMode,
        )
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun `ring environment reads the caller contact ringtone and starred flag`() {
        FakeContactsState.lookupRows = listOf(
            mapOf(
                ContactsContract.PhoneLookup.CUSTOM_RINGTONE to "content://media/ring/7",
                ContactsContract.PhoneLookup.STARRED to 1,
            ),
        )
        val env = RingEnvironmentReader.read(
            app, RingRequest(RingLine.Sim, "k2", number = "+14155550102", callWaiting = false),
        )
        assertEquals("content://media/ring/7", env.customRingtone?.toString())

        // A blank stored ringtone means "default", not a broken custom tone.
        FakeContactsState.lookupRows = listOf(
            mapOf(
                ContactsContract.PhoneLookup.CUSTOM_RINGTONE to "",
                ContactsContract.PhoneLookup.STARRED to 0,
            ),
        )
        assertNull(
            RingEnvironmentReader.read(
                app, RingRequest(RingLine.Sim, "k2", number = "+14155550102", callWaiting = false),
            ).customRingtone,
        )
    }

    // ---- SmsDeliverReceiver ----

    @Test
    fun `sms receiver drops garbage pdus without storing anything`() {
        val receiver = SmsDeliverReceiver()
        receiver.onReceive(
            app,
            Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
                putExtra("pdus", arrayOf(byteArrayOf(0x01, 0x02, 0x03)))
                putExtra("format", "3gpp")
                putExtra("subscription", 3)
            },
        )
        Thread.sleep(250)
        assertTrue(FakeTelephonyState.inserts.isEmpty(), "inserts=${FakeTelephonyState.inserts}")
    }

    @Test
    fun `sms receiver stores a delivered GSM pdu in the inbox and notifies`() {
        // SMS-DELIVER, sender 4155550102, GSM-7 body "hello" (E8329BFD06).
        val pdu = "00040A91145555102000002112121212120005E8329BFD06"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val receiver = SmsDeliverReceiver()
        receiver.onReceive(
            app,
            Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
                putExtra("pdus", arrayOf(pdu))
                putExtra("format", "3gpp")
                putExtra("subscription", -1)
            },
        )
        awaitTrue(5_000) {
            FakeTelephonyState.inserts.any { it.first.toString().contains("sms/inbox") }
        }
        val insert = FakeTelephonyState.inserts.first { it.first.toString().contains("sms/inbox") }
        assertEquals("hello", insert.second?.getAsString(Telephony.Sms.BODY))
        assertTrue(
            insert.second?.getAsString(Telephony.Sms.ADDRESS)?.contains("5550102") == true,
            "address=${insert.second?.getAsString(Telephony.Sms.ADDRESS)}",
        )
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_INBOX,
            insert.second?.getAsInteger(Telephony.Sms.TYPE),
        )
        // The incoming notification was posted on the SIM channel.
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        awaitTrue(5_000) { nm.allNotifications.isNotEmpty() }
    }
}
