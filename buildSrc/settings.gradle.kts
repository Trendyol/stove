rootProject.name = "buildSrc"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs").from(files("../gradle/libs.versions.toml"))
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
