plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    // SqlCipherDbCodec implements the DbBackupCodec interface from :library, so
    // this edge stays: both modules are permission-free (documented in #884).
    implementation(project(":library"))

    // room + SQLCipher. The 2 MB libsqlcipher.so lives here, so only apps that
    // depend on :library:room bundle it. sqlcipher is exposed via `api` because the
    // inline `buildDatabase` body references SQLCipherDriver at each app
    // call site, so it must be on the consuming app's compile classpath.
    implementRoom(libs)
    // The test database's generated impl, for repository/buildDatabase tests.
    add("kspTest", libs.androidx.room3.compiler)
    // Same for the device tests, which open real SQLCipher databases.
    add("kspAndroidTest", libs.androidx.room3.compiler)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    api(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
}
