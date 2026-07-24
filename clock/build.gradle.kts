plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "schedule"
}

android {
    defaultConfig {
        versionCode = 20260723
        versionName = "v2.6.1"
        applicationId = "com.vayunmathur.clock"
    }
}

metadataScreenshots {
    permissions.add("android.permission.POST_NOTIFICATIONS")
    appops.addAll("SCHEDULE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT")
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
}
