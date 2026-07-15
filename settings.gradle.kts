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
        // 如果需要 Snapshot
        // maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

rootProject.name = "BeHoly"
include(":app")
