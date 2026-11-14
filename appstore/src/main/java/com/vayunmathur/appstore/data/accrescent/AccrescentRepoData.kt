package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.appstore.data.security.Signify
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.accrescentDataStore by preferencesDataStore(name = "accrescent_repodata")
private val REPODATA_TIMESTAMP_KEY = longPreferencesKey("repodata_timestamp")

/**
 * The signed Accrescent allowlist (`repodata.N.json`).
 *
 * This is a pure trust anchor: for each legitimate app id it states the certificate its APK
 * must be signed with and the minimum version code that may be installed, plus a monotonic
 * [timestamp] for rollback protection. It carries no names, icons, descriptions or download
 * URLs — those come from the (untrusted) gRPC API.
 */
@Serializable
data class RepoData(
    val timestamp: Long,
    val apps: Map<String, RepoApp> = emptyMap(),
)

@Serializable
/** One app entry in Accrescent repository data with its signing requirements. */
data class RepoApp(
    @SerialName("min_version_code") val minVersionCode: Long,
    @SerialName("signing_cert_hash") val signingCertHash: String,
)

/**
 * Downloads, verifies and anti-rollback-checks Accrescent's signed repodata.
 *
 * The security model, preserved faithfully from upstream: trust comes entirely from the
 * ed25519 signature over the repodata bytes ([AccrescentSignify]) verified against the pinned
 * [AccrescentRepo.REPODATA_PUBKEY], plus a monotonic timestamp that can never go backwards
 * (floored at [AccrescentRepo.MIN_TIMESTAMP]). Every failure path returns
 * [Result.failure] — fail closed — so a tampered, unsigned or rolled-back document yields no
 * usable allowlist and therefore nothing installable.
 */
open class AccrescentRepoDataFetcher(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open suspend fun fetch(): Result<RepoData> {
        return try {
            val (jsonStatus, _, jsonBytes) =
                NetworkClient.performRequestBytesFull(AccrescentRepo.REPODATA_JSON_URL)
            if (jsonStatus !in HTTP_OK_MIN..HTTP_OK_MAX || jsonBytes.isEmpty()) {
                return Result.failure(IllegalStateException("repodata fetch failed: HTTP $jsonStatus"))
            }

            val sig = NetworkClient.performRequest(AccrescentRepo.REPODATA_SIG_URL)
            if (!sig.isSuccess || sig.body.isBlank()) {
                return Result.failure(IllegalStateException("repodata signature fetch failed: HTTP ${sig.status}"))
            }

            // Verify the signature over the exact bytes downloaded, before parsing them.
            if (!Signify.verify(jsonBytes, sig.body, AccrescentRepo.REPODATA_PUBKEY)) {
                return Result.failure(SecurityException("repodata signature did not verify"))
            }

            val repoData = json.decodeFromString(RepoData.serializer(), jsonBytes.toString(Charsets.UTF_8))

            // Anti-rollback: never accept a document older than the newest one already seen,
            // and never older than the pinned floor even on first run.
            val floor = maxOf(readStoredTimestamp(), AccrescentRepo.MIN_TIMESTAMP)
            if (repoData.timestamp < floor) {
                return Result.failure(
                    SecurityException(
                        "repodata timestamp ${repoData.timestamp} is older than $floor (rollback)"
                    )
                )
            }
            persistTimestamp(repoData.timestamp)

            Result.success(repoData)
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "repodata fetch/verify failed", expected)
            Result.failure(expected)
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "repodata fetch/verify failed", expected)
            Result.failure(expected)
        } catch (expected: SecurityException) {
            Log.status(TAG, "repodata fetch/verify failed", expected)
            Result.failure(expected)
        }
    }

    private suspend fun readStoredTimestamp(): Long =
        try {
            context.accrescentDataStore.data.first()[REPODATA_TIMESTAMP_KEY] ?: 0L
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "read stored timestamp", expected)
            0L
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "read stored timestamp", expected)
            0L
        }

    private suspend fun persistTimestamp(timestamp: Long) {
        try {
            context.accrescentDataStore.edit { it[REPODATA_TIMESTAMP_KEY] = timestamp }
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "persist timestamp", expected)
        }
    }

    private companion object {
        const val TAG = "AccrescentRepoData"
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
    }
}
