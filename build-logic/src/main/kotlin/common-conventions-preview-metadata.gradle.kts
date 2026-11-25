import com.android.build.api.dsl.ApplicationExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.tasks.bundling.AbstractArchiveTask

// Store-listing screenshots rendered from Compose @Preview instead of from an instrumented
// test on a device.
//
// Apply this INSTEAD OF `common-conventions-metadata` on an app whose screens have been
// split into stateless composables. It registers the same `metadata` task name, so
// `./gradlew :calculator:metadata` keeps working with the release build — but it
// needs no emulator, no adb, and no `pm clear` of a real device.
//
//     ./gradlew :calculator:metadata
//
// renders every @Preview in `src/screenshotTest/` and copies the PNGs into
// per-form-factor buckets under `metadata_data/photos/<module-key>/` —
// `phone/`, `tablet/`, `large-tablet/`, `tv/`, `wear/` — numbered 1.png, 2.png,
// ... in preview-function order *within each bucket*. The bucket comes from the
// preview's `group` parameter, which must be one of those five names:
//
//     @Preview(name = "1-keypad", device = PHONE, group = "phone", showSystemUi = true)
//
// Name the preview functions `Preview1Foo`, `Preview2Bar`, ... so that order is the
// listing order — the generated filenames embed the function name. The group value
// uses the Triple-T `gradle-play-publisher` spellings verbatim
// (`phone/tablet/large-tablet/tv/wear`, mapping to `graphics/<bucket>-screenshots`
// in the release build); the server's F-Droid index translates the two tablet
// buckets to F-Droid's `sevenInch`/`tenInch` keys, matching what official
// fdroidserver does when ingesting Triple-T listings.
//
// Trade-off worth knowing: previews render through Layoutlib, which has no wallpaper, so
// `DynamicTheme` falls back to its default palette rather than the user's Material You
// accent. On-device captures (`common-conventions-metadata`) show the real accent.

val libs = the<LibrariesForLibs>()

// The plugin refuses to apply unless android.experimental.enableScreenshotTest is already
// set, and it checks at apply time — so the authoritative switch is the one in the root
// gradle.properties, not this DSL. The per-module property below is kept only so the
// requirement is visible from the module that actually needs it.
apply(plugin = "com.android.compose.screenshot")

configure<ApplicationExtension> {
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    add("screenshotTestImplementation", platform(libs.androidx.compose.bom))
    add("screenshotTestImplementation", libs.androidx.compose.ui.tooling)
    add("screenshotTestImplementation", libs.androidx.compose.ui.tooling.preview)
    // @PreviewTest. A @Preview without it renders in Studio but is not collected as a
    // screenshot test, which shows up as "did not discover any tests".
    add("screenshotTestImplementation", libs.screenshot.validation.api)
}

val moduleKey = path.removePrefix(":").replace(":", "-")
val screenshotsOut = File(rootDir, "metadata_data/photos/$moduleKey")

/**
 * Form-factor buckets, using the Triple-T `gradle-play-publisher` spellings verbatim:
 * each bucket maps 1:1 onto `graphics/<bucket>-screenshots` in the release build.
 * (F-Droid's index calls the tablet buckets `sevenInch`/`tenInch` instead — that
 * translation lives in the server's `fdroid_repo`, matching official fdroidserver.)
 */
val screenshotBuckets = listOf("phone", "tablet", "large-tablet", "tv", "wear")

// `src/screenshotTest/**/*.kt`, scanned at task-execution time to resolve each
// preview function name to its `@Preview(group = ...)` bucket. The rendered PNG
// names only carry function + preview name + hash, never the group, so the
// source is the only place the bucket survives.
val previewSourcesDir = File(projectDir, "src/screenshotTest")

// Where the plugin puts its PNGs. Both paths are taken verbatim from the engine config it
// generates at build/tmp/<task>/preview_screenshot_config.properties (referenceImageDir and
// previewImageOutputDir) — worth re-checking there if a plugin upgrade moves them. In
// recording mode the images land in the reference dir; they are an intermediate for us (see
// .gitignore), though keeping them is also what would let `validate…ScreenshotTest` act as
// UI regression testing later.
//
// Order matters: `rendered/` is the fresh output of the `updateScreenshotTest` run this
// task just depended on; `reference/` accumulates across runs (and across plugin versions,
// whose filename hashes differ), so it is only a fallback. Reading reference first would
// publish stale duplicates alongside the fresh renders.
// The screenshot plugin names its source set, reference dir and output tree after the
// module's test build type (`dev` for apps via common-conventions-app, `debug` — the
// AGP default — for wear modules via common-conventions-wear). Read it here, after the
// module's convention plugin has run, instead of hardcoding `dev`, so the same script
// collects renders for both families.
val screenshotVariant = the<ApplicationExtension>().testBuildType
val screenshotVariantCap = screenshotVariant.replaceFirstChar { it.uppercase() }

val referenceDir = File(projectDir, "src/screenshotTest$screenshotVariantCap/reference")
// Lazily derived from the build directory provider: no .get() at
// configuration time, so merely configuring the project creates no tasks.
val renderedDir = layout.buildDirectory.map {
    it.asFile.resolve("outputs/screenshotTest-results/preview/$screenshotVariant/rendered")
}

/**
 * Collects rendered previews into per-bucket `metadata_data/photos/<module-key>/<bucket>/`.
 *
 * Everything is resolved to plain [File]s at configuration time so the task stays
 * configuration-cache compatible (the root build has it enabled); the
 * function-name → group map is built from the preview sources inside [run].
 */
abstract class CollectPreviewScreenshots : DefaultTask() {

    @get:Internal abstract val sourceDirs: ListProperty<File>

