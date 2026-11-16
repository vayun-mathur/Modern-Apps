package com.vayunmathur.madeveloper.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.madeveloper.domain.CatalogApp
import com.vayunmathur.madeveloper.domain.GitCommit
import com.vayunmathur.madeveloper.domain.GitFile
import com.vayunmathur.madeveloper.domain.GitIssue
import com.vayunmathur.madeveloper.domain.GitParsers
import com.vayunmathur.madeveloper.domain.GitPr
import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.domain.RepoDetail
import com.vayunmathur.madeveloper.domain.TestsStatus
import com.vayunmathur.madeveloper.domain.TreeEntry
import java.net.URLEncoder

/**
 * Reads the maproj git host through [NetworkClient] (FIRST_PARTY trust is
 * set once by the activity). Repositories, branches, commits, trees and
 * files all come from the host's first-party JSON API under `/api/repos`;
 * CI, issues, pull requests and the F-Droid catalog keep their existing
 * endpoints. The JSON API may not be deployed yet — callers surface the
 * HTTP error (e.g. 404) rather than falling back to any mirror.
 */
class GitHostRepository {
    private var treeCache: Map<String, List<TreeEntry>> = emptyMap()
    private var fileCache: Map<String, GitFile> = emptyMap()

    /** Repositories from `GET /api/repos`. */
    suspend fun repos(): List<GitRepo> = GitParsers.parseRepos(fetchText("$GIT_HOST/api/repos"))

    /** Branches and recent commits from `GET /api/repos/<repo>`. */
    suspend fun repo(name: String): RepoDetail =
        GitParsers.parseRepoDetail(fetchText("$GIT_HOST/api/repos/${enc(name)}"))

    /** One page of commits; [after] is the previous page's last sha. */
    suspend fun commits(name: String, branch: String, limit: Int, after: String?): List<GitCommit> {
        val query = buildString {
            append("?branch=").append(enc(branch)).append("&limit=").append(limit)
            if (!after.isNullOrBlank()) append("&after=").append(enc(after))
        }
        return GitParsers.parseCommits(fetchText("$GIT_HOST/api/repos/${enc(name)}/commits$query"))
    }

    /** CI status from `/tests.json`. */
    suspend fun tests(): TestsStatus = fetchText("$GIT_HOST/tests.json").let(GitParsers::parseTests)

    /** Open issues from `/issues.json?view=open`. */
    suspend fun issues(): List<GitIssue> = GitParsers.parseIssues(fetchText("$GIT_HOST/issues.json?view=open"))

    /** Open pull requests from `/prs.json`. */
    suspend fun prs(): List<GitPr> = GitParsers.parsePrs(fetchText("$GIT_HOST/prs.json"))

    /** Available builds from the first-party F-Droid repo (read-only catalog). */
    suspend fun catalog(): List<CatalogApp> =
        GitParsers.parseFdroidIndex(fetchText("$FDROID_BASE/index-v2.json"), FDROID_BASE)

    /** Tree of [path] in [repo] on [branch]; cached per repo+branch+path. */
    suspend fun tree(repo: String, branch: String, path: String): List<TreeEntry> {
        val key = "$repo:$branch:$path"
        treeCache[key]?.let { return it }
        val url = "$GIT_HOST/api/repos/${enc(repo)}/tree?branch=${enc(branch)}&path=${enc(path)}"
        val entries = GitParsers.parseTree(fetchText(url), path)
        treeCache = treeCache + (key to entries)
        return entries
    }

    /** File at [path] in [repo] on [branch]; cached per repo+branch+path. */
    suspend fun file(repo: String, branch: String, path: String): GitFile {
        val key = "$repo:$branch:$path"
        fileCache[key]?.let { return it }
        val url = "$GIT_HOST/api/repos/${enc(repo)}/file?branch=${enc(branch)}&path=${enc(path)}"
        val file = GitParsers.parseFile(fetchText(url))
        fileCache = fileCache + (key to file)
        return file
    }

    private suspend fun fetchText(url: String): String {
        val headers = mapOf("Accept" to "application/json", "User-Agent" to "MADeveloper/1.0")
        val response = NetworkClient.performRequest(url, headers = headers)
        if (!response.isSuccess) throw java.io.IOException("HTTP " + response.status + " for " + url)
        return response.body
    }

    /** Hosts and endpoints this app talks to. */
    companion object {
        const val GIT_HOST = "https://git.maproj.org"
        const val FDROID_BASE = "https://static.maproj.org/fdroid/repo"
        const val DEV_WS_URL = "wss://api.maproj.org/dev/ws"

        /** Default commits page size for the commits screen. */
        const val COMMITS_PAGE_SIZE = 30
    }
}

/** URL-encodes one path segment or query value. */
private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
