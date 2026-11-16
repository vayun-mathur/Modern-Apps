package com.vayunmathur.madeveloper.domain

/**
 * Pure navigation state for browsing one repository via the git host JSON
 * API: the selected branch, the current tree path and the loaded commit
 * page. The commit shown on the detail screen is identified by
 * `Route.Commit.sha`, not by state here. No Android imports, so every
 * transition is unit-testable.
 */
data class RepoBrowserState(
    val repoName: String = "",
    val branch: String = "",
    val branches: List<GitBranch> = emptyList(),
    val path: String = "",
    val commits: List<GitCommit> = emptyList(),
    val commitsExhausted: Boolean = false,
) {
    /** Breadcrumb segments of [path] as (label, cumulative path) pairs. */
    val breadcrumbs: List<Pair<String, String>>
        get() {
            if (path.isBlank()) return emptyList()
            val parts = path.split('/').filter { it.isNotEmpty() }
            return parts.mapIndexed { index, part -> part to parts.take(index + 1).joinToString("/") }
        }

    /** Cursor for the next commits page (`after=`), or null when exhausted. */
    val nextCommitCursor: String? get() = if (commitsExhausted) null else commits.lastOrNull()?.sha
}

/** State transitions for [RepoBrowserState]. */
object RepoBrowser {
    /** Starts browsing [detail], on its default branch, at the tree root. */
    fun open(detail: RepoDetail): RepoBrowserState = RepoBrowserState(
        repoName = detail.name,
        branch = detail.defaultBranch.ifBlank { detail.branches.firstOrNull()?.name ?: "" },
        branches = detail.branches,
        commits = detail.recentCommits,
        commitsExhausted = false,
    )

    /** Switches branch: tree returns to the root and the commit list resets. */
    fun selectBranch(state: RepoBrowserState, branch: String): RepoBrowserState =
        if (branch == state.branch) state else state.copy(
            branch = branch, path = "", commits = emptyList(),
            commitsExhausted = false,
        )

    /** Descends into a tree entry; blobs do not change the tree path. */
    fun openEntry(state: RepoBrowserState, entry: TreeEntry): RepoBrowserState =
        if (entry.isDir) state.copy(path = entry.path) else state

    /** Moves to [path] (a breadcrumb target); blank means the tree root. */
    fun navigateTo(state: RepoBrowserState, path: String): RepoBrowserState =
        state.copy(path = path.trim('/'))

    /** Moves one directory up; stays at the root when already there. */
    fun navigateUp(state: RepoBrowserState): RepoBrowserState =
        state.copy(path = state.path.substringBeforeLast('/', "").trim('/'))

    /** Replaces the commit list with a fresh first page. */
    fun commitsLoaded(state: RepoBrowserState, page: List<GitCommit>, pageSize: Int): RepoBrowserState =
        state.copy(commits = page, commitsExhausted = page.size < pageSize)

    /** Appends the next commits page, de-duplicated by sha. */
    fun commitsAppended(state: RepoBrowserState, page: List<GitCommit>, pageSize: Int): RepoBrowserState =
        state.copy(
            commits = (state.commits + page).distinctBy { it.sha },
            commitsExhausted = page.size < pageSize,
        )
}
