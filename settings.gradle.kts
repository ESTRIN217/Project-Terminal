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
    }
}

rootProject.name = "Terminal"
include(":app")
include(":terminal_core")

include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("terminal_core/terminal-emulator")

include(":terminal-view")
project(":terminal-view").projectDir = file("terminal_core/terminal-view")