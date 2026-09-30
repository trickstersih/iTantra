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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "itantra"
include(":app")
include(":core-domain")
include(":core-platform-api")
include(":core-protocol")
include(":platform-android")
include(":engine-discovery")
include(":engine-mesh")
include(":engine-speech")
include(":feature-ptt")
include(":feature-emergency")

 