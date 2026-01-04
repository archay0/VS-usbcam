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

dependencyResolutionManagement {
    // Allow individual projects to declare their own repositories
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repository.liferay.com/nexus/content/repositories/public/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "VideoShuffle"
include(":app")
