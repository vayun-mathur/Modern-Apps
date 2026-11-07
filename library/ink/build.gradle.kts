plugins {
    id("common-conventions-library")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Ink. libink.so / libgraphics-core.so (~1.5 MB) live here, so only apps that
    // depend on :library:ink bundle them. Exposed via `api` because consumers
    // (notes, photos) reference androidx.ink types (Stroke, Brush, ...) directly.
    api(libs.androidx.ink.authoring)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.geometry)
    api(libs.androidx.ink.strokes)
    api(libs.androidx.ink.rendering)

    // On-device suite: Brush/Stroke load libink.so, which has no host build, so the
    // canvas and serialization round-trips can only run on a device/emulator.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
