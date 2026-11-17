package com.vayunmathur.madeveloper.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Coarse CI outcome used to pick a status colour and icon in the UI. */
enum class CiStatus { Passed, Failed, Running, Unknown }

/** Coarse file kind used to pick a file-type icon in the tree and file screens. */
enum class FileKind { Folder, Code, Markup, Image, Archive, Data, Text, Other }

/**
 * Pure presentation mappings for the Developer screens: CI/install status
 * classification, relative timestamps, file kinds and repository language
 * chips. No Android or Compose imports, so every mapping is unit-testable
 * with a fixed clock.
 */
object DevPresentation {

    /** Classifies a free-form CI status string from the git host. */
    fun ciStatusOf(raw: String): CiStatus = when {
        raw.contains("pass", ignoreCase = true) ||
            raw.contains("success", ignoreCase = true) ||
            raw.equals("ok", ignoreCase = true) -> CiStatus.Passed

        raw.contains("fail", ignoreCase = true) ||
            raw.contains("error", ignoreCase = true) ||
            raw.contains("cancel", ignoreCase = true) -> CiStatus.Failed

        raw.contains("run", ignoreCase = true) ||
            raw.contains("pending", ignoreCase = true) ||
            raw.contains("queue", ignoreCase = true) -> CiStatus.Running

        else -> CiStatus.Unknown
    }

    /** Whether a CI status should be shown in the failure colour. */
    fun isFailure(raw: String): Boolean = ciStatusOf(raw) == CiStatus.Failed

    /** Human label for one pushed-install phase. */
    fun installPhaseLabel(phase: InstallPhase): String = when (phase) {
        InstallPhase.PUSHED -> "Pushed"
        InstallPhase.DOWNLOADING -> "Downloading"
        InstallPhase.VERIFYING -> "Verifying"
        InstallPhase.READY -> "Ready to install"
        InstallPhase.INSTALLING -> "Installing"
        InstallPhase.DONE -> "Installed"
        InstallPhase.FAILED -> "Failed"
    }

    /** Whether a pushed-install phase is a terminal failure. */
    fun isInstallFailure(phase: InstallPhase): Boolean = phase == InstallPhase.FAILED

    /**
     * Relative label for a git-host timestamp ("just now", "5m ago",
     * "3h ago", "2d ago"), falling back to a short date for older values
     * and to the raw value when it cannot be parsed. [nowEpochMs] is
     * injected so tests are deterministic.
     */
    fun relativeTimeLabel(time: String, nowEpochMs: Long): String {
        val raw = time.trim()
        if (raw.isBlank()) return ""
        val epochMs = parseEpochMs(raw) ?: return raw
        val delta = nowEpochMs - epochMs
        if (delta < 0) return shortDate(epochMs)
        val minutes = delta / MILLIS_PER_MINUTE
        val hours = delta / MILLIS_PER_HOUR
        val days = delta / MILLIS_PER_DAY
        return when {
            delta < MILLIS_PER_MINUTE -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> shortDate(epochMs)
        }
    }

    /** Parses ISO instants, offset date-times, plain dates and epoch values. */
    fun parseEpochMs(raw: String): Long? {
        val value = raw.trim()
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching {
            return LocalDate.parse(value.take(DATE_PREFIX_LENGTH))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val numeric = value.toLongOrNull() ?: return null
        // Host times may arrive as epoch seconds; values before 2001 in
        // milliseconds would be implausibly small for a commit time.
        return if (numeric < EPOCH_SECONDS_THRESHOLD) numeric * MILLIS_PER_SECOND else numeric
    }

    private fun shortDate(epochMs: Long): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /** File kind for a tree entry name; directories always win. */
    fun fileKindFor(name: String, isDir: Boolean): FileKind {
        if (isDir) return FileKind.Folder
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "kt", "kts", "java", "rs", "py", "js", "ts", "tsx", "c", "h", "cpp", "go", "sh" -> FileKind.Code
            "md", "markdown", "rst", "adoc" -> FileKind.Markup
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" -> FileKind.Image
            "zip", "tar", "gz", "xz", "apk", "jar" -> FileKind.Archive
            "json", "xml", "toml", "yaml", "yml", "csv", "properties", "gradle" -> FileKind.Data
            "txt", "log", "gitignore", "editorconfig" -> FileKind.Text
            else -> if (name.startsWith(".")) FileKind.Data else FileKind.Other
        }
    }

    /**
     * Language chip for a hosted repository. The JSON API carries no
     * language field, so this is a display heuristic for the repositories
     * the maproj host actually serves, defaulting to a neutral label.
     */
    fun repoLanguage(repoName: String): String = when (repoName.lowercase(Locale.US)) {
        "modern-apps" -> "Kotlin · Rust"
        "maos" -> "AOSP · Kotlin"
        "location_share_server" -> "Rust"
        else -> "Git"
    }

    /** Short display form of a sha, never longer than eight characters. */
    fun shortSha(sha: String): String = sha.take(SHORT_SHA_LENGTH)

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val EPOCH_SECONDS_THRESHOLD = 10_000_000_000L
    private const val DATE_PREFIX_LENGTH = 10
    private const val SHORT_SHA_LENGTH = 8
}
