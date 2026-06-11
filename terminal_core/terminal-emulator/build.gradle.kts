plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"

    // Se convierte el property de texto a Integer de forma segura para Kotlin DSL
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 36
    
    // Lectura del entorno del sistema con fallback al fallback del property
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: (project.findProperty("ndkVersion") as? String ?: "")

    defaultConfig {
        minSdk = (project.findProperty("minSdkVersion") as? String)?.toInt() ?: 26
        
        externalNativeBuild {
            // Se reemplaza ndkBuild por cmake
            cmake {
                // Puedes pasar banderas específicas aquí si lo requieres en el futuro
                cppFlags("") 
            }
        }

        ndk {
            abiFilters.addAll(listOf("x86_64", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        // Se apunta al nuevo archivo CMakeLists.txt en la raíz
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1" // Versión estándar recomendada por el SDK de Android
        }
    }

    compileOptions {
        // Elevado a Java 17 para asegurar compatibilidad nativa con tu entorno de JDK 25
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Configuración moderna de tareas en Kotlin DSL
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
