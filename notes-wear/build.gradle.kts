plugins {
    id("common-conventions-wear")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "subject"
}

// Version shared with the phone app (root version.txt), read directly because
// common-conventions-wear inlines 1/"1.0" until a wear consumer wires the shared reader.
val wearVersionLines = file("../version.txt").readLines()
val wearVersionCode = wearVersionLines[0].trim().toInt()
val wearVersionName = wearVersionLines[1].trim()

android {
    // The WearLink handshake only connects apps that share a package name and
    // signing certificate, so this module MUST ship as the phone app's package:
    // same applicationId (below) and the same signing config the convention
    // plugins already apply to both (debug key for dev, release key for
    // release). The namespace only names this module's own R/resources.
    namespace = "com.vayunmathur.notes.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.notes"
        // Convention defaults to 30, but :library:log (pulled in by the wear
        // convention) requires 31, so the watch floor is 31.
        minSdk = 31
        versionCode = wearVersionCode
        versionName = wearVersionName
    }
}

dependencies {
    // Watch side of the Wear OS sync (see domain/WearNotesProtocol.kt, the
    // copy of the phone protocol it must stay in step with).
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
