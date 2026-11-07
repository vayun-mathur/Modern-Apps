package com.vayunmathur.code.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for [GitRepo] against temp-dir repositories (JGit, no network). */
class GitRepoTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "gitrepo-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun isRepoFalseBeforeInit() {
        val dir = tempDir()
        assertFalse(GitRepo.isRepo(dir))
        dir.deleteRecursively()
    }

    @Test
    fun initMakesRepo() {
        val dir = tempDir()
        GitRepo.init(dir)
        assertTrue(GitRepo.isRepo(dir))
        dir.deleteRecursively()
    }

    @Test
    fun commitAndLogRoundTrip() {
        val dir = tempDir()
        GitRepo.init(dir)
        File(dir, "a.txt").writeText("hello")
        GitRepo.stage(dir, "a.txt")
        val id = GitRepo.commit(dir, "first", "Tester", "t@example.com")
        assertEquals(GitRepo.SHORT_ID_LENGTH, id.length)
        val log = GitRepo.log(dir)
        assertEquals(1, log.size)
        assertEquals("first", log[0].message)
        assertEquals("Tester", log[0].author)
        assertTrue(log[0].timeSeconds > 0)
        assertEquals(id, log[0].shortId)
        dir.deleteRecursively()
    }

    @Test
    fun logOfEmptyRepoIsEmpty() {
        val dir = tempDir()
        GitRepo.init(dir)
        assertTrue(GitRepo.log(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun statusTracksStagedUnstagedUntracked() {
        val dir = tempDir()
        GitRepo.init(dir)
        File(dir, "a.txt").writeText("v1")
        GitRepo.stage(dir, "a.txt")
        GitRepo.commit(dir, "c1", "T", "t@e.c")
        // Clean after commit.
        assertTrue(GitRepo.status(dir).isClean)
        // Modify -> unstaged.
        File(dir, "a.txt").writeText("v2")
        // New file -> untracked.
        File(dir, "b.txt").writeText("new")
        val s = GitRepo.status(dir)
        assertTrue("a.txt" in s.unstaged)
        assertTrue("b.txt" in s.untracked)
        assertFalse(s.isClean)
        // Stage the modification.
        GitRepo.stage(dir, "a.txt")
        val s2 = GitRepo.status(dir)
        assertTrue("a.txt" in s2.staged)
        // Unstage it again.
        GitRepo.unstage(dir, "a.txt")
        val s3 = GitRepo.status(dir)
        assertTrue("a.txt" in s3.unstaged)
        dir.deleteRecursively()
    }

    @Test
    fun branchIsNonBlankAfterInit() {
        val dir = tempDir()
        GitRepo.init(dir)
        File(dir, "a.txt").writeText("x")
        GitRepo.stage(dir, "a.txt")
        GitRepo.commit(dir, "c", "T", "t@e.c")
        assertTrue(GitRepo.currentBranch(dir).isNotBlank())
        dir.deleteRecursively()
    }

    @Test
    fun createAndCheckoutBranch() {
        val dir = tempDir()
        GitRepo.init(dir)
        File(dir, "a.txt").writeText("x")
        GitRepo.stage(dir, "a.txt")
        GitRepo.commit(dir, "c", "T", "t@e.c")
        GitRepo.createBranch(dir, "feature")
        assertTrue("feature" in GitRepo.branches(dir))
        GitRepo.checkout(dir, "feature")
        assertEquals("feature", GitRepo.currentBranch(dir))
        dir.deleteRecursively()
    }

    @Test
    fun diffShowsWorktreeChanges() {
        val dir = tempDir()
        GitRepo.init(dir)
        File(dir, "a.txt").writeText("line1\n")
        GitRepo.stage(dir, "a.txt")
        GitRepo.commit(dir, "c", "T", "t@e.c")
        File(dir, "a.txt").writeText("line1\nline2\n")
        val unstaged = GitRepo.diff(dir, "a.txt", staged = false)
        assertTrue("line2" in unstaged)
        assertTrue(GitRepo.diff(dir, "a.txt", staged = true).isEmpty())
        GitRepo.stage(dir, "a.txt")
        assertTrue("line2" in GitRepo.diff(dir, "a.txt", staged = true))
        // Structured rows parse through the same parser.
        val rows = GitRepo.structuredDiff(dir, "a.txt", staged = true)
        assertTrue(rows.any { it.type == DiffRowType.ADD && it.text == "line2" })
        dir.deleteRecursively()
    }

    @Test
    fun stageDeletedFileStagesRemoval() {
        val dir = tempDir()
        GitRepo.init(dir)
        val f = File(dir, "gone.txt").also { it.writeText("x") }
        GitRepo.stage(dir, "gone.txt")
        GitRepo.commit(dir, "c", "T", "t@e.c")
        assertTrue(f.delete())
        GitRepo.stage(dir, "gone.txt")
        assertTrue("gone.txt" in GitRepo.status(dir).staged)
        dir.deleteRecursively()
    }

    @Test
    fun shortIdLengthConstant() {
        assertEquals(7, GitRepo.SHORT_ID_LENGTH)
    }
}
