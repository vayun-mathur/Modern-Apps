plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:web:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "language"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.web"
    }
}

androidComponents {
    onVariants { variant ->
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
    }
}

// Brave Shields engine (Rust + adblock-rust). See web/src/main/rust/.
rustNativeLib("web_shields", "shields")

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.brotli.dec) // shields filter lists ship brotli-compressed
    implementation(project(":library:image"))
    // Browser must allow all certs (any host + corp proxies via user CAs) — SYSTEM permissive, documents intent.
    implementation(project(":library:network"))
}
