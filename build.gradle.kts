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

val proguardFile = File(projectDir, "proguard-rules.pro")

val (appVersionCode, appVersionName) = readVersionInfo()

subprojects {
    if(name == "app") return@subprojects

    // Apply the common conventions plugin to all subprojects

    if(name != "library") {
        //apply(plugin = "common-conventions-app")
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
                    if (signingConfigs.findByName("release") != null) {
                        signingConfig = signingConfigs.getByName("release")
                    }
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"), proguardFile.absolutePath,
                    )
                }
            }
        }

        dependencies {
            "implementation"(project(":library"))
        }
    } else {
        //apply(plugin = "common-conventions-library")
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
}
