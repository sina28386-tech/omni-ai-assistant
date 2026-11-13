pluginManagement {
    repositories {
        // Mirror first: dl.google.com returns 404 for all Maven artifacts from this
        // network (verified 2026-10-19), so google() metadata alone cannot supply
        // the AARs. Tencent's public Nexus group proxies Google Maven, Maven Central
        // and the Gradle Plugin Portal.
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
    }
}

rootProject.name = "OmniAIAssistant"
include(":app")
include(":core")
