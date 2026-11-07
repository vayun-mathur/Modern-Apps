package com.vayunmathur.library.avatar.yuu

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YuuPoseTest {

    @Test
    fun `gallery lists the five v1 presets in order`() {
        assertEquals(
            listOf("idle", "wave", "thumbs_up", "thinking", "cheering"),
            YUU_POSES.map { it.id },
        )
        assertEquals(
            listOf("Idle", "Wave", "Thumbs up", "Thinking", "Cheering"),
            YUU_POSES.map { it.name },
        )
    }

    @Test
    fun `pose ids are unique and non blank`() {
        assertEquals(YUU_POSES.size, YUU_POSES.map { it.id }.toSet().size)
        assertTrue(YUU_POSES.all { it.id.isNotBlank() && it.name.isNotBlank() })
    }

    @Test
    fun `default pose is the idle preset`() {
        assertEquals("idle", YUU_POSE_DEFAULT)
        assertTrue(YUU_POSES.any { it.id == YUU_POSE_DEFAULT }, "default must be a real preset")
        assertEquals(YUU_POSES.first().id, YUU_POSE_DEFAULT)
    }

    @Test
    fun `pose is a serializable data class`() {
        val pose = YuuPose("wave", "Wave")
        assertEquals(pose, YuuPose("wave", "Wave"))
        assertNotEquals(pose, YuuPose("idle", "Wave"))
        assertEquals(pose.copy(name = "Waving"), YuuPose("wave", "Waving"))
        val json = Json.encodeToString(YuuPose.serializer(), pose)
        assertEquals(pose, Json.decodeFromString(YuuPose.serializer(), json))
        assertTrue(json.contains("\"wave\""))
    }

    @Test
    fun `every gallery pose survives a json round trip`() {
        for (pose in YUU_POSES) {
            val decoded = Json.decodeFromString(
                YuuPose.serializer(),
                Json.encodeToString(YuuPose.serializer(), pose),
            )
            assertEquals(pose, decoded)
        }
    }
}
