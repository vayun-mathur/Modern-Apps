// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.protobuf) apply false
    // Dependency-update report: ./gradlew dependencyUpdates lists what is outdated
    // across the version catalog. Report-only; it never changes a version by itself.
    alias(libs.plugins.versions)
}

// Repairs corrupted translations contributed via Weblate where the <xliff:g> markup
// was escaped (e.g. "&lt;xliff:g ...&gt;%1$s&lt;/xliff:g&gt;"). When escaped, Android
// renders the markup literally in the UI instead of only the placeholder. This task
// unwraps such occurrences back to their inner placeholder (e.g. "%1$s"). See issue #477.
tasks.register("fixStrings") {
    group = "translation"
    description = "Fixes translated strings where <xliff:g> markup was escaped and shows up literally in the UI (#477)."
    val projectRoot = rootDir
    doLast {
        val escapedXliff = Regex(
            "&lt;\\s*xliff:g\\b.*?&gt;(.*?)&lt;\\s*/\\s*xliff:g\\s*&gt;",
            RegexOption.DOT_MATCHES_ALL,
        )
        var filesChanged = 0
        var replacements = 0
        projectRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "strings.xml" }
            .forEach { file ->
                val original = file.readText()
                var count = 0
                val fixed = escapedXliff.replace(original) { match ->
                    count++
                    match.groupValues[1]
                }
                if (count > 0) {
                    file.writeText(fixed)
                    filesChanged++
                    replacements += count
                    println("fixStrings: fixed $count occurrence(s) in ${file.relativeTo(projectRoot)}")
                }
            }
        println("fixStrings: done. Fixed $replacements occurrence(s) across $filesChanged file(s).")
    }
}

// Store listings are generated from metadata_data/<module-key>.md by the release build,
// which takes line 1
// as the short description and the whole file as the full description. Play and F-Droid cap a
// summary at 80 characters, and nothing else in the build looks at these files, so the format
// is enforced here. Required shape, exactly:
//
//   1  a summary, at most 80 characters
//   2  blank
//   3  Features:
//   4+ one or more "- " bullets
//      blank
//      a connectivity line: "100% offline", "Requires internet",
//      "Internet required only for initial asset downloads", or
//      "Internet only used for: <feature(s)>" - the last only when the app still mostly
//      works without a connection.
tasks.register("checkMetadata") {
    group = "verification"
    description = "Checks every app module has a metadata_data/*.md store listing in the required format."
    val projectRoot = rootDir
    doLast {
        val fixedConnectivity = listOf(
            "100% offline",
            "Requires internet",
            "Internet required only for initial asset downloads",
        )
        val connectivityPrefix = "Internet only used for: "

        // Declared inside the task action rather than as a script-level function so the
        // action does not capture a reference to the build script, which the configuration
        // cache cannot serialize.
        val problemsIn = { lines: List<String> ->
            // summary, blank, "Features:", one bullet, blank, connectivity
            if (lines.size < 6) {
                listOf("has only ${lines.size} line(s); the format needs at least 6")
            } else {
                val problems = mutableListOf<String>()
                val summary = lines[0]
                if (summary.isBlank()) {
                    problems += "line 1 (the short description) is empty"
                } else if (summary.length > 80) {
                    problems += "line 1 is ${summary.length} characters; the summary caps at 80"
                }
                if (lines[1].isNotEmpty()) {
                    problems += "line 2 must be blank, but is \"${lines[1]}\""
                }
                if (lines[2] != "Features:") {
                    problems += "line 3 must be \"Features:\", but is \"${lines[2]}\""
                }

                val connectivity = lines.last()
                if (connectivity in fixedConnectivity) {
                    // fine
                } else if (connectivity.startsWith(connectivityPrefix)) {
                    if (connectivity.removePrefix(connectivityPrefix).isBlank()) {
                        problems += "\"$connectivityPrefix\" must name the feature(s) using the internet"
                    }
                } else {
                    problems += "the last line must be one of " +
                        fixedConnectivity.joinToString(", ") { "\"$it\"" } +
                        " or \"$connectivityPrefix<feature(s)>\", but is \"$connectivity\""
                }
                if (lines[lines.size - 2].isNotEmpty()) {
                    problems += "the last line must be preceded by a blank line"
                }

                val bullets = lines.subList(3, lines.size - 2)
                if (bullets.isEmpty()) problems += "there must be at least one feature bullet"
                bullets.forEachIndexed { index, line ->
                    if (!line.startsWith("- ")) {
                        problems += "line ${index + 4} must be a \"- \" bullet, but is \"$line\""
                    } else if (line.removePrefix("- ").isBlank()) {
                        problems += "line ${index + 4} is an empty feature bullet"
                    }
                }
                problems
            }
        }

        val metadataDir = File(projectRoot, "metadata_data")

        // Module detection by grepping for the bare string
        // "common-conventions-app", which also matches library/map, where it appears only in a
        // comment. Matching the plugin application instead keeps this list to real apps.
        // analysis/ and personal/ hold gitignored experiments with copied app build files;
        // they are never published, so skip them (same exclusions as release-build.sh).
        val appPlugin = Regex("""id\("common-conventions-app"\)""")
        val moduleKeys = projectRoot.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".git" &&
                    it.name != "analysis" && it.name != "personal"
            }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .filter { appPlugin.containsMatchIn(it.readText()) }
            .map { it.parentFile.relativeTo(projectRoot).invariantSeparatorsPath.replace('/', '-') }
            .toSortedSet()

        val problems = mutableListOf<String>()
        for (key in moduleKeys) {
            val file = File(metadataDir, "$key.md")
            if (!file.isFile) {
                problems += "$key.md: missing; every app module needs a store listing"
                continue
            }
            problemsIn(file.readLines()).forEach { problems += "$key.md: $it" }
        }

        val listings = metadataDir.listFiles { f: File -> f.isFile && f.extension == "md" }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
        (listings - moduleKeys).sorted().forEach {
            problems += "$it.md: does not match any app module, so it is never published"
        }

        problems.forEach { println("checkMetadata: $it") }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkMetadata: ${problems.size} problem(s) across ${moduleKeys.size} app module(s).",
            )
        }
        println("checkMetadata: ${moduleKeys.size} store listing(s) are correctly formatted.")
    }
}

