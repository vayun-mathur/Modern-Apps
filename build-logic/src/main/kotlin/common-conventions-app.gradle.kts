import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
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

/**
 * version.txt is read once per build (not once per module): the first
 * convention plugin evaluated stashes the (code, name) pair on the Gradle
 * object, and every later module reuses it. Same File reads as before —
 * one stat + one read per build instead of per app module — and the plain
 * Pair keeps the configuration cache happy (no Project captured).
 */
fun readVersionInfoOnce(): Pair<Int, String> {
    val extra = gradle.extensions.extraProperties
    if (extra.has("modernAppsVersionInfo")) {
        @Suppress("UNCHECKED_CAST")
        return extra.get("modernAppsVersionInfo") as Pair<Int, String>
    }
    val versionFile = File(rootDir, "version.txt")
    val info = if (versionFile.exists()) {
        val lines = versionFile.readLines()
        if (lines.size >= 2) {
            val code = lines[0].trim().toIntOrNull() ?: 1
            val name = lines[1].trim()
            code to name
        } else throw IllegalStateException("Invalid version.txt format")
    } else throw IllegalStateException("version.txt not found")
    extra.set("modernAppsVersionInfo", info)
    return info
}

fun readVersionInfo(): Pair<Int, String> = readVersionInfoOnce()

val proguardFile
    get() = File(rootDir, "proguard-rules.pro")

val (releaseVersionCode, appVersionName) = readVersionInfo()

/**
 * `-PversionCodeOverride=N` — for sideloading over a preinstalled Modern App on MAOS.
 *
 * The platform refuses to update a system package to the same versionCode, so an APK built
 * from the same `version.txt` as the running OS image can never be installed over it. Passing
 * a higher code makes the dev loop work without touching the release version every time.
 *
 * Keep the bump small (`+1`). The update lives in /data and keeps shadowing the system app
 * across OS flashes until the image's own versionCode overtakes it, so a wild value would
 * pin the sideloaded build in place indefinitely.
 *
 * Read at configuration time, which the configuration cache requires.
 */
val appVersionCode: Int =
    (findProperty("versionCodeOverride") as String?)?.trim()?.toIntOrNull() ?: releaseVersionCode

// Adaptive launcher icons are generated at build time from Material Symbols
// instead of committing an ic_launcher_foreground.xml per app. Each app declares
// its symbol via `launcherIcon { symbol = "..." }` (see LauncherIconExtension).
// The symbol path is downloaded from google/material-design-icons pinned at this
// commit for reproducible builds, wrapped in the standard foreground vector, and
// wired into every variant's generated res.
val launcherIcon = extensions.create("launcherIcon", LauncherIconExtension::class.java).apply {
    scale.convention(0.435)
}
val materialSymbolsRef = "819d78680a849ceef4c78f863d8753e3160b7c89"
val materialSymbolsCache = File(gradle.gradleUserHomeDir, "material-symbols-cache")

// Apps are arm64-only unless they opt in with `nativeAbis { armv7 = true }`
// (see NativeAbisExtension). Read in finalizeDsl below, not here.
val nativeAbis = extensions.create("nativeAbis", NativeAbisExtension::class.java).apply {
    armv7.convention(false)
}

extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    // Only `dev` and `release` ship. AGP always creates a `debug` build type and
    // forbids removing it, so disable its variants instead — no debug variant means
    // no assembleDebug/compileDebugKotlin tasks are generated. (The `debug` *signing*
    // config is untouched; `release` still falls back to it, and testBuildType = "dev".)
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.enable = false
    }
    // abiFilters is a plain Set rather than a lazy Provider, so the opt-in can only be
    // read once the app's own build file has been evaluated — reading nativeAbis.armv7
    // straight from the defaultConfig block below would always observe the convention.
    finalizeDsl { ext ->
        if (nativeAbis.armv7.get()) {
            ext.defaultConfig.ndk.abiFilters.add(ABI_ARMV7)
            ext.buildTypes.getByName("dev").ndk.abiFilters.add(ABI_ARMV7)
        }
    }
    onVariants { variant ->
        val gen = tasks.register(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}LauncherIcon",
            GenerateLauncherIconTask::class.java,
        ) {
            symbol.set(launcherIcon.symbol)
            scale.set(launcherIcon.scale)
            ref.set(materialSymbolsRef)
            cacheDir.set(materialSymbolsCache)
        }
        variant.sources.res?.addGeneratedSourceDirectory(gen, GenerateLauncherIconTask::outputDir)
    }
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        // Repo-wide DEV_BUILD flag (see buildTypes below). Every app gets a
        // BuildConfig.DEV_BUILD constant: true for assembleDev, false for
        // assembleRelease. Gate experimental/dev-only features on it so
        // R8 strips them from release builds.
        buildConfig = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".")}"
    compileSdk {
        // compose 1.13.0-beta01 (BOM 2026.09.00) needs platform 37.1+.
        version = release(37) {
            minorApiLevel = 1
        }
    }
    //compileSdkExtension = 19

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Repo-wide Robolectric default SDK: build-logic/shared-test-resources holds a
    // robolectric.properties with `sdk=<ROBOLECTRIC_DEFAULT_SDK>` (see TestCoverage.kt),
    // merged by Robolectric as the base config for every test class without an explicit
    // `@Config(sdk = ...)`. Per-module `testOptions.unitTests.isReturnDefaultValues`
    // settings are untouched — that flag is never set here (it hides bugs).
    sourceSets.getByName("test").resources.srcDir(File(rootDir, "build-logic/shared-test-resources"))

    lint {
        checkDependencies = true
        // `abortOnError` is the only switch that lets lint fail anything. AGP's
        // AndroidLintTextOutputTask returns early on lint's error exit code unless it is
        // set, and it reads the same DSL value for `lintVitalRelease` — so while this was
        // off, `fatal` below was decorative and neither `./gradlew lint` nor
        // `assembleRelease` could fail on a Toast.
        //
        // It does not distinguish Error from Fatal, so the two gates below only work if
        // every other issue is demoted to `warning` first. There is no wildcard for that:
        // `lintConfig` with `id="all"` would also enable every default-off check, so the
        // backlog is listed by id. A new Error-severity check appearing (an AGP bump, a
        // new detector) will fail every module until it is triaged into this list or
        // fixed - that is the intended failure mode, not a surprise.
        abortOnError = true
        // Don't fail on missing translations - empty skeletons exist for Weblate.
        disable += listOf("MissingTranslation")
        // Advisory: reported by `./gradlew lint`, never blocking. Each of these is Error
        // (ExtraTranslation is Fatal) by default and has a pre-existing backlog.
        // Lint sweep (warn-then-error): the three comment detectors land here
        // as warnings; they promote to fatal with the rest at promotion.
        warning += listOf(
            "HardcodedText",
            // Repo rules from :lint-rules. Only the three gates below are not advisory.
            "DirectBuildDatabase",
            "OneComposablePerFile",
            "PackageStructure",
            "RawScaffoldInApp",
            "Room2Usage",
            "ExceptionMarkerFormat",
            "TodoEvasion",
            "CommentedOutCode",
            // Built-in checks.
            "ContextCastToActivity",
            "ExtraTranslation",
            "ForegroundServicePermission",
            "GestureBackNavigation",
            "LintError",
            "LocalContextGetResourceValueCall",
            "MissingIntentFilterForMediaSearch",
            "MissingPermission",
            "MissingQuantity",
            "NewApi",
            "NonObservableLocale",
            "NotificationPermission",
            "PermissionImpliesUnsupportedChromeOsHardware",
            "ProtectedPermissions",
            "QueryAllPackagesPermission",
            "RestrictedApi",
            "StartActivityAndCollapseDeprecated",
            "StateFlowValueCalledInComposition",
            "UnsafeOptInUsageError",
            "WrongConstant",
        )
        // Toast is banned repo-wide; this one fails the build even though
        // everything else is advisory. See :lint-rules.
        // DirectComposeAnimation keeps motion in the shared helpers, so the same
        // interaction cannot pick up a different duration on every screen.
        // FileLength caps Kotlin file size (350 ui / 800 elsewhere): oversized
        // screen files hide brace-imbalance breakage and rot into helper
        // grab-bags. Split first — the build fails until each file fits.
        fatal += listOf("ToastUsage", "DirectComposeAnimation", "FileLength", "DirectAndroidLog")
    }

    // Every app declares the same res/resources.properties (unqualifiedResLocale) for
    // per-app locale config. Share a single committed copy instead of one file per app.
    // (Generated res dirs are NOT scanned by extractSupportedLocales, so this must be a
    // real res source directory.)
    sourceSets.getByName("main").res.directories.add(File(rootDir, "build-logic/shared-res").absolutePath)

    ndkVersion = NDK_VERSION

    defaultConfig {
        minSdk = 31
        versionCode = appVersionCode
        versionName = appVersionName
        targetSdk = 37
        // Launch-no-crash tests run on-device (connectedDevAndroidTest). Mirrors
        // :library:map, the only other module with an androidTest source set.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64-v8a only by default — all phones/tablets + Apple Silicon emulator are
        // arm64. AGP otherwise configures buildCMakeDebug for armeabi-v7a/x86/x86_64
        // which wastes time and broke when offline router's sqlite3.c was removed.
        // 32-bit devices (Google TV) opt in with `nativeAbis { armv7 = true }`.
        ndk {
            abiFilters.add(ABI_ARM64)
            // `-PemulatorAbi=x86_64` additionally packages x86_64, so a native module can
            // be run in an emulator on an x86_64 host. Off unless asked for, so no release
            // build is affected. abiFilters only filters what was built, so the module
            // must also opt in via `rustNativeLib(extraAbis = ...)`.
            if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
                abiFilters.add(ABI_X86_64)
            }
        }
    }

    signingConfigs {
        val isSigningConfigAvailable = project.hasProperty("RELEASE_STORE_FILE")

        if (isSigningConfigAvailable) {
            create("release") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                // Emits the `<apk>.idsig` sidecar. PackageInstallerSession calls
                // enableFsVerityToAddedApksWithIdsig() for any non-incremental install that
                // stages one, which is the only way to get fs-verity onto an APK - and
                // GrapheneOS refuses to update a preinstalled system app without it. Incremental
                // install, the other route to fs-verity, is barred for system packages. So
                // without this a preinstalled Modern App can only be changed by a full OS build.
                // The APK itself is unchanged; consumers that copy `*-release.apk` (collect-apks.sh,
                // the F-Droid publish) simply ignore the sidecar.
                enableV4Signing = true
            }
        }
    }

    // AGP auto-creates a `debug` build type; this repo only ships `dev` and `release`.
    // Point all test tasks at `dev` before dropping `debug` in the block below.
    testBuildType = "dev"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), proguardFile.absolutePath,
            )
            // App-specific keeps belong next to the app, not in the shared root file.
            // Resolved to an absolute path here at configuration time so no Project
            // reference is captured in a task action (configuration cache).
            val appProguardFile = File(projectDir, "proguard-rules.pro")
            if (appProguardFile.exists()) {
                proguardFiles(appProguardFile.absolutePath)
            }
            buildConfigField("boolean", "DEV_BUILD", "false")
        }
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // The Compose screenshot-test plugin (com.android.compose.screenshot) only
            // registers its per-variant render tasks (preview/update/validateDevScreenshotTest)
            // for a variant whose component is debuggable. `dev` is our testBuildType and is
            // built from `release` (initWith), which is not debuggable, so without this the
            // render tasks are never created and `metadata` fails with "No rendered previews
            // found". `dev` is a developer build and never ships, so debuggable is correct here.
            isDebuggable = true
            matchingFallbacks += listOf("release")
            ndk {
                abiFilters.clear()
                abiFilters.add(ABI_ARM64)
                if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
                    abiFilters.add(ABI_X86_64)
                }
            }
            // initWith(release) copied DEV_BUILD=false; dev is a developer build, so flip it on.
            buildConfigField("boolean", "DEV_BUILD", "true")
            // Coverage for the launch-no-crash rollout and everything after it:
            // unit-test coverage via createDevUnitTestCoverageReport, on-device
            // coverage via the connectedDevAndroidTest + coverage report tasks.
            // Enable coverage for unit tests
            enableUnitTestCoverage = true
            // Enable coverage for instrumentation tests
            enableAndroidTestCoverage = true
        }
    }

    packaging {
        resources {
            // Multiple dependencies ship these license files, which collide
            // during Java-resource merge.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
            // protoc copies every .proto it compiles into the jar's resources, so any app
            // depending on a protobuf module ships its schemas plus protobuf's well-known
            // types - on the order of 100 KB of source that nothing reads. Every protobuf
            // module here (:appstore via grpc-protobuf-lite, :youpipe:extractor,
            // :auto:protocol) is lite, and lite never looks at a .proto at runtime; only
            // full-protobuf descriptor reflection would, and nothing in the repo uses it.
            excludes += setOf("**/*.proto", "*.proto")
        }
        // Left at the AGP default (false), which stores .so and .dex uncompressed so the
        // platform can mmap them straight out of the APK. MAOS ships these same APKs as
        // presigned prebuilts in the system image, and Soong requires `preprocessed: true`
        // for a presigned prebuilt targeting SDK >= 30 (otherwise it would re-zipalign and
        // wreck the v2 signature). A preprocessed APK is installed byte-for-byte, so Soong
        // rejects any compressed .so, and any compressed dex in a priv-app.
        //
        // This costs download size on :appstore, where the stored bytes _are_ the download
        // and native code plus dex is the bulk of every APK (dex ~236 MB and .so ~165 MB
        // across the repo, both roughly halving under deflate). It buys back install size
        // and first-launch time, since the libs no longer get extracted.
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            useLegacyPackaging = false
        }
    }
}

