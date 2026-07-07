plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.calendar"
    }
}

metadataScreenshots {
    permissions.addAll("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
}

dependencies {
    implementation(project(":library:widgets"))
}