// Guards issue #884: no library/SDK module may depend on another library
// module that declares Android permissions, or those permissions silently
// merge into every consuming app (pdf gained network access this way). A
// second list pins the remaining permission-free lib→lib edges, so a new
// edge (permission-free today, permission-declaring tomorrow) fails until
// it is reviewed and documented here.
// Runs standalone (`./gradlew verifyLibraryBoundaries`) and from ./check.
// Configuration-cache compatible: the action captures only string literals
// (project root path + both lists) and does all file IO at execution time.
tasks.register("verifyLibraryBoundaries") {
    group = "verification"
    description = "Fails if a library/SDK module depends on another library module " +
        "that declares Android permissions (directly or transitively), or on a " +
        "library module outside the documented permission-free edge list."
    val projectRoot = rootDir
    // Permission-declaring modules: nothing in library/* or sdk/* may depend on
    // these (directly or transitively). Declared here rather than inferred so a
    // module that newly gains a permission fails loudly instead of silently
    // widening the blast radius. No exceptions: map and downloadservice declare
    // INTERNET themselves exactly so they need no edge into network.
    val permissionDeclaringModules = setOf(
        ":library:network",
        ":library:map",
        ":library:downloadservice",
    )
    // The only permitted lib→lib edges (all permission-free; each is justified
    // in its module's AGENTS.md Deps line):
    // - many modules → :library (shared nav/DTO/Room base; declares nothing)
    // - :library:room → :library (DbBackupCodec SPI)
    // - :library:biometric → :library (DatabaseHelper keystore)
    // - :library:work → :library (reminder notification resources)
    // - :library:widgets → :library (Log facade only)
    // - :library:downloadservice → :library (DataStoreUtils)
    // - :library:ui → :library (shell + nav base)
    // - :library:avatar → :library:ui, :library:carhost → :library:ui,
    //   :library:tv → :library:ui (Compose shell; ui is permission-free)
    // - :library:tv → :library (base DTOs alongside the ui shell)
    // - :library:ocr → :library:ml (on-device OCR runs on the ml runtime)
    // - :sdk:controller → :library:e2ee-p2p, :cast:protocol → :library:e2ee-p2p
    //   (Hkdf key schedule + E2EE handshake; e2ee-p2p declares nothing)
    // - :library → :sdk:games (achievement contract in the shared base)
    // - :auto:protocol → :library (shared DTOs)
    // - :library:everysync-core → :library:e2ee-p2p (E2EE sync transport)
    // - :sdk:openassistant → (none)
    val allowedLibraryEdges = setOf(
        ":library" to ":sdk:games",
        ":auto:protocol" to ":library",
        ":library:everysync-core" to ":library:e2ee-p2p",
        ":library:avatar" to ":library:ui",
        ":library:biometric" to ":library",
        ":library:carhost" to ":library:ui",
        ":library:downloadservice" to ":library",
        ":library:ocr" to ":library:ml",
        ":library:room" to ":library",
        ":library:tv" to ":library",
        ":library:tv" to ":library:ui",
        ":library:ui" to ":library",
        ":library:widgets" to ":library",
        ":library:work" to ":library",
        ":sdk:controller" to ":library:e2ee-p2p",
        ":cast:protocol" to ":library:e2ee-p2p",
    )
    doLast {
        val projectRef = Regex("""project\(["'](:[A-Za-z0-9:_\-]+)["']\)""")
        val permissionRef = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
        val manifestOf = { moduleDir: File ->
            File(moduleDir, "src/main/AndroidManifest.xml").takeIf { it.isFile }
        }
        val permissionsOf = { moduleDir: File ->
            val manifest = manifestOf(moduleDir)
            if (manifest == null) {
                emptySet()
            } else {
                permissionRef.findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
            }
        }
        // Library modules = anything applying the library/KMP convention plugin
        // (apps apply common-conventions-app and may depend on libraries freely).
        // aosp-stubs is compile-only; log is added to every library by the
        // convention plugin and declares nothing.
        val skipDirs = setOf(".git", ".gradle", ".kotlin", "build", "build-logic", "analysis",
            "personal", "target", "third_party", "node_modules", "scripts", "firmware")
        val libraryPlugin = Regex("""id\("common-conventions-(library|kmp)"\)""")
        val libraryDirs = projectRoot.walkTopDown()
            .maxDepth(3)
            .onEnter { it == projectRoot || it.name !in skipDirs }
            .filter { it.isDirectory && it != projectRoot }
            .filter {
                val build = File(it, "build.gradle.kts")
                build.isFile && libraryPlugin.containsMatchIn(build.readText())
            }
            .filter {
                val rel = it.relativeTo(projectRoot).invariantSeparatorsPath
                rel != "library/aosp-stubs" && rel != "library/log"
            }
            .toList()
        val gradlePathOf = { dir: File ->
            ":" + dir.relativeTo(projectRoot).invariantSeparatorsPath.replace('/', ':')
        }
        val dirOf = { gradlePath: String ->
            File(projectRoot, gradlePath.trimStart(':').replace(':', '/'))
        }
        val directDeps = libraryDirs.associate { dir ->
            val text = File(dir, "build.gradle.kts").readText()
            gradlePathOf(dir) to projectRef.findAll(text).map { it.groupValues[1] }.toSet()
        }
        val violations = mutableListOf<String>()
        for ((module, deps) in directDeps) {
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(deps)
            while (queue.isNotEmpty()) {
                val dep = queue.removeFirst()
                if (!seen.add(dep)) continue
                val depDir = dirOf(dep)
                if (!depDir.isDirectory) continue
                val perms = permissionsOf(depDir)
                // Belt and braces: the declared set fails fast on known
                // permission modules, while the manifest scan catches a module
                // that newly gains a permission without updating the set.
                if (dep in permissionDeclaringModules || perms.isNotEmpty()) {
                    val why = if (perms.isNotEmpty()) {
                        "which declares " + perms.sorted().joinToString(", ")
                    } else {
                        "which is a permission-declaring module"
                    }
                    violations += "$module depends on $dep, $why"
                }
                directDeps[dep]?.let { queue.addAll(it) }
            }
            for (dep in deps) {
                if ((module to dep) !in allowedLibraryEdges) {
                    violations += "$module depends on $dep, which is not in the " +
                        "documented permission-free edge list — review and document " +
                        "it in verifyLibraryBoundaries (root build.gradle.kts) first"
                }
            }
        }
        violations.forEach { println("verifyLibraryBoundaries: VIOLATION: $it") }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "verifyLibraryBoundaries: ${violations.size} permission-leaking " +
                    "library edge(s). Move the permission-needing code into the " +
                    "consuming app (or the permission-declaring module) instead of " +
                    "depending across libraries — see issue #884.",
            )
        }
        println(
            "verifyLibraryBoundaries: ${libraryDirs.size} library module(s) checked, " +
                "no permission-leaking edges.",
        )
    }
}

