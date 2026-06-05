plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estrin217.terminal.core"
    compileSdk = 36

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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Aquí importarás las dependencias de Termux (terminal-view y terminal-emulator)
    // mediante JitPack o clonando sus clases directamente a este módulo.
    implementation("androidx.core:core-ktx:1.13.1")
}