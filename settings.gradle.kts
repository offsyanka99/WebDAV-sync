pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions JDK 21 for the compiler and unit tests. dav4jvm 5.0.0 is class-file 65.
    // App sources still emit JVM 17 bytecode.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // dav4jvm is published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WebDAV-sync"
include(":app")
