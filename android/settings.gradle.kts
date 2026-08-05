pluginManagement {
    repositories {
        providers.gradleProperty("meetingnotesGoogleMavenMirror")
            .orElse(providers.environmentVariable("MEETINGNOTES_GOOGLE_MAVEN_MIRROR"))
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { maven(url = it) }
        providers.gradleProperty("meetingnotesCentralMavenMirror")
            .orElse(providers.environmentVariable("MEETINGNOTES_CENTRAL_MAVEN_MIRROR"))
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { maven(url = it) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("meetingnotesGoogleMavenMirror")
            .orElse(providers.environmentVariable("MEETINGNOTES_GOOGLE_MAVEN_MIRROR"))
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { maven(url = it) }
        providers.gradleProperty("meetingnotesCentralMavenMirror")
            .orElse(providers.environmentVariable("MEETINGNOTES_CENTRAL_MAVEN_MIRROR"))
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { maven(url = it) }
        google()
        mavenCentral()
    }
}

rootProject.name = "OAAutomation"
include(":app")
