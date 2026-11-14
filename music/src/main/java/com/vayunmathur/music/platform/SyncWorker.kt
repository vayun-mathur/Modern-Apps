package com.vayunmathur.music.platform
import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.music.data.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the in-memory library in step with MediaStore.
 *
 * The worker no longer imports anything: songs, albums and artists are read from MediaStore on
 * demand by [MusicRepository.refresh]. All this does is notice that files changed and ask for a
 * re-read, plus run the tag backfill that MediaStore cannot serve.
 *
 * It re-enqueues itself because a content-URI trigger is one-shot.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val repo = MusicRepository.get(applicationContext)
        repo.refresh()
        repo.backfillTags()
        enqueue(applicationContext)
        WorkResult.success()
    }

    /** Shared constants and helpers for SyncWorker. */
    companion object {
        private const val WORK_NAME = "MusicSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
