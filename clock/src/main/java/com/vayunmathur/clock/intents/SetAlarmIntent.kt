package com.vayunmathur.clock.intents

import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.util.AlarmScheduler
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import kotlinx.datetime.LocalTime
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

/**
 * Lets another app (e.g. OpenAssistant) create an alarm without any user
 * interaction. Saves a one-time alarm at the requested time and schedules it,
 * exactly like the Clock UI does when the user adds an alarm.
 */
@OptIn(InternalSerializationApi::class)
class SetAlarmIntent : AssistantIntent<SetAlarmData, Unit>(
    serializer<SetAlarmData>(),
    serializer<Unit>(),
) {
    override suspend fun performCalculation(input: SetAlarmData) {
        val alarm = Alarm(
            time = LocalTime(input.hour, input.minute),
            name = input.label,
            enabled = true,
            days = 0, // no repeat: rings once at the next occurrence
        )
        val db = buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
        val id = db.alarmDao().upsert(alarm)
        AlarmScheduler.schedule(this, alarm.copy(id = id))
    }
}
