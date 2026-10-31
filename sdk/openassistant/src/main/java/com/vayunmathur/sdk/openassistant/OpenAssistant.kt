package com.vayunmathur.sdk.openassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAssistant(private val context: Context, private val timeoutMs: Long = 60_000L) {

    companion object {
        private const val OA_PACKAGE = "com.vayunmathur.openassistant"
        private const val OA_SERVICE = "$OA_PACKAGE.util.InferenceService"
        private const val DEFAULT_SCHEMA =
            """{"type":"object","properties":{"response":{"type":"string"}},"required":["response"]}"""
    }

    suspend fun generate(prompt: String): String {
        if (!isAvailable()) throw AssistantNotInstalledException()
        return withTimeout(timeoutMs) {
            dispatchInference(prompt, schema = null)
        }
    }

    suspend fun generateJson(prompt: String, schema: String): String {
        if (!isAvailable()) throw AssistantNotInstalledException()
        return withTimeout(timeoutMs) {
            dispatchInference(prompt, schema)
        }
    }

    fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(OA_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun close() {
        // No persistent connection to clean up; reserved for future use.
    }

    private suspend fun dispatchInference(prompt: String, schema: String?): String =
        suspendCancellableCoroutine { cont ->
            val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
                if (code == 0) {
                    val result = data?.getString("json_result") ?: ""
                    cont.resume(result)
                } else {
                    val error = data?.getString("error") ?: "Inference failed"
                    cont.resumeWithException(AssistantException(error))
                }
            }

            val intent = Intent().apply {
                component = ComponentName(OA_PACKAGE, OA_SERVICE)
                putExtra("user_text", prompt)
                putExtra("schema", schema ?: DEFAULT_SCHEMA)
                putExtra("RECEIVER", receiver as ResultReceiver)
            }

            try {
                context.startForegroundService(intent)
            } catch (e: SecurityException) {
                val message = "Failed to start InferenceService: ${e.message}"
                cont.resumeWithException(AssistantException(message))
            } catch (e: IllegalStateException) {
                val message = "Failed to start InferenceService: ${e.message}"
                cont.resumeWithException(AssistantException(message))
            }
        }
}
