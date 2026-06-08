pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Repositorio requerido si tus plugins o herramientas nativas dependen de integraciones de Git
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Obligatorio aquí para que módulos como ':terminal-emulator' resuelvan dependencias externas de Termux
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Terminal"

// Inclusión de la estructura principal de la aplicación
include(":app")
include(":terminal_core")

// Vinculación de submódulos anidados bajo la arquitectura de carpetas
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("terminal_core/terminal-emulator")

include(":terminal-view")
project(":terminal-view").projectDir = file("terminal_core/terminal-view")
