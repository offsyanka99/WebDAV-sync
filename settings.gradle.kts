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
        // sardine-android is published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WebDAV-sync"
include(":app")
