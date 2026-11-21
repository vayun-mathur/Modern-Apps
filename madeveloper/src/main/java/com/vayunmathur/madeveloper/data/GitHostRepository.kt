package com.vayunmathur.madeveloper.data

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** A parsed payload plus provenance: [stale] means it came from the last-good disk cache. */
data class LastGoodResult<T>(val value: T, val stale: Boolean, val fetchedAtMillis: Long?)

/**
 * Reads the maproj git host through [NetworkClient] (FIRST_PARTY trust is
 * set once by the activity). Repositories, branches, commits, trees and
 * files all come from the host's first-party JSON API under `/api/repos`;
 * CI, issues, pull requests and the F-Droid catalog keep their existing
 * endpoints. The JSON API may not be deployed yet — callers surface the
 * HTTP error (e.g. 404) rather than falling back to any mirror.
 */
class GitHostRepository(
    // The one network seam: how a URL becomes its body text. Production goes
    // through NetworkClient (below); tests substitute canned host replies so
    // the parsing, caching and error behaviour above it run off-device.
    private val fetcher: suspend (String) -> String = { url -> fetchViaNetwork(url) },
    // Disk last-good store for the outage path; null in tests that do not
    // exercise it. The in-memory tree/file caches below stay as-is.
    private val lastGood: LastGoodStore? = null,
) {
    private var treeCache: Map<String, List<TreeEntry>> = emptyMap()
    private var fileCache: Map<String, GitFile> = emptyMap()

    /**
     * Fetch [url] live, persisting the raw JSON under [key]; on any failure
     * serve the persisted last-good instead. Rethrows when no last-good
     * exists, so first-run outages still surface as errors.
     */
    private suspend fun <T> fetchLastGood(key: String, url: String, parse: (String) -> T): LastGoodResult<T> {
        return try {
            val text = fetchText(url)
            val now = System.currentTimeMillis()
            lastGood?.write(key, text, now)
            LastGoodResult(withContext(Dispatchers.IO) { parse(text) }, stale = false, fetchedAtMillis = now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = lastGood?.read(key) ?: throw e
            LastGoodResult(withContext(Dispatchers.IO) { parse(cached.text) }, stale = true, fetchedAtMillis = cached.fetchedAtMillis)
        }
    }

    /** Repositories from `GET /api/repos`. */
    suspend fun repos(): List<GitRepo> = reposResult().value

    /** Repositories with last-good provenance, for the stale indicator. */
    suspend fun reposResult(): LastGoodResult<List<GitRepo>> =
        fetchLastGood("repos", "$GIT_HOST/api/repos", GitParsers::parseRepos)

    /** Branches and recent commits from `GET /api/repos/<repo>`. */
    suspend fun repo(name: String): RepoDetail = withContext(Dispatchers.IO) {
        GitParsers.parseRepoDetail(fetchText("$GIT_HOST/api/repos/${enc(name)}"))
    }

    /** One page of commits; [after] is the previous page's last sha. */
    suspend fun commits(name: String, branch: String, limit: Int, after: String?): List<GitCommit> {
        val query = buildString {
            append("?branch=").append(enc(branch)).append("&limit=").append(limit)
            if (!after.isNullOrBlank()) append("&after=").append(enc(after))
        }
        return withContext(Dispatchers.IO) { GitParsers.parseCommits(fetchText("$GIT_HOST/api/repos/${enc(name)}/commits$query")) }
    }

    /** CI status from `/tests.json`. */
    suspend fun tests(): TestsStatus = testsResult().value

    /** CI status with last-good provenance, for the stale indicator. */
    suspend fun testsResult(): LastGoodResult<TestsStatus> =
        fetchLastGood("tests", "$GIT_HOST/tests.json", GitParsers::parseTests)

    /** Open issues from `/issues.json?view=open`. */
    suspend fun issues(): List<GitIssue> = issuesResult().value

    /** Open issues with last-good provenance, for the stale indicator. */
    suspend fun issuesResult(): LastGoodResult<List<GitIssue>> =
        fetchLastGood("issues", "$GIT_HOST/issues.json?view=open", GitParsers::parseIssues)

    /** Open pull requests from `/prs.json`. */
    suspend fun prs(): List<GitPr> = prsResult().value

    /** Open pull requests with last-good provenance, for the stale indicator. */
    suspend fun prsResult(): LastGoodResult<List<GitPr>> =
        fetchLastGood("prs", "$GIT_HOST/prs.json", GitParsers::parsePrs)

    /** Available builds from the first-party F-Droid repo (read-only catalog). */
    suspend fun catalog(): List<CatalogApp> = catalogResult().value

    /** Catalog with last-good provenance, for the stale indicator. */
    suspend fun catalogResult(): LastGoodResult<List<CatalogApp>> = fetchLastGood(
        "catalog",
        "$FDROID_BASE/index-v2.json",
    ) { text -> GitParsers.parseFdroidIndex(text, FDROID_BASE) }

    /** Tree of [path] in [repo] on [branch]; cached per repo+branch+path. */
    suspend fun tree(repo: String, branch: String, path: String): List<TreeEntry> {
        val key = "$repo:$branch:$path"
        treeCache[key]?.let { return it }
        val url = "$GIT_HOST/api/repos/${enc(repo)}/tree?branch=${enc(branch)}&path=${enc(path)}"
        val entries = withContext(Dispatchers.IO) { GitParsers.parseTree(fetchText(url), path) }
        treeCache = treeCache + (key to entries)
        return entries
    }

    /** File at [path] in [repo] on [branch]; cached per repo+branch+path. */
    suspend fun file(repo: String, branch: String, path: String): GitFile {
        val key = "$repo:$branch:$path"
        fileCache[key]?.let { return it }
        val url = "$GIT_HOST/api/repos/${enc(repo)}/file?branch=${enc(branch)}&path=${enc(path)}"
        val file = withContext(Dispatchers.IO) { GitParsers.parseFile(fetchText(url)) }
        fileCache = fileCache + (key to file)
        return file
    }

    private suspend fun fetchText(url: String): String = fetcher(url)

    /** Hosts and endpoints this app talks to. */
    companion object {
        const val GIT_HOST = "https://git.maproj.org"
        const val FDROID_BASE = "https://static.maproj.org/fdroid/repo"
        const val DEV_WS_URL = "wss://api.maproj.org/dev/ws"
        const val DEV_REGISTER_URL = "https://api.maproj.org/dev/register"

        /** Default commits page size for the commits screen. */
        const val COMMITS_PAGE_SIZE = 30
    }
}

/** Production [GitHostRepository] fetch: NetworkClient with this app's identity headers. */
private suspend fun fetchViaNetwork(url: String): String {
    val headers = mapOf("Accept" to "application/json", "User-Agent" to "MADeveloper/1.0")
    val response = NetworkClient.performRequest(url, headers = headers)
    if (!response.isSuccess) throw java.io.IOException("HTTP " + response.status + " for " + url)
    return response.body
}

/** URL-encodes one path segment or query value. */
private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
