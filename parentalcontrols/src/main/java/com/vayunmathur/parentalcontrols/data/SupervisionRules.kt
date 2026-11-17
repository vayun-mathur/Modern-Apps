package com.vayunmathur.parentalcontrols.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single owner of [ParentalControlsDatabase], shared by the UI, the alarm receiver, the usage
 * observers and the bound [android.app.supervision.SupervisionAppService].
 *
 * Those last three are the reason this is a process-wide singleton rather than a ViewModel
 * dependency: enforcement runs with no Activity alive.
 */
class SupervisionRules private constructor(context: Context) :
    RoomRepository<ParentalControlsDatabase>(
        context,
        ParentalControlsDatabase::class,
        DB_NAME,
        migrations = listOf(MIGRATION_1_2),
    ) {

    private val rules get() = db.appRuleDao()
    private val bedtime get() = db.bedtimeDao()
    private val downtime get() = db.downtimeDao()
    private val schoolTime get() = db.schoolTimeDao()
    private val dailyLimit get() = db.dailyLimitDao()
    private val bonus get() = db.bonusGrantDao()

    val allRules: Flow<List<AppRule>> = rules.allFlow()

    /** Apps with a daily cap, which is the set the usage observers are registered for. */
    val limitedRules: Flow<List<AppRule>> =
        rules.allFlow().map { list -> list.filter { it.dailyLimitMinutes != null } }

    suspend fun allRulesNow(): List<AppRule> = rules.all()

    suspend fun rule(packageName: String): AppRule? = rules.byPackage(packageName)

    suspend fun upsert(rule: AppRule) = rules.upsert(rule)

    suspend fun delete(rule: AppRule) = rules.delete(rule)

    /**
     * The schedule, defaulted rather than nullable.
     *
     * A missing row and a disabled schedule mean the same thing to every caller, so collapsing
     * them here keeps that distinction out of the enforcer and the UI.
     */
    val schedule: Flow<BedtimeSchedule> =
        bedtime.scheduleFlow().map { it ?: BedtimeSchedule() }

    suspend fun scheduleNow(): BedtimeSchedule = bedtime.schedule() ?: BedtimeSchedule()

    suspend fun setSchedule(schedule: BedtimeSchedule) = bedtime.upsert(schedule)

    /**
     * The downtime window, defaulted rather than nullable.
     *
     * Same collapsing as [schedule]: a missing row and a disabled window mean the same thing
     * to every caller.
     */
    val downtimeSchedule: Flow<DowntimeSchedule> =
        downtime.scheduleFlow().map { it ?: DowntimeSchedule() }

    suspend fun downtimeNow(): DowntimeSchedule = downtime.schedule() ?: DowntimeSchedule()

    suspend fun setDowntime(schedule: DowntimeSchedule) = downtime.upsert(schedule)

    /** The school-time window, defaulted rather than nullable. */
    val schoolTimeSchedule: Flow<SchoolTimeSchedule> =
        schoolTime.scheduleFlow().map { it ?: SchoolTimeSchedule() }

    suspend fun schoolTimeNow(): SchoolTimeSchedule =
        schoolTime.schedule() ?: SchoolTimeSchedule()

    suspend fun setSchoolTime(schedule: SchoolTimeSchedule) = schoolTime.upsert(schedule)

    /** The device-wide daily budget in minutes, or null for no device-wide cap. */
    val dailyLimitMinutes: Flow<Int?> = dailyLimit.limitFlow().map { it?.dailyLimitMinutes }

    suspend fun dailyLimitNow(): Int? = dailyLimit.limit()?.dailyLimitMinutes

    suspend fun setDailyLimit(minutes: Int?) = dailyLimit.upsert(DailyLimit(dailyLimitMinutes = minutes))

    /**
     * Today's bonus grants, with foreign-day rows pruned first so a grant can never leak into
     * tomorrow.
     */
    suspend fun bonusesToday(day: String): List<BonusGrant> {
        bonus.pruneForeignDays(day)
        return bonus.forDay(day)
    }

    suspend fun grantBonus(grant: BonusGrant) = bonus.upsert(grant)

    /** Process-wide singleton access to [SupervisionRules]. */
    companion object {
        @Volatile private var instance: SupervisionRules? = null

        fun get(context: Context): SupervisionRules =
            instance ?: synchronized(this) {
                instance ?: SupervisionRules(context).also { instance = it }
            }
    }
}
