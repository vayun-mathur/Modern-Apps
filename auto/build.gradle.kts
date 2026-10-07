plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}
launcherIcon {
    symbol = "directions_car"
}
android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.auto"
    }
}
dependencies {
    // The GAL wire format. Nothing in the app re-implements it, and keeping it out of here is
    // what lets the handshake be tested on the JVM.
    implementation(project(":auto:protocol"))
    // Car template rendering (HostTemplate model + parsers + Compose renderers) lives in
    // :library:carhost so apps can render their own car views in screenshot tests without
    // depending on this app. This module supplies the real TextureView map island.
    implementation(project(":library:carhost"))
    // Media3 controller for the Phase 4 now-playing feed from the on-device media session.
    implementation(libs.androidx.media3.session)
    // Car App Library host: CarAppHost binds the :maps car-app service and
    // implements the host binders (ICarHost/IAppHost/INavigationHost/
    // IConstraintHost) so the nav card renders whatever Maps publishes --
    // its own SurfaceContainer surface plus its NavigationTemplate -- instead
    // of MA Auto re-rendering a second map. Tracks the carApp catalog version.
    implementation(libs.androidx.car.app)
    // Compose-in-Presentation: the car display owns its own LifecycleOwner +
    // ViewModelStoreOwner + SavedStateRegistryOwner (see CarDisplayLifecycle),
    // so the Presentation hosts a ComposeView on its private virtual display.
    // Compose UI itself rides the BOM through :library:ui; these are the only
    // direct additions, scoped to this module like :library:map's own
    // lifecycle-runtime-compose (never in the shared convention plugin).
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
}
