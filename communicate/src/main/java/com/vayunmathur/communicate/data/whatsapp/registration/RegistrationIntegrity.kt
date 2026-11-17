package com.vayunmathur.communicate.data.whatsapp.registration

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Device-integrity signal collector for the WAMSYS `/v2` endpoints (w2.md §2.3, Phase B 2a).
 *
 * Computes the honestly-available integrity signals:
 *  - `aid` = base64(SHA-256(Android ID))                              (44B, real device)
 *  - `_gi` = ENC(JSON{apk sha256, source_dir, size, package}) — pinned to the OFFICIAL WhatsApp
 *           app identity (package=com.whatsapp + official base.apk hash/size), not this client.
 *  - `_gp` = base64(SHA-256(sorted OFFICIAL WhatsApp manifest permissions))  (44B)
 *  - `_ge` = {"sv":<virtio>,"sb":<vboxsf>}                            (emulation probe)
 *  - `_ga` = {"mp","mu","ae","ap","ai"}                              (automation signals)
 *  - `_gs` = {"em":"<base64>"}                                       (native-obfuscation placeholder)
 *  - `t`   = base64(int64 BE attestation timestamp seconds)          (8B)
 *  - `db`  = ADB-enabled flag (Settings.Global.ADB_ENABLED)          (0|1)
 *
 * NOT computed (bound to the official signed WhatsApp app identity; no FOSS way to mint):
 *  - `gpia` / `_gg` (Play Integrity JWT) and `recaptcha` (reCAPTCHA Enterprise). These are omitted
 *    entirely — an unofficial client cannot produce server-valid tokens (documented, not faked).
 *
 * The pure encoders ([aidOf], [permissionsHashOf], [tField], [emulationJson], [automationJson],
 * [nativeSignalsJson]) use `java.util.Base64` (API 26+, minSdk 31) so they are JVM-unit-testable.
 */
object RegistrationIntegrity {
    private const val MS_PER_SECOND = 1000L
    private const val LONG_BYTES = 8

    private val b64 = Base64.getEncoder()

    // ---------------------------------------------------------------- pure encoders (JVM-testable)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** `aid` = base64(SHA-256(Android ID)). Standard base64 (44 chars for a 32-byte digest). */
    fun aidOf(androidId: String): String = b64.encodeToString(sha256(androidId.toByteArray(Charsets.UTF_8)))

    /** `_gp` = base64(SHA-256(newline-joined sorted permission names)). */
    fun permissionsHashOf(permissions: List<String>): String =
        b64.encodeToString(sha256(permissions.sorted().joinToString("\n").toByteArray(Charsets.UTF_8)))

    /** `t` = base64(8-byte big-endian int64 seconds). */
    fun tField(epochSeconds: Long): String =
        b64.encodeToString(ByteBuffer.allocate(LONG_BYTES).putLong(epochSeconds).array())

    /** `_ge` emulation probe JSON `{"sv":<virtio>,"sb":<vboxsf>}` (stable key order). */
    fun emulationJson(virtio: Boolean, vboxsf: Boolean): String =
        """{"sv":$virtio,"sb":$vboxsf}"""

    /** `_ga` automation-signals JSON `{"mp","mu","ae","ap","ai"}` (stable key order). */
    fun automationJson(mockPackages: Boolean, multiUser: Boolean, ae: Long, ap: Long, ai: Long): String =
        """{"mp":$mockPackages,"mu":$multiUser,"ae":$ae,"ap":$ap,"ai":$ai}"""

    /**
     * `_gs` native-obfuscation placeholder JSON `{"em":"<base64>"}`. The real `_gs` is produced by a
     * native RFC we cannot reproduce; we ship the exact wire shape filled with an available signal
     * (or empty). Ref w2.md §2.3 `wa-android-native-obfuscation-rfc`.
     */
    fun nativeSignalsJson(emBase64: String): String = """{"em":"$emBase64"}"""

    // ---------------------------------------------------------------- Android collection

    /** The set of collected integrity signals (empty strings mean "not available / not sent"). */
    data class Signals(
        val aid: String,
        val gp: String,
        val ge: String,
        val ga: String,
        val gs: String,
        val gi: String?,
        val db: String,
        val tSeconds: Long,
    )

    /**
     * Collect all honestly-available integrity signals from [context].
     * @param encryptQueryString the ENC wrapper used for `_gi` (defaults to
     *   [RegistrationAttestation.encryptQueryString]); may return null on failure.
     */
    // ANDROID_ID is only fed into aidOf(), which hashes it into the
    // registration integrity signal the official client sends; the raw id
    // never leaves the device or identifies hardware to us.
    @SuppressLint("HardwareIds")
    fun collect(
        context: Context,
        nowSeconds: Long = System.currentTimeMillis() / MS_PER_SECOND,
        encryptQueryString: (String) -> String? = RegistrationAttestation::encryptQueryString,
    ): Signals {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")

        val aid = if (androidId.isNotEmpty()) aidOf(androidId) else ""
        val gp = permissionsHashOf(OFFICIAL_WHATSAPP_PERMISSIONS)
        val ge = emulationJson(virtio = probeVirtio(), vboxsf = probeVboxsf())
        val ga = automationJson(
            mockPackages = false,
            multiUser = false,
            ae = firstInstallTime(context),
            ap = lastUpdateTime(context),
            ai = 0L,
        )
        val gs = nativeSignalsJson("")
        val gi = buildGi(encryptQueryString)
        val db = runCatching {
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0) "1" else "0"
        }.getOrDefault("0")

