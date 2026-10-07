plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "key_vertical"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
    testOptions {
        // CtapProcessor logs through android.util.Log, which is a stub that throws in JVM unit
        // tests. Returning defaults lets the CTAP logic be tested without stripping the logging
        // that makes caBLE sessions diagnosable on a device.
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native KDBX (KeePass) read/write (Rust `keepass` crate). See passwords/src/main/rust/.
// Sole KDBX implementation (replaced keepassjava2 + Bouncy Castle, both now removed);
// existing .kdbx vaults stay interoperable.
rustNativeLib("passwords_kdbx", "passwords-kdbx")

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials.lib)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.work.runtime.ktx)
    // Own WebSocketClient via :library:network – no Ktor
    implementation(project(":library:network"))
}
