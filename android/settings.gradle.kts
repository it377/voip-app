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
        // Linphone's own repository, needed for the linphone-sdk-android artifact.
        maven { url = uri("https://linphone.org/maven_repository") }
    }
}

rootProject.name = "UCM Telnyx"
include(":app")
