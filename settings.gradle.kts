pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketRDP"

include(":app")
include(":feature-session")
include(":feature-connections")
include(":core-rdp")
include(":core-data")
include(":core-ui")

// FreeRDP's Android library lives at third_party/FreeRDP (git submodule). We do NOT include
// it as a Gradle subproject because freeRDPCore pulls in androidx.appcompat / room 2.8.4 /
// sqlcipher / preference / recyclerview which conflict with our Compose stack. Instead, the
// :core-rdp module compiles the FreeRDP JNI bridge directly from those source files (see
// :core-rdp/build.gradle.kts and the cpp source roots wired up there).