    @get:Internal abstract val destination: Property<File>

    @get:Internal abstract val previewSources: Property<File>

    @get:Internal abstract val buckets: ListProperty<String>

    @get:Internal abstract val label: Property<String>

    /** Bucket for previews that declare no `group` (wear modules default to `wear/`). */
    @get:Internal abstract val defaultBucket: Property<String>

    @TaskAction
    fun run() {
        val source = sourceDirs.get().firstOrNull { it.isDirectory && it.walkTopDown().any { f -> f.extension == "png" } }
            ?: error(
                "No rendered previews found for ${label.get()}. Looked in:\n" +
                    sourceDirs.get().joinToString("\n") { "  $it" } +
                    "\nDoes the module have @Preview functions under src/screenshotTest/?"
            )

        // Sorted by file name so the listing order is the preview function order
        // (Preview1…, Preview2…) — the generated names embed the function name.
        // Deliberately NOT the full path: previews live in per-class directories
        // (e.g. maps' CarMetadataPreviews/ vs MetadataPreviews/), and sorting by
        // path would put every Car… shot before every Metadata… shot regardless of
        // number. Anything else would silently shuffle the store listing.
        // Deduplicated by content: the same preview can render more than once (e.g. a
        // stale reference image from an older plugin version sitting next to the fresh
        // render), and the store listing must not show the same shot twice.
        val seen = mutableSetOf<String>()
        val images = source.walkTopDown().filter { it.extension == "png" }.sortedBy { it.name }.filter { png ->
            seen.add(sha256Hex(png.readBytes()))
        }.toList()
        if (images.size < seen.size) {
            logger.lifecycle("Metadata screenshots: dropped ${seen.size - images.size} byte-identical duplicate(s)")
        }

        val groups = readPreviewGroups(previewSources.get(), label.get())

        val dest = destination.get()
        val valid = buckets.get().toSet()
        images.groupBy { png -> groups[functionNameOf(png)] ?: defaultBucket.get() }.forEach { (bucket, bucketImages) ->
            require(bucket in valid) {
                "Unknown screenshot bucket \"$bucket\" for ${label.get()} — expected one of ${valid.sorted().joinToString(", ")}"
            }
            val bucketDir = File(dest, bucket)
            // Only the buckets this run rendered are replaced. A bucket with no
            // previews this run (e.g. `camera`'s hand-captured `phone/` shots, which
            // no preview can reproduce) is left untouched instead of being wiped
            // along with the destination.
            bucketDir.deleteRecursively()
            bucketDir.mkdirs()
            bucketImages.sortedBy { it.name }.forEachIndexed { index, png ->
                png.copyTo(File(bucketDir, "${index + 1}.png"), overwrite = true)
            }
        }
        logger.lifecycle("Metadata screenshots: ${images.size} image(s) -> $dest")
        images.forEach { f -> logger.lifecycle("  ${groups[functionNameOf(f)] ?: defaultBucket.get()}/${f.name}") }
    }

    /** Rendered filenames look like `Preview1Keypad_1-keypad_<hash>_0.png` — the leading segment is the function name. */
    private fun functionNameOf(png: File): String = png.nameWithoutExtension.substringBefore('_')

    /** Content hash for duplicate detection (a few small PNGs per module — cheap). */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Maps preview function names (`Preview1Keypad`) to their `@Preview(group = ...)`
     * bucket by scanning the module's `src/screenshotTest` sources. Only `@Preview(`
     * annotations carry a group; `@PreviewScreen`/`@PreviewTablet`-style wrappers
     * (none in the repo today) would need explicit handling here.
     */
    private fun readPreviewGroups(dir: File, label: String): Map<String, String> {
        val groupOf = mutableMapOf<String, String>()
        // `@Preview(... group = "phone" ...)` — annotations are single-line repo-wide;
        // the group is on the same line as the `@Preview(` opener.
        val groupArg = Regex("""group\s*=\s*"([^"]+)"""")
        // `fun Preview1Keypad(` — the listing-order contract.
        val previewFun = Regex("""fun\s+(Preview\d\w*)\s*\(""")
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            var pendingGroup: String? = null
            file.forEachLine { line ->
                if (line.contains("@Preview(")) {
                    pendingGroup = groupArg.find(line)?.groupValues?.get(1)
                }
                // NOTE: the group is deliberately NOT cleared on @Composable — it
                // always sits between @Preview and fun. It clears on the fun line.
                previewFun.find(line)?.let { match ->
                    val group = pendingGroup
                    if (group != null) groupOf[match.groupValues[1]] = group
                    pendingGroup = null
                }
            }
        }
        if (groupOf.isEmpty()) {
            logger.warn("No @Preview(group = ...) found under $dir for $label — routing everything to phone/. Add group to each preview.")
        }
        return groupOf
    }
}

tasks.register<CollectPreviewScreenshots>("metadata") {
    group = "metadata"
    description =
        "Render Compose previews and copy them into metadata_data/photos/$moduleKey/<bucket> (no device required)"
    // `update…` renders the previews and writes them out; `validate…` would additionally
    // diff them against a committed baseline, which we do not keep.
    dependsOn("updateScreenshotTest")
    sourceDirs.set(renderedDir.map { listOf(it, referenceDir) })
    destination.set(screenshotsOut)
    previewSources.set(previewSourcesDir)
    buckets.set(screenshotBuckets)
    // Wear modules file their captures under their own module key's `wear/` bucket
    // (metadata_data/photos/<wear-module-key>/wear/), never the phone app's folder.
    defaultBucket.set(if (path.contains("wear")) "wear" else "phone")
    label.set(path)
    // Cheap, and the whole point is to refresh the images on demand.
    outputs.upToDateWhen { false }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
