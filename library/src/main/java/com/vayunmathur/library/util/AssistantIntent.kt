package com.vayunmathur.library.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    @OptIn(InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the incoming data
        val inputString = intent.getStringExtra("DATA")
        if (inputString == null) {
            finish()
            return
        }
        val input = Json.decodeFromString(inputSerializer, inputString)

        // 2. Do your "rare" processing
        val result = runBlocking { performCalculation(input) }

        // 3. Prepare the response
        val responseData = Json.encodeToString(outputSerializer, result)
        val responseIntent = Intent()
        responseIntent.putExtra("RESPONSE_DATA", responseData)

        // 4. Send the result back to the calling app
        setResult(RESULT_OK, responseIntent)

        // Also send to ResultReceiver if present (useful for Services)
        val receiver = intent.getParcelableExtra<ResultReceiver>("RECEIVER")
        receiver?.send(RESULT_OK, Bundle().apply {
            putString("RESPONSE_DATA", responseData)
        })

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

    @SuppressLint("QueryPermissionsNeeded")
    @OptIn(InternalSerializationApi::class)
    suspend fun <Input : Any> launch(
        context: Context,
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

        // 1. Check if the package/activity is resolvable
        val packageManager = context.packageManager // You'll need access to context
        if (intent.resolveActivity(packageManager) == null) {
            continuation = null
            // Optional onCancellation cleanup
            cont.resume("package $packageName doesn't exist") { cause, _, _ -> // Optional onCancellation cleanup
                // Optional onCancellation cleanup
            }
            return@suspendCancellableCoroutine
        }

        // 2. Setup cancellation logic
        cont.invokeOnCancellation { continuation = null }

        // 3. Launch if everything looks good
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            continuation = null
            cont.resume("package $packageName doesn't exist")
        }
    }
}