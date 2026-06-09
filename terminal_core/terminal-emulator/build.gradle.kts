plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.termux.emulator"

    // Se convierte el property de texto a Integer de forma segura para Kotlin DSL
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 35
    
    // Lectura del entorno del sistema con fallback al fallback del property
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: (project.findProperty("ndkVersion") as? String ?: "")

    defaultConfig {
        minSdk = (project.findProperty("minSdkVersion") as? String)?.toInt() ?: 26
        //targetSdk = (project.findProperty("targetSdkVersion") as? String)?.toInt() ?: 35

        externalNativeBuild {
            ndkBuild {
                cFlags("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }

        ndk {
            abiFilters.addAll(listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
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

    publishing {
        multipleVariants {
            withSourcesJar()
            withJavadocJar()
            allVariants()
        }
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

// Declaración tipada de la tarea sourceJar
val sourceJar by tasks.registering(Jar::class) {
    from(android.sourceSets.getByName("main").java.srcDirs)
    archiveClassifier.set("sources")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName("default"))
                groupId = "com.termux"
                artifactId = "terminal-emulator"
                version = "0.118.0"
                artifact(sourceJar.get())
            }
        }
    }
}
