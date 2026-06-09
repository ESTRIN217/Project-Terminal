plugins {
    id("com.android.library")
    // Se elimina 'org.jetbrains.kotlin.android'. AGP 9.0+ gestiona Kotlin de forma nativa.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.estrin217.terminal.core"
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 37

    defaultConfig {
        minSdk = 26 // Android 8.0 mínimo para soporte PTY decente
        
        // Forzamos a que solo maneje arm64-v8a para optimizar tus compilaciones
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Configuración para cuando agregues tu propio código C (Fase 3)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Asegura que Gradle incluya jniLibs en el empaquetado del módulo
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Reemplazo de 'kotlinOptions' por la sintaxis moderna con Lazy Properties compatible con JDK 25
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":terminal-emulator"))
    api(project(":terminal-view"))
    
    // Corregido para usar tu catálogo de versiones centralizado en lugar de harcodear la versión vieja
    implementation(libs.androidx.core.ktx)
    
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    
    testImplementation(libs.junit)
}
