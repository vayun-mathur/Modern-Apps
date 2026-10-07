plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "graphic_eq"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.musicbrainz"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:image"))
    implementation(project(":library:media"))
    // The vendored NewPipe fork. Only the JVM extractor is used - the SABR/PO-token
    // machinery that needs QuickJS and a WebView lives in :youpipe itself, and audio
    // resolution works without it.
    implementation(project(":youpipe:extractor"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementRoom(libs)
    implementation(project(":library:room"))
}
