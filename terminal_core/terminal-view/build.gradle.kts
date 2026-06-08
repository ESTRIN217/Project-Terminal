plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.termux.view"
    
    // Configuración segura de propiedades para Kotlin DSL
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 35

    defaultConfig {
        minSdk = (project.findProperty("minSdkVersion") as? String)?.toInt() ?: 26
        targetSdk = (project.findProperty("targetSdkVersion") as? String)?.toInt() ?: 35
        
        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Subelevado a Java 17 para evitar conflictos de bytecode en tu entorno JDK 25
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        multipleVariants {
            withSourcesJar()
            withJavadocJar()
            allVariants()
        }
    }
}

// Bloque de dependencias unificado en la raíz del script
dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    
    // Enlace directo al módulo que acabamos de migrar
    api(project(":terminal-emulator"))
    
    testImplementation("junit:junit:4.13.2")
}

// Registro perezoso y fuertemente tipado de la tarea para generar el JAR de fuentes
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
                artifactId = "terminal-view"
                version = "0.118.0"
                artifact(sourceJar.get())
            }
        }
    }
}
