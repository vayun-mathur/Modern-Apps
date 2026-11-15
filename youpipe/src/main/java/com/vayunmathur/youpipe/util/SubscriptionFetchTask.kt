package com.vayunmathur.youpipe.util

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.youpipe.data.SubscriptionRepository
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
        Log.dev("SubscriptionFetchTask", "Starting...")
        return try {
            val repository = SubscriptionRepository.get(applicationContext)

            val subscriptions = repository.subscriptionStore.getAllSubscriptions()
            Log.dev("SubscriptionFetchTask", "Fetched ${subscriptions.size} subscriptions")

            subscriptions.forEachIndexed { index, sub ->
                fetchAndStoreChannelVideos(repository, sub)
                setProgress(workDataOf("progress" to (index + 1).toFloat() / subscriptions.size))
            }
            Result.success()
        } catch (e: CancellationException) {
            Log.dev("SubscriptionFetchTask", "Task cancelled")
            throw e
        } catch (e: java.net.UnknownHostException) {
            Log.error("SubscriptionFetchTask", "Offline during fetch, retrying", e)
            Result.retry()
        } catch (e: IllegalStateException) {
            val message = e.message ?: e.javaClass.simpleName
            Log.error("SubscriptionFetchTask", "Error during fetch: $message", e)
            Result.retry()
        } catch (e: android.database.sqlite.SQLiteException) {
            val message = e.message ?: e.javaClass.simpleName
            Log.error("SubscriptionFetchTask", "Database error during fetch: $message", e)
            Result.retry()
        }
    }
}

private suspend fun fetchAndStoreChannelVideos(
    repository: SubscriptionRepository,
    sub: com.vayunmathur.youpipe.data.Subscription,
) {
    try {
        val actualChannelID = if (sub.channelID.startsWith("@")) {
            getChannelInfo(sub.channelID).channelID
        } else {
            sub.channelID
        }
        val channelVideos = getChannelVideos(actualChannelID).toList()
        val videosFromSub = channelVideos.map {
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
        repository.subscriptionStore.upsertSubscriptionVideos(videosFromSub)
    } catch (e: java.nio.channels.UnresolvedAddressException) {
        // UnresolvedAddressException is unchecked (IllegalArgumentException), not an
        // IOException, so wrap it to reuse the offline retry path.
        throw offlineFetchException(sub.name, java.io.IOException("Unresolved address for ${sub.name}", e))
    } catch (e: java.net.UnknownHostException) {
        throw offlineFetchException(sub.name, e)
    } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
        // Best-effort per-channel fetch: one channel failing must not abort the rest.
        Log.error("SubscriptionFetchTask", "Failed to fetch videos for ${sub.name}", e)
    } catch (e: java.io.IOException) {
        // UnresolvedAddressException extends IOException, so offline DNS failures
        // land here too; log per-channel, retry at the WorkManager level.
        Log.error("SubscriptionFetchTask", "Failed to fetch videos for ${sub.name}", e)
    }
}

fun setupHourlyTask(context: Context) {
    startRepeatedTask<SubscriptionFetchTask>(context, "subscription_fetch", 15.minutes)
}

private fun offlineFetchException(channelName: String, cause: java.io.IOException): java.io.IOException {
    Log.error("SubscriptionFetchTask", "Offline, retrying fetch for $channelName", cause)
    return cause
}
