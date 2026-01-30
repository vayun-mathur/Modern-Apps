package com.vayunmathur.youpipe.ui

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionVideo
import kotlinx.coroutines.flow.first
import org.schabi.newpipe.extractor.ServiceList
import java.util.concurrent.TimeUnit
import kotlin.time.toKotlinInstant

private const val TAG = "SubscriptionFetchTask"

/**
 * FIXED: The constructor must match (Context, WorkerParameters) exactly.
 * Removed 'val' from context to prevent property shadowing and passed
 * parameters correctly to the super constructor.
 */
class SubscriptionFetchTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker started successfully")

        return try {
            val db = applicationContext.buildDatabase<SubscriptionDatabase>()
            val viewModel = DatabaseViewModel(
                Subscription::class to db.subscriptionDao(),
                SubscriptionVideo::class to db.subscriptionVideoDao()
            )

            val subscriptions = viewModel.getDaoInterface<Subscription>().dao.getAll().first()

            if (subscriptions.isEmpty()) {
                Log.d(TAG, "No subscriptions found to fetch.")
                return Result.success()
            }

            Log.d(TAG, "Fetching updates for ${subscriptions.size} subscriptions")

            val videos = subscriptions.mapIndexed { idx, sub ->
                try {
                    val feedExtractor = ServiceList.YouTube.getFeedExtractor(sub.url)
                    feedExtractor.fetchPage()

                    Log.d(TAG, "Progress: ${idx + 1} / ${subscriptions.size}")

                    feedExtractor.initialPage.items.mapNotNull { item ->
                        // Added safety check for uploadDate as it can be null in NewPipe extractor
                        val uploadInstant = item.uploadDate?.instant?.toKotlinInstant()

                        if (uploadInstant != null) {
                            SubscriptionVideo(
                                item.name,
                                item.url,
                                item.viewCount,
                                uploadInstant,
                                item.thumbnails.firstOrNull()?.url ?: "",
                                item.uploaderName
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching feed for ${sub.url}: ${e.message}")
                    emptyList<SubscriptionVideo>()
                }
            }.flatten().sortedByDescending { it.uploadDate }

            viewModel.replaceAll(videos)
            Log.d(TAG, "Sync complete. Saved ${videos.size} videos.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed with exception", e)
            Result.retry()
        }
    }
}

fun setupHourlyTask(context: Context) {
    val workManager = WorkManager.getInstance(context)

    val myConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // 1. ONE-TIME EXPEDITED TASK (To run immediately right now)
    // PeriodicWork cannot be expedited, so we run a one-off version for the "first run".
    val immediateRequest = OneTimeWorkRequestBuilder<SubscriptionFetchTask>()
        .setConstraints(myConstraints)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    // 2. PERIODIC TASK (To schedule the future recurring runs)
    val hourlyRequest = PeriodicWorkRequestBuilder<SubscriptionFetchTask>(
        15, TimeUnit.MINUTES,
        5, TimeUnit.MINUTES
    )
        .setConstraints(myConstraints)
        .build()

    Log.d(TAG, "Enqueuing unique work: subscription_fetch_task")

    // Run the immediate task first
    workManager.enqueueUniqueWork(
        "subscription_fetch_immediate",
        ExistingWorkPolicy.REPLACE,
        immediateRequest
    )

    // Schedule the recurring task
    workManager.enqueueUniquePeriodicWork(
        "subscription_fetch_periodic",
        ExistingPeriodicWorkPolicy.KEEP,
        hourlyRequest
    )
}