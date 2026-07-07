plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260707
        versionName = "v2.5.7b"
        applicationId = "com.vayunmathur.clock"
    }
}

metadataScreenshots {
    permissions.add("android.permission.POST_NOTIFICATIONS")
    appops.addAll("SCHEDULE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT")
}

dependencies {
    implementRoom(libs)
}
