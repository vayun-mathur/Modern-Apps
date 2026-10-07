plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "system_update"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.updater"
    }
}

dependencies {
    // WorkManager for the periodic check. The install itself is a foreground service, not a
    // worker - see UpdateInstallService for why.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    // Compile-only stubs for @SystemApi android.os.UpdateEngine / UpdateEngineCallback — the
    // A/B slot writer. Present at runtime on a MAOS image because this app is privileged;
    // absent from the public SDK, and must NOT be packaged. See :library:updateengine-stubs.
    compileOnly(project(":library:updateengine-stubs"))
}
