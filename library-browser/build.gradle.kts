import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

// Android-only 的可选模块（design：oauth-browser 方案 §5.2 / §11 差异 #10）。
// 用与 :library 相同的 KMP + android-library 插件组合（而非 com.android.library）：
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

    sourceSets {
        androidMain.dependencies {
            // api：消费方引本模块就能看到 AuthClient / OAuthOutcome 等核心类型，
            // 不必再手写一行 :library 依赖
            api(project(":library"))
            implementation(libs.androidx.browser)
            // 管理页 = ComponentActivity：Auth Tab 的结果经 ActivityResultLauncher 回来
            implementation(libs.androidx.activity)
            // Dispatchers.Main 的 Android 实现。版本与 core 的 coroutines 同一条目
            implementation(libs.kotlinx.coroutines.android)
        }

        getByName("androidHostTest").dependencies {
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
        description.set("Optional Android browser flow for loginbase-kt — in-library OAuth redirect handling (Custom Tab / system browser).")
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
