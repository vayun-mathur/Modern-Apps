package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaArtifactsComparisonTest {

    @Test
    fun `streaming and plain artifact names differ only by the suffix`() {
        assertEquals(
            "shiba-incremental-2026090500-2026090700.zip",
            OtaArtifacts.incremental("shiba", "2026090500", "2026090700", streaming = false),
        )
        assertEquals(
            "shiba-streaming-incremental-2026090500-2026090700.zip",
            OtaArtifacts.incremental("shiba", "2026090500", "2026090700", streaming = true),
        )
        assertEquals(
            "shiba-ota_update-2026090700.zip",
            OtaArtifacts.full("shiba", "2026090700", streaming = false),
        )
        assertEquals(
            "shiba-streaming-ota_update-2026090700.zip",
            OtaArtifacts.full("shiba", "2026090700", streaming = true),
        )
    }

    @Test
    fun `only a strictly newer build date is an update`() {
        val current = UpdateComparison.CurrentBuild("2026090500", 1000L)
        assertTrue(UpdateComparison.isNewer(current, UpdateMetadata("2026090700", 1001L)))
        assertFalse(UpdateComparison.isNewer(current, UpdateMetadata("2026090700", 1000L)))
        assertFalse(UpdateComparison.isNewer(current, UpdateMetadata("2026090700", 999L)))
    }

    @Test
    fun `payload offset skips malformed and unrelated entries`() {
        val metadata = OtaPackageMetadata(
            postTimestamp = null, postBuildIncremental = null, preDevice = null, serialNo = null,
            otaType = null, preBuildIncremental = null, preBuildFingerprint = null,
            streamingPropertyFiles = listOf("payload_properties.txt:5:60", "payload.bin:notanumber:20", "payload.bin"),
        )
        assertNull(metadata.payloadOffset())
    }

    @Test
    fun `payload offset uses the first payload_bin entry that parses`() {
        val metadata = OtaPackageMetadata(
            postTimestamp = null, postBuildIncremental = null, preDevice = null, serialNo = null,
            otaType = null, preBuildIncremental = null, preBuildFingerprint = null,
            streamingPropertyFiles = listOf("payload.bin:oops:20", "payload.bin:4096:99"),
        )
        assertEquals(4096L, metadata.payloadOffset())
    }

    @Test
    fun `metadata parse tolerates a non numeric timestamp and trims streaming entries`() {
        val parsed = OtaPackageMetadata.parse(
            sequenceOf(
                "post-timestamp=not-a-number",
                "ota-streaming-property-files= payload.bin:7:8 , payload_properties.txt:1:2 ",
                "serialno=",
            ),
        )
        assertNull(parsed.postTimestamp)
        assertEquals("", parsed.serialNo)
        assertEquals(listOf("payload.bin:7:8", "payload_properties.txt:1:2"), parsed.streamingPropertyFiles)
        assertEquals(7L, parsed.payloadOffset())
    }
}
