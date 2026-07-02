plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.office"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
