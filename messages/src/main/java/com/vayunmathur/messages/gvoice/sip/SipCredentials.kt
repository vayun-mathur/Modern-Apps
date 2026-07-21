package com.vayunmathur.messages.gvoice.sip

/**
 * SIP registration credentials for Bearer OAuth authentication (RFC 8760).
 *
 * Based on deobfuscation findings:
 * - BirdsongConfig has no password field, only auth_user and auth_realm
 * - Native code receives OAuth token via GaiaOauthTokenGetterAsync
 * - Official app uses Bearer OAuth, not digest authentication
 *
 * The OAuth token is obtained via Google OAuth flow (task 7) and used
 * as Bearer token in SIP Authorization header per RFC 8760.
 */
data class SipCredentials(
    val sipDeviceId: String,
    val oauthToken: String,  // Google OAuth access token for Bearer auth (RFC 8760)
    val authUser: String = "0",  // From BirdsongConfig, defaults to "0" per GV app
    val authRealm: String = "voice.sip.google.com",  // SIP realm from BirdsongConfig
    val field1: Long = 0L,    // Legacy field from GetSIPRegisterInfo, kept for compat
    val field2: Long = 0L,    // Legacy field from GetSIPRegisterInfo, kept for compat
) {
    // Based on GV APK decompilation, SIP server is hardcoded
    // or obtained from BirdsongConfig.sip_server_uri in account response
    val sipServer = "voice.sip.google.com"
    val sipPort = 5061  // TLS port for SIP over TLS

    /**
     * Get Authorization header value for Bearer OAuth authentication (RFC 8760).
     * Format: "Bearer <oauth_token>"
     */
    fun getAuthorizationHeader(): String = "Bearer $oauthToken"

    /**
     * SIP username for registration - uses authUser from BirdsongConfig.
     * Defaults to "0" per GV app deobfuscation findings.
     */
    val username: String get() = authUser

    /**
     * SIP ID URI for account configuration.
     * Format: sip:<sipDeviceId>@<sipServer>
     */
    val idUri: String get() = "sip:$sipDeviceId@$sipServer"
}
