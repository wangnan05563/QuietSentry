import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名材料不进版本库：keystore.properties 由发布方在本地填写，
// 缺失时 release 保持 unsigned，CI 与日常 debug 构建不受影响。
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
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

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "release.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有密钥时保持 unsigned，避免为了“能构建”而把调试签名混进发布包
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // BouncyCastle 的多版本 jar 里带三份 OSGI 清单，Android 打包不接受重复路径
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
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
    implementation("com.google.zxing:core:${property("depZxingVersion")}")
    // 局域网 TLS：Android 没有内置自签证书签发 API，用 BouncyCastle 生成每设备唯一证书
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
