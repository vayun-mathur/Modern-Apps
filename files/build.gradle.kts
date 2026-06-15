plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260615
        versionName = "v2.5.4"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
