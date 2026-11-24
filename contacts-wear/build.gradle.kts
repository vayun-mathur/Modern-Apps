plugins {
    id("common-conventions-wear")
}

launcherIcon {
    symbol = "person"
}

// Version shared with the phone app (root version.txt), read directly because
// common-conventions-wear inlines 1/"1.0" until a wear consumer wires the shared reader.
val wearVersionLines = file("../version.txt").readLines()
val wearVersionCode = wearVersionLines[0].trim().toInt()
val wearVersionName = wearVersionLines[1].trim()

android {
    // The convention derives a hyphenated namespace from the project path; pin the real one.
    namespace = "com.vayunmathur.contacts.wear"
    defaultConfig {
        // CRITICAL: Wearable Data Layer only connects same package + same signing.
        // This must equal :contacts' applicationId; dev builds on both sides use
        // the debug key, release builds on both sides use the same release key.
        applicationId = "com.vayunmathur.contacts"
        // Repo libraries (:library:log) are minSdk 31; the wear convention's 30 cannot merge.
        minSdk = 31
        versionCode = wearVersionCode
        versionName = wearVersionName
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
