package com.vayunmathur.speech.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.speech.testing.FakeAudioRecordShadow
import com.vayunmathur.speech.testing.ThrowingDetachFdShadow
import com.vayunmathur.speech.util.WhisperEngine
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhisperHarness : WhisperRecognitionService() {
    fun start(intent: Intent, cb: RecordingRecognitionCallback) = onStartListening(intent, cb.callback)
    fun stop(cb: RecordingRecognitionCallback) = onStopListening(cb.callback)
    fun cancel(cb: RecordingRecognitionCallback) = onCancel(cb.callback)
    override fun createEngine(): WhisperEngine =
        engineFactory?.invoke(applicationContext) ?: super.createEngine()
    companion object { var engineFactory: ((Context) -> WhisperEngine)? = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDetachFdShadow::class])
class WhisperRecognitionServiceTest {

    @Test
    fun startListeningErrorsWhenModelUnavailable() {
        val service = Robolectric.buildService(WhisperHarness::class.java).create().get()
        val cb = RecordingRecognitionCallback(service)
        service.start(Intent(), cb)
        assertEquals(listOf(SpeechRecognizer.ERROR_SERVER), cb.errors.toList())
        assertEquals(0, cb.readyCalls)
    }

    @Test
    fun stopCancelAndDestroyWithoutSessionAreSafe() {
        val controller = Robolectric.buildService(WhisperHarness::class.java).create()
        val service = controller.get()
        val cb = RecordingRecognitionCallback(service)
        service.stop(cb)
        service.cancel(cb)
        controller.destroy()
        assertTrue(cb.errors.isEmpty())
        assertTrue(cb.resultsTexts.isEmpty())
    }
}

