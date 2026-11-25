plugins {
    id("common-conventions-wear")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "apps"
}

// Version shared with the phone app (root version.txt), as in notes-wear:
// common-conventions-wear inlines 1/"1.0" until a consumer wires the reader.
val wearVersionLines = file("../version.txt").readLines()
val wearVersionCode = wearVersionLines[0].trim().toInt()
val wearVersionName = wearVersionLines[1].trim()

android {
    // Same applicationId + signing as :launcher is load-bearing: WearLink's
    // handshake token requires the same signing certificate on both sides,
    // and the watch app must be installable alongside (not instead of) the
    // phone launcher package identity. The namespace only names resources.
    namespace = "com.vayunmathur.launcher.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.launcher"
        minSdk = 31
        versionCode = wearVersionCode
        versionName = wearVersionName
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
