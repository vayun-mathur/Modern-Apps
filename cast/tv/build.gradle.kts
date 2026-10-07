plugins {
    id("common-conventions-app")
    // androidx.tv, so this app is built on a design system that knows what a D-pad is. Named here
    // *alongside* the app convention rather than instead of it, even though it applies that itself:
    // the type-safe `launcherIcon` and `nativeAbis` accessors below are generated from the plugins this
    // file applies directly.
    id("common-conventions-tv")
    // No preview-metadata convention. Every screen here is either a full-screen decoder surface or
    // a TV-shaped idle screen, and Layoutlib can render neither into a *phone* listing image -
    // release.sh writes previews into a hardcoded graphics/phone-screenshots. `camera` and
    // `games/voxels` set the precedent for opting out. metadata_data/cast-tv.md still exists, which
    // is what makes release.sh generate a listing at all.
}

launcherIcon {
    symbol = "cast"
}

// Google TV hardware (the onn 4K Streaming Box and friends) runs a 32-bit userspace and
// reports only `armeabi-v7a,armeabi`, so an arm64-only APK is rejected at install time.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.cast.tv"
    }
}

dependencies {
    // The wire format, shared verbatim with the phone. Nothing in this app re-implements it.
    implementation(project(":cast:protocol"))
    implementation(project(":library:e2ee-p2p"))
    // App content arrives as media rather than as pixels now, so the TV needs a player. Not
    // `media3-session`: the TV already has a remote-control path through its own control channel,
    // and a MediaSession would be a second one competing with it.
    implementation(libs.androidx.media3.exoplayer)
}