/** Sessions driven by a scripted microphone and a fake Whisper engine. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDetachFdShadow::class, FakeAudioRecordShadow::class])
class WhisperRecognitionSessionTest {

    class FakeWhisperEngine(context: Context) : WhisperEngine(context) {
        var modelPresent = true
        var transcript: String? = " hello world "
        val languages = mutableListOf<String?>()
        var transcribeCalls = 0
        var closed = false
        override fun isModelPresent(): Boolean = modelPresent
        override fun preload(): Boolean = modelPresent
        override fun transcribe(pcm16k: ShortArray, language: String?): String? {
            transcribeCalls++
            languages += language
            return transcript
        }
        override fun close() { closed = true }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun grantMic() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun fakeService(configure: FakeWhisperEngine.() -> Unit = {}): Pair<WhisperHarness, FakeWhisperEngine> {
        val created = java.util.concurrent.CountDownLatch(1)
        var fake: FakeWhisperEngine? = null
        WhisperHarness.engineFactory = { ctx ->
            FakeWhisperEngine(ctx).apply(configure).also { fake = it; created.countDown() }
        }
        try {
            val harness = Robolectric.buildService(WhisperHarness::class.java).create().get()
            // onCreate warms the engine on a thread; wait for the factory to run.
            assertTrue(created.await(10, TimeUnit.SECONDS), "fake engine was never created")
            return harness to fake!!
        } finally {
            WhisperHarness.engineFactory = null
        }
    }

    private fun intentWithLang(lang: String?): Intent = Intent().apply {
        if (lang != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
    }

    @Test
    fun missingMicPermissionErrorsWithInsufficientPermissions() {
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(listOf(10000))
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en-US"), cb)
        assertTrue(cb.awaitTerminal())
        assertEquals(listOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), cb.errors.toList())
        assertEquals(0, cb.readyCalls)
    }

    @Test
    fun speechThenSilenceDeliversPartialAndFinalResult() {
        grantMic()
        val (service, fake) = fakeService()
        FakeAudioRecordShadow.reset(listOf(10000, 10000, 10000) + List(15) { 0 })
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en-US"), cb)
        assertTrue(cb.awaitTerminal(), "no terminal callback; errors=${cb.errors} results=${cb.resultsTexts}")
        assertEquals(listOf("hello world"), cb.resultsTexts.toList())
        assertTrue(cb.partialTexts.contains("hello world"), "partials=${cb.partialTexts}")
        assertEquals(1, cb.readyCalls)
        assertEquals(1, cb.beginningCalls)
        assertEquals(1, cb.endOfSpeechCalls)
        assertTrue(cb.errors.isEmpty(), "errors=${cb.errors}")
        assertTrue(fake.languages.isNotEmpty() && fake.languages.all { it == "en" }, "langs=${fake.languages}")
        assertTrue(cb.rmsValues.isNotEmpty())
        assertEquals(10f, cb.rmsValues.max())
        assertEquals(0f, cb.rmsValues.min())
    }

    @Test
    fun noSpeechTimesOutWithNoMatch() {
        grantMic()
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(emptyList())
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang(null), cb)
        assertTrue(cb.awaitTerminal())
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), cb.errors.toList())
        assertTrue(cb.resultsTexts.isEmpty())
        assertEquals(0, cb.beginningCalls)
        assertTrue(FakeAudioRecordShadow.reads >= 80, "reads=${FakeAudioRecordShadow.reads}")
    }

    @Test
    fun blankTranscriptAfterSpeechIsNoMatch() {
        grantMic()
        val (service, _) = fakeService { transcript = "   " }
        FakeAudioRecordShadow.reset(listOf(10000, 10000) + List(15) { 0 })
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("fr-FR"), cb)
        assertTrue(cb.awaitTerminal())
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), cb.errors.toList())
        assertTrue(cb.resultsTexts.isEmpty())
        assertTrue(cb.partialTexts.isEmpty())
    }

    @Test
    fun nullTranscriptAfterSpeechIsNoMatch() {
        grantMic()
        val (service, _) = fakeService { transcript = null }
        FakeAudioRecordShadow.reset(listOf(10000) + List(15) { 0 })
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang(""), cb)
        assertTrue(cb.awaitTerminal())
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), cb.errors.toList())
    }

    @Test
    fun secondStartWhileBusyIsRejected() {
        grantMic()
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(listOf(10000, FakeAudioRecordShadow.BLOCK))
        val first = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en"), first)
        assertTrue(FakeAudioRecordShadow.blockEntered!!.await(10, TimeUnit.SECONDS))
        val second = RecordingRecognitionCallback(service)
        service.start(intentWithLang("de"), second)
        assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), second.errors.toList())
        service.cancel(first)
        FakeAudioRecordShadow.releaseBlock()
    }

    @Test
    fun userStopEndsSessionWithFinalResult() {
        grantMic()
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(listOf(10000, 10000, FakeAudioRecordShadow.BLOCK))
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("de-DE"), cb)
        assertTrue(FakeAudioRecordShadow.blockEntered!!.await(10, TimeUnit.SECONDS))
        service.stop(cb)
        FakeAudioRecordShadow.releaseBlock()
        assertTrue(cb.awaitTerminal(), "errors=${cb.errors} results=${cb.resultsTexts}")
        assertEquals(listOf("hello world"), cb.resultsTexts.toList())
        // German BCP-47 reduces to Whisper's ISO code.
        assertTrue(cb.errors.isEmpty())
    }

    @Test
    fun cancelClearsSessionSoNextStartWorks() {
        grantMic()
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(listOf(10000, FakeAudioRecordShadow.BLOCK))
        val first = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en"), first)
        assertTrue(FakeAudioRecordShadow.blockEntered!!.await(10, TimeUnit.SECONDS))
        service.cancel(first)
        FakeAudioRecordShadow.releaseBlock()
        assertTrue(first.errors.isEmpty())
        assertTrue(first.resultsTexts.isEmpty())

        FakeAudioRecordShadow.reset(emptyList())
        val second = RecordingRecognitionCallback(service)
        service.start(intentWithLang(null), second)
        assertTrue(second.awaitTerminal())
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), second.errors.toList())
    }

    @Test
    fun longSpeechHitsDurationCapAndTranscribes() {
        grantMic()
        val (service, _) = fakeService()
        // 29 s cap: 290 chunks of 100 ms of continuous speech.
        FakeAudioRecordShadow.reset(List(400) { 10000 })
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("ja"), cb)
        assertTrue(cb.awaitTerminal())
        assertEquals(listOf("hello world"), cb.resultsTexts.toList())
    }

    @Test
    fun persistentReadErrorsReportAudioErrorInsteadOfSpinning() {
        grantMic()
        val (service, _) = fakeService()
        // A dead microphone: every read fails. Failed reads advance no audio
        // clock, so without an error bound the loop would never terminate.
        FakeAudioRecordShadow.reset(List(200) { AudioRecord.ERROR_INVALID_OPERATION })
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en"), cb)
        assertTrue(cb.awaitTerminal(), "no terminal callback; errors=${cb.errors}")
        assertEquals(listOf(SpeechRecognizer.ERROR_AUDIO), cb.errors.toList())
        assertTrue(cb.resultsTexts.isEmpty())
    }

    @Test
    fun transientReadErrorsDoNotAbortTheSession() {
        grantMic()
        val (service, _) = fakeService()
        FakeAudioRecordShadow.reset(
            listOf(AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_INVALID_OPERATION) +
                listOf(10000, 10000, 10000) + List(15) { 0 },
        )
        val cb = RecordingRecognitionCallback(service)
        service.start(intentWithLang("en"), cb)
        assertTrue(cb.awaitTerminal(), "no terminal callback; errors=${cb.errors} results=${cb.resultsTexts}")
        assertEquals(listOf("hello world"), cb.resultsTexts.toList())
        assertTrue(cb.errors.isEmpty(), "errors=${cb.errors}")
    }
}
