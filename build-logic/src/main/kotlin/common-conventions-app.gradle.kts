plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.autonomousapps.dependency-analysis")
    id("com.google.devtools.ksp")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

fun readVersionInfo(): Pair<Int, String> {
    val versionFile = File(rootDir, "version.txt")

    return if (versionFile.exists()) {
        val lines = versionFile.readLines()
        if (lines.size >= 2) {
            val code = lines[0].trim().toIntOrNull() ?: 1
            val name = lines[1].trim()
            code to name
        } else throw IllegalStateException("Invalid version.txt format")
    } else throw IllegalStateException("version.txt not found")
}

val proguardFile
    get() = File(rootDir, "proguard-rules.pro")

val (appVersionCode, appVersionName) = readVersionInfo()

configure<com.android.build.api.dsl.ApplicationExtension> {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".")}"
    compileSdk {
        version = release(36)
    }

    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        versionCode = appVersionCode
        versionName = appVersionName
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

            // This applies ONLY to your release APK/Bundle
            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.okio)
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

    implementation(project(":library"))
}

fun DependencyHandlerScope.justSoItShowsAsUsedSomewhere() {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}