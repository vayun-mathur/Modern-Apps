plugins {
    id("common-conventions-library")
}

dependencies {
    // Headless contract + discovery + client/host only: no UI module here, so SDK
    // consumers gain no UI-library dependency (and no permissions) from it. The
    // Compose pad screen lives in the hosting app (:games:hub). Hkdf key
    // derivation and the E2EE handshake come from :library:e2ee-p2p (#884).
    implementation(project(":library:e2ee-p2p"))
    // Framework-class mocks (NsdManager/NsdServiceInfo) for discovery/advertiser tests.
    testImplementation(libs.mockito.core)
}
