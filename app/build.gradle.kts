plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quietsentry.app"
    compileSdk = 35
    // 本机 build-tools 仅有 34.0.0（镜像无 r35），AGP 8.7 允许使用该版本
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.quietsentry.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:${property("depCoreKtxVersion")}")
    implementation("androidx.appcompat:appcompat:${property("depAppcompatVersion")}")
    implementation("androidx.lifecycle:lifecycle-service:${property("depLifecycleVersion")}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${property("depLifecycleVersion")}")
    implementation("androidx.camera:camera-core:${property("depCameraXVersion")}")
    implementation("androidx.camera:camera-camera2:${property("depCameraXVersion")}")
    implementation("androidx.camera:camera-lifecycle:${property("depCameraXVersion")}")
    implementation("androidx.camera:camera-video:${property("depCameraXVersion")}")
    implementation("androidx.camera:camera-view:${property("depCameraXVersion")}")
}
