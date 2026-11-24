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
rootProject.name = "nodroid"
include(":app")
include(":core-model")
include(":core-engine")
include(":core-database")
include(":core-security")
include(":core-nodes")
include(":feature-canvas")
include(":feature-runtime")
