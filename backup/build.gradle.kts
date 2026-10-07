plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:backup:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "backup"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.backup"
    }
}

dependencies {
    // Compile-only stubs for the framework's @SystemApi app-data backup transport
    // classes (android.app.backup.BackupTransport + RestoreDescription/RestoreSet).
    // Provided by the framework at runtime on a system image; must NOT be packaged.
    compileOnly(project(":library:backup-stubs"))
    // WebDAV/Nextcloud remote reaches the server over the platform HTTP stack.
    implementation(project(":library:network"))
    // Scheduled file/media backups.
    implementation(project(":library:work"))
    // Optional biometric gate for the recovery code / master key.
    implementation(project(":library:biometric"))
    // AES-256-GCM segment crypto (aesEncrypt/aesDecrypt, newContentKey).
    implementation(project(":library:e2ee-p2p"))
    // Local config (backend uri/creds handle, enabled sets, last-run).
    implementation(libs.androidx.datastore.preferences)
}
