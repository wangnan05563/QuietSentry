import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 单元测试覆盖率：JaCoCo 生成 XML/HTML，SonarQube 通过 xmlReportPaths 读取，
    // 不再由 Sonar 自己插桩（两者同时插桩会互相破坏字节码）
    jacoco
}

// 模块构建目录改到项目根 release/.build/app：编译中间产物、报告与 APK 和最终交付物同处一棵树，
// 删掉 release/ 即为全量清理；前缀由 gradle.properties 的 qs.buildDir 统一提供。
layout.buildDirectory.set(
    rootProject.file(providers.gradleProperty("qs.buildDir").orElse("release/.build").get())
        .resolve("app")
)

// 发布签名材料不进版本库：keystore.properties 由发布方在本地填写，
// 缺失时 release 保持 unsigned，CI 与日常 debug 构建不受影响。
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

// 版本号外置在根 package.json：发布脚本每次打包前推进 patch，Gradle 只读取。
// 不在 Gradle 内部推进，是因为 IDE 里随手点一次 Run 也会改写工作区文件，给版本管理制造噪音。
val buildMetaText = rootProject.file("package.json")
    .takeIf { it.exists() }
    ?.readText()
    .orEmpty()

fun metaString(key: String, fallback: String): String =
    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(buildMetaText)?.groupValues?.get(1) ?: fallback

val baseVersion = metaString("version", "0.1.0")
val patchLabel = metaString("patch", "")
val buildNumber = Regex("\"buildNumber\"\\s*:\\s*(\\d+)")
    .find(buildMetaText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

// 在线更新直接读内置仓库：地址来自 package.json，避免把 owner/repo 散落在代码与脚本里
val repoSlug = Regex("github\\.com[/:]([^/]+/[^/.]+)")
    .find(metaString("url", ""))
    ?.groupValues?.get(1)
    .orEmpty()

android {
    namespace = "com.quietsentry.app"
    compileSdk = 35
    // 本机 build-tools 仅有 34.0.0（镜像无 r35），AGP 8.7 允许使用该版本
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.quietsentry.app"
        minSdk = 28
        targetSdk = 35
        // versionCode 必须单调递增，patch 号只用于人眼识别；未跑发布脚本时回退到基线版本
        versionCode = maxOf(buildNumber, 1)
        versionName = if (patchLabel.isEmpty()) baseVersion else "$baseVersion-$patchLabel"
        buildConfigField("String", "GIT_REPO", "\"$repoSlug\"")
    }

    buildFeatures {
        // BuildConfig 在 AGP 8 默认关闭；在线更新需要把仓库地址编译进包内
        buildConfig = true
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

    // 人形检测模型按原始文件打包：压缩后的 assets 无法直接按字节加载给 TFLite
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // 纯 JVM 单测里 Android 桩方法返回默认值而不是抛 "not mocked"，
        // 这样带 Context 参数的类也能只测其中的纯函数分支
        unitTests.isReturnDefaultValues = true
    }
}

// ---------- 单元测试覆盖率（JaCoCo → SonarQube） ----------
// 只统计 debug 单元的 Kotlin 产物；R/BuildConfig 与测试自身不参与覆盖率，
// 否则分母会被生成代码抬高，覆盖率数字失去意义。
jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        // Kotlin 内联函数会生成没有行号的类，不放开会被 JaCoCo 直接跳过
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "reporting"
    description = "生成 debug 单元测试覆盖率报告（XML 供 SonarQube 读取）"
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val kotlinClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    classDirectories.setFrom(
        files(kotlinClasses).asFileTree.matching {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.*",
                "**/Manifest*.*",
                "**/*Test*.*",
            )
        }
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
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
    // 端侧人形检测：TFLite 运行时 + 内置 EfficientDet-Lite0 模型（assets/models）
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 纯 JVM 单测：不引入 Robolectric，需要 Context 的分支由 returnDefaultValues 兜底
    testImplementation("junit:junit:4.13.2")
}
