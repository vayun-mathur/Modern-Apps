/**
 * Shared test-coverage infrastructure for `common-conventions-app` and
 * `common-conventions-library`, kept here so the two convention plugins cannot drift:
 * - [MERGED_COVERAGE_EXCLUDES]: class-file patterns stripped from every merged coverage
 *   report. AGP's own `createDevUnitTestCoverageReport` / `createDebugUnitTestCoverageReport`
 *   tasks are AGP-internal `JacocoReportTask`s with **no excludes support** (verified against
 *   AGP 9.5.0-alpha09: the task exposes the class file collection as-is), so the conventions
 *   register their own Gradle `JacocoReport` task ([MERGED_COVERAGE_TASK]) which does.
 * - [ROBOLECTRIC_DEFAULT_SDK]: documented next to the properties file that carries it.
 * - [mergedCoverageClassDirs]: the class files the merged report analyzes.
 */

import java.io.File
import java.io.Serializable
import org.gradle.api.file.FileCollection
import org.gradle.api.specs.Spec

/** Task name of the per-module merged (unit + instrumented) JaCoCo report. */
const val MERGED_COVERAGE_TASK = "createMergedCoverageReport"

/**
 * Unit-test variant each convention's merged report is built from: apps test `dev`
 * (`testDevUnitTest`, AGP `testBuildType = "dev"`), libraries test `debug`
 * (`testDebugUnitTest`).
 */
const val APP_UNIT_TEST_VARIANT = "dev"
const val LIBRARY_UNIT_TEST_VARIANT = "debug"

/** Stable output location of the merged report, relative to the module build directory. */
const val MERGED_COVERAGE_XML_PATH = "reports/coverage/merged/report.xml"
const val MERGED_COVERAGE_HTML_PATH = "reports/coverage/merged/html"

/**
 * JaCoCo version used to *render* merged reports. Must read the .exec/.ec execution data
 * written by AGP 9.5's coverage agent (JaCoCo 0.8.14); report rendering within the same
 * 0.8.x family is compatible.
 */
const val MERGED_COVERAGE_JACOCO_VERSION = "0.8.14"

/**
 * Robolectric default SDK, mirrored in `build-logic/shared-test-resources/robolectric.properties`
 * (which is the mechanism that actually applies it; this constant only documents the intent
 * next to the coverage/task wiring).
 */
const val ROBOLECTRIC_DEFAULT_SDK = 34

/**
 * Modules whose merged coverage report is suppressed entirely.
 *
 * `:library:aosp-stubs` is compile-only: its classes are `@SystemApi`/hidden-API
 * stubs that consumers depend on with `compileOnly`, they are never packaged
 * into an APK, and at runtime the framework's real implementations replace
 * them (see that module's `build.gradle.kts`). Its stub bodies either throw
 * `RuntimeException("stub")` or are signature-only shells, so a coverage
 * percentage for it can only measure how much of a compile-time fiction the
 * tests exercised — the number has no runtime meaning, and chasing it would
 * mean writing tests that assert stubs throw, forever, for 37 classes.
 *
 * This is deliberately a module list, not a class-file pattern: a broad
 * `android/...` exclude would also hide real code in any module that ever
 * legitimately shadows a framework package. `:library:locationprovider`
 * is also compile-only stubs but is NOT listed: it has a real test suite
 * pinning the stub contract its consumers subclass against, so its report
 * stays meaningful and counted.
 *
 * The suppression lives in `common-conventions-library` (the only convention
 * that registers a merged report for these modules) as an `onlyIf` on the
 * report task: with no report file, coverage tooling treats the module as
 * not-a-coverage-module rather than as 0%.
 */
val COMPILE_ONLY_STUB_MODULES: Set<String> = setOf(":library:aosp-stubs")

/**
 * Class-file excludes applied to every merged coverage report.
 *
 * JaCoCo file-tree excludes are purely name-based, which settles two of the requested cases:
 * - `*$Companion` is **not** excluded: companion objects hold real code (e.g. factory methods
 *   and default noop implementations), and there is no way to exclude "only empty" companions
 *   with a name pattern. They count as normal code.
 * - `*Preview*` composables are **not** excluded by annotation: JaCoCo cannot filter by
 *   annotation. This is moot in practice — screenshot previews live in the `screenshotTest`
 *   source set, which never enters the report's class directories. Keep `@Preview`
 *   functions out of `src/main` and they stay out of coverage.
 */
val MERGED_COVERAGE_EXCLUDES = listOf(
    // Generated Android code: R, BuildConfig, Manifest.
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.class",
    "**/BuildConfig\$*.class",
    "**/Manifest.class",
    "**/Manifest\$*.class",
    // View/Data Binding (Compose-first repo, but harmless where present).
    "**/databinding/**",
    "**/*Binding.class",
    "**/*Binding\$*.class",
    "**/DataBinderMapperImpl.class",
    // Room/KSP generated implementations.
    "**/*_Impl.class",
    "**/*_Impl\$*.class",
    // kotlinx.serialization generated serializers.
    "**/*\$\$serializer.class",
    "**/*\$\$serializer\$*.class",
    // Compose compiler singletons (remember/composable caches, no coverable logic).
    "**/ComposableSingletons\$*.class",
    // Hilt/Dagger generated code (no Hilt/Dagger in the repo today; future-proofing).
    "**/Hilt_*.class",
    "**/*_Hilt*.class",
    "**/Dagger*.class",
    "**/*_Factory.class",
    "**/*_MembersInjector.class",
    // protobuf-lite generated outer classes. Kept as belt-and-braces: the precise
    // protoc rule is ProtoGeneratedClassFilter below (every class whose top-level
    // type has a generated source); this pattern still catches an outer class if
    // its generated-source root was not scanned (e.g. a future variant layout).
    "**/*OuterClass*.class",
    // AIDL-generated stubs.
    "**/*\$Stub*.class",
)

