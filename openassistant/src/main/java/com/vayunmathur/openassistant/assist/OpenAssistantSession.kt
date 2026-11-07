package com.vayunmathur.openassistant.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.vayunmathur.library.log.Log
import com.vayunmathur.openassistant.MainActivity
import java.io.File
import java.io.FileOutputStream

/**
 * The session that actually receives the current screen when the user triggers the
 * assist gesture while OpenAssistant is the default digital assistant AND "use screen
 * context" is enabled. It flattens the [AssistStructure] view tree into a single text
 * blob (visible text + content descriptions), folds in any [AssistContent], then hands
 * off to [MainActivity] via [startVoiceActivity] so the chat can answer about the screen.
 *
 * We render no custom session UI — [setUiEnabled] is false and MainActivity is the UI.
 */
class OpenAssistantSession(context: Context) : VoiceInteractionSession(context) {

    private var screenshotPath: String? = null

    override fun onCreate() {
        super.onCreate()
        // We hand off to MainActivity for UI; don't draw a session overlay.
        setUiEnabled(false)
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val text = buildScreenText(state.assistStructure, state.assistContent)
        Log.dev(TAG, "Captured screen text (${text.length} chars)")
        handOffToChat(text)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null) return
        try {
            val file = File(context.cacheDir, "assist_screenshot.png")
            FileOutputStream(file).use { out ->
                screenshot.compress(Bitmap.CompressFormat.PNG, SCREENSHOT_QUALITY, out)
            }
            screenshotPath = file.absolutePath
        } catch (expected: Exception) {
            Log.status(TAG, "Failed to save assist screenshot", expected)
        }
    }

    /** Flattens the view tree + assist content into a single length-capped string. */
    internal fun buildScreenText(
        structure: AssistStructure?,
        content: AssistContent?,
    ): String {
        val sb = StringBuilder()
        if (structure != null) {
            val windowCount = structure.windowNodeCount
            for (i in 0 until windowCount) {
                if (sb.length >= MAX_CHARS) break
                val root = structure.getWindowNodeAt(i).rootViewNode
                appendNode(root, sb)
            }
        }
        content?.let { c ->
            c.structuredData?.takeIf { it.isNotBlank() }?.let {
                appendCapped(sb, "\n[structured] $it")
            }
            c.webUri?.let { appendCapped(sb, "\n[url] $it") }
        }
        return sb.toString().trim().take(MAX_CHARS)
    }

    private fun appendNode(node: AssistStructure.ViewNode?, sb: StringBuilder) {
        if (node == null || sb.length >= MAX_CHARS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendCapped(sb, it)
        }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendCapped(sb, it)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (sb.length >= MAX_CHARS) return
            appendNode(node.getChildAt(i), sb)
        }
    }

    private fun appendCapped(sb: StringBuilder, s: String) {
        if (sb.length >= MAX_CHARS) return
        if (sb.isNotEmpty()) sb.append('\n')
        val remaining = MAX_CHARS - sb.length
        if (s.length <= remaining) sb.append(s) else sb.append(s, 0, remaining)
    }

    private fun handOffToChat(text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_SCREEN_TEXT, text)
            screenshotPath?.let { putExtra(EXTRA_SCREENSHOT_PATH, it) }
        }
        startVoiceActivity(intent)
    }

    companion object {
        private const val TAG = "OpenAssistantSession"
        private const val MAX_CHARS = 6000
        private const val SCREENSHOT_QUALITY = 90

        const val EXTRA_SCREEN_TEXT = "assist_screen_text"
        const val EXTRA_SCREENSHOT_PATH = "assist_screenshot_path"
    }
}
