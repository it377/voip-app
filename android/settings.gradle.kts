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
        // The Linphone SDK is not on Maven Central - it's served from Linphone's own
        // Maven repo. Both of these hostnames appear in Linphone's docs and sample
        // projects, so declare both and let Gradle use whichever is live.
        maven { url = uri("https://download.linphone.org/maven_repository") }
        maven { url = uri("https://linphone.org/maven_repository") }
    }
}

rootProject.name = "UCM Telnyx"
include(":app")
