plugins {
    id("common-conventions-library")
}

// No project dependencies by design: SecureResultReceiver is owned by this
// module so the SDK stays free of the base :library edge (#884).
dependencies {
}
