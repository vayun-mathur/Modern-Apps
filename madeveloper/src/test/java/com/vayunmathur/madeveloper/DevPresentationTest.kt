package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.domain.CiStatus
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.FileKind
import com.vayunmathur.madeveloper.domain.InstallPhase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure presentation mappings: status classification, relative time, file kinds. */
class DevPresentationTest {

    private val now: Long = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()

    @Test fun ciStatusClassification() {
        assertEquals(CiStatus.Passed, DevPresentation.ciStatusOf("passed"))
        assertEquals(CiStatus.Passed, DevPresentation.ciStatusOf("SUCCESS"))
        assertEquals(CiStatus.Failed, DevPresentation.ciStatusOf("failed"))
        assertEquals(CiStatus.Failed, DevPresentation.ciStatusOf("error"))
        assertEquals(CiStatus.Running, DevPresentation.ciStatusOf("running"))
        assertEquals(CiStatus.Running, DevPresentation.ciStatusOf("queued"))
        assertEquals(CiStatus.Unknown, DevPresentation.ciStatusOf(""))
        assertEquals(CiStatus.Unknown, DevPresentation.ciStatusOf("weird"))
        assertTrue(DevPresentation.isFailure("failed 2"))
        assertFalse(DevPresentation.isFailure("passed"))
    }

    @Test fun installPhaseLabels() {
        assertEquals("Ready to install", DevPresentation.installPhaseLabel(InstallPhase.READY))
        assertEquals("Downloading", DevPresentation.installPhaseLabel(InstallPhase.DOWNLOADING))
        assertEquals("Failed", DevPresentation.installPhaseLabel(InstallPhase.FAILED))
        assertTrue(DevPresentation.isInstallFailure(InstallPhase.FAILED))
        assertFalse(DevPresentation.isInstallFailure(InstallPhase.READY))
    }

    @Test fun relativeTimeUsesInjectedClock() {
        assertEquals("just now", DevPresentation.relativeTimeLabel("2026-11-17T11:59:30Z", now))
        assertEquals("5m ago", DevPresentation.relativeTimeLabel("2026-11-17T11:55:00Z", now))
        assertEquals("3h ago", DevPresentation.relativeTimeLabel("2026-11-17T09:00:00Z", now))
        assertEquals("2d ago", DevPresentation.relativeTimeLabel("2026-11-15T12:00:00Z", now))
        assertEquals("", DevPresentation.relativeTimeLabel("", now))
        assertEquals("not-a-date", DevPresentation.relativeTimeLabel("not-a-date", now))
    }

    @Test fun relativeTimeFallsBackToShortDateForOldValues() {
        val label = DevPresentation.relativeTimeLabel("2026-01-02T12:00:00Z", now)
        assertTrue(label.contains("Jan"), label)
        assertTrue(label.contains("2026"), label)
    }

    @Test fun epochSecondsAreAccepted() {
        val epochSeconds = (now / 1_000L - 120L).toString()
        assertEquals("2m ago", DevPresentation.relativeTimeLabel(epochSeconds, now))
    }

    @Test fun fileKindsByExtension() {
        assertEquals(FileKind.Folder, DevPresentation.fileKindFor("src", isDir = true))
        assertEquals(FileKind.Code, DevPresentation.fileKindFor("Main.kt", isDir = false))
        assertEquals(FileKind.Code, DevPresentation.fileKindFor("build.gradle.kts", isDir = false))
        assertEquals(FileKind.Markup, DevPresentation.fileKindFor("README.md", isDir = false))
        assertEquals(FileKind.Image, DevPresentation.fileKindFor("logo.png", isDir = false))
        assertEquals(FileKind.Archive, DevPresentation.fileKindFor("app.apk", isDir = false))
        assertEquals(FileKind.Data, DevPresentation.fileKindFor("index.json", isDir = false))
        assertEquals(FileKind.Other, DevPresentation.fileKindFor("LICENSE", isDir = false))
    }

    @Test fun repoLanguageAndShortSha() {
        assertEquals("Kotlin · Rust", DevPresentation.repoLanguage("Modern-Apps"))
        assertEquals("Git", DevPresentation.repoLanguage("something-else"))
        assertEquals("aabbccdd", DevPresentation.shortSha("aabbccdd11223344"))
        assertEquals("abc", DevPresentation.shortSha("abc"))
    }
}
