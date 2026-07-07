import com.android.build.api.dsl.ApplicationExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.provider.ListProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Wires up on-device app-store screenshot generation.
//
// Apply this ALONGSIDE `common-conventions-app` on any app module that has a
// screenshot generator under `src/androidTest`. It registers a `metadata` task:
//
//     ./gradlew :clock:metadata
//
// which installs the app + its instrumented screenshot generator on a connected
// emulator/device, runs it, and pulls the resulting PNGs into
// `metadata_data/photos/<module-key>/` (where release.sh picks them up).
//
// Apps that need permissions/appops granted before launch (so first-run system
// prompts don't hijack the screenshots) declare them in their build.gradle.kts:
//
//     metadataScreenshots {
//         permissions.add("android.permission.POST_NOTIFICATIONS")
//         appops.addAll("SCHEDULE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT")
//     }
//
// Modules without a `src/androidTest` screenshot generator simply won't produce
// any images, so applying this plugin is harmless.

interface MetadataScreenshotsExtension {
    /** Runtime permissions to `pm grant` before launch (e.g. "android.permission.READ_CONTACTS"). */
    val permissions: ListProperty<String>
    /** App-ops to `appops set <op> allow` before launch (e.g. "MANAGE_MEDIA"). */
    val appops: ListProperty<String>
}

// A shared, build-wide service capped at one concurrent use. The metadata task
// declares it so that `./gradlew :a:metadata :b:metadata` never runs two on the
// single connected emulator at once (they contend for the device and the global
// `cmd uimode night` setting), even though org.gradle.parallel=true.
abstract class MetadataScreenshotLock : BuildService<BuildServiceParameters.None>

val metadataLock = gradle.sharedServices.registerIfAbsent(
    "metadataScreenshotLock", MetadataScreenshotLock::class.java
) {
    maxParallelUsages.set(1)
}

val metadataExt = extensions.create("metadataScreenshots", MetadataScreenshotsExtension::class.java).apply {
    permissions.convention(emptyList())
    appops.convention(emptyList())
}

val libs = the<LibrariesForLibs>()

configure<ApplicationExtension> {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    add("androidTestImplementation", libs.junit)
    add("androidTestImplementation", libs.androidx.test.runner)
    add("androidTestImplementation", libs.androidx.test.ext.junit)
    add("androidTestImplementation", platform(libs.androidx.compose.bom))
    add("androidTestImplementation", libs.androidx.compose.ui.test.junit4)
    add("debugImplementation", libs.androidx.compose.ui.test.manifest)
}

val moduleKey = path.removePrefix(":").replace(":", "-")
val screenshotsOut = File(rootDir, "metadata_data/photos/$moduleKey")

fun resolveAdb(): String {
    val localProps = File(rootDir, "local.properties")
    val sdkDir = if (localProps.exists()) {
        java.util.Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else null
    val sdk = sdkDir
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    return File(sdk, "platform-tools/adb").absolutePath
}

val metadataTask = tasks.register<Exec>("metadata") {
    group = "metadata"
    description = "Generate Play/F-Droid screenshots on a connected emulator and copy them into metadata_data/photos/$moduleKey"
    dependsOn("installDebug", "installDebugAndroidTest")
    // Serialize across modules: only one metadata run touches the emulator at a time.
    usesService(metadataLock)
    // Placeholder; the real command is assembled in afterEvaluate below, once the
    // applicationId is known, so the task only holds plain serializable strings
    // (required for the configuration cache).
    commandLine("true")
}

// Everything is resolved at configuration time into plain Strings so the Exec
// task stays configuration-cache compatible (no project/extension access at
// execution time). To target a specific emulator without touching other
// connected devices (e.g. a physical phone), export ANDROID_SERIAL before
// running -- both AGP's install tasks and adb honour it.
afterEvaluate {
    val appId = the<ApplicationExtension>().defaultConfig.applicationId
        ?: error("applicationId is not set for $path; the metadata task needs it to locate the app on device")
    val adb = resolveAdb()
    val runner = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
    val deviceDir = "/sdcard/Android/data/$appId/files/metadata_screenshots"
    val out = screenshotsOut.absolutePath

    // Grant the declared permissions/appops before launch (so first-run system
    // prompts don't hijack the screenshots), switch the device to night mode so
    // the app's DynamicTheme renders dark, run the generator, pull the PNGs, then
    // restore the device to light. pm clear gives every run a clean slate.
    val lines = mutableListOf("set -e")
    lines += """"$adb" shell pm clear $appId || true"""
    metadataExt.permissions.get().forEach {
        lines += """"$adb" shell pm grant $appId $it || true"""
    }
    metadataExt.appops.get().forEach {
        lines += """"$adb" shell appops set $appId $it allow || true"""
    }
    lines += """"$adb" shell cmd uimode night yes || true"""
    lines += """"$adb" shell rm -rf "$deviceDir" || true"""
    lines += """"$adb" shell am instrument -w "$runner""""
    lines += """rm -rf "$out""""
    lines += """mkdir -p "$out""""
    lines += """"$adb" pull "$deviceDir/." "$out""""
    lines += """"$adb" shell cmd uimode night no || true"""
    lines += """echo "Metadata screenshots written to $out""""
    val script = lines.joinToString("\n")

    metadataTask.configure {
        commandLine("bash", "-c", script)
    }
}
