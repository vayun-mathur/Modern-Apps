package com.vayunmathur.messages.rcs

/**
 * Parsed RCS configuration from ACS XML response (GSMA RCC.14 / RCC.15 format).
 *
 * Based on reverse engineering of Shannon RCS service
 * (com.shannon.rcsservice.deviceprovisioning.impl.gsma.ServiceProviderDeviceConfiguration)
 * and GSMA RCC.14 specification.
 */
data class RcsConfiguration(
    // IMS Core settings
    val pcscfAddress: String,           // P-CSCF SIP server address, e.g. "sip:pcscf.rcs.telephony.goog:5060"
    val publicUserIdentity: String,     // SIP URI, e.g. "sip:+1234567890@rcs.telephony.goog"
    val privateUserIdentity: String,    // Private ID for authentication
    val homeDomain: String,             // Home network domain, e.g. "rcs.telephony.goog"
    val authType: AuthType,             // DIGEST or AKAv1-MD5
    val username: String,               // SIP authentication username
    val password: String,               // SIP authentication password

    // Service authorization flags
    val chatAuth: Boolean,              // 1:1 chat enabled
    val groupChatAuth: Boolean,         // Group chat enabled
    val ftAuth: Boolean,                // File transfer enabled
    val standaloneMsgAuth: Boolean,     // Standalone messaging enabled
    val geolocPushAuth: Boolean,        // Geolocation push enabled
    val geolocPullAuth: Boolean,        // Geolocation pull enabled

    // File transfer over HTTP configuration
    val ftHttpServer: String?,          // FT HTTP server URI
    val ftHttpUser: String?,            // FT HTTP username
    val ftHttpPassword: String?,        // FT HTTP password
    val ftDefaultMech: String?,         // Default FT mechanism: "HTTP" or "MSRP"

    // Messaging UX mode
    val messagingUx: Int,               // 0=SMS only, 1=integrated, 2=converged

    // Presence / capability discovery
    val presenceAuth: Boolean,

    // Version info from ACS
    val version: Int,                   // Configuration version for future updates
    val validitySeconds: Long,          // How long this config is valid
    val token: String?,                 // Token for resuming provisioning session
) {
    enum class AuthType {
        DIGEST,
        AKA_V1_MD5,
        AKA_V2_MD5,
    }

    companion object {
        fun fromAuthTypeString(type: String): AuthType = when (type.uppercase()) {
            "DIGEST" -> AuthType.DIGEST
            "AKAV1-MD5", "AKA_V1_MD5", "AKAV1" -> AuthType.AKA_V1_MD5
            "AKAV2-MD5", "AKA_V2_MD5", "AKAV2" -> AuthType.AKA_V2_MD5
            else -> AuthType.DIGEST
        }
    }
}

/**
 * ACS provisioning request parameters per GSMA RCC.14.
 * Sent as query parameters in HTTP GET to ACS URL.
 */
data class AcsRequestParams(
    val vers: Int,                      // Configuration version (0 for first request)
    val rcsVersion: String,             // RCS version, e.g. "UP_2.4"
    val rcsProfile: String,             // RCS profile, e.g. "joyn_blackbird"
    val clientVendor: String,           // Client vendor, e.g. "VAYU"
    val clientVersion: String,          // Client version, e.g. "RCSAndrd-1.0"
    val terminalVendor: String,         // Terminal vendor, e.g. "GOOG"
    val terminalModel: String,          // Terminal model, e.g. "Pixel9Pro"
    val terminalSwVersion: String,      // Terminal SW version
    val imsi: String?,                  // IMSI from SIM (for GBA auth)
    val imei: String?,                  // IMEI
    val msisdn: String?,                // Phone number in E.164 format
    val token: String?,                 // Token from previous ACS response (for resuming)
    val otp: String? = null,            // OTP code for SMS verification flow (511 response)
) {
    fun toQueryString(): String {
        val params = mutableMapOf(
            "vers" to vers.toString(),
            "rcs_version" to rcsVersion,
            "rcs_profile" to rcsProfile,
            "client_vendor" to clientVendor,
            "client_version" to clientVersion,
            "terminal_vendor" to terminalVendor,
            "terminal_model" to terminalModel,
            "terminal_sw_version" to terminalSwVersion,
        )
        imsi?.let { params["IMSI"] = it }
        imei?.let { params["IMEI"] = it }
        msisdn?.let { params["msisdn"] = it }
        token?.let { params["token"] = it }
        otp?.let { params["otp"] = it } // Non-standard, for our OTP flow

        return params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    companion object {
        const val DEFAULT_RCS_VERSION = "UP_2.4"
        const val DEFAULT_RCS_PROFILE = "joyn_blackbird"
        const val DEFAULT_CLIENT_VENDOR = "Goog"  // Google Messages uses "Goog" per decompiled code analysis
        const val DEFAULT_CLIENT_VERSION = "RCSAndrd-1.0"  // Keep standard, but server may check User-Agent more strictly
    }
}

/**
 * ACS response types per GSMA RCC.14.
 */
sealed class AcsResponse {
    /** 200 OK with XML configuration document */
    data class Success(
        val config: RcsConfiguration,
        val rawXml: String,
    ) : AcsResponse()

    /** 511 Network Authentication Required - need SMS OTP */
    data object NeedsOtp : AcsResponse()

    /** 403 Forbidden - device not authorized */
    data class Forbidden(val reason: String?) : AcsResponse()

    /** 409 Conflict - needs user action (e.g. accept ToS) */
    data class NeedsUserAction(val message: String?) : AcsResponse()

    /** 503 Service Unavailable - retry later */
    data class RetryLater(val retryAfterSeconds: Int) : AcsResponse()

    /** 400 Bad Request or other error */
    data class Error(val code: Int, val message: String?) : AcsResponse()
}
