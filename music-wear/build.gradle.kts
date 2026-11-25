plugins {
    id("common-conventions-wear")
}

launcherIcon {
    symbol = "music_note"
}

// Version shared with the phone app (root version.txt).
val wearVersionLines = file("../version.txt").readLines()
val wearVersionCode = wearVersionLines[0].trim().toInt()
val wearVersionName = wearVersionLines[1].trim()

android {
    // WearLink handshake requires same package + signing as the phone app.
    namespace = "com.vayunmathur.music.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.music"
        minSdk = 31
        versionCode = wearVersionCode
        versionName = wearVersionName
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
