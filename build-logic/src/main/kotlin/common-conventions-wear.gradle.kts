import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Lint-sweep decision (warn-then-error rollout): this convention is
// intentionally NOT wired to detekt or :lint-rules. No module applies it
// (settings.gradle.kts has no wear consumers), so there is no slice to keep
// green. If a wear module lands, wire `dev.detekt` + the app lint block at
// warning parity with common-conventions-app before its first commit.

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

wireRoomSchemaLocation()

// Shared with common-conventions-app: version.txt is read once per build via
// the Gradle extra cache (see readVersionInfoOnce there). No wear module
// exists yet (settings.gradle.kts has no wear consumers); when one lands,
// call readVersionInfoOnce() instead of re-adding a local reader.
//
// NOTE: this script must not call functions declared in another precompiled
// script plugin (each compiles to an isolated class): the values below are
// inlined defaults, replaced by the real readVersionInfoOnce() call when a
// wear consumer appears.
val proguardFile
    get() = File(rootDir, "proguard-rules.pro")

val (appVersionCode, appVersionName) = 1 to "1.0"

// Adaptive launcher icons are generated at build time from Material Symbols
// (same mechanism as common-conventions-app). Each app declares its symbol via
// `launcherIcon { symbol = "..." }`.
val launcherIcon = extensions.create("launcherIcon", LauncherIconExtension::class.java).apply {
    scale.convention(0.435)
}
val materialSymbolsRef = "819d78680a849ceef4c78f863d8753e3160b7c89"
val materialSymbolsCache = File(gradle.gradleUserHomeDir, "material-symbols-cache")

extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
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
    }

    // Hyphens are not valid in a Java package: `:contacts-wear` -> `com.vayunmathur.contacts.wear`.
    namespace = "com.vayunmathur${path.replace(":", ".").replace("-", ".")}"
    compileSdk {
        // Platform 37.1+ for the current Compose train (BOM 2026.09.00).
        version = release(37) {
            minorApiLevel = 1
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    // Every app declares the same res/resources.properties (unqualifiedResLocale) for
    // per-app locale config. Share a single committed copy instead of one file per app.
    // (Generated res dirs are NOT scanned by extractSupportedLocales, so this must be a
    // real res source directory.)
    sourceSets.getByName("main").res.directories.add(File(rootDir, "build-logic/shared-res").absolutePath)

    ndkVersion = NDK_VERSION

    defaultConfig {
        minSdk = 30
        versionCode = appVersionCode
        versionName = appVersionName
        targetSdk = 37
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
                enableV4Signing = false
            }
        }
    }

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

            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Debuggable parity with common-conventions-app's `dev`: the Compose
            // screenshot-test plugin (com.android.compose.screenshot, applied by
            // common-conventions-preview-metadata) only registers its render tasks
            // for a debuggable variant. Wear's test build type stays `debug` (the
            // AGP default, debuggable already), so today's screenshot tasks are the
            // `*DebugScreenshotTest` ones; this keeps `dev` equally capable if the
            // test build type ever moves to it, and `dev` never ships.
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI (BOM Managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)

    // Wear Compose
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Repo-wide logging facade (com.vayunmathur.library.log.Log). Direct
    // android.util.Log use is banned by the DirectAndroidLog lint check.
    implementation(project(":library:log"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reproducible builds: log SOURCE_DATE_EPOCH if present for verification
providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let {
    logger.lifecycle("Reproducible build: SOURCE_DATE_EPOCH=$it")
}
