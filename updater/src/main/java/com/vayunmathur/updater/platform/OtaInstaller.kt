package com.vayunmathur.updater.platform

import android.os.RecoverySystem
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import com.vayunmathur.library.log.Log
import com.vayunmathur.updater.domain.OtaPackageMetadata
import com.vayunmathur.updater.domain.OtaPackageValidation
import com.vayunmathur.updater.domain.PayloadProperties
import com.vayunmathur.updater.domain.UpdateEngineOutcome
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.resume

private const val TAG = "OtaInstaller"

private const val ENTRY_METADATA = "META-INF/com/android/metadata"
private const val ENTRY_PAYLOAD_PROPERTIES = "payload_properties.txt"
private const val ENTRY_CARE_MAP = "care_map.pb"

/**
 * Where update_engine looks for the care map, matching the reference implementation.
 *
 * The care map lists the blocks that are actually in use, letting update_engine skip verifying
 * the rest. It is an optimisation, so staging it is best-effort and never fails an install.
 */
private val CARE_MAP_PATH = File("/data/ota_package/care_map.pb")

/**
 * Turns a downloaded file into a written inactive slot.
 *
 * **The ordering in [install] is the security model and is not negotiable.**
 * `RecoverySystem.verifyPackage` runs first, against the device's `/system/etc/security/
 * otacerts.zip`, before a single byte of the package's own contents is read. Everything after
 * it — the metadata, the payload properties, the offsets — is the package describing itself,
 * and a package that has not been signature-checked can describe itself however it likes. On
 * failure the file is deleted and the install aborts; there is no bypass, no
 * "verification unavailable" fallback and no configuration that skips it. This is the only
 * thing standing between a hostile zip and a system partition.
 */
object OtaInstaller {

    /** Test seam: replaces `RecoverySystem.verifyPackage`. True means verified. Null means real. */
    internal var signatureCheckOverride: ((File, (Int) -> Unit) -> Boolean)? = null

    /** Test seam: replaces staging the care map at its on-device path. Null means the real path. */
    internal var careMapPathOverride: File? = null

    /** The payload-engine surface [install] needs, so tests can drive the completion callback. */
    internal interface PayloadEngine {
        fun bind(
            onStatusUpdate: (status: Int, percent: Float) -> Unit,
            onPayloadApplicationComplete: (errorCode: Int) -> Unit,
        ): Boolean
        fun applyPayload(url: String, offset: Long, headers: Array<String>)
        fun cancel()
        fun unbind(): Boolean
    }

    /** Test seam: replaces constructing the real `UpdateEngine`. Null means the real engine. */
    internal var payloadEngineOverride: (() -> PayloadEngine)? = null

