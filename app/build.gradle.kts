plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aaria.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aaria.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"${project.findProperty("PICOVOICE_ACCESS_KEY") ?: ""}\"")

        // Only build for arm64 devices; excludes x86 emulator slices to keep APK lean.
        // Remove this filter if you need emulator support.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("ai.picovoice:porcupine-android:3.0.2")

    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // ML Kit language identification — bundled model (~900 KB), works fully offline
    implementation("com.google.mlkit:language-id:17.0.6")

    // sherpa-onnx offline STT (Whisper Base on-device).
    // Download sherpa-onnx-<version>.aar from
    //   https://huggingface.co/csukuangfj/sherpa-onnx-libs/tree/main/android/aar
    // and place it as app/libs/sherpa-onnx.aar before building.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
