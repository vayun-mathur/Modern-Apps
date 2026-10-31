plugins {
    id("common-conventions-library")
}

dependencies {
    // Permission-free by design: no project deps. HTTP(S) image fetching is
    // opted into per call site via ImageLoader.Builder.remote(), with the fetch
    // function built from NetworkClient in the consuming app — so this module
    // declares no INTERNET and offline consumers gain no network permission
    // from it (#884).

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // SVG rendering is now internal via Android stdlib (Canvas/Path/Paint/XmlPullParser) – no third-party

    // Foundation needed for Image composable in AsyncImage
    implementation(libs.androidx.compose.foundation)
}
