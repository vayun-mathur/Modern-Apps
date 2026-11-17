package com.vayunmathur.taxi.intents

import android.content.Context
import com.vayunmathur.taxi.MainActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Raised when the target app isn't installed, so callers can degrade gracefully (draw no
 * route) instead of waiting for a result that never comes. Mirrors openassistant's and maps'
 * `MissingAppException`.
 */
class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")

/**
 * Bridges a suspend call to another app's [AssistantIntent][com.vayunmathur.library.util.AssistantIntent]
 * activity via taxi's [MainActivity.intentLauncher] (mirrors openassistant's and maps'
 * `launchIntent`). Decodes the JSON reply for you and maps a missing package to
 * [MissingAppException].
 */
suspend inline fun <reified In : Any, reified Out : Any> launchIntent(
    context: Context,
    packageName: String,
    className: String,
    input: In,
): Out {
    val out = MainActivity.intentLauncher.launch(
        context, packageName, className, serializer<In>(), input,
    )
    if (out == "package $packageName doesn't exist") throw MissingAppException(packageName)
    return Json.decodeFromString(serializer<Out>(), out)
}
