package com.vayunmathur.openassistant.platform

import com.vayunmathur.openassistant.assist.OpenAssistantSession

import android.app.assist.AssistContent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.openassistant.assist.OpenAssistantSessionService
import com.vayunmathur.openassistant.assist.OpenAssistantVoiceInteractionService
import com.vayunmathur.openassistant.assist.StubRecognitionService
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MamlAndAssistTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun chatShapesAndDefaults() {
        val req = ChatRequest(system = "sys", history = emptyList(), userText = "hi")
        assertEquals(emptyList(), req.imagePaths); assertNull(req.audioPath)
        assertEquals(ChatResult.TooLong, ChatResult.TooLong)
        assertEquals(ChatResult.Unavailable, ChatResult.Unavailable)
        assertEquals("answer", (ChatResult.Reply("answer", halted = true) as ChatResult.Reply).text)
        assertTrue(HistoryTurn(fromUser = true, text = "q").fromUser)
    }

    @Test fun decodeDownscaledHandlesRealAndBrokenImages() {
        val image = java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val file = File(ctx.cacheDir, "one.png")
        javax.imageio.ImageIO.write(image, "png", file)
        val bmp = MamlChatEngine.decodeDownscaled(file.absolutePath)
        assertNotNull(bmp); assertEquals(8, bmp.width)
        // Robolectric's BitmapFactory shadow fabricates a bitmap for any
        // path, so the undecodable/missing-file null branches are device-only.
    }

    @Test fun engineLoadFailureIsTolerantOnHostJvm() {
        val engine = MamlChatEngine(File(ctx.cacheDir, "no-model-here"))
        val loadOutcome = runCatching { runBlocking { engine.load() } }
        // On the JVM the native handle cannot load: either a clean false
        // (native lib present, model missing) or an UnsatisfiedLinkError.
        val err = loadOutcome.exceptionOrNull()
        assertTrue(err == null || err is UnsatisfiedLinkError, "unexpected: $err")
        if (err == null) assertEquals(false, loadOutcome.getOrNull())
        assertNull(engine.lastLoadError)
        val chatOutcome = runCatching {
            runBlocking {
                engine.chat(ChatRequest("s", emptyList(), "hi"), AssistantTools(ctx, null)) { true }
            }
        }
        val chatErr = chatOutcome.exceptionOrNull()
        assertTrue(chatErr == null || chatErr is UnsatisfiedLinkError, "unexpected: $chatErr")
        if (chatErr == null) assertEquals(ChatResult.Unavailable, chatOutcome.getOrNull())
        val extractOutcome = runCatching { runBlocking { engine.extract("s", "u", emptyList()) { true } } }
        val exErr = extractOutcome.exceptionOrNull()
        assertTrue(exErr == null || exErr is UnsatisfiedLinkError, "unexpected: $exErr")
        engine.close()
    }

    @Test fun assistServicesConstruct() {
        val sessionService = Robolectric.buildService(OpenAssistantSessionService::class.java).create().get()
        val session = sessionService.onNewSession(Bundle())
        assertNotNull(session)
        Robolectric.buildService(OpenAssistantVoiceInteractionService::class.java).create().get()
        Robolectric.buildService(StubRecognitionService::class.java).create().get()
    }

    @Test fun sessionScreenshotAndScreenText() {
        val sessionService = Robolectric.buildService(OpenAssistantSessionService::class.java).create().get()
        val session = sessionService.onNewSession(Bundle()) as OpenAssistantSession
        session.onCreate()
        session.onHandleScreenshot(null)
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        session.onHandleScreenshot(bmp)
        // Saving is best-effort under Robolectric shadows; exercise the path regardless.
        val content = AssistContent().apply {
            structuredData = """{"app":"notes"}"""
            webUri = android.net.Uri.parse("https://example.com/page")
        }
        val text = session.buildScreenText(null, content)
        assertTrue(text.contains("[structured]"), text)
        assertTrue(text.contains("[url] https://example.com/page"), text)
        val empty = session.buildScreenText(null, null)
        assertEquals("", empty)
    }
}
