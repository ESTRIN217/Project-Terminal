plugins {
    alias(libs.plugins.android.application)
    // El plugin 'jetbrains.kotlin.android' ya no se declara aquí de forma explícita.
    // Con AGP 9.0+, el soporte para Kotlin está integrado de manera nativa (built-in).
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.estrin217.terminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.estrin217.terminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        // Obligatorio para entornos JDK 25: forzar bytecode compatible con la máquina virtual de Android.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Reemplazo moderno de 'kotlinOptions': Ahora se configura de forma global a nivel de bloque kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":terminal_core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Compose (Se gestionan de manera óptima mediante el plugin 'kotlin-compose' aplicado arriba)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
