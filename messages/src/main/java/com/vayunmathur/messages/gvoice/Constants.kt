package com.vayunmathur.messages.gvoice

/**
 * Endpoints + headers + content types ported from
 * `/Users/vayun/Documents/gvoice/pkg/libgv/constants.go`.
 *
 * Values are copied verbatim. Bumping `Chrome/128.0.0.0` in [UserAgent]
 * and the matching version in [SecChUa] occasionally is recommended to
 * stay current with real Chrome releases.
 */
object VoiceEndpoints {
    const val UserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    const val SecChUa =
        "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\""
    const val SecChPlatform = "\"Linux\""
    const val ClientVersion = "665865172"
    const val JavaScriptUserAgent = "google-api-javascript-client/1.1.0"
    const val WaaXUserAgent = "grpc-web-javascript/0.1"

    /** Sent as the X-ClientDetails header. URL-form-encoded blob the
     *  client sends to identify itself to the auth pipeline. */
    val ClientDetails: String = listOf(
        "appVersion" to "5.0 (X11)",
        "platform" to "Linux x86_64",
        "userAgent" to UserAgent,
    ).joinToString("&") { (k, v) ->
        java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
    }

    const val ApiKey = "AIzaSyDTYc1N4xiODyrQYK0Kl6g_y279LjYkrBg"
    const val WaaApiKey = "AIzaSyBGb5fGAyC-pRcRU6MUHb__b_vKha71HRE"
    const val WaaRequestKey = "/JR8jsAkqotcKsEKhXic"
    const val UploadOpi = "111538494"

    const val Origin = "https://voice.google.com"

    const val ApiDomain = "clients6.google.com"
    const val RealtimeDomain = "signaler-pa.$ApiDomain"
    const val ContactsDomain = "peoplestack-pa.$ApiDomain"
    const val UploadDomain = "docs.google.com"
    const val WaaDomain = "waa-pa.$ApiDomain"

    private const val ApiBaseUrl = "https://$ApiDomain/voice/v1/voiceclient"
    const val EndpointGetAccount = "$ApiBaseUrl/account/get"
    const val EndpointGetThread = "$ApiBaseUrl/api2thread/get"
    const val EndpointListThreads = "$ApiBaseUrl/api2thread/list"
    const val EndpointSendSms = "$ApiBaseUrl/api2thread/sendsms"
    const val EndpointGetThreadingInfo = "$ApiBaseUrl/threadinginfo/get"
    const val EndpointUpdateAttributes = "$ApiBaseUrl/thread/updateattributes"
    const val EndpointMarkAllRead = "$ApiBaseUrl/thread/markallread"
    const val EndpointDeleteThread = "$ApiBaseUrl/thread/delete"
    const val EndpointBatchUpdateAttributes = "$ApiBaseUrl/thread/batchupdateattributes"

    const val EndpointUpload = "https://$UploadDomain/upload/photos/resumable"
    const val EndpointDownloadTemplate = "https://voice.google.com/u/%s/a/i/%s"

    /**
     * Contacts/PeopleStack endpoints. These live on a different host
     * ([ContactsDomain]) and the request headers + URL-query bundle are
     * adjusted by [com.vayunmathur.messages.gvoice.GVoiceRpcClient]
     * based on the destination domain — see [GVoiceRpcClient.buildUrl]
     * and [GVoiceRpcClient.applyHeaders].
     */
    private const val ContactsBaseUrl =
        "https://$ContactsDomain/\$rpc/peoplestack.PeopleStackAutocompleteService"
    const val EndpointAutocompleteContacts = "$ContactsBaseUrl/Autocomplete"
    const val EndpointLookupContacts = "$ContactsBaseUrl/Lookup"

    private const val RealtimeBaseUrl = "https://$RealtimeDomain"
    const val EndpointRealtimeChannel = "$RealtimeBaseUrl/punctual/multi-watch/channel"
    const val EndpointRealtimeChooseServer = "$RealtimeBaseUrl/punctual/v1/chooseServer"

    private const val WaaBaseUrl = "https://$WaaDomain/\$rpc/google.internal.waa.v1.Waa"
    const val EndpointCreateWaa = "$WaaBaseUrl/Create"
    const val EndpointPingWaa = "$WaaBaseUrl/Ping"

    /** Cookie names the user must paste for sign-in to work. */
    val RequiredCookies = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")

    /** Cookies we recognize + persist if present, even if not required. */
    val OptionalCookies = listOf("OSID", "COMPASS", "__Secure-1PSIDTS")
}
