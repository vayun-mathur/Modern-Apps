package com.vayunmathur.watch.phone.health

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.vayunmathur.watch.phone.data.ReceivedRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure, side-effect-free derivations run over the phone-side record buffer.
 *
 * These remain heuristic by design: resting HR and sleep have no direct
 * watch-side measurement API, so they are estimated from raw HR + motion. All
 * other metrics (distance, floors, elevation, calories) are now measured
 * directly on the watch via Health Services and inserted verbatim. Outputs carry
 * a day-keyed clientRecordId so recomputing on later syncs upserts instead of
 * duplicating in Health Connect.
 */
class HealthDerivations(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** @param records the full buffered window of received raw records. */
    fun derive(records: List<ReceivedRecord>): List<Record> {
        if (records.isEmpty()) return emptyList()
        val hr = records.filter { it.type == "HeartRate" }.sortedBy { it.timestamp }

        val out = mutableListOf<Record>()
        out += restingHeartRate(hr)
        out += sleepSessions(hr)
        return out
    }

    // Resting HR = low-quantile of stationary HR readings per day.
    private fun restingHeartRate(hr: List<ReceivedRecord>): List<Record> {
        return hr.filter { it.stationary && it.value > 0.0 }
            .groupBy { dateOf(it.timestamp) }
            .mapNotNull { (day, rows) ->
                if (rows.size < MIN_RESTING_SAMPLES) return@mapNotNull null
                val sorted = rows.map { it.value }.sorted()
                val idx = ((sorted.size - 1) * RESTING_QUANTILE).toInt()
                val bpm = sorted[idx].toLong()
                if (bpm <= 0L) return@mapNotNull null
                val time = Instant.ofEpochMilli(rows.minOf { it.timestamp })
                RestingHeartRateRecord(
                    time = time,
                    zoneOffset = offset(time),
                    beatsPerMinute = bpm,
                    metadata = Metadata.manualEntry(clientRecordId = "resting-$day"),
                )
            }
    }

    // Sleep = contiguous night-time spans of stationary + low HR, >= min duration.
    private fun sleepSessions(hr: List<ReceivedRecord>): List<Record> {
        val nightly = hr.filter {
            it.stationary && it.value in 1.0..SLEEP_HR_THRESHOLD && isNightTime(it.timestamp)
        }.sortedBy { it.timestamp }
        if (nightly.isEmpty()) return emptyList()

        val out = mutableListOf<Record>()
        var runStart = nightly.first()
        var prev = nightly.first()
        fun flush(last: ReceivedRecord) {
            val durationMs = last.timestamp - runStart.timestamp
            if (durationMs < MIN_SLEEP_DURATION_MS) return
            val start = Instant.ofEpochMilli(runStart.timestamp)
            val end = Instant.ofEpochMilli(last.timestamp)
            // Key by the wake date so a single overnight sleep upserts on later syncs.
            val day = dateOf(last.timestamp)
            out += SleepSessionRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                stages = listOf(
                    SleepSessionRecord.Stage(
                        startTime = start,
                        endTime = end,
                        stage = SleepSessionRecord.STAGE_TYPE_SLEEPING,
                    ),
                ),
                metadata = Metadata.manualEntry(clientRecordId = "sleep-$day"),
            )
        }
        for (i in 1 until nightly.size) {
            val cur = nightly[i]
            if (cur.timestamp - prev.timestamp > SLEEP_GAP_MS) {
                flush(prev)
                runStart = cur
            }
            prev = cur
        }
        flush(prev)
        return out
    }

    // --- helpers ---

    private fun dateOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun offset(instant: Instant) = zone.rules.getOffset(instant)

    private fun isNightTime(ms: Long): Boolean {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        // Overnight window wraps midnight: [20:00, 24:00) ∪ [00:00, 10:00).
        return t >= NIGHT_START || t < NIGHT_END
    }

    companion object {
        private const val RESTING_QUANTILE = 0.05
        private const val MIN_RESTING_SAMPLES = 3
        private const val SLEEP_HR_THRESHOLD = 60.0
        private const val MIN_SLEEP_DURATION_MS = 3 * 60 * 60 * 1000L
        private const val SLEEP_GAP_MS = 30 * 60 * 1000L
        private val NIGHT_START: LocalTime = LocalTime.of(20, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(10, 0)
    }
}
