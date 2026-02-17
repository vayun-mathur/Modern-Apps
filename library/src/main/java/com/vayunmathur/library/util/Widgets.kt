package com.vayunmathur.library.util

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class GenericWidgetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val className = inputData.getString(ARG_WIDGET_CLASS) ?: return Result.failure()

        try {
            // Use reflection to get the GlanceAppWidget instance
            val kClass = Class.forName(className).kotlin
            val widget = kClass.java.getDeclaredConstructor().newInstance() as GlanceAppWidget

            widget.updateAll(context)
            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }

    companion object {
        const val ARG_WIDGET_CLASS = "widget_class_name"
    }
}

fun <T : GlanceAppWidget> Context.scheduleHourlyUpdate(widgetClass: KClass<T>) {
    val className = widgetClass.qualifiedName ?: return

    val inputData = workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to className)

    val request = PeriodicWorkRequestBuilder<GenericWidgetWorker>(1, TimeUnit.HOURS)
        .setInputData(inputData)
        .build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        className, // Unique name per widget class
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}