package com.vayunmathur.madeveloper.domain

/** A repository hosted on the maproj git host, from `GET /api/repos`. */
data class GitRepo(
    val name: String,
    val defaultBranch: String = "",
    val headSha: String = "",
    val headSubject: String = "",
    val headTime: String = "",
) {
    /** Short form of [headSha] for display. */
    val shortHeadSha: String get() = headSha.take(8)

    /** Clone URL on the git host. */
    val cloneUrl: String get() = "https://git.maproj.org/$name.git"

    /** Human web page for this repository on the git host. */
    val webUrl: String get() = "https://git.maproj.org/$name"
}

/** One branch of a repository, from `GET /api/repos/<repo>`. */
data class GitBranch(val name: String, val sha: String, val time: String = "") {
    /** Short form of [sha] for display. */
    val shortSha: String get() = sha.take(8)
}

/** One commit, from the repo detail or `GET /api/repos/<repo>/commits`. */
data class GitCommit(
    val sha: String,
    val shortSha: String = sha.take(8),
    val author: String = "",
    val time: String = "",
    val subject: String = "",
    val body: String = "",
)

/** Full repository detail from `GET /api/repos/<repo>`. */
data class RepoDetail(
    val name: String,
    val defaultBranch: String = "",
    val branches: List<GitBranch> = emptyList(),
    val recentCommits: List<GitCommit> = emptyList(),
)

/** One file read from `GET /api/repos/<repo>/file`. */
data class GitFile(
    val path: String,
    val size: Long = 0,
    val isBinary: Boolean = false,
    val content: String? = null,
    val truncated: Boolean = false,
    val sha: String = "",
)

/** One issue as served by `/issues.json`. */
data class GitIssue(
    val repo: String, val number: Int, val title: String, val author: String,
    val comments: Int, val labels: List<String>, val updatedAt: String,
) {
    /** GitHub page for this issue; [repo] is the `owner/name` slug issues are mirrored from. */
    val webUrl: String get() = "https://github.com/$repo/issues/$number"
}

/** One pull request as served by `/prs.json`. */
data class GitPr(val repo: String, val number: Int, val title: String, val author: String) {
    /** Hosted repository name for this PR (`Modern-Apps.git` or an `owner/name` slug). */
    val hostRepo: String get() = repo.substringAfterLast('/').removeSuffix(".git")

    /** Git host page for this PR's repository. */
    val webUrl: String get() = "https://git.maproj.org/$hostRepo"
}

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

/** One entry in a repository tree listing from `GET /api/repos/<repo>/tree`. */
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
