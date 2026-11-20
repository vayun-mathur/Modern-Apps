package com.vayunmathur.library.widgets

import android.content.Context
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.GlanceStateDefinition
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/** WorkManager worker that refreshes the Glance widget named in its input data. */
class GenericWidgetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val className = inputData.getString(ARG_WIDGET_CLASS)
        if (className == null) {
            Log.error(TAG, "Missing widget class name in input data")
            return Result.failure()
        }

        return try {
            // Use reflection to get the GlanceAppWidget instance
            val kClass = Class.forName(className).kotlin
            val widget = kClass.java.getDeclaredConstructor().newInstance() as GlanceAppWidget

            widget.updateAll(context)
            Log.debug(TAG, "Successfully updated widget: $className")
            Result.success()
        } catch (reflect: ReflectiveOperationException) {
            Log.error(TAG, "Failed to update widget: $className", reflect)
            Result.failure()
        }
    }

    /** Input-data keys for [GenericWidgetWorker]. */
    companion object {
        const val ARG_WIDGET_CLASS = "widget_class_name"
        private const val TAG = "GenericWidgetWorker"
    }
}

fun <T : GlanceAppWidget> Context.scheduleHourlyUpdate(
    widgetClass: KClass<T>,
    requiresNetwork: Boolean = true,
) {
    val className = widgetClass.qualifiedName ?: return

    val inputData = workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to className)

    // Hourly refresh is WorkManager periodic work (not Glance's
    // updatePeriodMillis). Widgets whose data needs the network require
    // CONNECTED so the hourly wake-up is deferred/coalesced until the
    // device is online instead of firing on every offline hour. Offline-data
    // widgets (calendar/today/email/photos read only on-device stores) pass
    // requiresNetwork = false so they still refresh hourly while offline.
    val constraints = Constraints.Builder().apply {
        if (requiresNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
    }.build()
    val request = PeriodicWorkRequestBuilder<GenericWidgetWorker>(1, TimeUnit.HOURS)
        .setConstraints(constraints)
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        className, // Unique name per widget class
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

/**
 * Schedule an immediate widget update via WorkManager.
 * Uses REPLACE policy to debounce rapid updates - if multiple updates are requested
 * quickly, only the last one will be executed. This prevents Android's widget update
 * throttling from dropping updates.
 */
fun <T : GlanceAppWidget> Context.updateWidget(widgetClass: KClass<T>) {
    val className = widgetClass.qualifiedName ?: return

    val inputData = workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to className)

    val request = OneTimeWorkRequestBuilder<GenericWidgetWorker>()
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniqueWork(
        "${className}_immediate",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

/**
 * Worker that pushes composable widget previews to the system so they appear in the
 * launcher's widget picker. Backed by Glance's [GlanceAppWidgetManager.setWidgetPreviews],
 * which renders the receiver's [GlanceAppWidget.providePreview] content. The underlying
 * platform API only exists on Android 15+ (API 35); on older releases this is a no-op.
 */
class WidgetPreviewWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.debug(TAG, "Previews require API 35+, current API is ${Build.VERSION.SDK_INT}, skipping")
            return Result.success()
        }
        val className = inputData.getString(ARG_RECEIVER_CLASS)
        if (className == null) {
            Log.error(TAG, "Missing receiver class name in input data")
            return Result.failure()
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val receiverClass = Class.forName(className).kotlin as KClass<out GlanceAppWidgetReceiver>
            Log.debug(TAG, "Setting widget previews for $className (SDK=${Build.VERSION.SDK_INT})")
            when (GlanceAppWidgetManager(applicationContext).setWidgetPreviews(receiverClass)) {
                GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_RATE_LIMITED -> {
                    Log.status(TAG, "Rate limited setting widget previews for $className, will retry")
                    Result.retry()
                }
                else -> {
                    Log.debug(TAG, "Successfully set widget previews for $className")
                    Result.success()
                }
            }
        } catch (reflect: ReflectiveOperationException) {
            Log.error(TAG, "Failed to set widget previews for $className", reflect)
            Result.failure()
        }
    }

    /** Input-data keys for [WidgetPreviewWorker]. */
    companion object {
        const val ARG_RECEIVER_CLASS = "receiver_class_name"
        private const val TAG = "WidgetPreviewWorker"
    }
}

