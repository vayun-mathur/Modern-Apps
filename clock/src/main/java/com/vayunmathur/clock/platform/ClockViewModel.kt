package com.vayunmathur.clock.platform

import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import java.io.DataInputStream
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * ViewModel for the Clock app.
 *
 * Owns:
 *  - city → timezone map (loaded once from assets/cities.bin off the main thread)
 *  - a shared 100ms wall-clock tick (paused while no UI subscribes)
 *  - stopwatch run state, lap list, and derived counting time
 *  - inbound AlarmClock.ACTION_* intent dispatch (set alarm / set timer / show alarms)
 *  - alarm and timer DAOs for persistence
 */
class ClockViewModel(
    application: Application,
    private val repository: ClockRepository,
) : AndroidViewModel(application), StopwatchActions {

    private val ds = DataStoreUtils.getInstance(application)

    /**
     * Build a new [Alarm] pre-filled with the user's default ringtone, vibrate,
     * snooze and gradual-volume preferences (set on the alarm settings page).
     */
    fun buildDefaultAlarm(time: LocalTime, name: String, days: Int): Alarm = Alarm(
        time = time,
        name = name,
        enabled = true,
        days = days,
        ringtoneUri = ds.getString(KEY_DEFAULT_RINGTONE),
        vibrate = ds.getBoolean(KEY_DEFAULT_VIBRATE, true),
        snoozeMinutes = (ds.getLong(KEY_DEFAULT_SNOOZE) ?: 5L).toInt(),
        gradualVolumeSeconds = (ds.getLong(KEY_DEFAULT_GRADUAL) ?: 0L).toInt(),
    )

    // --- Database-backed lists -----------------------------------------------

    val timers: StateFlow<List<Timer>> = repository.timers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        emptyList(),
    )

    val alarms: StateFlow<List<Alarm>> = repository.alarms.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        emptyList(),
    )

    fun upsert(timer: Timer, andThen: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.upsertTimer(timer)
            andThen(id)
        }
    }

    fun upsert(alarm: Alarm, andThen: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.upsertAlarm(alarm)
            andThen(id)
        }
    }

    fun delete(timer: Timer) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTimer(timer)
        }
    }

    fun delete(alarm: Alarm) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAlarm(alarm)
        }
    }

    // --- City → timezone map ---------------------------------------------------

    private val _cities = MutableStateFlow<Map<String, String>?>(null)
    /** Lazily-loaded mapping of city name → IANA timezone ID. Null until loaded. */
    val cities: StateFlow<Map<String, String>?> = _cities.asStateFlow()

    // --- Shared wall-clock tick -----------------------------------------------

    private val tick = flow {
        while (true) {
            emit(Clock.System.now())
            delay(TICK_MS)
        }
    }

    /**
     * Wall-clock time at [TICK_MS], for the stopwatch only. Pauses when nothing collects.
     *
     * Private on purpose. This is 100ms because the stopwatch renders centiseconds, and it used to
     * be shared with the clock and timer tabs - which show seconds and minutes, and so recomposed
     * ten times a second to redraw a label that changes once a second, or once a minute. Screens
     * that need a displayable clock should use `rememberClock` from `:library:ui`, which lets them
     * pick their own period and hands back a getter so the tick reaches only the labels that show
     * it.
     */
    private val now: StateFlow<Instant> = tick.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        Clock.System.now(),
    )

    // --- Stopwatch state ------------------------------------------------------

    private val _stopwatchRunning = MutableStateFlow(false)
    val stopwatchRunning: StateFlow<Boolean> = _stopwatchRunning.asStateFlow()

    private val _stopwatchTotal = MutableStateFlow(Duration.ZERO)
    private val _stopwatchStart = MutableStateFlow(Clock.System.now())

    private val _lapTimes = MutableStateFlow<List<Duration>>(emptyList())
    val lapTimes: StateFlow<List<Duration>> = _lapTimes.asStateFlow()
    
    private fun loadStopwatchState() {
        val ctx = getApplication<Application>()
        val ds = DataStoreUtils.getInstance(ctx)
        // Observe, don't just read once: the notification actions are the
        // stopwatch's control plane (see clock/AGENTS.md) and write these
        // same keys. A one-shot load left a living ViewModel showing — and
        // then persisting over — stale totals after a notification pause,
        // lap or stop. Our own persistStopwatchState writes flow back as
        // the identical values, so this converges instead of fighting.
        viewModelScope.launch {
            combine(
                ds.booleanFlow(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false),
                ds.longFlow(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, 0L),
                ds.longFlow(StopwatchActionReceiver.KEY_STOPWATCH_START, 0L),
                ds.stringFlow(StopwatchActionReceiver.KEY_STOPWATCH_LAPS),
            ) { isRunning, totalMs, startMs, lapsStr ->
                _stopwatchRunning.value = isRunning
                _stopwatchTotal.value = totalMs.milliseconds
                _stopwatchStart.value =
                    if (startMs > 0) Instant.fromEpochMilliseconds(startMs) else Clock.System.now()
                _lapTimes.value = lapsStr
                    .takeIf { it.isNotEmpty() }
                    ?.split(",")?.mapNotNull { it.toLongOrNull()?.milliseconds }
                    .orEmpty()
            }.collect {}
        }
    }
    
    private fun persistStopwatchState() {
        val ctx = getApplication<Application>()
        val ds = DataStoreUtils.getInstance(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            ds.setBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, _stopwatchRunning.value)
            ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL, _stopwatchTotal.value.inWholeMilliseconds)
            ds.setLong(StopwatchActionReceiver.KEY_STOPWATCH_START, _stopwatchStart.value.toEpochMilliseconds())
            val lapsStr = _lapTimes.value.joinToString(",") { it.inWholeMilliseconds.toString() }
            ds.setString(StopwatchActionReceiver.KEY_STOPWATCH_LAPS, lapsStr)
        }
    }

    /**
     * Elapsed time the stopwatch should display. When running this includes the
     * live delta since the last start. Re-emits on every tick while a screen is
     * subscribed and pauses otherwise.
     */
    val stopwatchCountingTime: StateFlow<Duration> = combine(
        _stopwatchRunning,
        _stopwatchTotal,
        _stopwatchStart,
        now,
    ) { running, total, start, instant ->
        if (running) (instant - start) + total else total
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        Duration.ZERO,
    )

    override fun toggleStopwatch() {
        val ctx = getApplication<Application>()
        if (_stopwatchRunning.value) {
            _stopwatchTotal.value = _stopwatchTotal.value + (Clock.System.now() - _stopwatchStart.value)
            _stopwatchRunning.value = false
        } else {
            _stopwatchStart.value = Clock.System.now()
            _stopwatchRunning.value = true
        }
        persistStopwatchState()
        StopwatchNotificationHelper.updateNotification(ctx)
    }

    override fun resetStopwatch() {
        val ctx = getApplication<Application>()
        _stopwatchRunning.value = false
        _stopwatchTotal.value = Duration.ZERO
        _lapTimes.value = emptyList()
        persistStopwatchState()
        StopwatchNotificationHelper.updateNotification(ctx)
    }

    override fun addLap() {
        val ctx = getApplication<Application>()
        _lapTimes.update { it + stopwatchCountingTime.value }
        persistStopwatchState()
        StopwatchNotificationHelper.updateNotification(ctx)
    }

    // --- Inbound AlarmClock intent dispatch ----------------------------------

    /**
     * Handle an inbound [AlarmClock] action intent. Returns the initial Route the
     * UI should navigate to, or null if the intent was either unrelated or
     * fully handled in the background (skipUi).
     */
    fun handleIncomingIntent(intent: Intent?): Route? {
        intent ?: return null
        return when (intent.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1).takeIf { it != -1 }
                val minutes = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1).takeIf { it != -1 }
                val message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)
                val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)
                val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

                if (skipUi && hour != null && minutes != null) {
                    val time = LocalTime(hour, minutes)
                    var daysMask = 0
                    days?.forEach { day -> daysMask = daysMask or (1 shl (day - 1)) }
                    val alarm = buildDefaultAlarm(time, message ?: "", daysMask)
                    val ctx = getApplication<Application>()
                    viewModelScope.launch(Dispatchers.IO) {
                        val id = repository.upsertAlarm(alarm)
                        AlarmScheduler.schedule(ctx, alarm.copy(id = id))
                    }
                    null
                } else {
                    Route.NewAlarmDialog(hour, minutes, message, days, skipUi)
                }
            }
            AlarmClock.ACTION_SET_TIMER -> {
                val length = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1).takeIf { it != -1 }
                val message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)
                val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

                if (skipUi && length != null) {
                    val timer = Timer(
                        true,
                        message ?: "",
                        Clock.System.now(),
                        length.seconds,
                        length.seconds,
                    )
                    val ctx = getApplication<Application>()
                    viewModelScope.launch(Dispatchers.IO) {
                        val id = repository.upsertTimer(timer)
                        sendTimerNotification(ctx, timer.copy(id = id), true)
                    }
                    null
                } else {
                    Route.NewTimerDialog(length, message)
                }
            }
            AlarmClock.ACTION_SHOW_ALARMS -> Route.Alarm
            else -> null
        }
    }

    // --- Init -----------------------------------------------------------------

    init {
        loadCities()
        loadStopwatchState()
        viewModelScope.launch(Dispatchers.IO) { WorldClockCities.migrate(ds) }
    }

    private fun loadCities() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            // Broad catch is deliberate: a corrupt asset throws undocumented
            // RuntimeExceptions from the parser, not just IOException.
            @Suppress("TooGenericExceptionCaught")
            val map = try {
                ctx.assets.open("cities.bin").use { parseCitiesBin(it) }
            } catch (e: Exception) {
                Log.error(TAG, "Failed to load cities.bin", e)
                emptyMap()
            }
            _cities.value = map
        }
    }

    /**
     * Parse the packed cities.bin asset into a name → timezone map.
     *
     * Format (all integers big-endian, see scripts/generate_cities_bin.py):
     *   magic "CTB1" | tzCount:i32 | tz[]{ len:u8, utf8 } |
     *   cityCount:i32 | city[]{ nameLen:u16, utf8, tzIndex:u16 }
     *
     * City records are stored in source order, so duplicate names resolve to
     * the last occurrence — identical to the previous CSV `associate` logic.
     */
    private fun parseCitiesBin(input: java.io.InputStream): Map<String, String> {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4).also { data.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "CTB1") { "bad cities.bin magic" }

        val tzCount = data.readInt()
        val timezones = Array(tzCount) {
            val bytes = ByteArray(data.readUnsignedByte())
            data.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }

        val cityCount = data.readInt()
        val map = LinkedHashMap<String, String>(cityCount)
        repeat(cityCount) {
            val name = ByteArray(data.readUnsignedShort())
            data.readFully(name)
            val tzIndex = data.readUnsignedShort()
            map[String(name, Charsets.UTF_8)] = timezones[tzIndex]
        }
        return map
    }

    companion object {
        private const val TAG = "ClockViewModel"
        private const val TICK_MS = 100L
        private const val STOP_TIMEOUT_MS = 5_000L

        const val KEY_DEFAULT_RINGTONE = "alarm_default_ringtone"
        const val KEY_DEFAULT_VIBRATE = "alarm_default_vibrate"
        const val KEY_DEFAULT_SNOOZE = "alarm_default_snooze"
        const val KEY_DEFAULT_GRADUAL = "alarm_default_gradual"
    }
}

/** Factory for [ClockViewModel] that injects the repository. */
class ClockViewModelFactory(
    private val application: Application,
    private val repository: ClockRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ClockViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return ClockViewModel(application, repository) as T
    }
}