dependencies {
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
    // Material 3 is intentionally NOT declared here. Apps must consume Material only
    // through `:library:ui` (which exposes it via `api`), so all Material usage goes
    // through the curated wrappers in `com.vayunmathur.library.ui`.

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":library"))
    implementation(project(":library:ui"))
    // Repo-wide logging facade (com.vayunmathur.library.log.Log). Direct
    // android.util.Log use is banned by the DirectAndroidLog lint check.
    implementation(project(":library:log"))

    // Repo-specific lint checks (currently: no Toast).
    lintChecks(project(":lint-rules"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    // JVM unit-test harness shared by every app (plain JUnit, Robolectric including
    // Compose-under-Robolectric via createComposeRule, coroutines-test). The compose
    // test artifact version comes from the BOM, like the main-source Compose deps.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Bare ComponentActivity for Robolectric Compose tests (createComposeRule needs the
    // manifest entry; the artifact ships only a manifest, no code). Scoped to `dev`,
    // the variant unit tests run against (testBuildType = "dev"). `devImplementation`
    // has no static Kotlin-DSL accessor (the `dev` build type is repo-custom, unlike the
    // standard `debug`/`release`), so this uses add() like any custom configuration.
    add("devImplementation", libs.androidx.compose.ui.test.manifest)

    // Launch-no-crash tests (src/androidTest, run on-device via assemble +
    // `am instrument`). Wired once here so no app build file needs its own
    // block; modules without an androidTest source set compile nothing extra.
    // NOTE on compose ui-test below: its Espresso idle-sync reflects on hidden
    // InputManager APIs that no longer exist on current releases, so an on-device
    // compose-rule test still dies before its first line. The artifact is present so
    // androidTest sources resolve (and UiDevice polling, which covers Compose and
    // non-Compose screens alike with no Espresso involved, needs no rule at all);
    // prefer UiDevice polling on-device and createComposeRule only under Robolectric.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// Merged coverage: a Gradle JacocoReport combining the unit-test .exec (Robolectric
// included) with the on-device .ec files ./test pulls to build/outputs/coverage-manual.
// AGP's own createDevUnitTestCoverageReport cannot take excludes (AGP-internal task
// type), so this task applies MERGED_COVERAGE_EXCLUDES (see TestCoverage.kt) and writes
// XML+HTML to build/reports/coverage/merged/. It depends on testDevUnitTest, so invoking
// it runs the unit tests first; whichever of .exec/.ec exist are merged (fileTrees are
// lenient about missing inputs). Skipped only when there is no execution data at all.
// Configuration-cache safe: all inputs are lazy file collections, no Project capture.
//
// NOTE: this intentionally does NOT apply the Gradle `jacoco` plugin. AGP wires its own
// JaCoCo agent onto unit-test tasks when enableUnitTestCoverage is on, and expects the
// Test tasks to carry no Gradle JacocoTaskExtension (`testDevUnitTest` fails to even
// materialize when one is present). The report below reuses AGP's own `jacocoAnt`
// configuration instead of adding another one — looked up lazily at task-registration
// time (AGP creates it when its JacocoPlugin applies, which can be after this script
// runs), pinned by AGP to the same version family as its own coverage agent so
// .exec/.ec files read cleanly.
val jacocoAntClasspath: FileCollection = objects.fileCollection().from(
    provider { configurations.getByName("jacocoAnt") },
)

tasks.register<JacocoReport>(MERGED_COVERAGE_TASK) {
    group = "verification"
    description = "Merged JaCoCo report for the dev variant: unit-test .exec plus " +
        "androidTest .ec files (whichever exist), with generated-code excludes."
    dependsOn("testDevUnitTest")
    setJacocoClasspath(jacocoAntClasspath)
    classDirectories.from(mergedCoverageClassDirs(APP_UNIT_TEST_VARIANT))
    executionData.from(
        files(layout.buildDirectory.dir("outputs/unit_test_code_coverage/devUnitTest"))
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

fun DependencyHandlerScope.justSoItShowsAsUsedSomewhere() {
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
}

// Apps consume Material only through `:library:ui` wrappers, but some re-exported
// Material 3 types (sheets, tabs, adaptive, etc.) are still marked @RequiresOptIn.
// Opt in globally so app code doesn't need to import Material's markers.
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
