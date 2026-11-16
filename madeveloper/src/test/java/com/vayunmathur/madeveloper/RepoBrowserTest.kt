package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.domain.GitBranch
import com.vayunmathur.madeveloper.domain.GitCommit
import com.vayunmathur.madeveloper.domain.RepoBrowser
import com.vayunmathur.madeveloper.domain.RepoDetail
import com.vayunmathur.madeveloper.domain.TreeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the pure repo-browser navigation state holder. */
class RepoBrowserTest {
    private val detail = RepoDetail(
        name = "Modern-Apps", defaultBranch = "main",
        branches = listOf(GitBranch("main", "a".repeat(40)), GitBranch("dev", "b".repeat(40))),
        recentCommits = listOf(GitCommit("a".repeat(40), subject = "head")),
    )

    @Test fun openUsesDefaultBranchAndRecentCommits() {
        val state = RepoBrowser.open(detail)
        assertEquals("main", state.branch)
        assertEquals(1, state.commits.size)
        assertEquals("", state.path)
    }

    @Test fun selectBranchResetsPathAndCommits() {
        var state = RepoBrowser.open(detail)
        state = RepoBrowser.navigateTo(state, "src/main")
        val switched = RepoBrowser.selectBranch(state, "dev")
        assertEquals("dev", switched.branch)
        assertEquals("", switched.path)
        assertTrue(switched.commits.isEmpty())
        // Re-selecting the same branch is a no-op.
        assertEquals(switched, RepoBrowser.selectBranch(switched, "dev"))
    }

    @Test fun treeNavigationBreadcrumbsAndUp() {
        var state = RepoBrowser.open(detail)
        state = RepoBrowser.openEntry(state, TreeEntry("src", "src", isDir = true, size = null))
        state = RepoBrowser.openEntry(state, TreeEntry("main", "src/main", isDir = true, size = null))
        assertEquals("src/main", state.path)
        assertEquals(listOf("src" to "src", "main" to "src/main"), state.breadcrumbs)
        // Blobs do not move the tree path.
        assertEquals(state, RepoBrowser.openEntry(state, TreeEntry("A.kt", "src/main/A.kt", false, 1)))
        assertEquals("src", RepoBrowser.navigateUp(state).path)
        assertEquals("", RepoBrowser.navigateUp(RepoBrowser.navigateUp(state)).path)
        assertEquals("", RepoBrowser.navigateTo(state, "").path)
    }

    @Test fun commitPagingAppendsDedupesAndExhausts() {
        var state = RepoBrowser.open(detail.copy(recentCommits = emptyList()))
        val page1 = listOf(GitCommit("1".repeat(40)), GitCommit("2".repeat(40)))
        state = RepoBrowser.commitsLoaded(state, page1, pageSize = 2)
        assertEquals(false, state.commitsExhausted)
        assertEquals("2".repeat(40), state.nextCommitCursor)
        state = RepoBrowser.commitsAppended(state, listOf(GitCommit("2".repeat(40)), GitCommit("3".repeat(40))), pageSize = 2)
        assertEquals(3, state.commits.size)
        state = RepoBrowser.commitsAppended(state, listOf(GitCommit("4".repeat(40))), pageSize = 2)
        assertTrue(state.commitsExhausted)
        assertNull(state.nextCommitCursor)
    }
}
