package com.vayunmathur.music.intents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Assistant intent activities on device: they finish synchronously, do
 * their work in the background and answer over a ResultReceiver. The
 * library is empty on the test device, so searches answer empty and plays
 * are no-ops — the IPC contract is what is pinned here.
 */
class MusicIntentsDeviceTest {

    private class CapturingReceiver : ResultReceiver(Handler(Looper.getMainLooper())) {
        val latch = CountDownLatch(1)
        @Volatile var code: Int? = null
        @Volatile var data: Bundle? = null

        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            code = resultCode
            data = resultData
            latch.countDown()
        }
    }

    private fun launchAndCapture(activity: Class<*>, dataJson: String?): CapturingReceiver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiver = CapturingReceiver()
        val intent = Intent(context, activity).apply {
            if (dataJson != null) putExtra("DATA", dataJson)
            putExtra("RECEIVER", receiver)
        }
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            assertTrue(
                "intent activity must answer within 30s",
                receiver.latch.await(30, TimeUnit.SECONDS),
            )
        }
        return receiver
    }

    @Test
    fun searchAnswersOkWithAnEmptyResultForAnEmptyLibrary() {
        val receiver = launchAndCapture(SearchIntent::class.java, "\"anything\"")
        assertEquals(Activity.RESULT_OK, receiver.code)
    }

    @Test
    fun searchWithMalformedInputIsCanceled() {
        val receiver = launchAndCapture(SearchIntent::class.java, "not json")
        assertEquals(Activity.RESULT_CANCELED, receiver.code)
    }

    @Test
    fun searchWithoutInputIsCanceled() {
        val receiver = launchAndCapture(SearchIntent::class.java, null)
        assertEquals(Activity.RESULT_CANCELED, receiver.code)
    }

    @Test
    fun playUnknownSongIsAnOkNoOp() {
        val receiver = launchAndCapture(
            PlayIntent::class.java,
            "{\"id\": 999999, \"type\": \"song\"}",
        )
        assertEquals(Activity.RESULT_OK, receiver.code)
    }

    @Test
    fun playFromUnknownPlaylistIsAnOkNoOp() {
        val receiver = launchAndCapture(
            PlayIntent::class.java,
            "{\"id\": 999999, \"type\": \"playlist\"}",
        )
        assertEquals(Activity.RESULT_OK, receiver.code)
    }

    @Test
    fun playWithUnknownTypeIsAnOkNoOp() {
        val receiver = launchAndCapture(
            PlayIntent::class.java,
            "{\"id\": 1, \"type\": \"genre\"}",
        )
        assertEquals(Activity.RESULT_OK, receiver.code)
    }
}
