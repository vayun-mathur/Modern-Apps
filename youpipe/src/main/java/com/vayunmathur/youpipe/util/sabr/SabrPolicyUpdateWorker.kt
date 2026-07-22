package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches and installs a signed SABR policy document.
 *
 * The remote policy mechanism is DISABLED BY DEFAULT: [SABR_POLICY_URL] and
 * [SABR_POLICY_PUBLIC_KEY_BASE64] are blank (buildConfig is disabled in youpipe), so [doWork]
 * short-circuits and [initialize] never schedules any work. Nothing calls [initialize] in the app,
 * so this worker stays dormant. Playback keeps using [SabrPolicyRuntime]'s builtin fallback.
 */
class SabrPolicyUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val url = SABR_POLICY_URL
        if (url.isEmpty() || SABR_POLICY_PUBLIC_KEY_BASE64.isEmpty()) {
            return Result.success()
        }
        return try {
            val response = NewPipe.getDownloader().get(url)
            when (response.responseCode()) {
                200 -> {
                    val body = response.responseBody().takeIf { it.isNotEmpty() }
                        ?: throw IOException("SABR policy response had no body")
                    SabrPolicyRuntime.installDocument(
                        body.toByteArray(Charsets.ISO_8859_1),
                        System.currentTimeMillis(),
                    )
                    Result.success()
                }
                204, 304 -> Result.success()
                in 500..599 -> Result.retry()
                else -> {
                    Log.w(TAG, "SABR policy update failed with HTTP ${response.responseCode()}")
                    Result.failure()
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not update SABR policy", error)
            Result.retry()
        } catch (error: ReCaptchaException) {
            Log.w(TAG, "Could not update SABR policy", error)
            Result.retry()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Rejected SABR policy update", error)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SabrPolicyUpdate"
        private const val IMMEDIATE_WORK = "sabr-policy-update-now"
        private const val PERIODIC_WORK = "sabr-policy-update-periodic"

        // Blank config mirrors SabrPolicyRuntime: remote policy disabled ⇒ builtin only.
        private const val SABR_POLICY_URL = ""
        private const val SABR_POLICY_PUBLIC_KEY_BASE64 = ""

        @JvmStatic
        fun initialize(context: Context) {
            if (SABR_POLICY_URL.isEmpty() || SABR_POLICY_PUBLIC_KEY_BASE64.isEmpty()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val immediate = OneTimeWorkRequestBuilder<SabrPolicyUpdateWorker>()
                .setConstraints(constraints)
                .build()
            val periodic = PeriodicWorkRequestBuilder<SabrPolicyUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }
    }
}
