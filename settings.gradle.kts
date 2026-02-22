pluginManagement {
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
include(":library")
include(":calendar")
include(":contacts")
include(":pdf")
include(":crypto")
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
