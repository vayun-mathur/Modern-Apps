package com.vayunmathur.logviewer.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.os.UserManager

/**
 * The parts of the report header that the public SDK cannot express.
 *
 * The app this replaces is built with `platform_apis: true` and so links these directly. We build
 * against the public SDK, and the two ways out are not equivalent:
 *
 *  - A **whole hidden class** can get a `compileOnly` stub, the way `:library:aosp-stubs` does,
 *    because the real class is in the boot classpath and the stub is never packaged.
 *  - A **hidden member of a public class** cannot. A stub of `android.os.UserManager` would shadow
 *    the real one for every other caller in the app.
 *
 * So these go through reflection. The four that need it are `ApplicationErrorReport`'s
 * `applicationInfo`, `CrashInfo.processUptimeMs` / `processStartupLatencyMs` and
 * `AnrInfo.tracesFilePath`, plus `UserManager.getUserInfo` and `OemLockManager`. The `dump(Printer,
 * String)` methods on the report's inner classes are NOT among them - those are ordinary public API
 * and are called directly.
 *
 * Every lookup returns null on failure and every caller omits the line rather than substituting a
 * wrong value, which also covers the case this code cannot verify from here: whether a system app
 * signed with the Modern Apps release key (rather than the platform key) is exempt from hidden-API
 * enforcement on MAOS. If it is not, these degrade to a header with three fewer fields instead of a
 * crash.
 */
@SuppressLint("PrivateApi") // Reflection is the documented design here: hidden members of
// public classes cannot be stubbed (see above).
internal object HiddenFrameworkApi {

    /**
     * The current user's type, e.g. `android.os.usertype.profile.MANAGED`, or null.
     *
     * `UserManager.getUserInfo` and `UserInfo.userType` are both hidden; the user id is derived
     * from the uid with the same arithmetic `UserHandle.getUserId` uses, which needs no hidden API.
     */
    fun userType(context: Context): String? = runCatching {
        val userManager = context.getSystemService(UserManager::class.java) ?: return null
        val userId = Process.myUid() / PER_USER_RANGE
        val getUserInfo = UserManager::class.java.getMethod("getUserInfo", Int::class.javaPrimitiveType)
        val info = getUserInfo.invoke(userManager, userId) ?: return null
        info.javaClass.getField("userType").get(info) as? String
    }.getOrNull()

    /**
     * Whether the bootloader is unlocked, or null when it cannot be established.
     *
     * `OemLockManager` is `@SystemApi`, and the call is gated on READ_OEM_UNLOCK_STATE. Null and
     * false are deliberately different: false prints nothing, and so does null, but only false
     * means "asked, and it is locked".
     */
    fun isBootloaderUnlocked(context: Context): Boolean? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val clazz = Class.forName("android.service.oemlock.OemLockManager") as Class<Any>
        val manager = context.getSystemService(clazz) ?: return null
        clazz.getMethod("isDeviceOemUnlocked").invoke(manager) as? Boolean
    }.getOrNull()

    /** A hidden `long` field, e.g. `ApplicationInfo.longVersionCode`. */
    fun longField(target: Any, name: String): Long? = runCatching {
        target.javaClass.getField(name).getLong(target)
    }.getOrNull()

    /** A hidden `String` field, e.g. `ApplicationErrorReport.AnrInfo.tracesFilePath`. */
    fun stringField(target: Any, name: String): String? = runCatching {
        target.javaClass.getField(name).get(target) as? String
    }.getOrNull()

    /** A hidden object field, e.g. `ApplicationErrorReport.applicationInfo`. */
    fun objectField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getField(name).get(target)
    }.getOrNull()

    /** `UserHandle.PER_USER_RANGE`, itself hidden, but fixed at this value since it was added. */
    private const val PER_USER_RANGE = 100_000
}