    private class RealPayloadEngine : PayloadEngine {
        private val engine = UpdateEngine()
        override fun bind(
            onStatusUpdate: (Int, Float) -> Unit,
            onPayloadApplicationComplete: (Int) -> Unit,
        ): Boolean = engine.bind(object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) =
                onStatusUpdate(status, percent)
            override fun onPayloadApplicationComplete(errorCode: Int) =
                onPayloadApplicationComplete(errorCode)
        })
        override fun applyPayload(url: String, offset: Long, headers: Array<String>) =
            engine.applyPayload(url, offset, 0L, headers)
        override fun cancel() = engine.cancel()
        override fun unbind(): Boolean = engine.unbind()
    }

    sealed interface Result {
        /** Written to the inactive slot. A reboot will boot into it. */
        data object Applied : Result

        data class Failed(
            val reason: String,
            val retryable: Boolean = false,
            /** The package cannot apply to this build; see [UpdateEngineOutcome.Outcome.Failed]. */
            val initializationFailure: Boolean = false,
        ) : Result
    }

    /**
     * Verify [packageFile], check it is the package we asked for, and apply it.
     *
     * [onVerifyProgress] and [onApplyProgress] both report 0..100.
     */
    suspend fun install(
        packageFile: File,
        expected: OtaPackageValidation.Expected,
        onVerifyProgress: (percent: Int) -> Unit,
        onApplyProgress: (percent: Int) -> Unit,
    ): Result {
        // ---- THE SECURITY BOUNDARY. Nothing below reads the package until this passes. ----
        val verification = verifySignature(packageFile, onVerifyProgress)
        if (verification != null) return verification

        // ---- Only now is the package trusted enough to read. ----
        val contents = readContents(packageFile)
            ?: return abort(packageFile, "the package could not be read")

        val validation = OtaPackageValidation.validate(contents.metadata, expected)
        if (validation is OtaPackageValidation.Result.Rejected) {
            return abort(packageFile, validation.reason)
        }
        val valid = validation as OtaPackageValidation.Result.Valid

        val properties = PayloadProperties.parse(contents.payloadProperties.asSequence())
        if (properties is PayloadProperties.Result.Rejected) {
            return abort(packageFile, "payload properties: ${properties.reason}")
        }
        val headers = (properties as PayloadProperties.Result.Parsed).headers

        copyCareMap(packageFile)

        return applyPayload(packageFile, valid.payloadOffset, headers, onApplyProgress)
    }

    /**
     * Runs the signature check. Returns null when it passed, or the failure to report.
     *
     * A failure here deletes the file. Keeping it would leave an unverified multi-gigabyte blob
     * on disk that the resume logic would happily treat as a completed download next time.
     *
     * Broad catch is deliberate: IOException, GeneralSecurityException and SecurityException
     * all mean the package is not one we signed, and an unanticipated exception type must
     * not become an accidental pass.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun verifySignature(
        packageFile: File,
        onProgress: (percent: Int) -> Unit,
    ): Result.Failed? = withContext(Dispatchers.IO) {
        signatureCheckOverride?.let { check ->
            return@withContext if (check(packageFile, onProgress)) {
                null
            } else {
                packageFile.delete()
                Result.Failed("the update package failed signature verification")
            }
        }
        try {
            RecoverySystem.verifyPackage(
                packageFile,
                RecoverySystem.ProgressListener { progress -> onProgress(progress) },
                // null selects the device's own /system/etc/security/otacerts.zip, which holds
                // the MAOS release key. Passing our own certificate file here would be us
                // vouching for the package to ourselves.
                null,
            )
            null
        } catch (e: Exception) {
            // IOException, GeneralSecurityException and SecurityException all land here, and
            // all of them mean the same thing: this package is not one we signed. Broad on
            // purpose — an unanticipated exception type must not become an accidental pass.
            Log.error(TAG, "SIGNATURE VERIFICATION FAILED for ${packageFile.name}", e)
            packageFile.delete()
            Result.Failed("the update package failed signature verification")
        }
    }

    private fun abort(packageFile: File, reason: String): Result.Failed {
        Log.error(TAG, "rejecting ${packageFile.name}: $reason")
        packageFile.delete()
        return Result.Failed(reason)
    }

    private class Contents(
        val metadata: OtaPackageMetadata,
        val payloadProperties: List<String>,
    )

    // Broad catch is deliberate: zip reads and metadata parsing throw undocumented
    // RuntimeExceptions (not just IOException), all meaning "unreadable package".
    @Suppress("TooGenericExceptionCaught")
    private fun readContents(packageFile: File): Contents? = try {
        ZipFile(packageFile).use { zip ->
            val metadataEntry = zip.getEntry(ENTRY_METADATA)
            val propertiesEntry = zip.getEntry(ENTRY_PAYLOAD_PROPERTIES)
            if (metadataEntry == null || propertiesEntry == null) {
                Log.error(TAG, "package is missing $ENTRY_METADATA or $ENTRY_PAYLOAD_PROPERTIES")
                null
            } else {
                Contents(
                    metadata = zip.getInputStream(metadataEntry).bufferedReader().use { reader ->
                        OtaPackageMetadata.parse(reader.readLines().asSequence())
                    },
                    payloadProperties = zip.getInputStream(propertiesEntry)
                        .bufferedReader()
                        .use { it.readLines() },
                )
            }
        }
    } catch (e: Exception) {
        Log.error(TAG, "could not read ${packageFile.name}", e)
        null
    }

    /**
     * Best-effort copy of the care map out of the package.
     *
     * Failure is logged and ignored: the care map only tells update_engine which blocks it can
     * skip verifying, so its absence makes the apply slower rather than wrong.
     *
     * World-readable afterwards for the same reason the package itself is — update_engine opens
     * it as its own uid, not ours.
     */
    private fun copyCareMap(packageFile: File) {
        runCatching {
            ZipFile(packageFile).use { zip ->
                val entry = zip.getEntry(ENTRY_CARE_MAP)
                if (entry == null) {
                    Log.status(TAG, "$ENTRY_CARE_MAP missing; continuing without it")
                    return
                }
                zip.getInputStream(entry).use { input ->
                    (careMapPathOverride ?: CARE_MAP_PATH).outputStream().use { output -> input.copyTo(output) }
                }
                (careMapPathOverride ?: CARE_MAP_PATH).setReadable(true, false)
            }
        }.onFailure { Log.status(TAG, "could not stage the care map; continuing without it", it) }
    }

    /**
     * Hands the payload to update_engine and waits for it to finish.
     *
     * **`applyPayload` does not report failure by throwing.** It returns as soon as the work is
     * accepted, and both completion and failure arrive later on
     * `onPayloadApplicationComplete(errorCode)`. A caller that only wraps this call in a
     * try/catch sees no exception on a failed update and concludes it worked. The only thing
     * that distinguishes the two is the int, which [UpdateEngineOutcome] classifies.
     *
     * An exception from the call itself means the opposite: the request was *rejected* and
     * never started, so no completion callback will ever arrive and the continuation has to be
     * resumed here or this suspends forever.
     */
    // Broad catch is deliberate: the update_engine binder binding throws undocumented
    // RuntimeExceptions (not just SecurityException) when unavailable.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun applyPayload(
        packageFile: File,
        payloadOffset: Long,
        headers: List<String>,
        onProgress: (percent: Int) -> Unit,
    ): Result {
        val engine = try {
            payloadEngineOverride?.invoke() ?: RealPayloadEngine()
        } catch (e: Exception) {
            // The constructor binds to the update_engine binder and throws when it is
            // unavailable - which is what a non-privileged build looks like from here.
            Log.error(TAG, "update_engine is unavailable", e)
            return Result.Failed("the system update service is unavailable")
        }
        return try {
            awaitPayload(engine, packageFile, payloadOffset, headers, onProgress)
        } finally {
            // Deliberately here rather than inside the callback. The reference unbinds on the
            // calling thread after the completion arrives (Service.java:134), not from within
            // the binder callback, and unbinding from the callback thread risks deadlocking
            // against the same binder we are being called on. `finally` also covers
            // cancellation, which would otherwise leave the callback registered against a
            // service that outlives this object.
            runCatching { if (!engine.unbind()) Log.status(TAG, "could not unbind update_engine") }
        }
    }

    private suspend fun awaitPayload(
        engine: PayloadEngine,
        packageFile: File,
        payloadOffset: Long,
        headers: List<String>,
        onProgress: (percent: Int) -> Unit,
    ): Result = suspendCancellableCoroutine { continuation ->

        // percent is 0..1 here, not 0..100.
        val onStatusUpdate: (Int, Float) -> Unit = { _, percent ->
            onProgress((percent * 100).toInt().coerceIn(0, 100))
        }
        val onComplete: (Int) -> Unit = { errorCode ->
            continuation.finish(
                when (val outcome = UpdateEngineOutcome.of(errorCode)) {
                    UpdateEngineOutcome.Outcome.Applied -> {
                        // Whatever happened, the 1-2 GB staged package has served its
                        // purpose and must not be left behind or resumed from.
                        packageFile.delete()
                        Result.Applied
                    }

                    is UpdateEngineOutcome.Outcome.Failed -> {
                        Log.error(TAG, "apply failed: ${outcome.reason} (${outcome.code})")
                        packageFile.delete()
                        Result.Failed(
                            outcome.reason,
                            outcome.retryable,
                            outcome.initializationFailure,
                        )
                    }
                },
            )
        }

        if (!engine.bind(onStatusUpdate, onComplete)) {
            continuation.resume(Result.Failed("could not attach to the system update service"))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { runCatching { engine.cancel() } }

        // Broad catch is deliberate: a rejected payload throws undocumented
        // RuntimeExceptions, and no completion callback follows a rejection.
        @Suppress("TooGenericExceptionCaught")
        try {
            // update_engine opens this path as its own uid, not ours, so it has to be readable
            // by others. Without it applyPayload fails on open.
            packageFile.setReadable(true, false)
            engine.applyPayload(
                "file://${packageFile.absolutePath}",
                payloadOffset,
                headers.toTypedArray(),
            )
        } catch (e: Exception) {
            // Rejected outright — most often because a payload is already applied and awaiting
            // a reboot. No completion callback follows a rejection.
            Log.error(TAG, "update_engine refused the payload", e)
            continuation.finish(Result.Failed("the system update service refused the update"))
        }
    }

    /** Completion can race cancellation, and resuming twice throws. */
    private fun CancellableContinuation<Result>.finish(result: Result) {
        if (isActive) resume(result)
    }
}

