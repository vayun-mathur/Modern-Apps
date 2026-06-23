package com.vayunmathur.youpipe.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionVideo
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException

/**
 * FIXED: The constructor must match (Context, WorkerParameters) exactly. Removed 'val' from context
 * to prevent property shadowing and passed parameters correctly to the super constructor.
 */
class SubscriptionFetchTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d("SubscriptionFetchTask", "Starting...")
        return try {
            val db = applicationContext.buildDatabase<SubscriptionDatabase>()
            val subscriptionDao = db.subscriptionDao()
            val subscriptionVideoDao = db.subscriptionVideoDao()

            val subscriptions = subscriptionDao.getAll()
            Log.d("SubscriptionFetchTask", "Fetched ${subscriptions.size} subscriptions")

            subscriptions.forEachIndexed { index, sub ->
                try {
                    val actualChannelID = if (sub.channelID.startsWith("@")) {
                        getChannelInfo(sub.channelID).channelID
                    } else {
                        sub.channelID
                    }
                    val channelVideos = getChannelVideos(actualChannelID).toList()
                    val videosFromSub =
                            channelVideos.map {
                                SubscriptionVideo(
                                        id = it.videoID,
                                        name = it.name,
                                        duration = it.duration,
                                        views = it.views,
                                        uploadDate = it.uploadDate,
                                        thumbnailURL = it.thumbnailURL,
                                        author = it.author,
                                        channelID = sub.id
                                )
                            }

                    subscriptionVideoDao.upsertAll(videosFromSub)
                } catch (e: Exception) {
                    Log.e("SubscriptionFetchTask", "Failed to fetch videos for ${sub.name}", e)
                    if (e is java.nio.channels.UnresolvedAddressException ||
                                    e is java.net.UnknownHostException
                    ) {
                        throw e
                    }
                }
                setProgress(workDataOf("progress" to (index + 1).toFloat() / subscriptions.size))
            }
            Result.success()
        } catch (e: CancellationException) {
            Log.d("SubscriptionFetchTask", "Task cancelled")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            Log.e("SubscriptionFetchTask", "Error during fetch: $message", e)
            Result.retry()
        }
    }
}

fun setupHourlyTask(context: Context) {
    startRepeatedTask<SubscriptionFetchTask>(context, "subscription_fetch", 15.minutes)
}
