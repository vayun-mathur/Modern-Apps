package com.vayunmathur.screentime.platform

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.PausedApps
import com.vayunmathur.screentime.data.ScreenTimeRules
import com.vayunmathur.screentime.data.WindDownSchedule
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app with its usage over the selected range and its timer, if any. */
data class TimedApp(
    val packageName: String,
    val label: String,
    val usedMillis: Long,
    val timer: AppTimer?,
) {
    /** Whole minutes, truncated; prefer [usedMillis] for display so sub-minute use shows. */
    val usedMinutes: Long get() = usedMillis / 60_000L
}

/** Everything the Screen Time dashboard draws. */
data class ScreenTimeUiState(
    /** Which span the chart and list cover. */
    val period: UsagePeriod = UsagePeriod.Week,
    /** Any date inside the shown week, or the shown day itself. */
    val anchorDate: LocalDate = LocalDate.now(),
    /** The chart bars: 7 (Mon-Sun) for a week, 24 (hours) for a day. */
    val chart: List<UsageBar> = emptyList(),
    /** Total foreground time across the range. */
    val totalMillis: Long = 0,
    /** Daily average (week) or hourly average (day) shown beside the total. */
    val averageMillis: Long = 0,
    val apps: List<TimedApp> = emptyList(),
    val paused: PausedApps = PausedApps(),
    val windDown: WindDownSchedule = WindDownSchedule(),
    /** True while the launchable-app list is still being read; never derived from its content. */
    val loading: Boolean = true,
    /** False when the usage-stats op is denied - the dashboard must prompt, not show "0 min". */
    val hasUsageAccess: Boolean = false,
    /** True once [loadLaunchableApps] has run, even when it found nothing (visibility loss). */
    val installedLoaded: Boolean = false,
) {
    /** Whole minutes, truncated; prefer [totalMillis] for display. */
    val totalMinutes: Long get() = totalMillis / 60_000L
}

/** Everything the per-app details screen draws. */
data class AppDetailsUiState(
    val packageName: String? = null,
    val label: String? = null,
    val period: UsagePeriod = UsagePeriod.Week,
    val anchorDate: LocalDate = LocalDate.now(),
    val chart: List<UsageBar> = emptyList(),
    val totalMillis: Long = 0,
    val averageMillis: Long = 0,
    val timerMinutes: Int? = null,
    val appPaused: Boolean = false,
    val hasUsageAccess: Boolean = false,
)

class ScreenTimeViewModel(app: Application) : AndroidViewModel(app) {

    private val rules = ScreenTimeRules.get(app)
    private val history = UsageHistory(app)
    private val installed = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val installedLoaded = MutableStateFlow(false)
    private val hasUsageAccess = MutableStateFlow(false)
    private val period = MutableStateFlow(UsagePeriod.Week)
    private val anchorDate = MutableStateFlow(LocalDate.now())
    private val rangeHistory = MutableStateFlow(UsageHistoryData())

    private val detailPackage = MutableStateFlow<String?>(null)
    private val detailHistory = MutableStateFlow(UsageHistoryData())

