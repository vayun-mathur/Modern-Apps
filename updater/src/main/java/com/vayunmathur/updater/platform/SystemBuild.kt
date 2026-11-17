package com.vayunmathur.updater.platform

import android.annotation.SuppressLint
import com.vayunmathur.library.log.Log
import com.vayunmathur.updater.domain.UpdateComparison

private const val TAG = "SystemBuild"

/**
 * The build this device is running, and where its updates come from.
 *
 * Both come from system properties via reflection on `android.os.SystemProperties`, which is
 * hidden API. The updater is a privileged system app so the read succeeds, but each is guarded
 * anyway: a failure here should degrade to "cannot check for updates", never crash a background
 * job.
 */
@SuppressLint("PrivateApi") // Hidden SystemProperties has no public/stubbable accessor; every read fails soft to ""/0 (see class doc).
object SystemBuild {

    /**
     * Where the OTA server lives.
     *
     * Read from `ro.maos.ota.server`, set in `vendor/modern-apps/maos_branding.mk`, so a
     * differently-branded build points somewhere else without a code change or a resource
     * overlay. The GrapheneOS updater needed an auto-generated RRO to be repointed precisely
     * because its URL was a baked-in resource; ours has one source of truth.
     *
     * The default is only a fallback for a build that forgot to set the property.
     */
    fun otaServer(): String =
        stringProperty("ro.maos.ota.server").ifEmpty { "https://ota.maproj.org" }
            .trimEnd('/')

    /** e.g. `shiba`. The OTA metadata and artifacts are named per device. */
    fun device(): String = stringProperty("ro.product.device")

    /**
     * `ro.build.fingerprint` — the full identity of the running build.
     *
     * An incremental package names the fingerprint it patches FROM, and applying one to a
     * different starting point produces a corrupt system. See `OtaPackageValidation`. Empty
     * when it cannot be read, which that check treats as a mismatch rather than a pass.
     */
    fun fingerprint(): String = stringProperty("ro.build.fingerprint")

    /**
     * What is installed now, for comparison against a fetched metadata line.
     *
     * Returns null when either property is missing, which means "do not offer an update" rather
     * than "offer everything" — the safe direction when we cannot tell what is running.
     */
    fun current(): UpdateComparison.CurrentBuild? {
        val build = stringProperty("ro.build.version.incremental")
        val date = longProperty("ro.build.date.utc")
        if (build.isEmpty() || date <= 0) {
            Log.status(TAG, "cannot read the running build (incremental='$build' date=$date)")
            return null
        }
        return UpdateComparison.CurrentBuild(build, date)
    }

    private fun stringProperty(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    }.onFailure { Log.status(TAG, "SystemProperties.get($key) failed", it) }.getOrDefault("")

    private fun longProperty(key: String): Long = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("getLong", String::class.java, Long::class.javaPrimitiveType)
            .invoke(null, key, 0L) as? Long ?: 0L
    }.onFailure { Log.status(TAG, "SystemProperties.getLong($key) failed", it) }.getOrDefault(0L)
}
