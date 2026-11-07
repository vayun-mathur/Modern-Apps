plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    // Exposed, not just used: LocalizedDateNames declares public extensions on
    // kotlinx-datetime's DateTimeFormatBuilder.WithTime (localizedAmPmMarker), so the
    // receiver type has to be part of this module's ABI for consumers to resolve them.
    api(libs.kotlinx.datetime)

    // navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.adaptive.navigation3)

    // room
    implementRoom(libs)

    // datastore
    implementation(libs.androidx.datastore.preferences)

    // GameHub SDK — GameHubReporter bridge
    implementation(project(":sdk:games"))

    // The unit tests' Room database (MatchingDao) needs its generated impl.
    add("kspTest", libs.androidx.room3.compiler)
}
