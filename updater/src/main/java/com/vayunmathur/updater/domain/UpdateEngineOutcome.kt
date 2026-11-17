package com.vayunmathur.updater.domain

/**
 * What an `onPayloadApplicationComplete(errorCode)` actually means.
 *
 * This exists because [android.os.UpdateEngine.applyPayload] does not report failure by
 * throwing. Success and failure arrive on the same callback, distinguished only by an int, so
 * code that wraps `applyPayload` in a try/catch and treats "no exception" as "applied" will
 * cheerfully reboot a device onto a slot that was never written. Classifying the int is the
 * only thing standing between those two outcomes, so it is pure and it is tested.
 *
 * The codes mirror `android.os.UpdateEngine.ErrorCodeConstants`. They are duplicated rather
 * than referenced because that class lives in a `compileOnly` stub: it is absent at unit-test
 * runtime, and Kotlin does not promise to inline a Java `static final` the way javac does, so
 * referencing it here would trade a testable classifier for a `NoClassDefFoundError`.
 * `analysis/diff_updateengine_stubs.sh` is what keeps the values honest.
 *
 * Only [SUCCESS] is load-bearing — every other code lands in [Failed] regardless of whether the
 * description below is the right one, so a stale entry costs a confusing message, not a bad
 * update.
 */
object UpdateEngineOutcome {

    private const val SUCCESS = 0

    /**
     * update_engine could not initialise the download state from this payload.
     *
     * Not in `ErrorCodeConstants` — the reference implementation declares it locally too. On an
     * incremental it means the payload cannot be applied to this device's current slot contents
     * at all, so the same incremental will fail identically forever and the caller must stop
     * offering it.
     */
    private const val DOWNLOAD_STATE_INITIALIZATION_ERROR = 20

    private const val DOWNLOAD_TRANSFER_ERROR = 9
    private const val PAYLOAD_HASH_MISMATCH_ERROR = 10
    private const val PAYLOAD_SIZE_MISMATCH_ERROR = 11
    private const val DOWNLOAD_PAYLOAD_VERIFICATION_ERROR = 12
    private const val PAYLOAD_TIMESTAMP_ERROR = 51
    private const val UPDATED_BUT_NOT_ACTIVE = 52
    private const val NOT_ENOUGH_SPACE = 60
    private const val DEVICE_CORRUPTED = 61

    /** Classified result of an update_engine payload application. */
    sealed interface Outcome {
        /** The inactive slot holds the new build. Rebooting will boot into it. */
        data object Applied : Outcome

        /**
         * Nothing usable was written.
         *
         * [retryable] separates "the network dropped" from "this package will never apply on
         * this device". Retrying a permanent failure just burns another gigabyte of the user's
         * data to arrive at the same answer.
         *
         * [initializationFailure] is narrower and only matters for an incremental: it means
         * this specific patch cannot be applied here, so the caller should remember it and go
         * straight to the full package next time rather than looping on it.
         */
        data class Failed(
            val code: Int,
            val reason: String,
            val retryable: Boolean,
            val initializationFailure: Boolean,
        ) : Outcome
    }

    fun of(errorCode: Int): Outcome {
        if (errorCode == SUCCESS) return Outcome.Applied
        return Outcome.Failed(
            code = errorCode,
            reason = reasonFor(errorCode),
            retryable = errorCode in RETRYABLE,
            initializationFailure = errorCode == DOWNLOAD_STATE_INITIALIZATION_ERROR,
        )
    }

    /**
     * Failures worth a second attempt: a transfer that broke mid-flight, or a device that was
     * simply out of room at the time.
     *
     * A hash or size mismatch is deliberately NOT here. The package was signature-verified
     * before it got this far, so a payload that then fails its own integrity check is not a
     * flaky download — it is a package that disagrees with itself, and re-fetching it produces
     * the same bytes.
     */
    private val RETRYABLE = setOf(DOWNLOAD_TRANSFER_ERROR, NOT_ENOUGH_SPACE)

    private fun reasonFor(code: Int): String = when (code) {
        DOWNLOAD_STATE_INITIALIZATION_ERROR -> "the update could not be applied to this build"
        DOWNLOAD_TRANSFER_ERROR -> "the payload transfer failed"
        PAYLOAD_HASH_MISMATCH_ERROR -> "the payload hash did not match"
        PAYLOAD_SIZE_MISMATCH_ERROR -> "the payload size did not match"
        DOWNLOAD_PAYLOAD_VERIFICATION_ERROR -> "the payload failed verification"
        // update_engine refuses to move a device backwards; the package is older than what is
        // installed. Re-downloading cannot change that.
        PAYLOAD_TIMESTAMP_ERROR -> "the package is older than the installed build"
        // Written, but the slot will not be switched — rebooting boots the OLD build. Treated
        // as a failure precisely so nothing reports success and reboots into no change.
        UPDATED_BUT_NOT_ACTIVE -> "the update was written but the new slot was not activated"
        NOT_ENOUGH_SPACE -> "there was not enough space to apply the update"
        DEVICE_CORRUPTED -> "the device reported corrupted storage"
        else -> "update_engine reported error $code"
    }
}
