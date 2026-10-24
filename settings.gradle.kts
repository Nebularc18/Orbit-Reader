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
        maven {
            url = uri("https://maven.ghostscript.com")
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}

rootProject.name = "OrbitReader"

include(":app")
