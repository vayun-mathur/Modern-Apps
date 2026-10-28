package com.vayunmathur.appstore.data.installer

import android.content.Context
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.vayunmathur.library.log.Log
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.PinnedStampEntity
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.accrescent.IncompatibleDeviceException
import com.vayunmathur.appstore.data.play.CertUtil
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Where an install has got to.
 *
 * The old UI had a single `Float?` per package, which meant the whole verify-and-commit
 * tail — often several seconds on a large split install — showed as either "100%" or
 * nothing at all, and a rejected APK looked identical to a finished one.
 */
sealed interface InstallStage {
    data object Preparing : InstallStage
    data class Downloading(val fraction: Float) : InstallStage
    data object Verifying : InstallStage
    data object Installing : InstallStage

    /** The install is over and did not start. Cleared when the row is next touched. */
    data class Failed(val reason: String) : InstallStage
}

/**
 * Downloads, verifies and installs, for every source.
 *
 * Both paths end at the same place — [SessionInstaller.installSplits] with an
 * [InstallRequirement] describing what the bytes have to prove — but they get there
 * differently: F-Droid, Modern Apps and the proprietary mirror publish a URL and a
 * hash, while Play requires an account, a purchase call and a delivery response
 * that names the files.
 */
class InstallCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val play: PlayRepository,
    private val accrescent: AccrescentRepository,
    scope: CoroutineScope,
    private val ownSigningCertificates: () -> Set<String>,
) {
    private val sessionInstaller = SessionInstaller(context)
    private val playDownloader = PlayDownloader(context)

    /**
     * Whether this device is a TV. Read once: the leanback feature set cannot change
     * without a reboot, and every install consults it for the TV-compat gate.
     */
    private val isTv: Boolean by lazy {
        com.vayunmathur.appstore.data.accrescent.DeviceAttributesProvider(
            context.applicationContext
        ).isTv()
    }

    /**
     * The reason [app] must not install on this TV, or null when it may proceed.
     *
     * Null on phones unconditionally, and null for Accrescent everywhere: its server
     * already decides per-device, so a client-side refusal would second-guess the
     * authority. Everything else defers to the same verdict the catalogue filter uses.
     */
    private fun tvRefusal(app: UnifiedApp): String? {
        if (!isTv) return null
        if (app.source == com.vayunmathur.appstore.data.AppSource.ACCRESCENT) return null
        if (app.tvCompatible == false) {
            return "this app is not marked TV-compatible, so it is hidden on TV"
        }
        if (com.vayunmathur.appstore.data.AppProvider.requiresPhoneHardware(app.requiredFeatures)) {
            return "this app needs phone hardware (${app.requiredFeatures.firstOrNull().orEmpty()}) " +
                "this TV does not have"
        }
        return null
    }

    private val _stages = MutableStateFlow<Map<String, InstallStage>>(emptyMap())
    val stages: StateFlow<Map<String, InstallStage>> = _stages.asStateFlow()

    /** Per-package result of the last install attempt's certificate/hash checks. */
    private val _verification = MutableStateFlow<Map<String, VerificationResult>>(emptyMap())
    val verification: StateFlow<Map<String, VerificationResult>> = _verification.asStateFlow()

    init {
        // The session is committed on a background thread and the OS reports the real
        // outcome later, at InstallStatusReceiver. Until it arrives the row sits at
        // InstallStage.Installing; this ends that stage once the install has actually
        // finished (or was aborted/failed), rather than leaving the button stuck.
        scope.launch {
            InstallEvents.results.collect { result ->
                if (_stages.value[result.packageName] is InstallStage.Installing) {
                    clear(result.packageName)
                }
            }
        }
    }

    /** True while anything is downloading or installing. */
    fun isBusy(packageName: String): Boolean =
        _stages.value[packageName].let { it != null && it !is InstallStage.Failed }

    /**
     * Run one install to completion.
     *
     * Returns the outcome so a caller updating several apps in a row can wait for each
     * rather than poll a progress map, which is what the old "update all" did.
     */
    suspend fun install(app: UnifiedApp): SessionInstaller.Outcome {
        if (isBusy(app.packageName)) {
            return SessionInstaller.Outcome(false, VerificationResult.Rejected("already installing"))
        }
        // Pre-download TV-compat refusal, mirroring the catalogue filter: on a TV, a
        // listing the catalogue already marked incompatible (or naming phone-only
        // hardware) is refused before any bytes download. This is a fast path only —
        // the downloaded APK is still re-checked by InstallVerifier.checkLeanback, so a
        // lying listing cannot bypass the gate by carrying no metadata.
        tvRefusal(app)?.let { reason ->
            val outcome = SessionInstaller.Outcome(false, VerificationResult.Rejected(reason))
            record(app.packageName, outcome.verification)
            return finishInstall(app.packageName, outcome)
        }
        stage(app.packageName, InstallStage.Preparing)
        return try {
            val outcome = when (app.source) {
                AppSource.PLAYSTORE -> installFromPlay(app)
                AppSource.ACCRESCENT -> installFromAccrescent(app)
                else -> installFromUrl(app)
            }
            record(app.packageName, outcome.verification)
            finishInstall(app.packageName, outcome)
        } catch (e: CancellationException) {
            clear(app.packageName)
            throw e
        } catch (e: IOException) {
            failInstall(app.packageName, e)
        } catch (e: SecurityException) {
            failInstall(app.packageName, e)
        } catch (e: IllegalStateException) {
            failInstall(app.packageName, e)
        } catch (e: GooglePlayException) {
            // Purchase throttled (e.g. 429, issue #797/#812): withApi covers reads,
            // but purchase() throws — fail the row instead of crashing the tap.
            failInstall(app.packageName, e)
        } catch (e: Exception) {
            // Accrescent gRPC (StatusRuntimeException), dispenser AuthError, Room, etc.
            // A tap on Update must never crash the store (issue #812).
            failInstall(app.packageName, e)
        }
    }

    private fun finishInstall(
        packageName: String,
        outcome: SessionInstaller.Outcome,
    ): SessionInstaller.Outcome {
        if (outcome.started) {
            // The bytes are committed but the OS is still writing them in; hold the
            // row at Installing until InstallStatusReceiver reports the real outcome
            // (a large split install can take several seconds past this point).
            stage(packageName, InstallStage.Installing)
        } else {
            stage(packageName, InstallStage.Failed(outcome.verification.shortReason()))
        }
        return outcome
    }

    private fun failInstall(packageName: String, e: Exception): SessionInstaller.Outcome {
        Log.error(TAG, "Install failed for $packageName", e)
        stage(packageName, InstallStage.Failed(e.message ?: "download failed"))
        return SessionInstaller.Outcome(false, VerificationResult.Rejected(e.message ?: "download failed"))
    }

    /** Drop a terminal [InstallStage.Failed] marker, e.g. when the user retries. */
    fun dismissFailure(packageName: String) {
        if (_stages.value[packageName] is InstallStage.Failed) clear(packageName)
    }

    // --- Direct APK: Modern Apps and F-Droid ---------------------------------------

    private suspend fun installFromUrl(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            val apkUrl = app.apkUrl
                ?: return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("this source published no download")
                )

            val requirement = when (app.source) {
                // The trust root: whatever certificate this store is itself signed
                // with, read back from PackageManager rather than hardcoded.
                AppSource.MODERN_APPS -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = ownSigningCertificates(),
                    expectedSha256 = app.apkSha256
                        ?.let { mapOf("${app.packageName}.apk" to it) } ?: emptyMap(),
                    signerOrigin = "this store",
                    requireTvCompatible = isTv,
                )
                // This store's own Play mirror: the same release key signs the index, but
                // the APKs carry the developers' keys, so the per-version signers from
                // the signed index are the anchor — not this store's own certificate.
                // Split siblings are keyed by their LOCAL file names (the verifier
                // matches on File.name): base keeps `${package}.apk`, splits land
                // as `${package}.splitN.apk` — see the download loop below.
                AppSource.PROPRIETARY -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = app.expectedSigners.toSet(),
                    expectedSha256 = buildMap {
                        app.apkSha256?.let { put("${app.packageName}.apk", it) }
                        app.splitFiles.forEachIndexed { index, split ->
                            split.sha256?.let { put("${app.packageName}.split$index.apk", it) }
                        }
                    },
                    signerOrigin = "this store's Play mirror",
                    requireTvCompatible = isTv,
                )
                else -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = app.expectedSigners.toSet(),
                    expectedSha256 = app.apkSha256
                        ?.let { mapOf("${app.packageName}.apk" to it) } ?: emptyMap(),
                    signerOrigin = when (app.source) {
                        AppSource.IZZY -> "IzzyOnDroid's signed app list"
                        else -> "F-Droid's signed app list"
                    },
                    requireTvCompatible = isTv,
                )
            }

            val file = File(context.cacheDir, "${app.packageName}.apk")
            download(apkUrl, file, app.sizeBytes) { fraction ->
                stage(app.packageName, InstallStage.Downloading(fraction))
            }

            // Split-set versions install every file in one session: the base
            // plus the sibling splits sharing its versionCode. Without the set,
            // Android 14+ refuses with INSTALL_FAILED_MISSING_SPLIT.
            // Aggregate progress mirrors installFromAccrescent's split loop.
            // Each file is size-checked after download (a truncated 200MB base
            // otherwise surfaces only as a hash mismatch) and retried once.
            val files = if (app.splitFiles.isEmpty()) {
                downloadVerified(apkUrl, file, app.sizeBytes, app.packageName, null, null)
                listOf(file)
            } else {
                val totalSize =
                    (app.sizeBytes + app.splitFiles.sumOf { it.size }).takeIf { it > 0 } ?: -1L
                var downloadedBytes = 0L
                downloadVerified(apkUrl, file, app.sizeBytes, app.packageName, totalSize, 0L)
                downloadedBytes += file.length()
                val siblings = app.splitFiles.mapIndexed { index, split ->
                    val splitFile = File(context.cacheDir, "${app.packageName}.split$index.apk")
                    val start = downloadedBytes
                    downloadVerified(split.url, splitFile, split.size, app.packageName, totalSize, start)
                    downloadedBytes += splitFile.length()
                    splitFile
                }
                listOf(file) + siblings
            }

            stage(app.packageName, InstallStage.Verifying)
            sessionInstaller.installSplits(app.packageName, files, requirement, files.sumOf { it.length() })
        }

    /**
     * Download + size-check one file, retrying once on truncation. The raw
     * [download] reports HTTP errors but never enforces length, so a dropped
     * connection on a 200MB base otherwise fails minutes later as a hash
     * mismatch with no hint it was truncated.
     */
    private suspend fun downloadVerified(
        url: String,
        target: File,
        expectedSize: Long,
        packageName: String,
        totalSize: Long?,
        start: Long?,
    ) {
        suspend fun once() {
            if (totalSize != null && start != null && totalSize > 0) {
                download(url, target, expectedSize) { fileFraction ->
                    val overall = (start + fileFraction * expectedSize) / totalSize
                    stage(packageName, InstallStage.Downloading(overall.coerceIn(0f, 1f)))
                }
            } else {
                download(url, target, expectedSize) { fraction ->
                    stage(packageName, InstallStage.Downloading(fraction))
                }
            }
        }
        once()
        if (expectedSize > 0 && target.length() != expectedSize) {
            // Truncated (or over-long) — one retry before giving up. Delete
            // first: HttpURLConnection won't overwrite cleanly on all devices.
            target.delete()
            once()
            if (target.length() != expectedSize) {
                throw java.io.IOException(
                    "${target.name} downloaded ${target.length()} bytes, expected $expectedSize"
                )
            }
        }
    }

    private fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ) {
        val rawConnection = URL(url).openConnection()
        val sslSocketFactory = NetworkClient.defaultSslSocketFactory
        if (sslSocketFactory != null && rawConnection is HttpsURLConnection) {
            rawConnection.sslSocketFactory = sslSocketFactory
        }
        val conn = (rawConnection as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            // Without this a 404 reaches the caller as a FileNotFoundException whose whole
            // message is the URL, which the UI then shows the user as the reason the install
            // failed. Read the status first and say what the server actually said.
            val code = conn.responseCode
            if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw IOException("the server returned HTTP $code for ${target.name}")
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var downloaded = 0L
            val counting = object : FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { read ->
                        if (read > 0) {
                            downloaded += read
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
            }
            counting.use { source ->
                target.outputStream().use { out ->
                    source.copyTo(out, COPY_BUFFER_SIZE)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Accrescent ----------------------------------------------------------------

    /**
     * Install an Accrescent app: verified signed-list trust anchor + per-device split URLs.
     *
     * Fail closed at every step. The signing certificate and minimum version come only from
     * the ed25519-signed allowlist ([AccrescentRepository.refreshRepoData] +
     * [AccrescentRepository.entryFor]); if the list doesn't vouch for this app id, or the
     * refresh fails and nothing is cached, the install is refused before anything downloads.
     * The gRPC API supplies only the split URLs, which are then checked against that anchor by
     * [InstallVerifier] (correct signer + version code >= minimum). Accrescent publishes no
     * per-file hashes, so there is no `expectedSha256` — signature + min-version is the guarantee.
     */
    private suspend fun installFromAccrescent(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            // Refresh the signed allowlist (best effort — a cached, previously-verified copy is
            // fine), then read this app's trust anchor. No anchor, no install.
            accrescent.refreshRepoData()
            val trust = accrescent.entryFor(app.packageName)
                ?: return@withContext SessionInstaller.Outcome(
                    false,
                    VerificationResult.Rejected("Accrescent's signed app list does not vouch for this app"),
                )

            val splits = try {
                accrescent.downloadInfo(app.packageName)
            } catch (expected: IncompatibleDeviceException) {
                Log.status(TAG, "no Accrescent build for ${app.packageName}", expected)
                return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("this app has no build for your device")
                )
            }
            if (splits.isEmpty()) {
                return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("Accrescent returned no files to download")
                )
            }

            val totalSize = splits.sumOf { it.size }.takeIf { it > 0 } ?: -1L
            var downloadedBytes = 0L
            val files = splits.mapIndexed { index, split ->
                val file = File(context.cacheDir, "${app.packageName}.split$index.apk")
                val start = downloadedBytes
                download(split.url, file, split.size) { fileFraction ->
                    if (totalSize > 0) {
                        val overall = (start + fileFraction * split.size) / totalSize
                        stage(app.packageName, InstallStage.Downloading(overall.coerceIn(0f, 1f)))
                    }
                }
                downloadedBytes += file.length()
                file
            }

            val requirement = InstallRequirement(
                expectedPackage = app.packageName,
                requiredSigners = setOf(trust.signingCertHash),
                expectedSha256 = emptyMap(),
                minVersionCode = trust.minVersionCode,
                signerOrigin = "Accrescent's signed app list",
                // Server is the authority for Accrescent: it already picks splits
                // per-device, so a client-side leanback refusal would second-guess
                // it (mirrors the tvRefusal() exemption). Every other source keeps
                // requireTvCompatible = isTv.
                requireTvCompatible = false,
            )

            stage(app.packageName, InstallStage.Verifying)
            sessionInstaller.installSplits(
                app.packageName, files, requirement, files.sumOf { it.length() }
            )
        }

    // --- Play ----------------------------------------------------------------------

    private suspend fun installFromPlay(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            // The listing that got the user here may be a cluster entry with no version
            // code, and purchase() needs one that matches what Play will serve.
            // details() already applies the TV filter, so a null on a TV for a
            // TV-incompatible listing is a refusal, not a missing read: propagate it
            // instead of falling back to the unfiltered cluster entry. A null for any
            // other reason (no auth, network) keeps the old fallback. Phones are
            // untouched — tvRefusal() is null there unconditionally.
            val details = play.details(app.packageName) ?: run {
                tvRefusal(app)?.let { reason ->
                    return@withContext SessionInstaller.Outcome(
                        false, VerificationResult.Rejected(reason)
                    )
                }
                app
            }
            val versionCode = details.versionCode.takeIf { it > 0 } ?: app.versionCode
            val offerType = details.offerType

            // Key rotation: Play wants the certificate of the copy already on the device
            // so it can serve a delivery the installer will accept as an update.
            val certHash = runCatching {
                CertUtil.getEncodedCertificateHashes(context, app.packageName).lastOrNull()
            }.getOrNull()

            var files = purchase(app.packageName, versionCode, offerType, certHash)
            if (files.isEmpty()) throw java.io.IOException("Play returned no files to download")

            var downloaded = playDownloader.downloadFiles(app.packageName, versionCode, files) { f ->
                stage(app.packageName, InstallStage.Downloading(f))
            }

            // Delivery URLs are short-lived; one expiry is worth a second purchase call
            // rather than making the user tap install again.
            if (downloaded.exceptionOrNull() is PlayDownloader.ExpiredUrlException) {
                files = purchase(app.packageName, versionCode, offerType, certHash)
                downloaded = playDownloader.downloadFiles(app.packageName, versionCode, files) { f ->
                    stage(app.packageName, InstallStage.Downloading(f))
                }
            }
            val localFiles = downloaded.getOrThrow()

            stage(app.packageName, InstallStage.Verifying)

            // Everything Play can actually give us, all enforced:
            //  - per-split SHA-256 from the delivery response (PlayFile.sha256)
            //  - the expected signing certificate from AppDetails.certificateSet
            //  - the source stamp, pinned TOFU (survives Play App Signing)
            //  - continuity with the installed copy (InstallVerifier, always)
            val expectedHashes = files.mapNotNull { f ->
                val name = f.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                f.sha256.takeIf { it.isNotBlank() }?.let { name to it }
            }.toMap()

            val requirement = InstallRequirement(
                expectedPackage = app.packageName,
                requiredSigners = details.expectedSigners.toSet(),
                expectedSha256 = expectedHashes,
                signerOrigin = "Google",
                pinnedStamp = db.pinnedStampDao().byPackage(app.packageName)?.stampSha256,
                requireTvCompatible = isTv,
            )

            sessionInstaller.installSplits(
                app.packageName, localFiles, requirement, localFiles.sumOf { it.length() }
            )
        }

    /** Retries once without the installed-copy certificate, which Play sometimes rejects. */
    private suspend fun purchase(
        packageName: String,
        versionCode: Long,
        offerType: Int,
        certHash: String?,
    ) = try {
        play.purchase(packageName, versionCode, offerType, certHash)
    } catch (expected: IOException) {
        if (certHash == null) throw expected
        play.purchase(packageName, versionCode, offerType, null)
    } catch (expected: IllegalStateException) {
        if (certHash == null) throw expected
        play.purchase(packageName, versionCode, offerType, null)
    }

    // --- Bookkeeping ----------------------------------------------------------------

    /**
     * Surface the verdict, and persist a newly observed source stamp so the next update
     * for this package is pinned to it.
     */
    private suspend fun record(packageName: String, result: VerificationResult) {
        _verification.value = _verification.value + (packageName to result)
        val stamp = result.stamp ?: return
        try {
            db.pinnedStampDao().upsert(
                PinnedStampEntity(
                    packageName = packageName,
                    stampSha256 = stamp,
                    firstSeen = System.currentTimeMillis(),
                )
            )
        } catch (expected: android.database.sqlite.SQLiteException) {
            Log.status(TAG, "pin stamp for $packageName", expected)
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "pin stamp for $packageName", expected)
        }
    }

    private fun stage(packageName: String, stage: InstallStage) {
        _stages.value = _stages.value + (packageName to stage)
    }

    private fun clear(packageName: String) {
        _stages.value = _stages.value - packageName
    }

    private fun VerificationResult.shortReason(): String = when (this) {
        is VerificationResult.Rejected -> reason
        is VerificationResult.Unverified -> reason
        is VerificationResult.Verified -> "install did not start"
    }

    private companion object {
        const val TAG = "InstallCoordinator"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
