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
    // protobuf-lite generated outer classes.
    "**/*OuterClass*.class",
    // AIDL-generated stubs.
    "**/*\$Stub*.class",
)

/**
 * This module's own compiled classes for [variant], before AGP's offline JaCoCo
 * instrumentation (JaCoCo can only render from original classes), minus
 * [MERGED_COVERAGE_EXCLUDES]. Covers Kotlin and javac output; dependency classes never
 * appear here, so no package filter is needed.
 */
fun org.gradle.api.Project.mergedCoverageClassDirs(variant: String): org.gradle.api.file.FileTree {
    val cap = variant.replaceFirstChar { it.uppercase() }
    return files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant/compile${cap}Kotlin/classes"),
        layout.buildDirectory.dir("intermediates/javac/$variant/compile${cap}JavaWithJavac/classes"),
    ).builtBy("compile${cap}Kotlin", "compile${cap}JavaWithJavac").asFileTree.matching {
        MERGED_COVERAGE_EXCLUDES.forEach { exclude(it) }
        // Generated protobuf classes. Protos with a custom java_outer_classname
        // (communicate's WhatsAppE2EProto, SignalServiceProtos, ...) or a foreign
        // java_package slip past the name patterns above, so drop every class whose
        // top-level type has a generated source. Plain Files only: configuration-cache safe.
        val protoRoots = listOf(
            layout.buildDirectory.dir("generated/source/proto").get().asFile,
            layout.buildDirectory.dir("generated/java/generate" + cap + "Proto").get().asFile,
        )
        exclude { el -> !el.isDirectory && isGeneratedProtoClass(protoRoots, el.relativePath.pathString) }
    }
}

/**
 * True when `a/b/Foo.class` or a nested `a/b/Foo` class has a generated `a/b/Foo.java` under
 * `<root>/java/` or `<root>/<sourceSet>/java/` of one of [roots]: `generated/source/proto`
 * (older protobuf-gradle-plugin layout) or `generated/java/generate<Variant>Proto` (current).
 */
private fun isGeneratedProtoClass(roots: List<java.io.File>, classPath: String): Boolean {
    if (!classPath.endsWith(".class")) return false
    val topLevel = classPath.removeSuffix(".class").substringBefore('\u0024')
    val source = "java/" + topLevel + ".java"
    return roots.any { root ->
        java.io.File(root, source).isFile ||
            (root.listFiles() ?: emptyArray()).any { java.io.File(it, source).isFile }
    }
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
