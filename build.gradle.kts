// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

fun readVersionInfo(): Pair<Int, String> {
    val versionFile = File(projectDir, "version.txt")

    return if (versionFile.exists()) {
        val lines = versionFile.readLines()
        if (lines.size >= 2) {
            val code = lines[0].trim().toIntOrNull() ?: 1
            val name = lines[1].trim()
            code to name
        } else throw IllegalStateException("Invalid version.txt format")
    } else throw IllegalStateException("version.txt not found")
}

val (appVersionCode, appVersionName) = readVersionInfo()

subprojects {
    if(name == "app") return@subprojects

    if(name != "library") {
        apply(plugin = "com.android.application")
        apply(plugin = "org.jetbrains.kotlin.plugin.compose")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
        apply(plugin = "com.google.devtools.ksp")

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

                //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                release {
                    isMinifyEnabled = true
                    signingConfig = signingConfigs.getByName("debug")
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt")
                    )
                }
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-DEBUG" // Helpful to see in App Info
                }
            }
        }

        dependencies {
            "implementation"(project(":library"))
        }
    } else {
        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.plugin.compose")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

        configure<com.android.build.api.dsl.LibraryExtension> {
            buildFeatures {
                compose = true
            }

            namespace = "com.vayunmathur.$name"
            compileSdk {
                version = release(36)
            }

            defaultConfig {
                minSdk = 31
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }


    pluginManager.withPlugin("com.google.devtools.ksp") {
        extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        // AndroidX Core & Lifecycle
        "implementation"(libs.findLibrary("okio").get())
        "implementation"(libs.findLibrary("kotlinx-datetime").get())
        "implementation"(libs.findLibrary("androidx-core-ktx").get())
        "implementation"(libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
        "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
        "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-ktx").get())
        "implementation"(libs.findLibrary("androidx-activity-compose").get())

        // Compose UI (BOM Managed)
        val composeBom = libs.findLibrary("androidx-compose-bom").get()
        "implementation"(platform(composeBom))

        "implementation"(libs.findLibrary("androidx-compose-ui").get())
        "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
        "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        "implementation"(libs.findLibrary("androidx-compose-material3").get())

        // Kotlin Serialization
        "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
    }
    dependencies {
//        "testImplementation"(libs.findLibrary("junit").get())
//        "androidTestImplementation"(libs.findLibrary("androidx.junit").get())
//        "androidTestImplementation"(libs.findLibrary("androidx.espresso.core").get())
//
//        // Handling the BOM
//        val composeBom = libs.findLibrary("androidx.compose.bom").get()
//        "androidTestImplementation"(platform(composeBom))
//
//        "androidTestImplementation"(libs.findLibrary("androidx.compose.ui.test.junit4").get())
        "debugImplementation"(libs.findLibrary("androidx.compose.ui.tooling").get())
        "debugImplementation"(libs.findLibrary("androidx.compose.ui.test.manifest").get())
    }
}