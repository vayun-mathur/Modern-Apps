package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.Instance
import kotlinx.datetime.LocalDate

/**
 * Represents an event slice inside a single day (minutes from 0..1440)
 */
data class PositionedEvent(
    val instanceID: Long,
    val eventID: Long,
    val title: String,
    val color: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val columnIndex: Int,
    val totalColumns: Int,
)

/**
 * Compute positioned events (column assignment) for the given day. Caller may pass events that span the day.
 * This function will clamp per-day start/end to [0,1440) and ignore zero-length slices.
 */
fun computePositionedEventsForDay(instances: List<Instance>, day: LocalDate): List<PositionedEvent> {
    // Build per-day slices
    data class Slice(val instanceID: Long, val eventID: Long, val title: String, val color: Int, val start: Int, val end: Int)

    val slices = ArrayList<Slice>()
    for (instance in instances) {
        // determine start and end minutes for this day
        val startMinutes = if (day == instance.spanDays.first()) {
            instance.startDateTime.hour * 60 + instance.startDateTime.minute
        } else 0
        val endMinutes = if (day == instance.spanDays.last()) {
            instance.endDateTime.hour * 60 + instance.endDateTime.minute
        } else 24 * 60

        val s = startMinutes.coerceAtLeast(0).coerceAtMost(24 * 60)
        val e = endMinutes.coerceAtLeast(0).coerceAtMost(24 * 60)
        if (s >= e) continue
        slices.add(Slice(instance.id, instance.eventID, instance.eventTitle, instance.color, s, e))
    }

    if (slices.isEmpty()) return emptyList()

    // sort by start then end
    val sorted = slices.withIndex().sortedWith(compareBy({ it.value.start }, { it.value.end }))

    // Build ranges with original index
    data class Range(val idx: Int, val start: Int, val end: Int, val slice: Slice)
    val ranges = sorted.mapIndexed { i, si -> Range(i, si.value.start, si.value.end, si.value) }

    // Split into components (connected overlapping groups)
    val components = ArrayList<List<Range>>()
    var currentComp = ArrayList<Range>()
    var currentEnd = -1
    for (r in ranges) {
        if (currentComp.isEmpty()) {
            currentComp.add(r)
            currentEnd = r.end
        } else {
            if (r.start < currentEnd) {
                currentComp.add(r)
                if (r.end > currentEnd) currentEnd = r.end
            } else {
                components.add(currentComp)
                currentComp = ArrayList()
                currentComp.add(r)
                currentEnd = r.end
            }
        }
    }
    if (currentComp.isNotEmpty()) components.add(currentComp)

    // Prepare output array matching slices order (we'll collect in order of processing)
    val output = ArrayList<PositionedEvent>(slices.size)

    // Process each component with greedy interval partitioning
    for (comp in components) {
        // We need minimal columns for this component
        // pq holds pairs of (endTime, columnIndex)
        val pq = java.util.PriorityQueue<Pair<Int, Int>>(compareBy { it.first })
        val freeCols = ArrayDeque<Int>()
        var nextCol = 0

        // map local assigned -> PositionedEvent (we'll collect events for this component then set totalColumns)
        val assigned = ArrayList<PositionedEvent>()

        for (r in comp.sortedWith(compareBy({ it.start }, { it.end }))) {
            while (pq.isNotEmpty() && pq.peek()!!.first <= r.start) {
                val freed = pq.poll()!!
                freeCols.addLast(freed.second)
            }
            val col = if (freeCols.isNotEmpty()) freeCols.removeLast() else nextCol++
            pq.add(Pair(r.end, col))
            assigned.add(
                PositionedEvent(
                    instanceID = r.slice.instanceID,
                    eventID = r.slice.eventID,
                    title = r.slice.title,
                    color = r.slice.color,
                    startMinutes = r.start,
                    endMinutes = r.end,
                    columnIndex = col,
                    totalColumns = -1 // placeholder
                )
            )
        }

        val used = nextCol
        // set totalColumns for assigned events in this component
        for (p in assigned) {
            output.add(p.copy(totalColumns = used))
        }
    }

    // The output is grouped by components; that's fine for rendering — client doesn't rely on original order
    return output
}
