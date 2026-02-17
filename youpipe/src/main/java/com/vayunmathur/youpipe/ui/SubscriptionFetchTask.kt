package com.vayunmathur.youpipe.ui

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.startRepeatedTask
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.getChannelVideos
import com.vayunmathur.youpipe.getVideoDetails
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.minutes

/**
 * FIXED: The constructor must match (Context, WorkerParameters) exactly.
 * Removed 'val' from context to prevent property shadowing and passed
 * parameters correctly to the super constructor.
 */
class SubscriptionFetchTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d("SubscriptionFetchTask", "Starting...")
        return try {
            val db = applicationContext.buildDatabase<SubscriptionDatabase>()
            val viewModel = DatabaseViewModel(db,
                Subscription::class to db.subscriptionDao(),
                SubscriptionVideo::class to db.subscriptionVideoDao()
            )


            val subscriptions = viewModel.getAll<Subscription>()
            Log.d("SubscriptionFetchTask", "Fetched ${subscriptions.size} subscriptions")

            val videoIDs = getChannelVideos(subscriptions.map { it.toChannelInfo() })
            Log.d("SubscriptionFetchTask", "Fetched ${videoIDs.size} video IDs")

            val videos = getVideoDetails(videoIDs).map{
                SubscriptionVideo(
                    id = it.videoID,
                    name = it.name,
                    duration = it.duration,
                    views = it.views,
                    uploadDate = it.uploadDate,
                    thumbnailURL = it.thumbnailURL,
                    author = it.author,
                    channelID = subscriptions.first { sub -> sub.name == it.author }.id
                )
            }.sortedByDescending { it.uploadDate }

            viewModel.replaceAll(videos)
            Result.success()
        } catch (e: Exception) {
            Log.d("SubscriptionFetchTask", "Error: ${e.message}")
            Log.d("SubscriptionFetchTask", "Stack Trace: ${e.stackTraceToString()}")

            Result.retry()
        }
    }
}

fun setupHourlyTask(context: Context) {
    startRepeatedTask<SubscriptionFetchTask>(context, "subscription_fetch", 15.minutes)
}