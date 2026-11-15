package com.vayunmathur.health.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/** Whether [MedicationSchedule.interval] counts days or weeks. */
enum class RepeatUnit { Daily, Weekly }

/**
 * When a medication should be taken, and therefore when to remind.
 *
 * Deliberately smaller than an RFC-5545 recurrence rule. The clock app's model — one time and a
 * weekday mask — cannot say "twice a day" or "every other week", and calendar's `RRule` covers
 * BYSETPOS, BYYEARDAY and BYWEEKNO that no medication regimen needs and is in any case private to
 * that app. Between [times], [interval] and [daysOfWeek] this covers the realistic space:
 *
 *  - every other week, Mon and Thu, 08:00 and 20:00 → Weekly, 2, {Mon,Thu}, [08:00, 20:00]
 *  - every 3 days at 09:00                          → Daily, 3, [09:00]
 *  - twice daily                                    → Daily, 1, [08:00, 20:00]
 *
 * At most one per medication, and deleting the medication deletes it.
 */
@Entity(indices = [Index(value = ["medicationId"], unique = true)])
data class MedicationSchedule(
    @PrimaryKey val id: String,
    val medicationId: String,
    val enabled: Boolean = true,
    /** Seconds since midnight, one entry per dose, ascending. */
    val times: List<Int> = emptyList(),
    val repeatUnit: RepeatUnit = RepeatUnit.Daily,
    /** Every N days or every N weeks. Always at least 1. */
    val interval: Int = 1,
    /** Seven-bit mask, bit 0 = Sunday. Only meaningful when [repeatUnit] is Weekly. */
    val daysOfWeek: Int = 0,
    /**
     * The date the interval counts from.
     *
     * Without it "every other week" has no meaning — there is nothing to say which week is week
     * zero. Set to the day the schedule was created and left alone thereafter.
     */
    val anchorDate: LocalDate,
    val endDate: LocalDate? = null,
)

/** Contract for schedule dao. */
@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(schedule: MedicationSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: MedicationSchedule)

    @Query("DELETE FROM MedicationSchedule WHERE medicationId = :medicationId")
    suspend fun deleteScheduleFor(medicationId: String)

    @Query("SELECT * FROM MedicationSchedule WHERE medicationId = :medicationId")
    suspend fun getScheduleFor(medicationId: String): MedicationSchedule?

    @Query("SELECT * FROM MedicationSchedule")
    fun getSchedulesFlow(): Flow<List<MedicationSchedule>>

    /** Everything that needs an alarm armed — used on boot and after a time-zone change. */
    @Query("SELECT * FROM MedicationSchedule WHERE enabled = 1")
    suspend fun getEnabledSchedules(): List<MedicationSchedule>

    @Query("SELECT * FROM MedicationSchedule WHERE id = :id")
    suspend fun getSchedule(id: String): MedicationSchedule?
}
