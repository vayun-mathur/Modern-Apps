package com.vayunmathur.youpipe.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.startRepeatedTask
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.videoURLtoID
import kotlinx.coroutines.flow.first
import org.schabi.newpipe.extractor.ServiceList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinInstant

/**
 * FIXED: The constructor must match (Context, WorkerParameters) exactly.
 * Removed 'val' from context to prevent property shadowing and passed
 * parameters correctly to the super constructor.
 */
class SubscriptionFetchTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val db = applicationContext.buildDatabase<SubscriptionDatabase>()
            val viewModel = DatabaseViewModel(
                Subscription::class to db.subscriptionDao(),
                SubscriptionVideo::class to db.subscriptionVideoDao()
            )

            val subscriptions = viewModel.getDaoInterface<Subscription>().dao.getAll().first()

            val videos = subscriptions.mapIndexed { idx, sub ->
                try {
                    val feedExtractor = ServiceList.YouTube.getFeedExtractor(sub.url)
                    feedExtractor.fetchPage()

                    feedExtractor.initialPage.items.mapNotNull { item ->
                        // Added safety check for uploadDate as it can be null in NewPipe extractor
                        val uploadInstant = item.uploadDate?.instant?.toKotlinInstant()

                        if (uploadInstant != null) {
                            SubscriptionVideo(
                                item.name,
                                videoURLtoID(item.url),
                                item.viewCount,
                                uploadInstant,
                                item.thumbnails.firstOrNull()?.url ?: "",
                                item.uploaderName
                            )
                        } else null
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }.flatten().sortedByDescending { it.uploadDate }

            viewModel.replaceAll(videos)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

fun setupHourlyTask(context: Context) {
    startRepeatedTask<SubscriptionFetchTask>(context, "subscription_fetch", 15.minutes)
}