/**
 * Registers composable previews for [receiverClass] with the system widget picker.
 * Safe and cheap to call on every app launch: it dedups within a session and the
 * platform rate-limits repeated registrations. No-op below Android 15.
 *
 * Uses REPLACE policy so that if a previous attempt failed (e.g. app not fully
 * installed, crash during preview generation), the next call will retry and
 * regenerate the preview instead of being stuck blank.
 */
fun <T : GlanceAppWidgetReceiver> Context.updateWidgetPreviews(receiverClass: KClass<T>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        Log.debug("WidgetPreview", "Skipping preview update on API ${Build.VERSION.SDK_INT}: $receiverClass")
        return
    }
    val className = receiverClass.qualifiedName ?: return
    Log.debug("WidgetPreview", "Enqueueing preview update for $className")

    val inputData = workDataOf(WidgetPreviewWorker.ARG_RECEIVER_CLASS to className)

    val request = OneTimeWorkRequestBuilder<WidgetPreviewWorker>()
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniqueWork(
        "${className}_preview",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

/**
 * GlanceStateDefinition that stores data as JSON in Preferences DataStore.
 * Use this for widgets that need to store complex state objects.
 * 
 * This uses Glance's built-in PreferencesGlanceStateDefinition which is the
 * standard way to store Preferences state in Glance widgets.
 */
typealias JsonPreferencesStateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Helper to update a Glance widget's state with a serializable object.
 * The state is stored as JSON in Preferences. This triggers automatic widget
 * recomposition without needing updateAll().
 *
 * @param context Android context
 * @param widgetClass The GlanceAppWidget class to update
 * @param key The preferences key to store the data under
 * @param value The value to store (must be serializable)
 * @param serializer The KSerializer for the value type
 */
suspend fun <T : GlanceAppWidget, V> updateWidgetState(
    context: Context,
    widgetClass: KClass<T>,
    key: String,
    value: V,
    serializer: KSerializer<V>
) {
    val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(widgetClass.java)
    if (glanceIds.isEmpty()) return

    val json = Json.encodeToString(serializer, value)
    val prefsKey = stringPreferencesKey(key)

    glanceIds.forEach { glanceId ->
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[prefsKey] = json
        }
    }
}

/**
 * Helper to read a widget's state. Returns null if state is not available.
 *
 * @param prefs The Preferences object from currentState()
 * @param key The preferences key
 * @param serializer The KSerializer for the value type
 * @return The deserialized value, or null if not found or parsing fails
 */
inline fun <reified V> readWidgetState(
    prefs: Preferences,
    key: String,
    serializer: KSerializer<V>
): V? = readWidgetStateOrNull(prefs, key, serializer)

/** Non-inline core of [readWidgetState] (kept in one compiled body). */
@PublishedApi
internal fun <V> readWidgetStateOrNull(
    prefs: Preferences,
    key: String,
    serializer: KSerializer<V>
): V? {
    val prefsKey = stringPreferencesKey(key)
    val json = prefs[prefsKey] ?: return null
    return decodeWidgetState(serializer, json, key)
}

/**
 * Decoded by [readWidgetState]. Published (not private) because a public inline
 * function calls it — Kotlin forbids non-public-API access from public inline code.
 */
@PublishedApi
internal fun <V> decodeWidgetState(serializer: KSerializer<V>, json: String, key: String): V? {
    return try {
        Json.decodeFromString(serializer, json)
    } catch (expected: IllegalArgumentException) {
        Log.status(TAG, "Failed to decode widget state for key $key", expected)
        null
    }
}

private const val TAG = "Widgets"
