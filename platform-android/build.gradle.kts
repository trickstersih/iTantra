plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.tactical.platform"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 28
        // targetSdk is not set on library modules (AGP ignores it there);
        // the app module's targetSdk governs runtime behavior.


    }

    buildFeatures {
        // No Compose/View binding needed here — this module is pure
        // hardware/model glue, no UI.
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    packaging {
        // TFLite/ONNX ship native .so libs for multiple ABIs; avoid
        // duplicate-file merge failures from transitive deps.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Contracts this module implements
    implementation(project(":core-domain"))
    implementation(project(":core-platform-api"))
    implementation(project(":core-protocol"))

    // Coroutines (callbackFlow for BLE/Wi-Fi callback-based APIs)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Moonshine's AAR compiles against these runtime dependencies.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.9.1")

    // On-device inference backends — `implementation`, never `api`, so
    // dependent modules (engine-speech, feature-ptt, app) never see these
    // types directly. SpeechBackendModule is the only seam.
    implementation(libs.tensorflow.lite)
    implementation(libs.onnxruntime.android)

//    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
//    testImplementation(libs.robolectric)
}