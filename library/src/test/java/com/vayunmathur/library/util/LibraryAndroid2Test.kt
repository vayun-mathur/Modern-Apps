package com.vayunmathur.library.util

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.sensor.DeviceOrientation
import com.vayunmathur.library.sensor.OrientationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications, IntentHelper, DataStore companion/flows, orientation state, intents IPC. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryAndroid2Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun notificationChannelCreateUpdateDelete() {
        ctx.ensureNotificationChannel("chan1", "First Name", NotificationManager.IMPORTANCE_HIGH, "desc1")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val created = nm.getNotificationChannel("chan1")
        assertNotNull(created)
        assertEquals("First Name", created.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, created.importance)
        // Re-creating with a new name refreshes it (the bug the helper exists to fix).
        ctx.ensureNotificationChannel("chan1", "Renamed", NotificationManager.IMPORTANCE_HIGH, "desc2")
        assertEquals("Renamed", nm.getNotificationChannel("chan1").name.toString())
        ctx.deleteNotificationChannel("chan1")
        assertNull(nm.getNotificationChannel("chan1"))
        ctx.deleteNotificationChannel("never-existed") // no-op, must not throw
    }

    @Test fun maNotificationsPostAndBroadcast() {
        val app = ctx.applicationContext as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id = MaNotifications.post(
            ctx, "ma-chan", "MA Channel", notificationId = 77,
            launchIntent = Intent(ctx, ComponentActivity::class.java),
        ) { builder ->
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("HelloTitle").setContentText("body")
        }
        assertEquals(77, id)
        val shadow = Shadows.shadowOf(ctx.getSystemService(NotificationManager::class.java))
        assertEquals(1, shadow.allNotifications.size)
        assertEquals("HelloTitle", shadow.allNotifications[0].extras.getString("android.title"))

        val bad = MaNotifications.post(ctx, "ma-chan", "MA Channel", notificationId = 78) {
            throw IllegalStateException("builder boom")
        }
        assertNull(bad)

        val pending = MaNotifications.broadcastIntent(ctx, Intent("com.example.PING"), 5)
        assertNotNull(pending)

        // Without the runtime permission the post is refused with null.
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val denied = MaNotifications.post(ctx, "ma-chan", "MA Channel", notificationId = 79) { builder ->
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        }
        assertNull(denied)
    }

    @Test fun liveUpdateSupportOnSdk34() {
        assertEquals(36, LiveUpdateSupport.LIVE_UPDATE_SDK)
        assertFalse(LiveUpdateSupport.isStyleSupported())
        assertFalse(LiveUpdateSupport.isPromotedOngoingSupported())
        assertFalse(LiveUpdateSupport.canPostPromoted(ctx))
    }

    @Test fun intentHelperExtractsUrisPerAction() {
        val view = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("file:///docs/a.pdf") }
        assertEquals(listOf(Uri.parse("file:///docs/a.pdf")), IntentHelper.getUrisFromIntent(view))

        val single = Uri.parse("content://media/1")
        val send = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, single) }
        assertEquals(listOf(single), IntentHelper.getUrisFromIntent(send))

        val many = arrayListOf(Uri.parse("content://media/2"), Uri.parse("content://media/3"))
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
        }
        assertEquals(many, IntentHelper.getUrisFromIntent(multi))

        assertEquals(emptyList(), IntentHelper.getUrisFromIntent(Intent(Intent.ACTION_VIEW)))
        assertEquals(emptyList(), IntentHelper.getUrisFromIntent(Intent("com.example.OTHER")))
    }

    @Test fun intentHelperFileNameFromPathAndContentFallback() {
        assertEquals("report.pdf", IntentHelper.getFileName(ctx, Uri.parse("file:///storage/docs/report.pdf")))
        assertEquals("report.pdf", IntentHelper.getFileName(ctx, Uri.parse("/storage/docs/report.pdf")))
        // content:// with no provider registered resolves through the path fallback.
        assertEquals("42", IntentHelper.getFileName(ctx, Uri.parse("content://com.example.provider/items/42")))
    }

    @Test fun dataStoreSyncGettersFlowsAndSetOps() = runBlocking {
        val store = DataStoreUtils.getInstance(ctx)
        val suffix = System.nanoTime().toString()
        store.setDouble("dbl$suffix", 3.25)
        assertEquals(3.25, store.doubleFlow("dbl$suffix").first())
        assertEquals(3.25, store.getDouble("dbl$suffix") ?: store.doubleFlow("dbl$suffix").first())
        store.setLong("lng$suffix", 10, onlyIfAbsent = true)
        store.setLong("lng$suffix", 20, onlyIfAbsent = true)
        assertEquals(10L, store.getLongAwait("lng$suffix"))
        assertEquals(10L, store.longFlow("lng$suffix").first())
        assertEquals(99L, store.longFlow("missing$suffix", 99L).first())
        assertEquals(true, store.booleanFlow("missingb$suffix", true).first())

        store.addStringToSet("set$suffix", "one")
        withTimeout(5000) { while (!store.getStringSetAwait("set$suffix").contains("one")) delay(20) }
        store.removeStringFromSet("set$suffix", "one")
        withTimeout(5000) { while (store.getStringSetAwait("set$suffix").isNotEmpty()) delay(20) }

        // copyFrom: empty keys and empty source are no-ops.
        assertEquals(0, store.copyFrom(store, emptyList(), overwrite = false))
        // getInstance is a singleton per storage mode.
        assertTrue(store === DataStoreUtils.getInstance(ctx))
        val dps = DataStoreUtils.getInstance(ctx, deviceProtected = true)
        assertTrue(dps === DataStoreUtils.getInstance(ctx, deviceProtected = true))
        assertFalse(store === dps)
        assertEquals(0, DataStoreUtils.seedDeviceProtectedStorage(ctx, listOf("no-such-key-$suffix")))
        store.setString("seed$suffix", "seeded")
        assertEquals(1, DataStoreUtils.seedDeviceProtectedStorage(ctx, listOf("seed$suffix")))
        assertEquals("seeded", dps.getStringAwait("seed$suffix"))
    }

    @Test fun deviceOrientationRadiansAndDefaults() {
        val o = DeviceOrientation(
            azimuthTrueDeg = 90.0, azimuthMagDeg = 80.0, pitchDeg = 10.0, rollDeg = -5.0,
            pointingAzTrueDeg = 180.0, pointingAltDeg = 45.0, viewRotationDeg = 30.0,
        )
        assertEquals(PI, o.pointingAzRad, 1e-9)
        assertEquals(PI / 4, o.pointingAltRad, 1e-9)
        assertEquals(PI / 6, o.viewRotationRad, 1e-9)
        val d = DeviceOrientation(azimuthTrueDeg = 12.0, azimuthMagDeg = 12.0, pitchDeg = 0.0, rollDeg = 0.0)
        assertEquals(12.0, d.pointingAzTrueDeg) // pointing defaults to azimuth
        assertEquals(0.0, d.pointingAltDeg)
    }

    @Test fun orientationManagerLifecycleAndLocation() {
        val mgr = OrientationManager(ctx)
        assertNull(mgr.orientation.value)
        mgr.updateLocation(37.7749, -122.4194, 10f)
        mgr.start()
        mgr.start() // idempotent while running
        mgr.onAccuracyChanged(null, android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        mgr.stop()
        mgr.stop() // idempotent while stopped
        mgr.start()
        mgr.stop()
        assertNull(mgr.orientation.value) // no sensor events delivered by Robolectric
    }

    @Test fun findActivityWalksWrapperChain() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertEquals(activity, wrapped.findActivity<ComponentActivity>())
        assertNull(ctx.findActivity<ComponentActivity>())
    }

    @Test fun esimLinkBuildsProvisioningUri() {
        assertEquals("https", EsimLink.SCHEME)
        assertEquals("esimsetup.android.com", EsimLink.HOST)
        assertEquals("com.vayunmathur.euicc", EsimLink.LPA_PACKAGE)
        val uri = EsimLink.provisioningUri("LPA:1\$example.com\$CODE-1")
        assertEquals("esimsetup.android.com", uri.host)
        assertEquals("/esim_qrcode_provisioning", uri.path)
        assertEquals("LPA:1\$example.com\$CODE-1", uri.getQueryParameter("carddata"))
    }

    @Test fun formatAndLocaleExtras() {
        assertEquals("3", 3.14159.toStringDigits(0))
        assertEquals("-1,234", (-1234L).toStringCommas())
        assertEquals("0.500", 0.5.toStringDigits(3))
        assertTrue(0f.formatSpeed(Locale.US).isNotBlank())
        assertEquals("Jan", localizedMonthNames(DateNameStyle.SHORT, Locale.US)[0])
        assertEquals(12, localizedMonthNames(DateNameStyle.NARROW, Locale.US).size)
        assertEquals("Mon", localizedDayOfWeekNames(DateNameStyle.SHORT, Locale.US)[0])
        assertEquals(7, localizedDayOfWeekNames(DateNameStyle.NARROW, Locale.US).size)
        val markers = localizedAmPmMarkers(Locale.JAPAN)
        assertTrue(markers.first.isNotBlank() && markers.second.isNotBlank())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), localeWeekDayNumbers(Locale.GERMANY))
    }

    @Test fun localizedAmPmMarkerInTimeFormat() {
        val format = kotlinx.datetime.LocalTime.Format {
            localizedAmPmMarker(Locale.US)
        }
        val rendered = format.format(kotlinx.datetime.LocalTime(13, 5))
        assertTrue(rendered.contains("PM"), rendered)
        val lower = kotlinx.datetime.LocalTime.Format {
            localizedAmPmMarker(Locale.US, lowercase = true)
        }
        assertTrue(lower.format(kotlinx.datetime.LocalTime(13, 5)).contains("pm"))
    }

    class CalcIntent : AssistantIntent<com.vayunmathur.library.intents.notes.NoteData, com.vayunmathur.library.intents.notes.NoteData>(
        com.vayunmathur.library.intents.notes.NoteData.serializer(),
        com.vayunmathur.library.intents.notes.NoteData.serializer(),
    ) {
        override suspend fun performCalculation(
            input: com.vayunmathur.library.intents.notes.NoteData,
        ) = input.copy(title = input.title.uppercase())
    }

    class FailingIntent : AssistantIntent<com.vayunmathur.library.intents.notes.NoteData, com.vayunmathur.library.intents.notes.NoteData>(
        com.vayunmathur.library.intents.notes.NoteData.serializer(),
        com.vayunmathur.library.intents.notes.NoteData.serializer(),
    ) {
        override suspend fun performCalculation(
            input: com.vayunmathur.library.intents.notes.NoteData,
        ): com.vayunmathur.library.intents.notes.NoteData = throw RuntimeException("calc boom")
    }

    private fun intentWith(data: String?, results: MutableList<Pair<Int, Bundle?>>): Intent {
        val receiver = SecureResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) { code, bundle ->
            results += code to bundle
        }
        return Intent(Intent.ACTION_MAIN).apply {
            if (data != null) putExtra("DATA", data)
            putExtra("RECEIVER", receiver)
        }
    }

    @Test fun assistantIntentDecodesCalculatesAndReplies() = runBlocking {
        val results = mutableListOf<Pair<Int, Bundle?>>()
        val json = """{"title":"hello","content":"body"}"""
        Robolectric.buildActivity(CalcIntent::class.java, intentWith(json, results)).create()
        withTimeout(8000) { while (results.isEmpty()) delay(20) }
        assertEquals(android.app.Activity.RESULT_OK, results[0].first)
        val response = results[0].second!!.getString("RESPONSE_DATA")!!
        assertTrue(response.contains("HELLO"), response)
    }

    @Test fun intentLauncherBuildsIntentAndStaysPendingWithoutResponse() = runBlocking {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        val launcher = IntentLauncher(activity)
        // Robolectric's parcel shadow drops the ResultReceiver binder, so the
        // simulated target can never answer; assert the launch side instead:
        // the intent is built for the right component with the JSON payload,
        // and the call stays bounded-pending until cancelled.
        val deferred = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            launcher.launch(
                ctx, "com.example.target", "com.example.target.AnswerIntent",
                com.vayunmathur.library.intents.notes.NoteData.serializer(),
                com.vayunmathur.library.intents.notes.NoteData("payload-title", "c"),
            )
        }
        var started: Intent? = null
        withTimeout(10_000) {
            while (started == null) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                started = Shadows.shadowOf(activity).nextStartedActivity
                if (started == null) delay(20)
            }
        }
        assertEquals("com.example.target", started!!.component?.packageName)
        assertEquals("com.example.target.AnswerIntent", started!!.component?.className)
        assertTrue(started!!.getStringExtra("DATA")!!.contains("payload-title"))
        assertNotNull(started!!.getParcelableExtra<android.os.ResultReceiver>("RECEIVER"))
        assertFalse(deferred.isCompleted)
        deferred.cancel()
    }

    @Test fun assistantIntentMissingAndBadInputCancel() = runBlocking {
        val missing = mutableListOf<Pair<Int, Bundle?>>()
        Robolectric.buildActivity(CalcIntent::class.java, intentWith(null, missing)).create()
        withTimeout(8000) { while (missing.isEmpty()) delay(20) }
        assertEquals(android.app.Activity.RESULT_CANCELED, missing[0].first)

        val bad = mutableListOf<Pair<Int, Bundle?>>()
        Robolectric.buildActivity(CalcIntent::class.java, intentWith("{not json", bad)).create()
        withTimeout(8000) { while (bad.isEmpty()) delay(20) }
        assertEquals(android.app.Activity.RESULT_CANCELED, bad[0].first)

        val failing = mutableListOf<Pair<Int, Bundle?>>()
        Robolectric.buildActivity(FailingIntent::class.java, intentWith("""{"title":"x","content":"y"}""", failing)).create()
        withTimeout(8000) { while (failing.isEmpty()) delay(20) }
        assertEquals(android.app.Activity.RESULT_CANCELED, failing[0].first)
    }

}
