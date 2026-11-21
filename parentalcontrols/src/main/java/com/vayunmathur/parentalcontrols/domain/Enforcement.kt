package com.vayunmathur.parentalcontrols.domain

/** Why an app is blocked. Order is precedence order (first holding reason wins). */
enum class EnforcementReason {
    Bedtime,
    Downtime,
    SchoolTime,
    DailyLimit,
    AppLimit,
}

/** The restriction one app carries, without any storage framework attached. */
data class AppRestriction(
    val packageName: String,
    val dailyLimitMinutes: Int? = null,
    val blockedAtBedtime: Boolean = false,
    val allowedInDowntime: Boolean = false,
)

/** Everything one enforcement decision is made from. Pure: no Android, no storage. */
data class EnforcementInput(
    /** Every package a child can launch (already minus exclusions such as our own app). */
    val launchablePackages: Set<String> = emptySet(),
    val restrictions: List<AppRestriction> = emptyList(),
    val bedtimeActive: Boolean = false,
    val downtimeActive: Boolean = false,
    val schoolTimeActive: Boolean = false,
    val deviceOverBudget: Boolean = false,
    /** Packages whose per-app daily budget has already been observed as spent today. */
    val reachedPackages: Set<String> = emptySet(),
)

/**
 * Decide, for every package in scope, whether it is blocked and why.
 *
 * Scope is launchable packages plus every restricted package (a restricted app stays
 * governed even if it currently has no launcher entry). The crucial property — the one the
 * old enforcer got wrong — is that downtime, school time and the device-wide daily limit
 * are *allow-list* features: they block every launchable app that is not explicitly
 * approved, including apps the parent has never touched and therefore has no restriction
 * row for. Bedtime and per-app limits stay block-list features, applying only where set.
 */
fun decideEnforcement(input: EnforcementInput): Map<String, EnforcementReason> {
    val byPackage = input.restrictions.associateBy { it.packageName }
    val scope = input.launchablePackages + byPackage.keys
    val result = LinkedHashMap<String, EnforcementReason>()
    for (packageName in scope) {
        val rule = byPackage[packageName]
        val allowedInWindow = rule?.allowedInDowntime == true
        val reason = when {
            rule?.blockedAtBedtime == true && input.bedtimeActive -> EnforcementReason.Bedtime
            input.downtimeActive && !allowedInWindow -> EnforcementReason.Downtime
            input.schoolTimeActive && !allowedInWindow -> EnforcementReason.SchoolTime
            input.deviceOverBudget -> EnforcementReason.DailyLimit
            packageName in input.reachedPackages -> EnforcementReason.AppLimit
            else -> null
        }
        if (reason != null) result[packageName] = reason
    }
    return result
}

/** Effective daily budget in minutes for [baseMinutes] plus today's bonus minutes. */
fun effectiveBudgetMinutes(baseMinutes: Int, bonusMinutes: List<Int>): Int =
    baseMinutes + bonusMinutes.sum()

/** Whether [usedMinutes] has spent a budget of [budgetMinutes]. */
fun budgetSpent(usedMinutes: Long, budgetMinutes: Int): Boolean =
    budgetMinutes >= 0 && usedMinutes >= budgetMinutes.toLong()
