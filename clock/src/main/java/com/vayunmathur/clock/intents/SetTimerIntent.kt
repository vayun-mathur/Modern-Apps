package com.vayunmathur.clock.intents

import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.sendTimerNotification
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Lets another app (e.g. OpenAssistant) start a countdown timer without any user
 * interaction. Saves a running timer and posts its notification, exactly like
 * the Clock UI does when the user starts a timer.
 */
@OptIn(InternalSerializationApi::class)
class SetTimerIntent : AssistantIntent<SetTimerData, Unit>(
    serializer<SetTimerData>(),
    serializer<Unit>(),
) {
    override suspend fun performCalculation(input: SetTimerData) {
        val length = input.seconds.seconds
        val timer = Timer(
            isRunning = true,
            name = input.label,
            remainingStartTime = Clock.System.now(),
            remainingLength = length,
            totalLength = length,
        )
        val db = buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
        val id = db.timerDao().upsert(timer)
        sendTimerNotification(this, timer.copy(id = id), true)
    }
}
