plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))

    // Expose Material 3 to consumers via `api` so the same-named wrappers/aliases in this
    // module compile in app code. Apps depend only on `:library:ui` and must not import
    // `androidx.compose.material*` directly (enforced by convention + repo grep check).
    api(libs.androidx.compose.material3)
    // MaFragmentActivity extends FragmentActivity, so the fragment classes must resolve
    // on every app's compile classpath. R8 strips them from APKs whose app never touches
    // a fragment (only passwords/photos keep them, and they already ship fragment anyway).
    api(libs.androidx.fragment.ktx)
    // NavigationSuiteScaffold / currentWindowAdaptiveInfo used (via the facade) by openassistant.
    api(libs.androidx.compose.material3.adaptive.navigation.suite)
    // V2 adaptive info (currentWindowAdaptiveInfoV2) used by the facade in MaterialFunctions.kt.
    // implementation (not api): app modules must use the facade, not name adaptive types directly.
    implementation(libs.androidx.compose.adaptive.navigation3)
    // Used only internally (editor toolbars); not re-exported to apps.
    implementation(libs.androidx.compose.material.icons.extended)
    // Health Connect client for the consolidated permission gate's async grant probe.
    implementation(libs.androidx.connect.client)
    // XmlPull impl for JVM unit tests (LatexExporter's MathML parser; same as :office).
    testImplementation(libs.kxml2)

}
