import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

// Static analysis (./check runs `:module:detekt`). Rules live in the committed
// root detekt.yml; findings fail the task (no ignoreFailures).
// Config-cache compatible: the config file is resolved here, never a Project.
detekt {
    config.setFrom(files(rootDir.resolve("detekt.yml")))
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

// JVM unit tests load Rust cdylibs from build/rustHostLibs (see RustNative.kt):
// every Test task builds + links the host libs of this module and each project
// dep that registers one. Lazy — assemble/install never build them.
wireRustHostLibsForUnitTests()
includeRobolectricClassesInCoverage()

configure<com.android.build.api.dsl.LibraryExtension> {
    // Same unit-test setup as common-conventions-app: merged manifest/resources for
    // Robolectric, plus the shared robolectric.properties (default sdk).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets.getByName("test").resources.srcDir(File(rootDir, "build-logic/shared-test-resources"))

    buildFeatures {
        compose = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".").replace("-", "")}"
    compileSdk {
        // compose 1.13.0-beta01 (BOM 2026.09.00) needs platform 37.1+.
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
    }

    buildTypes {
        named("release") {
            // Coverage for unit tests, mirroring common-conventions-app so
            // library modules report into the same per-module tasks.
            // Enable coverage for unit tests
            enableUnitTestCoverage = true
        }
        named("debug") {
            // Enable coverage for unit tests
            enableUnitTestCoverage = true
            // Enable coverage for instrumentation tests (the :library:map
            // on-device renderer harness).
            enableAndroidTestCoverage = true
        }
    }

    lint {
        // Toast is banned repo-wide. Apps also catch this transitively via
        // checkDependencies, but failing here gives faster feedback when
        // working inside a library module.
        // FileLength caps Kotlin file size, same as apps: split first.
        // DirectAndroidLog keeps all logging on the repo facade.
        fatal += listOf("ToastUsage", "FileLength", "DirectAndroidLog")
        // Lint sweep (warn-then-error): mirror the app warnings so
        // library modules surface marker-shape drift without failing the build.
        warning += listOf("ExceptionMarkerFormat", "TodoEvasion", "CommentedOutCode")
    }
}

dependencies {
    // Repo-specific lint checks (currently: no Toast).
    lintChecks(project(":lint-rules"))

    // Repo-wide logging facade (com.vayunmathur.library.log.Log). Direct
    // android.util.Log use is banned by the DirectAndroidLog lint check.
    // Self-excluded: this convention also applies to :library:log itself, and a
    // module depending on itself is a task-graph cycle (bundleLibCompileToJar
    // <-> compileJavaWithJavac). Configuration-time branch, cache-safe.
    if (project.path != ":library:log") {
        implementation(project(":library:log"))
    }

    // AndroidX Core & Lifecycle
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI (BOM Managed)
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    // JVM unit-test harness shared by every library (plain JUnit, Robolectric including
    // Compose-under-Robolectric via createComposeRule, coroutines-test). The compose
    // test artifact version comes from the BOM, like the main-source Compose deps.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Bare ComponentActivity for Robolectric Compose tests (createComposeRule needs the
    // manifest entry; the artifact ships only a manifest, no code). Scoped to `debug`,
    // the variant unit tests run against.
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // On-device test harness (src/androidTest; e.g. :library:map's renderer harness).
    // Same Espresso caveat as the app convention: the compose test artifact is present
    // so sources resolve, but prefer UiDevice polling on-device and createComposeRule
    // only under Robolectric.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// Merged coverage: same shape as the app convention's task (see TestCoverage.kt), but for
// the `debug` variant libraries test (testDebugUnitTest). Writes XML+HTML to
// build/reports/coverage/merged/ with the shared generated-code excludes. Like the app
// side, this deliberately avoids the Gradle `jacoco` plugin (AGP owns the Test-task
// agent wiring) and reuses AGP's own `jacocoAnt` configuration for the report runtime,
// looked up lazily (AGP creates it when its JacocoPlugin applies).
val jacocoAntClasspath: FileCollection = objects.fileCollection().from(
    provider { configurations.getByName("jacocoAnt") },
)

tasks.register<JacocoReport>(MERGED_COVERAGE_TASK) {
    group = "verification"
    description = "Merged JaCoCo report for the debug variant: unit-test .exec plus " +
        "androidTest .ec files (whichever exist), with generated-code excludes."
    dependsOn("testDebugUnitTest")
    setJacocoClasspath(jacocoAntClasspath)
    classDirectories.from(mergedCoverageClassDirs(LIBRARY_UNIT_TEST_VARIANT))
    executionData.from(
        files(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest"))
            .asFileTree.matching { include("*.exec") },
        files(layout.buildDirectory.dir("outputs/coverage-manual"))
            .asFileTree.matching { include("*.ec") },
    )
    sourceDirectories.from(
        files(layout.projectDirectory.dir("src/main/java")),
        files(layout.projectDirectory.dir("src/main/kotlin")),
    )
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file(MERGED_COVERAGE_XML_PATH))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir(MERGED_COVERAGE_HTML_PATH))
        csv.required.set(false)
    }
    onlyIf("there is unit or instrumented execution data to report on") {
        executionData.files.isNotEmpty()
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
    )
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reproducible builds: log SOURCE_DATE_EPOCH if present for verification
providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let {
    logger.lifecycle("Reproducible build: SOURCE_DATE_EPOCH=$it")
}
