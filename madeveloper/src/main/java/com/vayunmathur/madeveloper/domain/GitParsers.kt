package com.vayunmathur.madeveloper.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsers for the machine-readable forms the git host and F-Droid repo
 * actually expose. The git host has no repository-browsing pages: repositories
 * are the fixed clone set, refs come from the smart-HTTP advertisement, and
 * issues, pull requests and CI all serve JSON next to their HTML pages.
 */
object GitParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Repositories known to the host, from its landing-page clone URLs. */
    val knownRepos: List<GitRepo> = listOf(
        GitRepo("Modern-Apps", "https://git.maproj.org/Modern-Apps.git", "vayun-mathur/Modern-Apps"),
        GitRepo("MAOS", "https://git.maproj.org/MAOS.git", "vayun-mathur/MAOS"),
        GitRepo(
            "location_share_server",
            "https://git.maproj.org/location_share_server.git", "vayun-mathur/location_share_server",
        ),
    )

    /** Parses the pkt-line ref advertisement of `GET /<repo>/info/refs?service=git-upload-pack`. */
    fun parseRefs(body: ByteArray): List<GitRef> {
        val text = body.toString(Charsets.UTF_8)
        val refs = mutableListOf<GitRef>()
        for (line in text.split('\n')) {
            val clean = line.trim()
            if (clean.length < 45) continue
            val payload = clean.drop(4)
            val parts = payload.split(' ', '\t', '\u0000')
            if (parts.size < 2) continue
            val sha = parts[0].trim()
            val ref = parts[1].trim().removeSuffix("^{}")
            if (sha.length in 40..64 && ref.startsWith("refs/")) {
                refs += GitRef(ref.removePrefix("refs/heads/").removePrefix("refs/tags/"), sha)
            }
        }
        return refs.distinctBy { it.name }
    }

    /** Parses `/tests.json` into the latest state plus recent history. */
    fun parseTests(text: String): TestsStatus {
        val root = json.parseToJsonElement(text).jsonObject
        fun run(obj: JsonObject?): TestRun? {
            if (obj == null) return null
            return TestRun(
                id = obj.s("id"), sha = obj.s("sha"), subject = obj.s("subject"),
                status = obj.s("status"), failed = obj.i("failed"), tests = obj.i("tests"),
                durationSecs = obj["duration_secs"]?.jsonPrimitive?.longOrNull,
            )
        }
        val latestObj = root["latest"] as? JsonObject
        val coverage = (latestObj?.get("coverage") as? JsonObject)?.let { cov ->
            val covered = cov["covered"]?.jsonPrimitive?.doubleOrNull
            val total = cov["total"]?.jsonPrimitive?.doubleOrNull
            if (covered != null && total != null && total > 0) covered * 100.0 / total else null
        }
        return TestsStatus(
            latest = run(latestObj), running = run(root["running"] as? JsonObject),
            history = (root["history"] as? JsonArray).orEmpty().mapNotNull { run(it as? JsonObject) },
            coveragePct = coverage,
        )
    }

    /** Parses `/issues.json?view=open`. */
    fun parseIssues(text: String): List<GitIssue> {
        val root = json.parseToJsonElement(text).jsonObject
        val out = mutableListOf<GitIssue>()
        for (repo in (root["repos"] as? JsonArray).orEmpty()) {
            val repoObj = repo as? JsonObject ?: continue
            val github = repoObj.s("github")
            for (issue in (repoObj["issues"] as? JsonArray).orEmpty()) {
                val o = issue as? JsonObject ?: continue
                out += GitIssue(
                    repo = github, number = o.i("number"), title = o.s("title"), author = o.s("author"),
                    comments = o.i("comments"),
                    labels = (o["labels"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.s("name")?.takeIf { n -> n.isNotBlank() } },
                    updatedAt = o.s("updated_at"),
                )
            }
        }
        return out
    }

    /** Parses `/prs.json`; tolerant of both a title/author shape and minimal rows. */
    fun parsePrs(text: String): List<GitPr> {
        val root = json.parseToJsonElement(text).jsonObject
        val out = mutableListOf<GitPr>()
        for (repo in (root["repos"] as? JsonArray).orEmpty()) {
            val repoObj = repo as? JsonObject ?: continue
            val repoName = repoObj.s("repo").ifBlank { repoObj.s("github") }
            for (pr in (repoObj["prs"] as? JsonArray).orEmpty()) {
                val o = pr as? JsonObject ?: continue
                out += GitPr(repoName, o.i("number"), o.s("title").ifBlank { "Pull request" }, o.s("author"))
            }
        }
        return out
    }

    /**
     * Parses the F-Droid `index-v2.json` shape (packages -> metadata + versions)
     * into the newest build per package. Pure over the decoded bytes so the
     * catalog can be unit-tested without a network or an Android runtime.
     */
    fun parseFdroidIndex(text: String, repoBase: String): List<CatalogApp> {
        val root = json.parseToJsonElement(text).jsonObject
        val packages = root["packages"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<CatalogApp>()
        for ((pkg, value) in packages) {
            val entry = value as? JsonObject ?: continue
            val meta = entry["metadata"] as? JsonObject
            val versions = entry["versions"] as? JsonObject ?: continue
            newestCatalogApp(pkg, meta, versions, repoBase)?.let { out += it }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun newestCatalogApp(
        pkg: String, meta: JsonObject?, versions: JsonObject, repoBase: String,
    ): CatalogApp? {
        var best: CatalogApp? = null
        for ((_, versionValue) in versions) {
            val version = versionValue as? JsonObject ?: continue
            val candidate = catalogCandidate(pkg, meta, version, repoBase) ?: continue
            if (best == null || candidate.versionCode > best.versionCode) best = candidate
        }
        return best
    }

    private fun catalogCandidate(
        pkg: String, meta: JsonObject?, version: JsonObject, repoBase: String,
    ): CatalogApp? {
        val manifest = version["manifest"] as? JsonObject ?: return null
        val code = manifest["versionCode"]?.jsonPrimitive?.longOrNull ?: return null
        val file = version["file"] as? JsonObject
        val fileName = file?.get("name")?.jsonPrimitive?.contentOrNull
        return CatalogApp(
            packageName = pkg,
            name = meta?.localized("name") ?: pkg.substringAfterLast('.'),
            summary = meta?.localized("summary") ?: "",
            versionName = manifest["versionName"]?.jsonPrimitive?.contentOrNull,
            versionCode = code,
            sizeBytes = file?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
            apkPath = fileName?.let { repoBase.trimEnd('/') + "/" + it.trimStart('/') },
        )
    }

    private fun JsonObject.localized(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonObject) {
            return value["en-US"]?.jsonPrimitive?.contentOrNull
                ?: value.values.firstOrNull()?.jsonPrimitive?.contentOrNull
        }
        return value.jsonPrimitive.contentOrNull
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    private fun JsonObject.s(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.i(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
}
