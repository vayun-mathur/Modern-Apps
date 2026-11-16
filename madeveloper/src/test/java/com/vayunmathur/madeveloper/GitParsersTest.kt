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

    @Test fun refsParseAdvertisement() {
        val body = "0000" + "003d" + "a".repeat(40) + " refs/heads/main\u0000multi_ack\n" + "003d" + "b".repeat(40) + " refs/tags/v1\n"
        val refs = GitParsers.parseRefs(body.toByteArray())
        assertTrue(refs.any { it.name == "main" })
    }

    @Test fun fdroidIndexPicksNewestVersion() {
        val apps = GitParsers.parseFdroidIndex("""{"packages":{"com.example":{"metadata":{"name":{"en-US":"Example"},"summary":{"en-US":"Demo"}},"versions":{"h1":{"file":{"name":"/a_1.apk","size":100},"manifest":{"versionCode":1,"versionName":"1"}},"h2":{"file":{"name":"/a_2.apk","size":200},"manifest":{"versionCode":2,"versionName":"2"}}}}}}""", "https://static.maproj.org/fdroid/repo")
        assertEquals(1, apps.size)
        assertEquals(2L, apps[0].versionCode)
        assertTrue((apps[0].apkPath ?: "").endsWith("a_2.apk"))
    }
}
