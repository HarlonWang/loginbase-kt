# loginbase-kt

> [loginbase](https://github.com/HarlonWang/loginbase) 的 Kotlin Multiplatform 客户端——登录、会话、令牌刷新，全都替你办了。

[English](README.md) | **简体中文**

[![Maven Central](https://img.shields.io/maven-central/v/wang.harlon/loginbase-kt)](https://central.sonatype.com/artifact/wang.harlon/loginbase-kt)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

接完之后，**你的 App 代码里不会再出现任何 token**——没有 `Authorization` 头，没有刷新调用，没有 401 处理。服务端那一半是 [loginbase](https://github.com/HarlonWang/loginbase)，一个跑在你自己 Cloudflare Worker 里的库。

| 平台 | 状态 |
|---|---|
| Android | 已在生产环境 |
| iOS | 占位 target，不承诺可用——它的作用是约束 `commonMain` 不写死 JVM API。[转正条件](docs/design.md) |

## 能力

- **token 不再是你的事。** 存储、轮换、过期、重试全都收在一个 `AuthClient` 后面，你的 API 调用重新长得像 API 调用。
- **并发刷新只发一次。** 二十个请求同一瞬间撞上 401，只有一次刷新真正发出去。按 HTTP client 各自做单飞——也就是最顺手的那种写法——会悄悄烧掉服务端的会话救活配额，没有报错、没有症状，直到某天用户被强制登出。
- **「登出」和「离线」是两件事。** 弱网下刷新失败不是登出，但手写的客户端照样会把用户踢去登录页。四个明确的状态，在一处穷尽处理。
- **社交登录，从头到尾。** 授权页在合规的外部 user-agent 打开（Auth Tab → Custom Tab → 系统浏览器按可用性回退），回跳捕获、登录与绑定的分辨、码兑换、取消判定、进程被回收后的续跑，全都不用写。可选的 Android 模块，不用它的项目零感知。
- **三个依赖，不含 UI，不绑 engine。** `ktor-client-core`、`kotlinx-serialization-json`、`kotlinx-coroutines-core`。HTTP engine 由你带，本库绝不替你选。

## 快速开始

**1. 加依赖。** engine 由你挑。

```kotlin
dependencies {
    implementation("wang.harlon:loginbase-kt:<version>")
    implementation("io.ktor:ktor-client-okhttp:<ktor-version>")   // engine，Android
    implementation("io.ktor:ktor-client-auth:<ktor-version>")     // 第 3 步要用
}
```

**2. 全 App 建一个实例。** 做成 DI 单例——单飞的锁是实例字段，两个实例就是两把锁，单飞随即失效。

```kotlin
val auth = AuthClient(
    baseUrl = "https://api.example.com/auth",
    tokenStore = SharedPreferencesTokenStore(context),
) {
    httpEngine = okHttpEngine   // 可选；与业务共用 engine，连接池也共用
}
```

**3. 教你的 API client 怎么刷新。**

```kotlin
val api = HttpClient(okHttpEngine) {
    install(Auth) {
        bearer {
            // refresh token 归本库管，插件不需要知道，传 null 即可
            loadTokens { auth.accessToken()?.let { BearerTokens(it, null) } }
            refreshTokens {
                when (val r = auth.refresh()) {
                    is RefreshOutcome.Success -> BearerTokens(r.tokens.accessToken, null)
                    else -> null   // 放弃：401 返给调用方，导航交给第 4 步
                }
            }
        }
    }
}
```

这里**必须调 `auth.refresh()`，不要自己去 POST `/refresh`**：ktor 插件的单飞是 per-client 的，绕过去在测试里看起来一切正常，是唯一一个不会报错的错。[为什么，以及代价是什么](docs/integration.md#refresh)

**4. 在一处观察登录态做导航。** 散在各页面之后，「什么时候该跳登录页」就没有单一答案了。

```kotlin
auth.restore()   // 启动时恢复

auth.authState.collect { state ->
    when (state) {
        AuthState.Unknown          -> Unit                 // 还没 restore，别急着跳转
        AuthState.SignedIn         -> Unit
        is AuthState.RefreshFailed -> showOfflineBadge()   // 不是登出，多半只是弱网
        is AuthState.SignedOut     -> {
            navigateToLogin()
            if (state.reason is SignOutReason.SessionEnded) toast("登录已失效，请重新登录")
        }
    }
}
```

**5. 登录。**

```kotlin
val cooldown = auth.sendCode(email).cooldownSeconds   // 倒计时用服务端给的值，别写死
auth.verifyCode(email, code)                          // 成功即落盘，authState 自动变 SignedIn

auth.signIn(activity, OAuthProvider.GitHub)           // 需要下面那个 browser 模块
auth.signOut()                                        // 或 signOutAll() 登出该用户全部会话
```

到这里，业务代码就只剩 `api.get("$BASE/api/feed").body()`。

## 你不用写的东西

| | |
|---|---|
| 给刷新加一把锁 | 一波 401 无论多少个，只刷一次 |
| 分辨会话没了还是网络不好 | 两个独立状态，不再把离线用户踢去登录页 |
| 刷新成功后重放原请求 | 插件负责，重试那次自动带上新 token |
| 用 `ON_RESUME` 猜用户是不是从授权页空手回来了 | 取消会以确定的 `Cancelled` 送达 |
| 授权途中进程被 Android 杀掉后重启整个流程 | 结果扛得住进程回收，且只从唯一通道送达 |
| 在 API 错误处理旁边再 `catch (IOException)` | 传输层失败同样是 `LoginbaseException`——ktor 只是实现细节 |

## 社交登录

只用邮箱验证码的话，核心 artifact 就够了。加上这个可选的 Android 模块，整个浏览器往返都不用管：

```kotlin
dependencies { implementation("wang.harlon:loginbase-kt-browser:<version>") }

android.defaultConfig {
    // 自有域名反写（RFC 8252 §7.1 的 MUST）；忘配是构建失败，不是登录失败
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example"
}
```

中转页的 intent-filter 与运行时推导的 redirect 都读这一个占位符，不会漂移。`Loginbase.redirectUri(context)` 一行打印出该填给服务端白名单什么。[完整接线](docs/integration.md#social-sign-in) · [设计方案](docs/oauth-browser-design.md)

## 文档

| | |
|---|---|
| [接入指南](docs/integration.md) | 令牌存储、异常处置、定制 engine、社交登录完整接线 |
| [排错](docs/troubleshooting.md) | 症状 → 原因，以及社交登录的已知限制 |
| [设计决策](docs/design.md) | 单飞、四态、依赖红线、iOS 为什么是占位 |
| [协议契约](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md) | wire API，住在服务端仓——唯一权威 |

## 协议兼容

本库用 `PROTOCOL_VERSION` 声明自己实现的协议版本，当前是 **1.3.0**。服务端的 minor 版本在 wire 上向后兼容，所以新服务端配老客户端能用——该升级的理由是你想要某个后续 minor 加的能力，而不是版本号对不上。两仓各自独立版本线。

## License

MIT。已发布的 0.1.0 / 0.1.1 的 POM 元数据有误，以 [LICENSE](LICENSE) 为准。
