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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://download.linphone.org/maven_repository/")
    }
}

rootProject.name = "StringTelephone"

// Include only modules whose directories exist, so server-only Docker
// builds (which copy just core/ and server/api/) configure cleanly.
if (file("app/build.gradle.kts").exists()) include(":app")
include(":core")
if (file("server/api/build.gradle.kts").exists()) include(":server:api")
