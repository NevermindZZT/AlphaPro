pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AlphaPro"
include(":app")
include(":core:bluetooth")
include(":core:location")
include(":core:ptp")
include(":core:heif")
include(":core:ftp")
