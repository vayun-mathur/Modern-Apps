package com.vayunmathur.parentalcontrols.platform

import android.app.supervision.PackageUsagePolicy
import android.app.supervision.SupervisionManager
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService

private const val TAG = "ParentalControlsPolicy"

/**
 * Writes enforcement decisions to the platform.
 *
 * `SupervisionManager.setPolicy` is the only policy channel that exists - `validatePolicyLocked`
 * rejects anything that is not a [PackageUsagePolicy] - and it needs `MANAGE_SUPERVISION`, which
 * arrives with the `SYSTEM_SUPERVISION` role rather than with privileged placement. On a build
 * where the role is unheld every call here fails, which is why each one is guarded rather than
 * allowed to throw into a receiver.
 *
 * ## Why the timing is ours and not the platform's
 *
 * `TYPE_TIME_LIMIT` sounds like it should do the work, but
 * `SupervisionService.applyPackageUsagePolicy` suspends the package the moment the policy is
 * written - there is a standing `TODO(b/482425646): Only suspend the package when limit is
 * reached`, and no usage tracking, no timer and no daily reset behind it. The stored `Duration`
 * is never read by the platform.
 *
 * So [block] and [allow] are what actually gate an app, and the decision of *when* to call them
 * comes from `UsageStatsManager` observers ([AppLimits]) and from the bedtime alarm
 * ([BedtimeScheduler]).
 *
 * There is deliberately no `limit()` writer any more. The previous implementation wrote a
 * `TYPE_TIME_LIMIT` policy *after* the block/allow decision on every reconcile, intending it
 * as a record of the parent's cap. Because the platform suspends on that write, the record
 * replaced the decision: every capped app ended every reconcile suspended, whatever its
 * usage. A policy type that enforces on write cannot be used as a notebook.
 */
class SupervisionPolicies(context: Context) {

    private val manager = context.getSystemService<SupervisionManager>()

    /**
     * False when nothing will enforce: no platform service, or supervision is not enabled.
     *
     * Merely obtaining the manager is not evidence — the service can exist on a build where
     * this app does not hold the supervision role or supervision was never switched on, and
     * then every `setPolicy` is rejected. `isSupervisionEnabled` is the platform's own answer;
     * a probe failure is treated as unavailable rather than optimistic.
     */
    val isAvailable: Boolean
        get() {
            val manager = manager ?: return false
            return runCatching { manager.isSupervisionEnabled() }
                .onFailure { Log.status(TAG, "could not probe supervision state", it) }
                .getOrDefault(false)
        }


    fun block(packageName: String) = write(packageName, PackageUsagePolicy.TYPE_BLOCKED)

    fun allow(packageName: String) = write(packageName, PackageUsagePolicy.TYPE_ALLOWED)

    private fun write(packageName: String, type: Int) {
        val policy = runCatching {
            PackageUsagePolicy.Builder(packageName, type).build()
        }.getOrElse {
            Log.status(TAG, "could not build a type-$type policy for $packageName", it)
            return
        }
        set(policy)
    }

    private fun set(policy: PackageUsagePolicy) {
        val manager = manager ?: run {
            Log.status(TAG, "no SupervisionManager; is the supervision role held?")
            return
        }
        runCatching { manager.setPolicy(policy) }
            .onFailure { Log.status(TAG, "setPolicy rejected for ${policy.packageName}", it) }
    }
}

/**
 * Whether supervision enforcement is available on this device, probed reflectively.
 *
 * UI and other host-adjacent code must use this rather than touching [SupervisionPolicies]:
 * `SupervisionManager` is a compile-only stub that does not exist on a stock device (or a
 * host JVM), so loading the class that names it throws instead of answering false. Here a
 * missing class, a missing service and a negative answer all mean the same truthful thing:
 * controls can be configured but nothing will be blocked.
 */
object SupervisionAvailability {

    fun isAvailable(context: Context): Boolean = runCatching {
        val managerClass = Class.forName("android.app.supervision.SupervisionManager")
        val manager = context.getSystemService(managerClass) ?: return false
        managerClass.getMethod("isSupervisionEnabled").invoke(manager) as? Boolean ?: false
    }.getOrDefault(false)
}
