package com.vayunmathur.speech.service

import android.content.AttributionSource
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records what a [WhisperRecognitionService] tells its client.
 *
 * The framework Callback is an inner class whose runtime constructor takes
 * the service, a hidden IRecognitionListener and an AttributionSource (the
 * compile SDK spells it differently), so the instance is built reflectively
 * around a recording proxy of that listener. This reflects on framework
 * classes only, never on the code under test.
 */
class RecordingRecognitionCallback(service: RecognitionService) {
    val errors = CopyOnWriteArrayList<Int>()
    val resultsTexts = CopyOnWriteArrayList<String?>()
    val partialTexts = CopyOnWriteArrayList<String?>()
    val rmsValues = CopyOnWriteArrayList<Float>()
    @Volatile var readyCalls = 0
    @Volatile var beginningCalls = 0
    @Volatile var endOfSpeechCalls = 0
    private val terminalLatch = CountDownLatch(1)

    val callback: RecognitionService.Callback

    init {
        val listenerClass = Class.forName("android.speech.IRecognitionListener")
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "onReadyForSpeech" -> readyCalls++
                "onBeginningOfSpeech" -> beginningCalls++
                "onRmsChanged" -> rmsValues += (args[0] as Float)
                "onEndOfSpeech" -> endOfSpeechCalls++
                "onError" -> { errors += (args[0] as Int); terminalLatch.countDown() }
                "onResults" -> {
                    resultsTexts += firstText(args[0] as? Bundle)
                    terminalLatch.countDown()
                }
                "onPartialResults" -> partialTexts += firstText(args[0] as? Bundle)
            }
            defaultValue(method.returnType)
        }
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader, arrayOf(listenerClass), handler,
        )
        val callbackClass = Class.forName("android.speech.RecognitionService\$Callback")
        val ctor = callbackClass.getDeclaredConstructor(
            RecognitionService::class.java, listenerClass, AttributionSource::class.java,
        )
        ctor.isAccessible = true
        callback = ctor.newInstance(service, listener, null) as RecognitionService.Callback
    }

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    fun awaitTerminal(seconds: Long = 15): Boolean = terminalLatch.await(seconds, TimeUnit.SECONDS)
}
