dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // Points to the .toml file in your actual project root
            from(files("../gradle/libs.versions.toml"))
        }
    }
}