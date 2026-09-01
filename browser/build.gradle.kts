import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

// 可选模块：把「拉起授权页 + 捕获回跳」按平台各自的一等公民做法封掉
// （Android 见 design 的 oauth-browser 方案 §5.2 / §11 差异 #10，iOS 用 ASWebAuthenticationSession）。
// 用与 :core 相同的 KMP + android-library 插件组合（而非 com.android.library）：
// 构建基建只维护一套，host test（JVM 跑 android 源集）也复用同一形态。
kotlin {
    android {
        namespace = "wang.harlon.loginbase.browser"
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

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        androidMain.dependencies {
            // api：消费方引本模块就能看到 AuthClient / OAuthOutcome 等核心类型，
            // 不必再手写一行 :core 依赖
            api(project(":core"))
            implementation(libs.androidx.browser)
            // 管理页 = ComponentActivity：Auth Tab 的结果经 ActivityResultLauncher 回来
            implementation(libs.androidx.activity)
            implementation(libs.androidx.core.ktx)
            // Dispatchers.Main 的 Android 实现。版本与 core 的 coroutines 同一条目
            implementation(libs.kotlinx.coroutines.android)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }

        iosMain.dependencies {
            // api 的理由同 androidMain
            api(project(":core"))
            // Dispatchers.Main 的 Native 实现。与 core 复用同一个版本条目
            implementation(libs.kotlinx.coroutines.core)
        }

        iosTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(groupId = "wang.harlon", artifactId = "loginbase-kt-browser")

    pom {
        name.set("loginbase-kt-browser")
        description.set("Optional OAuth redirect handling for loginbase-kt — Custom Tab / system browser on Android, ASWebAuthenticationSession on iOS.")
        url.set("https://github.com/HarlonWang/loginbase-kt")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
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
