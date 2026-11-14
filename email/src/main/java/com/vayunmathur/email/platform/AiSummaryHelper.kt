package com.vayunmathur.email.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.library.util.SecureResultReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** OpenAssistant summarization extracted from [EmailViewModel] to keep it under the function cap. */
class AiSummaryHelper(private val appContext: Context) {

    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun clear() {
        _summary.value = null
        _loading.value = false
    }

    fun request(messages: List<EmailPreview>) {
        if (_loading.value) return

        try {
            appContext.packageManager.getPackageInfo(OA_PACKAGE, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return
        }

        _loading.value = true
        _summary.value = null

        val emailSnippets = messages.take(SNIPPET_COUNT).joinToString("\n---\n") { msg ->
            val plainBody = msg.peekContent.take(SNIPPET_CHARS)
            "Subject: ${msg.subject}\nFrom: ${senderDisplayName(msg.from)}\n$plainBody"
        }
        val prompt = "Summarize these emails in 1-2 sentences:\n\n$emailSnippets"
        val schema = """{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}"""

        val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
            if (code == 0) {
                val json = data?.getString("json_result")
                if (json != null) {
                    try {
                        val obj = Json.parseToJsonElement(json).jsonObject
                        _summary.value = obj["summary"]?.jsonPrimitive?.content
                    } catch (_: IllegalArgumentException) { }
                }
            }
            _loading.value = false
        }

        val intent = Intent().apply {
            component = ComponentName(OA_PACKAGE, OA_SERVICE)
            putExtra("user_text", prompt)
            putExtra("schema", schema)
            putExtra("RECEIVER", receiver as android.os.ResultReceiver)
        }

        try {
            appContext.startForegroundService(intent)
        } catch (_: SecurityException) {
            _loading.value = false
        } catch (_: IllegalStateException) {
            _loading.value = false
        }
    }

    /** Holds the OpenAssistant inference service coordinates used for summaries. */
    companion object {
        private const val OA_PACKAGE = "com.vayunmathur.openassistant"
        private const val OA_SERVICE = "$OA_PACKAGE.util.InferenceService"
        private const val SNIPPET_COUNT = 5
        private const val SNIPPET_CHARS = 150
    }
}
