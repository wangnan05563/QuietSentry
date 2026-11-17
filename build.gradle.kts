plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// 根工程自己产出的报告也收进 release/.build/root，删 release/ 时一起清掉；
// 前缀与模块侧同源，来自 gradle.properties 的 qs.buildDir。
layout.buildDirectory.set(
    rootProject.file(providers.gradleProperty("qs.buildDir").orElse("release/.build").get())
        .resolve("root")
)
