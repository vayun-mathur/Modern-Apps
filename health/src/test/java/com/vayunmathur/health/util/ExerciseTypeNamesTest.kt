package com.vayunmathur.health.util

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Health Connect exercise type name tables.
 *
 * These names label every workout in the exercise history; an unknown type
 * must degrade to "Workout" rather than a blank or a crash.
 */
class ExerciseTypeNamesTest {

    @Test
    fun `known types have names`() {
        assertEquals("Running", exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals("Walking", exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals("Yoga", exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_YOGA))
        assertEquals(
            "Pool Swimming",
            exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL),
        )
    }

    @Test
    fun `unknown types degrade to Workout`() {
        assertEquals("Workout", exerciseTypeName(-1))
        assertEquals("Workout", exerciseTypeName(Int.MAX_VALUE))
    }

    @Test
    fun `segment types have names`() {
        assertEquals(
            "Bench Press",
            exerciseSegmentTypeName(ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS),
        )
        assertEquals("Activity", exerciseSegmentTypeName(-1))
    }
}
