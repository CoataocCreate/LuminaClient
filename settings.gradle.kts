@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        mavenLocal()

        maven("https://repo.opencollab.dev/maven-snapshots")
        maven("https://repo.opencollab.dev/maven-releases")
        maven("https://jitpack.io")
    }
}

rootProject.name = "Lumina v4"

include(
    ":app",

    ":Lunaris",
    ":Pixie",
    ":SSC",
    ":TablerIcons",

    ":Protocol:bedrock-codec",
    ":Protocol:bedrock-connection",
    ":Protocol:common",
    ":Protocol:adventure",

    ":Network:codec-query",
    ":Network:codec-rcon",
    ":Network:transport-raknet",

    ":minecraft-msftauth",
    ":lunarisrpc",
    ":animatedux"
)