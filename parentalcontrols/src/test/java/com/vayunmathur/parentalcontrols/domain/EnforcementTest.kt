package com.vayunmathur.parentalcontrols.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnforcementTest {

    private val launchable = setOf("com.example.game", "com.example.video", "com.example.reader")

    @Test
    fun downtimeBlocksLaunchableAppsWithNoRuleRow() {
        // The pre-fix enforcer iterated only the rules table, so an app the parent
        // had never touched was never blocked by downtime. It must be.
        val plan = decideEnforcement(
            EnforcementInput(launchablePackages = launchable, downtimeActive = true),
        )
        assertEquals(launchable, plan.keys)
        assertTrue(plan.values.all { it == EnforcementReason.Downtime })
    }

    @Test
    fun downtimeAndSchoolExemptOnlyApprovedApps() {
        val rules = listOf(
            AppRestriction("com.example.reader", allowedInDowntime = true),
            AppRestriction("com.example.game", dailyLimitMinutes = 30),
        )
        val downtime = decideEnforcement(
            EnforcementInput(launchable, rules, downtimeActive = true),
        )
        assertNull(downtime["com.example.reader"])
        assertEquals(EnforcementReason.Downtime, downtime["com.example.game"])
        assertEquals(EnforcementReason.Downtime, downtime["com.example.video"])

        val school = decideEnforcement(
            EnforcementInput(launchable, rules, schoolTimeActive = true),
        )
        assertNull(school["com.example.reader"])
        assertEquals(EnforcementReason.SchoolTime, school["com.example.video"])
    }

    @Test
    fun bedtimeBlocksOnlyFlaggedApps() {
        val rules = listOf(AppRestriction("com.example.game", blockedAtBedtime = true))
        val plan = decideEnforcement(
            EnforcementInput(launchable, rules, bedtimeActive = true),
        )
        assertEquals(mapOf("com.example.game" to EnforcementReason.Bedtime), plan)
    }

    @Test
    fun deviceLimitBlocksEveryLaunchableApp() {
        val plan = decideEnforcement(
            EnforcementInput(launchablePackages = launchable, deviceOverBudget = true),
        )
        assertEquals(launchable, plan.keys)
        assertTrue(plan.values.all { it == EnforcementReason.DailyLimit })
    }

    @Test
    fun precedenceIsBedtimeThenDowntimeThenSchoolThenDeviceThenApp() {
        val rules = listOf(
            AppRestriction("com.example.game", blockedAtBedtime = true),
            AppRestriction("com.example.reader", allowedInDowntime = true),
        )
        val all = EnforcementInput(
            launchablePackages = launchable,
            restrictions = rules,
            bedtimeActive = true,
            downtimeActive = true,
            schoolTimeActive = true,
            deviceOverBudget = true,
            reachedPackages = setOf("com.example.video", "com.example.reader"),
        )
        val plan = decideEnforcement(all)
        assertEquals(EnforcementReason.Bedtime, plan["com.example.game"])
        assertEquals(EnforcementReason.Downtime, plan["com.example.video"])
        // Approved for windows but over the device budget: device limit still applies.
        assertEquals(EnforcementReason.DailyLimit, plan["com.example.reader"])

        val noWindows = decideEnforcement(
            all.copy(
                bedtimeActive = false, downtimeActive = false,
                schoolTimeActive = false, deviceOverBudget = false,
            ),
        )
        assertEquals(EnforcementReason.AppLimit, noWindows["com.example.video"])
        assertEquals(EnforcementReason.AppLimit, noWindows["com.example.reader"])
        assertNull(noWindows["com.example.game"])
    }

    @Test
    fun restrictedAppOutsideLaunchableListStaysGoverned() {
        val rules = listOf(AppRestriction("com.example.hidden", blockedAtBedtime = true))
        val plan = decideEnforcement(
            EnforcementInput(launchablePackages = launchable, restrictions = rules, bedtimeActive = true),
        )
        assertEquals(EnforcementReason.Bedtime, plan["com.example.hidden"])
    }

    @Test
    fun nothingActiveBlocksNothing() {
        val rules = listOf(
            AppRestriction("com.example.game", dailyLimitMinutes = 30, blockedAtBedtime = true),
        )
        assertTrue(
            decideEnforcement(EnforcementInput(launchable, rules)).isEmpty(),
        )
    }

    @Test
    fun bonusExtendsBudgetAndSpentTestUsesIt() {
        assertEquals(45, effectiveBudgetMinutes(30, listOf(15)))
        assertEquals(30, effectiveBudgetMinutes(30, emptyList()))
        assertTrue(budgetSpent(usedMinutes = 45, budgetMinutes = 45))
        assertTrue(!budgetSpent(usedMinutes = 44, budgetMinutes = 45))
    }
}