/**
 * Keeps protoc output out of merged coverage: a class file is dropped when its
 * top-level type has a generated source under the module's proto output roots
 * (`build/generated/source/proto` and
 * `build/generated/java/generate<Variant>Proto`). This is the same rule the
 * CI zero-coverage fallback applies (location_share_server
 * `scripts/test-run.sh`: "drop every class whose top-level type has a generated
 * .java source"), so modules with tests and modules without agree on what
 * counts. Protoc message/builder/or-builder/grpc classes are pure generated
 * plumbing with no hand-written logic; counting them drowned real coverage
 * (repo-wide ~587k missed + ~75k covered instructions at the time of writing).
 *
 * Why not name patterns (`*Protos*`, `*Proto$*`, proto-package globs)? They are both
 * too broad and too narrow: `communicate` has hand-written `*Protocol` classes
 * (`SignalProtocol`, `WhatsAppProtocol*`) one suffix away from `*Proto*`, and
 * hand-written code may legitimately live in a `proto` package later, while
 * GAL/grpc types (`AbsoluteEvent`, `AppServiceGrpc`) match no proto name
 * pattern at all. Source presence is exact: only types protoc actually emitted
 * are excluded, so no hand-written class can be caught. Verified on
 * `communicate`, `auto/protocol` and `appstore`: the excluded top-level set is
 * exactly the generated-source set, and no `src/main` class is in it.
 *
 * Laziness / configuration cache: the filter stores only the build directory
 * and variant (both serializable) and scans the generated sources on first use,
 * i.e. at report execution time, after the compile tasks have produced them.
 * Nothing is read from disk while configuring, and the [Spec] itself is
 * serializable, so the report task stays configuration-cache compatible
 * (verified by running the report twice: the second run reuses the cache).
 * The generated roots contain one source-set directory (`java`, plus `grpc` /
 * `grpckt` for grpc modules); the class relative path is the source path minus
 * that segment and its extension, so `.../java/com/foo/Bar.java` (and
 * `.../grpc/com/foo/BarGrpc.java`, `.../grpckt/... .kt`) excludes
 * `com/foo/Bar.class` and `com/foo/Bar$*.class`. Unknown class-dir layouts keep
 * the file: exclusion must never guess.
 */
class ProtoGeneratedClassFilter(
    private val buildDir: File,
    private val variantCap: String,
) : Spec<File>, Serializable {

    @Transient
    private var generatedTopLevels: Set<String>? = null

    override fun isSatisfiedBy(element: File): Boolean {
        if (!element.name.endsWith(".class")) return true
        val path = element.path.replace('\\', '/')
        val marker = "/classes/"
        val idx = path.lastIndexOf(marker)
        if (idx < 0) return true
        val rel = path.substring(idx + marker.length).removeSuffix(".class")
        val topLevel = rel.substringBefore('$')
        return topLevel !in generatedTopLevels()
    }

    private fun generatedTopLevels(): Set<String> {
        generatedTopLevels?.let { return it }
        val found = mutableSetOf<String>()
        val roots = listOf(
            File(buildDir, "generated/source/proto"),
            File(buildDir, "generated/java/generate${variantCap}Proto"),
        )
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                if (file.extension != "java" && file.extension != "kt") return@forEach
                val rel = file.toRelativeString(root).replace('\\', '/')
                val parts = rel.split('/')
                if (parts.size < 2) return@forEach
                val stripped = if (parts[0] == "java" || parts[0] == "grpc" || parts[0] == "grpckt") {
                    parts.drop(1).joinToString("/")
                } else {
                    rel
                }
                found += stripped.removeSuffix(".java").removeSuffix(".kt")
            }
        }
        generatedTopLevels = found
        return found
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * This module's own compiled classes for [variant], before AGP's offline JaCoCo
 * instrumentation (JaCoCo can only render from original classes), minus
 * [MERGED_COVERAGE_EXCLUDES] and minus protoc-generated classes
 * ([ProtoGeneratedClassFilter]). Covers Kotlin and javac output; dependency
 * classes never appear here, so no package filter is needed.
 */
fun org.gradle.api.Project.mergedCoverageClassDirs(variant: String): FileCollection {
    val cap = variant.replaceFirstChar { it.uppercase() }
    val tree = files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant/compile${cap}Kotlin/classes"),
        layout.buildDirectory.dir("intermediates/javac/$variant/compile${cap}JavaWithJavac/classes"),
    ).builtBy("compile${cap}Kotlin", "compile${cap}JavaWithJavac").asFileTree.matching {
        MERGED_COVERAGE_EXCLUDES.forEach { exclude(it) }
    }
    // Only the build-dir path is captured here (configuration time); the
    // generated-source scan happens lazily when JaCoCo resolves the files.
    val buildDirFile = layout.buildDirectory.asFile.get()
    return tree.filter(ProtoGeneratedClassFilter(buildDirFile, cap))
}

/**
 * Robolectric defines classes in its sandbox loader without a code-source location, and
 * JaCoCo skips such classes by default, so Robolectric-run code reported 0% coverage. AGP
 * attaches Gradle's JacocoTaskExtension to unit Test tasks; flip it on there.
 * `jdk.internal.*` must stay excluded or JaCoCo instruments JDK internals and the JVM crashes.
 */
fun org.gradle.api.Project.includeRobolectricClassesInCoverage() {
    afterEvaluate {
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java)?.apply {
                isIncludeNoLocationClasses = true
                excludes = (excludes.orEmpty() + "jdk.internal.*").distinct()
            }
        }
    }
}
