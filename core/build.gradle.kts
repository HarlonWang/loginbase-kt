import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
    android {
        namespace = "wang.harlon.loginbase"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    // iOS 只作占位（见 README）：保留 target 是为了让 commonMain 在编译期就被约束住，
    // 不会悄悄写死 JVM API。**不产出 framework/XCFramework**——那是给原生 Swift App 的
    // 分发格式，占位阶段没人消费，白build 白发。真要面向 Swift 时再加回来。
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.auth)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // CI 注入 signingInMemoryKey 时启用签名；本地无密钥跳过
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(groupId = "wang.harlon", artifactId = "loginbase-kt")

    pom {
        name.set("loginbase-kt")
        description.set("Kotlin Multiplatform client for loginbase — email OTP, social OAuth and session management.")
        url.set("https://github.com/HarlonWang/loginbase-kt")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("HarlonWang")
                name.set("HarlonWang")
                url.set("https://github.com/HarlonWang")
            }
        }
        scm {
            url.set("https://github.com/HarlonWang/loginbase-kt")
            connection.set("scm:git:git://github.com/HarlonWang/loginbase-kt.git")
            developerConnection.set("scm:git:ssh://git@github.com/HarlonWang/loginbase-kt.git")
        }
    }
}