    val state: StateFlow<ScreenTimeUiState> = combine(
        rules.allTimers,
        rules.pausedApps,
        rules.windDownSchedule,
        installed,
        installedLoaded,
        hasUsageAccess,
        period,
        anchorDate,
        rangeHistory,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val timerList = flows[0] as List<AppTimer>
        val paused = flows[1] as PausedApps
        val windDown = flows[2] as WindDownSchedule
        @Suppress("UNCHECKED_CAST")
        val apps = flows[3] as List<Pair<String, String>>
        val appsLoaded = flows[4] as Boolean
        val access = flows[5] as Boolean
        val periodValue = flows[6] as UsagePeriod
        val anchor = flows[7] as LocalDate
        val hist = flows[8] as UsageHistoryData
        val byPackage = timerList.associateBy { it.packageName }
        // Keep timed apps even at 0 ms; keep used-but-untimed apps even under a minute.
        val timed = apps.mapNotNull { (pkg, label) ->
            val millis = hist.perApp[pkg] ?: 0L
            if (millis == 0L && byPackage[pkg] == null) null
            else TimedApp(pkg, label, millis, byPackage[pkg])
        }.sortedByDescending { it.usedMillis }
        ScreenTimeUiState(
            period = periodValue,
            anchorDate = anchor,
            chart = hist.bars,
            totalMillis = hist.totalMillis,
            averageMillis = hist.averageMillis,
            apps = timed,
            paused = paused,
            windDown = windDown,
            loading = !appsLoaded,
            hasUsageAccess = access,
            installedLoaded = appsLoaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScreenTimeUiState())

    val detailState: StateFlow<AppDetailsUiState> = combine(
        detailPackage,
        detailHistory,
        rules.allTimers,
        rules.pausedApps,
        installed,
        period,
        anchorDate,
        hasUsageAccess,
    ) { flows ->
        val pkg = flows[0] as String?
        val hist = flows[1] as UsageHistoryData
        @Suppress("UNCHECKED_CAST")
        val timerList = flows[2] as List<AppTimer>
        val paused = flows[3] as PausedApps
        @Suppress("UNCHECKED_CAST")
        val apps = flows[4] as List<Pair<String, String>>
        val periodValue = flows[5] as UsagePeriod
        val anchor = flows[6] as LocalDate
        val access = flows[7] as Boolean
        AppDetailsUiState(
            packageName = pkg,
            label = apps.firstOrNull { it.first == pkg }?.second,
            period = periodValue,
            anchorDate = anchor,
            chart = hist.bars,
            totalMillis = hist.totalMillis,
            averageMillis = hist.averageMillis,
            timerMinutes = timerList.firstOrNull { it.packageName == pkg }?.dailyLimitMinutes,
            appPaused = pkg != null && pkg in paused.pausedPackages,
            hasUsageAccess = access,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppDetailsUiState())

    init {
        viewModelScope.launch {
            installed.value = loadLaunchableApps()
            installedLoaded.value = true
        }
        refresh()
    }

    /** Re-reads permission state and usage history; called on resume and after mutations. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            // Permission state first: the history below is empty exactly when this is DENIED,
            // so set it before reading so a frame never mixes the old flag with the new data.
            hasUsageAccess.value =
                UsageAccess.ensure(getApplication()) == UsageAccess.Status.GRANTED
            val periodValue = period.value
            val anchor = anchorDate.value
            val fresh = history.aggregate(periodValue, anchor)
            // Drop stale results: a slow query for a previous day must never overwrite
            // the chart for the day now on screen (yesterday's bars under today's label).
            if (period.value == periodValue && anchorDate.value == anchor) {
                rangeHistory.value = fresh
            }
            detailPackage.value?.let { pkg ->
                val detailFresh = history.forPackage(periodValue, anchor, pkg)
                if (period.value == periodValue && anchorDate.value == anchor) {
                    detailHistory.value = detailFresh
                }
            }
        }
    }

    /** Switches the chart between the week and day spans. */
    fun setPeriod(newPeriod: UsagePeriod) {
        if (period.value == newPeriod) return
        period.value = newPeriod
        anchorDate.value = LocalDate.now()
        refresh()
    }

    /** Steps the shown week/day backward or forward, never past today. */
    fun stepAnchor(forward: Boolean) {
        val delta = if (period.value == UsagePeriod.Day) 1L else 7L
        val next = if (forward) anchorDate.value.plusDays(delta) else anchorDate.value.minusDays(delta)
        val today = LocalDate.now()
        anchorDate.value = if (next.isAfter(today)) today else next
        refresh()
    }

    /** Points the details screen at [packageName] and loads its series. */
    fun openDetail(packageName: String?) {
        detailPackage.value = packageName
        refresh()
    }

    fun setTimer(packageName: String, minutes: Int?) {
        viewModelScope.launch {
            if (minutes == null) {
                rules.timer(packageName)?.let { rules.delete(it) }
                // Deleting the row is not enough: the platform observer stays armed and
                // the spent mark stays set, so re-adding a timer later the same day can
                // pause the app instantly on stale state. Clean up both off the main
                // thread before reconciling.
                withContext(Dispatchers.IO) {
                    AppTimers(getApplication()).unregister(packageName)
                    SpentState(getApplication()).clear(packageName)
                }
            } else {
                rules.upsert(AppTimer(packageName, minutes))
            }
            reconcile()
            refresh()
        }
    }

    fun setAppPaused(packageName: String, paused: Boolean) {
        viewModelScope.launch {
            val current = rules.pausedNow()
            val next = current.pausedPackages.toMutableSet()
            if (paused) next.add(packageName) else next.remove(packageName)
            rules.setPaused(current.copy(pausedPackages = next.toList()))
            reconcile()
        }
    }

    fun setWindDown(
        enabled: Boolean? = null,
        startMinute: Int? = null,
        endMinute: Int? = null,
        daysMask: Int? = null,
        grayscale: Boolean? = null,
        doNotDisturb: Boolean? = null,
    ) {
        viewModelScope.launch {
            val current = rules.windDownNow()
            rules.setWindDown(
                current.copy(
                    enabled = enabled ?: current.enabled,
                    startMinute = startMinute ?: current.startMinute,
                    endMinute = endMinute ?: current.endMinute,
                    daysMask = daysMask ?: current.daysMask,
                    grayscale = grayscale ?: current.grayscale,
                    doNotDisturb = doNotDisturb ?: current.doNotDisturb,
                ),
            )
            reconcile()
        }
    }

    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        Coordinator(getApplication()).reconcile()
    }

    private suspend fun loadLaunchableApps(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val self = getApplication<Application>().packageName
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != self }
                .map { it.packageName to it.label(pm) }
                .sortedBy { it.second.lowercase() }
        }

    private fun ApplicationInfo.label(pm: PackageManager): String =
        runCatching { pm.getApplicationLabel(this).toString() }.getOrDefault(packageName)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
