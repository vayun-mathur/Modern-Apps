package com.vayunmathur.madeveloper.domain

/** A repository hosted on the maproj git host. */
data class GitRepo(val name: String, val cloneUrl: String, val github: String?, val refs: List<GitRef> = emptyList())

/** One advertised git reference (branch or tag) from `info/refs`. */
data class GitRef(val name: String, val sha: String) {
    /** Short form of [sha] for display. */
    val shortSha: String get() = sha.take(8)
}

/** One issue as served by `/issues.json`. */
data class GitIssue(
    val repo: String, val number: Int, val title: String, val author: String,
    val comments: Int, val labels: List<String>, val updatedAt: String,
)

/** One pull request as served by `/prs.json`. */
data class GitPr(val repo: String, val number: Int, val title: String, val author: String)

/** One CI run from `/tests.json`. */
data class TestRun(
    val id: String, val sha: String, val subject: String, val status: String,
    val failed: Int, val tests: Int, val durationSecs: Long?,
)

/** The latest CI state plus optional in-progress run from `/tests.json`. */
data class TestsStatus(
    val latest: TestRun?, val running: TestRun?,
    val history: List<TestRun>, val coveragePct: Double?,
)

/** One entry in a repository tree listing. */
data class TreeEntry(val name: String, val path: String, val isDir: Boolean, val size: Long?)

/** One installable build in the read-only F-Droid catalog. */
data class CatalogApp(
    val packageName: String, val name: String, val summary: String,
    val versionName: String?, val versionCode: Long, val sizeBytes: Long, val apkPath: String?,
) {
    /** Human-readable size for display. */
    val sizeLabel: String get() = when {
        sizeBytes >= 1_000_000 -> "%.1f MB".format(sizeBytes / 1_000_000.0)
        sizeBytes >= 1_000 -> "%.0f KB".format(sizeBytes / 1_000.0)
        else -> "$sizeBytes B"
    }
}
