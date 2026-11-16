package com.vayunmathur.madeveloper.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsers for the git host's first-party JSON API (`/api/repos…`),
 * its dashboard JSON (`/tests.json`, `/issues.json`, `/prs.json`) and the
 * F-Droid repo index. Times may arrive as ISO-8601 strings or epoch
 * seconds; both are normalised to a display string by [t].
 */
object GitParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses `GET /api/repos` into the hosted repository list. */
    fun parseRepos(text: String): List<GitRepo> {
        val root = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.s("name")
            if (name.isBlank()) null else GitRepo(
                name = name,
                defaultBranch = obj.s("defaultBranch"),
                headSha = obj.s("headSha"),
                headSubject = obj.s("headSubject"),
                headTime = obj.t("headTime"),
            )
        }
    }

    /** Parses `GET /api/repos/<repo>` into branches plus recent commits. */
    fun parseRepoDetail(text: String): RepoDetail {
        val root = json.parseToJsonElement(text).jsonObject
        return RepoDetail(
            name = root.s("name"),
            defaultBranch = root.s("defaultBranch"),
            branches = (root["branches"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.s("name")
                if (name.isBlank()) null else GitBranch(name, obj.s("sha"), obj.t("time"))
            },
            recentCommits = parseCommitArray(root["recentCommits"] as? JsonArray),
        )
    }

    /** Parses `GET /api/repos/<repo>/commits` into one page of commits. */
    fun parseCommits(text: String): List<GitCommit> {
        val root = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return parseCommitArray(root)
    }

    /** Parses one commit object; shared by detail and commits responses. */
    fun parseCommit(obj: JsonObject): GitCommit {
        val sha = obj.s("sha")
        return GitCommit(
            sha = sha,
            shortSha = obj.s("shortSha").ifBlank { sha.take(8) },
            author = obj.s("author"),
            time = obj.t("time"),
            subject = obj.s("subject"),
            body = obj.s("body"),
        )
    }

    /**
     * Parses `GET /api/repos/<repo>/tree` for [parentPath] (blank = root).
     * Entries carry only a name, so the full path is joined here; trees
     * sort before blobs, each group by name.
     */
    fun parseTree(text: String, parentPath: String): List<TreeEntry> {
        val root = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        val parent = parentPath.trim('/')
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.s("name")
            if (name.isBlank()) null else TreeEntry(
                name = name,
                path = if (parent.isEmpty()) name else "$parent/$name",
                isDir = obj.s("type") == "tree",
                size = obj["size"]?.jsonPrimitive?.longOrNull,
            )
        }.sortedWith(compareByDescending<TreeEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Parses `GET /api/repos/<repo>/file`. */
    fun parseFile(text: String): GitFile {
        val root = json.parseToJsonElement(text).jsonObject
        return GitFile(
            path = root.s("path"),
            size = root["size"]?.jsonPrimitive?.longOrNull ?: 0,
            isBinary = root.b("isBinary"),
            content = root["content"]?.jsonPrimitive?.contentOrNull,
            truncated = root.b("truncated"),
            sha = root.s("sha"),
        )
    }

    private fun parseCommitArray(array: JsonArray?): List<GitCommit> =
        array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseCommit) }


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
    private fun JsonObject.b(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    /** Time fields arrive as ISO strings or epoch seconds; normalise both. */
    private fun JsonObject.t(key: String): String {
        val prim = this[key]?.jsonPrimitive ?: return ""
        prim.contentOrNull?.takeIf { it.isNotBlank() && prim.isString }?.let { return it }
        val epoch = prim.longOrNull ?: return prim.contentOrNull ?: ""
        return epoch.toString()
    }
}