        return Signals(aid = aid, gp = gp, ge = ge, ga = ga, gs = gs, gi = gi, db = db, tSeconds = nowSeconds)
    }

    /**
     * The OFFICIAL WhatsApp manifest permission set (com.whatsapp 2.26.29.73 / versionCode
     * 262907320), pulled from the device's installed APK (`dumpsys package com.whatsapp` →
     * "requested permissions"). Used for `_gp` so the hash matches the real app, not this client.
     * [permissionsHashOf] sorts before hashing, so declared order here is irrelevant.
     */
    private val OFFICIAL_WHATSAPP_PERMISSIONS: List<String> = listOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_LOCAL_NETWORK",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.AUTHENTICATE_ACCOUNTS",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BROADCAST_STICKY",
        "android.permission.CALL_PHONE",
        "android.permission.CAMERA",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.DETECT_SCREEN_CAPTURE",
        "android.permission.DETECT_SCREEN_RECORDING",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
        "android.permission.GET_ACCOUNTS",
        "android.permission.GET_TASKS",
        "android.permission.INSTALL_SHORTCUT",
        "android.permission.INTERNET",
        "android.permission.MANAGE_ACCOUNTS",
        "android.permission.MANAGE_OWN_CALLS",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.NFC",
        "android.permission.OTHER_SENSORS",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_BASIC_PHONE_STATE",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PROFILE",
        "android.permission.READ_SYNC_SETTINGS",
        "android.permission.READ_SYNC_STATS",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.RUN_USER_INITIATED_JOBS",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.SEND_SMS",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_CREDENTIALS",
        "android.permission.USE_FINGERPRINT",
        "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.WRITE_CONTACTS",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.WRITE_SYNC_SETTINGS",
        "com.android.launcher.permission.INSTALL_SHORTCUT",
        "com.android.launcher.permission.UNINSTALL_SHORTCUT",
        "com.android.vending.BILLING",
        "com.google.android.c2dm.permission.RECEIVE",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "com.google.android.gms.permission.AD_ID",
        "com.google.android.providers.gsf.permission.READ_GSERVICES",
        "com.htc.launcher.permission.READ_SETTINGS",
        "com.htc.launcher.permission.UPDATE_SHORTCUT",
        "com.huawei.android.launcher.permission.CHANGE_BADGE",
        "com.huawei.android.launcher.permission.READ_SETTINGS",
        "com.huawei.android.launcher.permission.WRITE_SETTINGS",
        "com.sec.android.provider.badge.permission.READ",
        "com.sec.android.provider.badge.permission.WRITE",
        "com.sonyericsson.home.permission.BROADCAST_BADGE",
        "com.sonymobile.home.permission.PROVIDER_INSERT_BADGE",
        "com.whatsapp.permission.BROADCAST",
        "com.whatsapp.permission.MAPS_RECEIVE",
        "com.whatsapp.permission.REGISTRATION",
    )

    @Suppress("DEPRECATION")
    private fun firstInstallTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime / MS_PER_SECOND
    }.getOrDefault(0L)

    @Suppress("DEPRECATION")
    private fun lastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / MS_PER_SECOND
    }.getOrDefault(0L)

    /**
     * `_gi` = ENC(JSON{apk_sha256, source_dir, size, package}) pinned to the OFFICIAL WhatsApp
     * identity so the blob describes the real app, not this client
     * ([WhatsAppRegistrationConstants.OFFICIAL_APK_SHA256_B64] etc., same pinned APK as the token).
     */
    private fun buildGi(encrypt: (String) -> String?): String? = runCatching {
        val json = buildString {
            append('{')
            append("\"apk_sha256\":\"").append(WhatsAppRegistrationConstants.OFFICIAL_APK_SHA256_B64).append("\",")
            append("\"source_dir\":\"").append(WhatsAppRegistrationConstants.OFFICIAL_SOURCE_DIR).append("\",")
            append("\"size\":").append(WhatsAppRegistrationConstants.OFFICIAL_APK_SIZE).append(',')
            append("\"package\":\"").append(WhatsAppRegistrationConstants.PACKAGE_NAME).append("\"")
            append('}')
        }
        encrypt(json)
    }.getOrNull()

    private fun probeVirtio(): Boolean = runCatching {
        Build.HARDWARE.contains("virtio", ignoreCase = true) ||
            File("/proc/mounts").takeIf { it.exists() }?.readText()?.contains("virtio") == true
    }.getOrDefault(false)

    private fun probeVboxsf(): Boolean = runCatching {
        File("/proc/mounts").takeIf { it.exists() }?.readText()?.contains("vboxsf") == true
    }.getOrDefault(false)
}
