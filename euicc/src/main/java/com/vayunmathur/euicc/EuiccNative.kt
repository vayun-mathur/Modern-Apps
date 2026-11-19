// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.euicc

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI entry points into the native SGP.22 core (libeuicc.so).
 *
 * The Rust side owns the ASN.1 + ES10 protocol logic and drives the eUICC by
 * calling back into [transmitApdu], which forwards each command APDU over the
 * telephony logical channel currently opened by [com.vayunmathur.euicc.telephony.EuiccChannelManager].
 * Only marshalling lives here; see euicc/src/main/rust/.
 *
 * The native `nativeXxx` operations are only valid while a channel is open, i.e.
 * inside `EuiccChannelManager.withIsdrChannel { ... }`, which installs
 * [activeChannel] for the duration of the block.
 */
object EuiccNative {
    val available: Boolean = NativeLoader.load("euicc", "EuiccNative")

    /**
     * The transmit function for the currently open ISD-R logical channel, or
     * null when no channel is open. Set by `EuiccChannelManager.withIsdrChannel`.
     */
    @Volatile
    @JvmStatic
    var activeChannel: ((ByteArray) -> ByteArray)? = null

    /**
     * Called by the native core to send one command APDU to the eUICC. Returns
     * the response bytes (response data followed by the two status bytes).
     */
    @JvmStatic
    fun transmitApdu(command: ByteArray): ByteArray =
        (activeChannel ?: error("no active eUICC channel")).invoke(command)

    /** Returns the native core's version string. */
    external fun nativeVersion(): String

    /** Returns the 32-hex-digit EID, or null on error. */
    external fun nativeGetEid(): String?

    /** Returns the EUICCInfo1 subset as a JSON string, or null on error. */
    external fun nativeGetEuiccInfo(): String?

    /** Returns the installed profiles as a JSON array string, or null on error. */
    external fun nativeGetProfiles(): String?

    /** Enables the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeEnableProfile(iccid: String): Int

    /** Disables the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeDisableProfile(iccid: String): Int

    /** Deletes the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeDeleteProfile(iccid: String): Int

    /** Sets the nickname of the profile with [iccid] (raw hex). 0 = success, else error / -1. */
    external fun nativeSetNickname(iccid: String, nickname: String): Int

    /** Returns pending notifications as a JSON array string, or null on error. */
    external fun nativeListNotifications(): String?

    /** Removes the notification with sequence number [seq]. 0 = success, else error / -1. */
    external fun nativeRemoveNotification(seq: Int): Int

    /**
     * Runs the full SGP.22 download for an activation code and returns a JSON
     * `{"success":Boolean,"message":String}` string. Must be called while the
     * ISD-R channel is open (inside `withIsdrChannel`).
     *
     * Kept for the `EuiccManagerService` platform path; the UI flow uses the
     * split-phase [nativeAuthenticate] / [nativeFinishDownload] /
     * [nativeCancelDownload] entries so it can preview the carrier, confirm,
     * and collect a confirmation code mid-download.
     */
    external fun nativeDownloadProfile(activationCode: String): String

    /**
     * Authenticates both sides up to AuthenticateClient and returns a JSON session:
     * `{"error":String?, "transactionId":String?, "carrier":String?,
     * "profileName":String?, "iccid":String?, "ccRequired":Boolean}`.
     * No eUICC profile-store write has happened; cancel via [nativeCancelDownload].
     *
     * @param confirmationCode already-known confirmation code, or "" when unknown.
     * @param tacHex 8 hex digits of the device TAC, or "" for the default.
     * @param imei device IMEI digits for ctxParams1, or "" to omit.
     */
    external fun nativeAuthenticate(
        activationCode: String,
        confirmationCode: String,
        tacHex: String,
        imei: String,
    ): String

    /** Progress sink for [nativeFinishDownload]: return false to abort the install. */
    interface DownloadProgressCallback {
        fun onProgress(done: Int, total: Int): Boolean
    }

    /**
     * Finishes an authenticated session (PrepareDownload with hashCc, BPP fetch,
     * segment install with [callback] progress, then SetNickname from the carrier
     * preview) and returns `{"success":Boolean,"message":String,"iccid":String}`.
     * Consumes the session.
     */
    external fun nativeFinishDownload(
        transactionId: String,
        confirmationCode: String,
        nickname: String,
        callback: DownloadProgressCallback,
    ): String

    /**
     * Cancels an authenticated-but-unfinished session (ES10 CancelSession + ES9+
     * cancelSession). Best-effort; always consumes the session.
     *
     * @param reason 0 end-user rejection, 1 postponed, 2 timeout, 3 PPR,
     * 4 metadata mismatch, 5 BPP execution error.
     */
    external fun nativeCancelDownload(transactionId: String, reason: Int): Boolean
}
