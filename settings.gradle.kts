pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Deterministic R8 (reproducible release APKs, see gradle.properties
// android.r8.threadPoolSize): R8 9.5 races its worker threads while recording
// argument-type-strengthened signatures, so its output (method protos in
// classes.dex and the mapping hash embedded as the map id) depends on thread
// timing, CPU count and how many R8 tasks share AGP's in-process thread pool.
// Running each R8 task in its own worker process gives it its own pool, which
// gradle.properties sizes to one thread: every APK is then byte-identical on
// any machine (F-Droid's builder included) while tasks still run in parallel
// across apps (android.r8.maxWorkers). The version must equal `agp` in
// gradle/libs.versions.toml (settings plugins cannot read the catalog).
plugins {
    id("com.android.settings") version "9.5.0-alpha09"
}

android {
    execution {
        profiles {
            create("deterministicR8") {
                r8 {
                    runInSeparateProcess = true
                    jvmOptions += listOf(
                        "-Xmx3g", "-XX:+UseParallelGC", "-Dfile.encoding=UTF-8",
                        "-XX:ActiveProcessorCount=1",
                        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=1",
                        "-Dcom.android.tools.r8.deterministicdebugging=true",
                    )
                }
            }
        }
        defaultProfile = "deterministicR8"
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RingRTC is published by Signal, not to Maven Central. Scoped to org.signal so nothing else
        // resolves through it.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "apps"
include(":lint-rules")
include(":library")
include(":library:network")
include(":library:biometric")
include(":library:downloadservice")
include(":library:ui")
include(":library:room")
include(":library:ink")
include(":library:e2ee-p2p")
include(":library:e2ee-openpgp")
include(":library:everysync-core")
include(":library:widgets")
include(":library:work")
include(":library:ocr")
include(":library:map")
include(":library:ml")
include(":library:avatar")
include(":library:image")
include(":library:media")
include(":library:log")
include(":library:carhost")
include(":calendar")
include(":contacts")
include(":pdf")
include(":openassistant")
include(":office")
include(":passwords")
include(":notes")
include(":flashcards")
include(":files")
include(":photos")
include(":health")
include(":youpipe")
include(":youpipe:extractor")
include(":uniflix")
include(":findfamily")
include(":maps")
include(":games:chess")
include(":games:unblockjam")
include(":games:wordmaker")
include(":music")
include(":clock")
include(":games:pipes")
include(":games:solitaire")
include(":games:logicgate")
include(":games:voxels")
include(":games:sudoku")
include(":games:minesweeper")
include(":games:nonogram")
include(":games:arrows")
include(":games:kart")
include(":games:hub")
include(":email")
include(":securemail")
include(":weather")
include(":camera")
include(":sdk:openassistant")
include(":sdk:games")
include(":sdk:cast")
include(":sdk:controller")
include(":things")
include(":todo")
include(":education")
include(":everysync")
include(":travel")
include(":astronomy")
include(":translate")
include(":calculator")
include(":code")
include(":keyboard")
include(":speech")
include(":vpn")
include(":web")
include(":appstore")
include(":library:locationprovider")
include(":library:aosp-stubs")
include(":library:tv")
include(":networklocation")
include(":fooddelivery")
include(":measure")
include(":taxi")
include(":communicate")
include(":euicc")
include(":globalesim")
include(":backup")
include(":shop")
include(":share")
include(":launcher")
include(":cast")
include(":cast:protocol")
include(":cast:tv")
include(":tuner")
include(":setupwizard")
include(":logviewer")
include(":updater")
include(":parentalcontrols")
include(":screentime")
include(":emergency")
include(":auto")
include(":auto:protocol")

// Personal / private app modules live under personal/ (gitignored). Included only
// when present so the public repo still configures without them.
if (file("personal/dooraccess").exists()) {
    include(":personal:dooraccess")
}
if (file("personal/amazon").exists()) {
    include(":personal:amazon")
}
if (file("personal/kaiser").exists()) {
    include(":personal:kaiser")
}
if (file("personal/uniflix_strem").exists()) {
    include(":personal:uniflix_strem")
}

// Desktop JVM shells (Phase 2): host-side sync over the shared core.
include(":desktop:notes")
include(":desktop:passwords")
