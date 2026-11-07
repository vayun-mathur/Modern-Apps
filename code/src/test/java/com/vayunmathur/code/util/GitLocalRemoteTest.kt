package com.vayunmathur.code.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for [GitRepo] clone/pull against local (file) remotes — no network. */
class GitLocalRemoteTest {

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "$tag-${System.nanoTime()}").also { it.mkdirs() }

    private fun seedRepo(): File {
        val src = tempDir("gitsrc")
        GitRepo.init(src)
        File(src, "a.txt").writeText("v1")
        GitRepo.stage(src, "a.txt")
        GitRepo.commit(src, "c1", "T", "t@e.c")
        return src
    }

    @Test
    fun cloneFromLocalPathCopiesHistory() {
        val src = seedRepo()
        val dest = File(System.getProperty("java.io.tmpdir"), "gitdest-${System.nanoTime()}")
        GitRepo.clone(src.absolutePath, dest, "", "")
        assertTrue(GitRepo.isRepo(dest))
        assertEquals("v1", File(dest, "a.txt").readText())
        assertEquals(1, GitRepo.log(dest).size)
        src.deleteRecursively(); dest.deleteRecursively()
    }

    @Test
    fun pullFastForwardsFromLocalRemote() {
        val src = seedRepo()
        val dest = File(System.getProperty("java.io.tmpdir"), "gitdest2-${System.nanoTime()}")
        GitRepo.clone(src.absolutePath, dest, "", "")
        // Advance the source, then pull with (ignored, file-transport) credentials.
        File(src, "a.txt").writeText("v2")
        GitRepo.stage(src, "a.txt")
        GitRepo.commit(src, "c2", "T", "t@e.c")
        GitRepo.pull(dest, "user", "token")
        assertEquals("v2", File(dest, "a.txt").readText())
        assertEquals(2, GitRepo.log(dest).size)
        src.deleteRecursively(); dest.deleteRecursively()
    }

    @Test
    fun currentBranchAndDiffAfterChange() {
        val dir = seedRepo()
        assertTrue(GitRepo.currentBranch(dir).isNotBlank())
        File(dir, "a.txt").writeText("changed")
        val unstaged = GitRepo.diff(dir, "a.txt", staged = false)
        assertTrue(unstaged.contains("changed") || unstaged.contains("v1"), "diff: $unstaged")
        val rows = GitRepo.structuredDiff(dir, "a.txt", staged = false)
        assertTrue(rows.isNotEmpty())
        dir.deleteRecursively()
    }
}

class GitPushTest {
    @Test
    fun pushToBareRemotePublishesCommit() {
        val bareDir = File(System.getProperty("java.io.tmpdir"), "gitbare-${System.nanoTime()}")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bareDir).call().close()
        // Seed the bare repo via a scratch clone.
        val scratch = File(System.getProperty("java.io.tmpdir"), "gitscratch-${System.nanoTime()}")
        GitRepo.clone(bareDir.absolutePath, scratch, "", "")
        File(scratch, "seed.txt").writeText("seed")
        GitRepo.stage(scratch, "seed.txt")
        GitRepo.commit(scratch, "seed", "T", "t@e.c")
        GitRepo.push(scratch, "", "")

        val work = File(System.getProperty("java.io.tmpdir"), "gitwork-${System.nanoTime()}")
        GitRepo.clone(bareDir.absolutePath, work, "", "")
        File(work, "work.txt").writeText("work")
        GitRepo.stage(work, "work.txt")
        GitRepo.commit(work, "work commit", "T", "t@e.c")
        GitRepo.push(work, "user", "token")
        assertEquals(2, GitRepo.log(work).size)

        val verify = File(System.getProperty("java.io.tmpdir"), "gitverify-${System.nanoTime()}")
        GitRepo.clone(bareDir.absolutePath, verify, "", "")
        assertTrue(File(verify, "work.txt").exists())
        bareDir.deleteRecursively(); scratch.deleteRecursively(); work.deleteRecursively(); verify.deleteRecursively()
    }
}
