// Top-level build file donde añades configuraciones globales
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // Limita el compilador a un solo hilo para no saturar la CPU y la RAM
        freeCompilerArgs.add("-Xbackend-threads=1")
    }
}
