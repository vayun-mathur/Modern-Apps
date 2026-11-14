package com.vayunmathur.library.util

import android.annotation.SuppressLint
import android.app.Activity
import com.vayunmathur.library.log.Log
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Base class for cross-app assistant actions: receives a serialized [Input],
 * computes an [Output] off the activity lifecycle and replies over a ResultReceiver.
 */
abstract class AssistantIntent<Input : Any, Output : Any>(
    val inputSerializer: KSerializer<Input>,
    val outputSerializer: KSerializer<Output>
) : ComponentActivity() {
    @OptIn(InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the incoming data + the channel to answer on.
        val inputString = intent.getStringExtra("DATA")

        // 2. These activities are themed @android:style/Theme.NoDisplay, which
        // REQUIRES finish() to be called before onResume() completes. If we finish
        // asynchronously (after suspending work) Android throws
        // IllegalStateException "did not call finish() prior to onResume()
        // completing" and force-finishes the whole task — which previously tore
        // down the OpenAssistant caller sharing that task. So we finish
        // synchronously here and run the real work on a scope that outlives this
        // activity, delivering the result over the ResultReceiver binder (which is
        // task/lifecycle independent).
        finish()

        val receiver = try {
            IntentCompat.getParcelableExtra(intent, "RECEIVER", ResultReceiver::class.java)
        } catch (expected: BadParcelableException) {
            Log.error(TAG, "Unreadable RECEIVER extra for ${javaClass.name}", expected)
            return
        }

        // Decode defensively: this activity runs in the caller's task, so an
        // exception escaping onCreate would crash and tear that task down.
        val input = try {
            inputString?.let { Json.decodeFromString(inputSerializer, it) }
        } catch (expected: IllegalArgumentException) {
            Log.error(TAG, "Failed to decode input for ${javaClass.name}", expected)
            null
        }

        if (input == null) {
            receiver?.send(Activity.RESULT_CANCELED, Bundle())
            return
        }

        scope.launch {
            try {
                val result = performCalculation(input)
                val responseData = Json.encodeToString(outputSerializer, result)
                receiver?.send(Activity.RESULT_OK, Bundle().apply {
                    putString("RESPONSE_DATA", responseData)
                })
            } catch (expected: RuntimeException) {
                Log.error(TAG, "performCalculation failed for ${javaClass.name}", expected)
                receiver?.send(Activity.RESULT_CANCELED, Bundle())
            }
        }
    }

    abstract suspend fun performCalculation(input: Input): Output

    /** Process-scoped work scope so calculations survive the NoDisplay activity. */
    companion object {
        private const val TAG = "AssistantIntent"

        // Process-scoped (not tied to the activity lifecycle) so the work survives
        // the immediate finish() above and still completes / reports its result.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Bridges a suspend call to another app's [AssistantIntent] activity.
 *
 * This is driven from the OpenAssistant tool-calling path, which runs on a single
 * background inference coroutine (via `runBlocking`). Four properties are
 * therefore essential and were the source of T495 ("assistant hangs/crashes and
 * stops responding until reboot" when creating a note) and its follow-up (the
 * NoDisplay activity force-crashing the caller's task):
 *  - The result is delivered over a [ResultReceiver] binder rather than an
 *    activity result, because the target activity is NoDisplay and finishes
 *    synchronously before its async work has produced a value.
 *  - The target is launched into the caller's own task via the [activity]
 *    context (no FLAG_ACTIVITY_NEW_TASK). A new task would match the target's
 *    default task affinity and surface that app's MainActivity once the NoDisplay
 *    activity finishes; keeping it in the caller's task means nothing ever shows.
 *  - The launch must happen on the main thread; the inference coroutine is on
 *    [Dispatchers.IO].
 *  - The call must be time-bounded. If the launched activity never returns a
 *    result (e.g. it was killed, or a background-launch was blocked), the call
 *    must fail instead of blocking the sole inference coroutine forever, which
 *    would wedge every future prompt.
 * A [mutex] serializes launches so only one call is in flight at a time.
 */
class IntentLauncher(private val activity: ComponentActivity) {
    private val mutex = Mutex()

    @SuppressLint("QueryPermissionsNeeded")
    @OptIn(InternalSerializationApi::class)
    suspend fun <Input : Any> launch(
        context: Context,
        packageName: String,
        className: String,
        serializer: SerializationStrategy<Input>,
        input: Input
    ): String = mutex.withLock {
        val result = withTimeoutOrNull(INTENT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // The target answers over this binder callback (main thread),
                // decoupled from the activity's task and lifecycle.
                val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) handler@{ code, data ->
                    if (!cont.isActive) return@handler
                    val response = data?.getString("RESPONSE_DATA")
                    if (code == Activity.RESULT_OK && response != null) {
                        cont.resume(response)
                    } else {
                        cont.resumeWithException(Exception("No data returned"))
                    }
                }

                val intent = Intent().apply {
                    setClassName(packageName, className)
                    putExtra("DATA", Json.encodeToString(serializer, input))
                    putExtra("RECEIVER", receiver.forIpc())
                }

                // Not installed / not resolvable: report back so callers can raise a
                // MissingAppException rather than waiting for a result that never comes.
                if (intent.resolveActivity(context.packageManager) == null) {
                    cont.resume("package $packageName doesn't exist")
                    return@suspendCancellableCoroutine
                }

                // Launch into the caller's (openassistant's) task via the Activity
                // context — NOT a new task. FLAG_ACTIVITY_NEW_TASK would match the
                // target's default task affinity and bring that app's existing task
                // (its MainActivity) to the foreground, which then becomes visible
                // once this NoDisplay activity finishes. The target is NoDisplay and
                // finishes synchronously, so it never shows; the result comes back
                // over the task-independent ResultReceiver binder regardless of task.
                // startActivity is main-thread only; the inference path that calls
                // this runs on a background dispatcher.
                activity.runOnUiThread {
                    try {
                        activity.startActivity(intent)
                    } catch (security: SecurityException) {
                        Log.error(INTENT_TAG, "Failed to launch $className in $packageName", security)
                        if (cont.isActive) cont.resumeWithException(security)
                    } catch (notFound: ActivityNotFoundException) {
                        Log.error(INTENT_TAG, "Failed to launch $className in $packageName", notFound)
                        if (cont.isActive) cont.resumeWithException(notFound)
                    }
                }
            }
        }

        // Timed out: surface a recoverable error instead of blocking the
        // inference loop forever.
        result ?: throw TimeoutException("Timed out waiting for a response from $packageName")
    }

    // Strips the subclass name from the parcel so the target app (which lacks
    // SecureResultReceiver, or has it R8-stripped) can unmarshal it.
    private fun ResultReceiver.forIpc(): ResultReceiver {
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /** Launch timeout for [IntentLauncher] calls. */
    companion object {
        private const val INTENT_TIMEOUT_MS = 30_000L
        private const val INTENT_TAG = "IntentLauncher"
    }
}
