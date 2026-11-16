package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.domain.GitParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the git host and F-Droid parsers. */
class GitParsersTest {
    @Test fun testsJsonParsesLatestHistoryAndCoverage() {
        val status = GitParsers.parseTests("""{"latest":{"id":"a","sha":"abc123","subject":"fix","status":"passed","failed":2,"tests":10,"coverage":{"covered":50,"total":100}},"running":null,"history":[{"id":"b","sha":"def456","subject":"old","status":"failed","failed":1,"tests":5}]}""")
        assertEquals("passed", status.latest?.status)
        assertEquals(50.0, status.coveragePct ?: 0.0, 0.01)
        assertEquals(1, status.history.size)
    }

    @Test fun issuesJsonParsesRepos() {
        val issues = GitParsers.parseIssues("""{"repos":[{"github":"vayun-mathur/Modern-Apps","issues":[{"number":7,"title":"Bug","author":"vayun","comments":3,"labels":[{"name":"bug"}],"updated_at":"2026-01-01"}]}]}""")
        assertEquals(1, issues.size)
        assertEquals(listOf("bug"), issues[0].labels)
    }

    @Test fun reposJsonParsesList() {
        val repos = GitParsers.parseRepos(
            """[{"name":"Modern-Apps","defaultBranch":"main","headSha":"aabbccdd11223344","headSubject":"madeveloper: x (#madev)","headTime":"2026-11-16T10:00:00Z"},{"name":"","defaultBranch":"main"}]""",
        )
        assertEquals(1, repos.size)
        assertEquals("main", repos[0].defaultBranch)
        assertEquals("aabbccdd", repos[0].shortHeadSha)
        assertEquals("https://git.maproj.org/Modern-Apps.git", repos[0].cloneUrl)
    }

    @Test fun repoDetailParsesBranchesAndRecentCommits() {
        val detail = GitParsers.parseRepoDetail(
            """{"name":"MAOS","defaultBranch":"main","branches":[{"name":"main","sha":"11111111aaaa","time":"2026-11-15T00:00:00Z"},{"name":"dev","sha":"22222222bbbb","time":1760000000}],"recentCommits":[{"sha":"11111111aaaa0000","author":"vayun","time":"2026-11-15T01:00:00Z","subject":"init","body":"notes"}]}""",
        )
        assertEquals("MAOS", detail.name)
        assertEquals(2, detail.branches.size)
        assertEquals("1760000000", detail.branches[1].time)
        assertEquals(1, detail.recentCommits.size)
        assertEquals("11111111", detail.recentCommits[0].shortSha)
        assertEquals("notes", detail.recentCommits[0].body)
    }

    @Test fun commitsJsonParsesPageWithDefaults() {
        val commits = GitParsers.parseCommits(
            """[{"sha":"abcdef0123456789","shortSha":"abcdef01","author":"a","time":"2026-01-01T00:00:00Z","subject":"s"},{"sha":"1234567890abcdef","author":"b","time":"2026-01-02T00:00:00Z","subject":"t","body":"body"}]""",
        )
        assertEquals(2, commits.size)
        assertEquals("abcdef01", commits[0].shortSha)
        assertEquals("12345678", commits[1].shortSha)
        assertEquals("", commits[0].body)
    }

    @Test fun treeJsonJoinsPathsAndSortsDirsFirst() {
        val entries = GitParsers.parseTree(
            """[{"name":"README.md","type":"blob","size":42},{"name":"src","type":"tree"}]""",
            "docs",
        )
        assertEquals(listOf("src", "README.md"), entries.map { it.name })
        assertEquals("docs/src", entries[0].path)
        assertTrue(entries[0].isDir)
        assertEquals(42L, entries[1].size)
    }

    @Test fun fileJsonParsesTextBinaryAndTruncated() {
        val text = GitParsers.parseFile(
            """{"path":"a.kt","size":3,"isBinary":false,"content":"hi\n","sha":"abc123"}""",
        )
        assertEquals("hi\n", text.content)
        assertEquals(false, text.isBinary)
        val binary = GitParsers.parseFile(
            """{"path":"a.png","size":99,"isBinary":true,"truncated":true,"sha":"def456"}""",
        )
        assertTrue(binary.isBinary)
        assertTrue(binary.truncated)
        assertEquals(null, binary.content)
    }

    @Test fun fdroidIndexPicksNewestVersion() {
        val apps = GitParsers.parseFdroidIndex("""{"packages":{"com.example":{"metadata":{"name":{"en-US":"Example"},"summary":{"en-US":"Demo"}},"versions":{"h1":{"file":{"name":"/a_1.apk","size":100},"manifest":{"versionCode":1,"versionName":"1"}},"h2":{"file":{"name":"/a_2.apk","size":200},"manifest":{"versionCode":2,"versionName":"2"}}}}}}""", "https://static.maproj.org/fdroid/repo")
        assertEquals(1, apps.size)
        assertEquals(2L, apps[0].versionCode)
        assertTrue((apps[0].apkPath ?: "").endsWith("a_2.apk"))
    }
}
