package com.vayunmathur.euicc

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the eSIM manager (LPA) flow, from the profile list into a download. */
@Serializable
sealed interface Route : NavKey {
    /** The profile list - the LUI's landing screen. */
    @Serializable
    data object Home : Route

    /**
     * One profile's detail screen. Keyed by raw ICCID hex rather than by the profile
     * itself so the destination survives a reload: every mutation re-reads the eUICC and
     * replaces the [com.vayunmathur.euicc.data.Profile] instances.
     */
    @Serializable
    data class ProfileDetail(val iccid: String) : Route

    /** EID, eUICC version, and pending notifications. */
    @Serializable
    data object DeviceInfo : Route

    /** Choose how to add a SIM: scan a QR code, or type an activation code. */
    @Serializable
    data object AddSim : Route

    /** Camera viewfinder for an activation-code QR. */
    @Serializable
    data object ScanQr : Route

    /** Manual activation-code entry. */
    @Serializable
    data object ActivationCode : Route

    /**
     * The download itself. One destination for every step - confirm, confirmation code,
     * installing, done, failed - because they are phases of a single eUICC session rather
     * than places the user can navigate between. Which one shows is decided by
     * [com.vayunmathur.euicc.platform.DownloadState], so the screen cannot drift out of
     * sync with the session driving it.
     *
     * [imei] and [confirmationCode] come from the wizard (manual entry collects them
     * up front); QR/universal-link entries leave them null and the flow collects the
     * confirmation code mid-download when the SM-DP+ demands one.
     */
    @Serializable
    data class Download(
        val activationCode: String,
        val imei: String? = null,
        val confirmationCode: String? = null,
    ) : Route
}
