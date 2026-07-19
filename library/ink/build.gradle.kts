plugins {
    id("common-conventions-library")
}

dependencies {
    // Ink. libink.so / libgraphics-core.so (~1.5 MB) live here, so only apps that
    // depend on :library:ink bundle them. Exposed via `api` because consumers
    // (notes, photos) reference androidx.ink types (Stroke, Brush, ...) directly.
    api(libs.androidx.ink.authoring)
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.strokes)
    api(libs.androidx.ink.rendering)
    api(libs.androidx.ink.geometry)
}
