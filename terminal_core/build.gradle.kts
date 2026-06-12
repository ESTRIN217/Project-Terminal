plugins {
    id("com.android.library")
    // Se elimina 'org.jetbrains.kotlin.android'. AGP 9.0+ gestiona Kotlin de forma nativa.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.estrin217.terminal.core"
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 36
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: (project.findProperty("ndkVersion") as? String ?: "")
    
    defaultConfig {
        minSdk = 26 // Android 8.0 mínimo para soporte PTY decente
        
        // Solo 64-bit: arm64-v8a (dispositivos físicos) y x86_64 (emulador)
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
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
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    
    testImplementation(libs.junit)
}
