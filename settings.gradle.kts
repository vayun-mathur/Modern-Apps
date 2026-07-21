pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "apps"
include(":pjsip")
include(":library")
include(":library:network")
include(":library:biometric")
include(":library:downloadservice")
include(":library:ui")
include(":library:room")
include(":library:ink")
include(":library:e2ee-p2p")
include(":library:widgets")
include(":library:work")
include(":library:ocr")
include(":calendar")
include(":contacts")
include(":pdf")
include(":openassistant")
include(":passwords")
include(":notes")
include(":files")
include(":photos")
include(":health")
include(":youpipe")
include(":findfamily")
include(":maps")
include(":games:chess")
include(":games:unblockjam")
include(":games:wordmaker")
include(":music")
include(":clock")
include(":games:alchemist")
include(":games:pipes")
include(":games:solitaire")
include(":email")
include(":weather")
include(":messages")
include(":whatsapp-signal")
include(":camera")
include(":sdk:openassistant")
include(":sdk:games")
include(":things")
include(":office")
include(":tools:holidaygen")
include(":education")
include(":everysync")
include(":travel")

if (file("dooraccess").exists()) {
    include(":dooraccess")
}
include(":netcapture")
