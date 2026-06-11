plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    
    // Configuración segura de propiedades para Kotlin DSL
    compileSdk = (project.findProperty("compileSdkVersion") as? String)?.toInt() ?: 36

    defaultConfig {
        minSdk = (project.findProperty("minSdkVersion") as? String)?.toInt() ?: 26
        //targetSdk = (project.findProperty("targetSdkVersion") as? String)?.toInt() ?: 35
        
        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Subelevado a Java 17 para evitar conflictos de bytecode en tu entorno JDK 25
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


}

// Bloque de dependencias unificado en la raíz del script
dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    
    // Enlace directo al módulo que acabamos de migrar
    api(project(":terminal-emulator"))
    
    testImplementation("junit:junit:4.13.2")
}
