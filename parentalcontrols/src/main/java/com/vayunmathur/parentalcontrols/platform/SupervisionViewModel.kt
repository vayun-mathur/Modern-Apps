package com.vayunmathur.parentalcontrols.platform

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.data.SchoolTimeSchedule
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build

/** One launchable app the parent can put a rule on. */
data class SupervisableApp(
    val packageName: String,
    val label: String,
    val rule: AppRule?,
) {
    val isSupervised: Boolean get() = rule != null
}

/** Everything the supervision screens draw. */
data class SupervisionUiState(
    val controlsEnabled: Boolean = true,
    val schedule: BedtimeSchedule = BedtimeSchedule(),
    val downtime: DowntimeSchedule = DowntimeSchedule(),
    val schoolTime: SchoolTimeSchedule = SchoolTimeSchedule(),
    val dailyLimitMinutes: Int? = null,
    val apps: List<SupervisableApp> = emptyList(),
    val loading: Boolean = true,
    /**
     * Whether the platform will actually enforce any of this (supervision role held and
     * supervision enabled). False on a build without the MAOS supervision plumbing — the
     * dashboard must say so instead of presenting controls that do nothing.
     */
    val enforcementAvailable: Boolean = false,
)

/** ViewModel combining supervision rules and installed apps into screen state. */
class SupervisionViewModel(app: Application) : AndroidViewModel(app) {

    private val rules = SupervisionRules.get(app)
    // Probed on every state emission rather than cached: completing the platform setup
    // flow in another activity must be reflected when the parent lands back here.
    private fun enforcementAvailable(): Boolean = SupervisionAvailability.isAvailable(getApplication())
    private val installed = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val controlsEnabled = MutableStateFlow(SupervisionMaster.isEnabled(app))

