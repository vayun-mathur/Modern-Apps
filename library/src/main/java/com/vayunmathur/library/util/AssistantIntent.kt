package com.vayunmathur.library.util

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass

abstract class AssistantIntent<Input: Any, Output: Any>(val inputSerializer: KSerializer<Input>, val outputSerializer: KSerializer<Output>): Activity() {
    @OptIn(InternalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the incoming data
        val input = Json.decodeFromString(inputSerializer, intent.getStringExtra("DATA")!!)

        // 2. Do your "rare" processing
        val result = runBlocking { performCalculation(input) }

        // 3. Prepare the response
        val responseIntent = Intent()
        responseIntent.putExtra("RESPONSE_DATA", Json.encodeToString(outputSerializer, result))

        // 4. Send the result back to the calling app
        setResult(RESULT_OK, responseIntent)

        // 5. Vital: Close immediately!
        finish()
    }

    abstract suspend fun performCalculation(input: Input): Output
}

class IntentLauncher(activity: ComponentActivity) {
    private var continuation: CancellableContinuation<String>? = null

    // This MUST be a property so it registers during class initialization
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data?.getStringExtra("RESPONSE_DATA")
        if (data != null) {
            continuation?.resume(data)
        } else {
            continuation?.resumeWithException(Exception("No data returned"))
        }
        continuation = null
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun <Input : Any> launch(
        packageName: String,
        className: String,
        kClass: KClass<Input>,
        input: Input
    ): String = suspendCancellableCoroutine { cont ->
        continuation = cont

        val intent = Intent().apply {
            setClassName(packageName, className)
            putExtra("DATA", Json.encodeToString(kClass.serializer(), input))
        }

        cont.invokeOnCancellation { continuation = null }
        launcher.launch(intent)
    }
}