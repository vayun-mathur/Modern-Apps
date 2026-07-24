plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "description"
}

android {
    defaultConfig {
        versionCode = 20260723
        versionName = "v2.6.1"
        applicationId = "com.vayunmathur.office"
    }
    // Ship the real sample documents (metadata_data/assets) inside the instrumented
    // test APK so the screenshot generator can open them via ACTION_VIEW on device.
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("metadata_data/assets").absolutePath)
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation("junit:junit:4.13.2")
    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