// Jacoco XML summaries land under build/reports/coverage after unit or instrumented runs,
// while manual runs drop .ec files under build/outputs/coverage-manual. This task only reads
// existing outputs, so it never triggers test execution.
tasks.register("coverageSummary") {
    group = "verification"
    description = "Sums per-module Jacoco report.xml counters " +
        "into a per-module + total table (unit + instrumented + manual .ec inventory)."
    val projectRoot = rootDir
    doLast {
        val reportMarker = "build/reports/coverage/"
        // analysis/ holds gitignored reference-app checkouts, not modules.
        val skipDirs = setOf(".git", "analysis")
        val reports = projectRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.name == "report.xml" }
            .filter { it.relativeTo(projectRoot).invariantSeparatorsPath.contains(reportMarker) }
            .toList()
        if (reports.isEmpty()) {
            println("coverageSummary: no coverage data yet, run ./test")
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isValidating = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val docBuilder = factory.newDocumentBuilder()
        val coveredByModule = mutableMapOf<String, MutableMap<String, LongArray>>()
        val moduleKeyFor = { file: File, marker: String ->
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            val modulePath = rel.substringBefore("/$marker").trim()
            if (modulePath.isEmpty()) "root" else modulePath.replace('/', '-')
        }
        for (report in reports) {
            val key = moduleKeyFor(report, reportMarker)
            val counts = coveredByModule.getOrPut(key) { mutableMapOf() }
            val doc = report.inputStream().use { docBuilder.parse(it) }
            // Only the root <report> element's own counters: getElementsByTagName
            // returns every nested package/class/method counter too, which would
            // multiply-count the same instructions.
            val root = doc.documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? org.w3c.dom.Element ?: continue
                if (element.tagName != "counter") continue
                val type = element.getAttribute("type")
                if (type != "INSTRUCTION" && type != "BRANCH" && type != "LINE") {
                    if (type != "METHOD") continue
                }
                val slot = counts.getOrPut(type) { longArrayOf(0L, 0L) }
                slot[0] += element.getAttribute("covered").toLongOrNull() ?: 0L
                slot[1] += element.getAttribute("missed").toLongOrNull() ?: 0L
            }
        }
        val pct = { covered: Long, total: Long ->
            if (total > 0) "%.1f".format(covered * 100.0 / total) else "100.0"
        }
        val lineFor = { counts: Map<String, LongArray> ->
            val instr = counts["INSTRUCTION"] ?: longArrayOf(0L, 0L)
            val branch = counts["BRANCH"] ?: longArrayOf(0L, 0L)
            val line = counts["LINE"] ?: longArrayOf(0L, 0L)
            val instrTotal = instr[0] + instr[1]
            val branchTotal = branch[0] + branch[1]
            val lineTotal = line[0] + line[1]
            "instr ${pct(instr[0], instrTotal)}% (${instr[0]}/$instrTotal), " +
                "branch ${pct(branch[0], branchTotal)}%, line ${pct(line[0], lineTotal)}%"
        }
        for (key in coveredByModule.keys.sorted()) {
            println("coverageSummary: $key: ${lineFor(coveredByModule.getValue(key))}")
        }
        if (coveredByModule.isNotEmpty()) {
            val total = mutableMapOf<String, LongArray>()
            for (counts in coveredByModule.values) {
                for ((type, slot) in counts) {
                    val acc = total.getOrPut(type) { longArrayOf(0L, 0L) }
                    acc[0] += slot[0]
                    acc[1] += slot[1]
                }
            }
            println("coverageSummary: TOTAL: ${lineFor(total)}")
        }
        val ecMarker = "build/outputs/coverage-manual/"
        val ecFiles = projectRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.extension == "ec" }
            .filter { it.relativeTo(projectRoot).invariantSeparatorsPath.contains(ecMarker) }
            .sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toList()
        if (ecFiles.isEmpty()) {
            println("coverageSummary: no manual .ec files yet")
        } else {
            for (ec in ecFiles) {
                val key = moduleKeyFor(ec, ecMarker)
                val rel = ec.relativeTo(projectRoot).invariantSeparatorsPath
                println("coverageSummary: ec $key: $rel (${ec.length()} bytes)")
            }
        }
        val testedModules = projectRoot.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != "analysis" }
            .filter { it.isDirectory && (it.name == "test" || it.name == "androidTest") }
            .filter { it.parentFile != null && it.parentFile.name == "src" }
            .map {
                val rel = it.relativeTo(projectRoot).invariantSeparatorsPath
                val modulePath = rel.substringBefore("/src/")
                if (modulePath.isEmpty()) "root" else modulePath.replace('/', '-')
            }
            .toSortedSet()
        val gaps = (testedModules - coveredByModule.keys).sorted()
        if (gaps.isEmpty()) {
            println("coverageSummary: no coverage gaps.")
        } else {
            for (key in gaps) {
                println("coverageSummary: no report for $key (has src/test or src/androidTest)")
            }
        }
    }
}