    val state: StateFlow<SupervisionUiState> = combine(
        rules.schedule,
        rules.downtimeSchedule,
        rules.schoolTimeSchedule,
        rules.dailyLimitMinutes,
        rules.allRules,
        installed,
        controlsEnabled,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val schedule = flows[0] as BedtimeSchedule
        val downtime = flows[1] as DowntimeSchedule
        val schoolTime = flows[2] as SchoolTimeSchedule
        val dailyLimit = flows[3] as Int?
        val ruleList = flows[4] as List<AppRule>
        @Suppress("UNCHECKED_CAST")
        val apps = flows[5] as List<Pair<String, String>>
        val enabled = flows[6] as Boolean
        val byPackage = ruleList.associateBy { it.packageName }
        SupervisionUiState(
            controlsEnabled = enabled,
            schedule = schedule,
            downtime = downtime,
            schoolTime = schoolTime,
            dailyLimitMinutes = dailyLimit,
            apps = apps.map { (pkg, label) -> SupervisableApp(pkg, label, byPackage[pkg]) },
            loading = apps.isEmpty(),
            enforcementAvailable = enforcementAvailable(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SupervisionUiState())

    init {
        viewModelScope.launch { installed.value = loadLaunchableApps() }
    }

    /** Flips the device-wide master switch and reconciles the whole device to match. */
    fun setControlsEnabled(enabled: Boolean) {
        SupervisionMaster.setEnabled(getApplication(), enabled)
        controlsEnabled.value = enabled
        viewModelScope.launch { reconcile() }
    }

    fun setScheduleEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    fun setStart(hour: Int, minute: Int) = edit { it.copy(startMinute = hour * 60 + minute) }

    fun setEnd(hour: Int, minute: Int) = edit { it.copy(endMinute = hour * 60 + minute) }

    fun toggleDay(dayIndex: Int) = edit {
        it.copy(daysMask = it.daysMask xor (1 shl dayIndex))
    }

    fun setBedtimeBlocked(packageName: String, blocked: Boolean) = editRule(packageName) {
        it.copy(blockedAtBedtime = blocked)
    }

    fun setDailyLimit(packageName: String, minutes: Int?) = editRule(packageName) {
        it.copy(dailyLimitMinutes = minutes)
    }

    fun setAllowedInDowntime(packageName: String, allowed: Boolean) = editRule(packageName) {
        it.copy(allowedInDowntime = allowed)
    }

    fun setDowntimeEnabled(enabled: Boolean) = editDowntime { it.copy(enabled = enabled) }

    fun setDowntimeStart(hour: Int, minute: Int) =
        editDowntime { it.copy(startMinute = hour * 60 + minute) }

    fun setDowntimeEnd(hour: Int, minute: Int) =
        editDowntime { it.copy(endMinute = hour * 60 + minute) }

    fun toggleDowntimeDay(dayIndex: Int) = editDowntime {
        it.copy(daysMask = it.daysMask xor (1 shl dayIndex))
    }

    fun setSchoolTimeEnabled(enabled: Boolean) = editSchoolTime { it.copy(enabled = enabled) }

    fun setSchoolTimeStart(hour: Int, minute: Int) =
        editSchoolTime { it.copy(startMinute = hour * 60 + minute) }

    fun setSchoolTimeEnd(hour: Int, minute: Int) =
        editSchoolTime { it.copy(endMinute = hour * 60 + minute) }

    fun toggleSchoolTimeDay(dayIndex: Int) = editSchoolTime {
        it.copy(daysMask = it.daysMask xor (1 shl dayIndex))
    }

    fun setDeviceDailyLimit(minutes: Int?) {
        viewModelScope.launch {
            rules.setDailyLimit(minutes)
            reconcile()
        }
    }

    private fun edit(transform: (BedtimeSchedule) -> BedtimeSchedule) {
        viewModelScope.launch {
            rules.setSchedule(transform(rules.scheduleNow()))
            reconcile()
        }
    }

    private fun editDowntime(transform: (DowntimeSchedule) -> DowntimeSchedule) {
        viewModelScope.launch {
            rules.setDowntime(transform(rules.downtimeNow()))
            reconcile()
        }
    }

    private fun editSchoolTime(transform: (SchoolTimeSchedule) -> SchoolTimeSchedule) {
        viewModelScope.launch {
            rules.setSchoolTime(transform(rules.schoolTimeNow()))
            reconcile()
        }
    }

    /**
     * Applies [transform] to the app's rule, creating one if absent and deleting it once it
     * carries no restriction. A row that restricts nothing is indistinguishable from no row, and
     * keeping it would make the app look supervised in the list when it is not.
     */
    private fun editRule(packageName: String, transform: (AppRule) -> AppRule) {
        // Exempt packages (home, dialer, Settings, ourselves) are never governed, even
        // if a stale caller asks: no rule row is written for them.
        if (ExemptPackages.isExempt(getApplication(), packageName)) return
        viewModelScope.launch {
            val existing = rules.rule(packageName) ?: AppRule(packageName)
            val next = transform(existing)
            if (next.dailyLimitMinutes == null && !next.blockedAtBedtime && !next.allowedInDowntime) {
                rules.delete(next)
            } else {
                rules.upsert(next)
            }
            reconcile()
        }
    }

    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        Enforcer(getApplication()).reconcile()
    }

    /**
     * Launchable apps the parent may put a rule on, excluding the exempt packages.
     *
     * Supervising the supervision app would let a limit lock the parent out of the controls, so
     * it is not offered; neither are the home/launcher, the dialer or Settings (see
     * [ExemptPackages]) — blocking any of them would strand the child or the parent, so the
     * supervisor flows never offer them. Queried with `MATCH_ALL` against the launcher intent
     * rather than listing every installed package, because a package with no launcher entry is
     * not something a child "uses" and would only make the list unusable.
     */
    private suspend fun loadLaunchableApps(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val exempt = ExemptPackages.resolve(getApplication())
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            // ResolveInfoFlags.of is API 33+; on 31-32 the int-flags overload is the same query.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            }
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName !in exempt }
                .map { it.packageName to it.label(pm) }
                .sortedBy { it.second.lowercase() }
        }

    private fun ApplicationInfo.label(pm: PackageManager): String =
        runCatching { pm.getApplicationLabel(this).toString() }.getOrDefault(packageName)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
