package com.vayunmathur.madeveloper.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.madeveloper.domain.CatalogApp
import com.vayunmathur.madeveloper.domain.GitIssue
import com.vayunmathur.madeveloper.domain.GitParsers
import com.vayunmathur.madeveloper.domain.GitPr
import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.domain.TestsStatus
import com.vayunmathur.madeveloper.domain.TreeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads the maproj git host through [NetworkClient] (FIRST_PARTY trust is set
 * once by the activity). The host serves clone + dashboards only, so code
 * browsing reads the host's own GitHub push-mirror contents API; every screen
 * still links out to the canonical git host page as a fallback.
 */
class GitHostRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var treeCache: Map<String, List<TreeEntry>> = emptyMap()
    private var fileCache: Map<String, String> = emptyMap()

    /** Repositories with their advertised refs filled in. */
    suspend fun repos(): List<GitRepo> = GitParsers.knownRepos.map { repo ->
        val refs = runCatching {
            val refsUrl = repo.cloneUrl.removeSuffix(".git") + ".git/info/refs?service=git-upload-pack"
            val (status, bytes) = NetworkClient.performRequestBytes(refsUrl)
            if (status in 200..299) GitParsers.parseRefs(bytes) else emptyList()
        }.getOrDefault(emptyList())
        repo.copy(refs = refs)
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

    /** Tree of [path] in [repo] via the mirror contents API; cached per repo+path. */
    suspend fun tree(repo: GitRepo, path: String): List<TreeEntry> {
        val key = repo.name + ":" + path
        treeCache[key]?.let { return it }
        val github = repo.github ?: return emptyList()
        val url = "https://api.github.com/repos/$github/contents/$path"
        val entries = parseTree(fetchText(url))
        treeCache = treeCache + (key to entries)
        return entries
    }

    /** File text of [path] in [repo] via the mirror contents API; cached. */
    suspend fun file(repo: GitRepo, path: String): String {
        val key = repo.name + ":" + path
        fileCache[key]?.let { return it }
        val github = repo.github ?: return ""
        val root = json.parseToJsonElement(fetchText("https://api.github.com/repos/$github/contents/$path")).jsonObject
        val encoded = root["content"]?.jsonPrimitive?.contentOrNull ?: return ""
        val text = withContext(Dispatchers.IO) { String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8) }
        fileCache = fileCache + (key to text)
        return text
    }

    private fun parseTree(text: String): List<TreeEntry> {
        val root = json.parseToJsonElement(text)
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            TreeEntry(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
                isDir = obj["type"]?.jsonPrimitive?.contentOrNull == "dir",
                size = obj["size"]?.jsonPrimitive?.longOrNull,
            )
        }.sortedWith(compareByDescending<TreeEntry> { it.isDir }.thenBy { it.name.lowercase() })
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
    }
}
