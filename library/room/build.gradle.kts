plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":library"))

    // room + SQLCipher. The 2 MB libsqlcipher.so lives here, so only apps that
    // depend on :library:room bundle it. sqlcipher is exposed via `api` because the
    // inline `buildDatabase` body references SupportOpenHelperFactory at each app
    // call site, so it must be on the consuming app's compile classpath.
    implementRoom(libs)
    api(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
}
