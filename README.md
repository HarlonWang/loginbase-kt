# loginbase-kt

> Kotlin Multiplatform client for [loginbase](https://github.com/HarlonWang/loginbase) — email OTP + social OAuth + session management.

[loginbase](https://github.com/HarlonWang/loginbase)（Cloudflare Workers 服务端库）的 KMP 客户端。接完之后，业务代码里不会再出现任何 token 相关的东西。

```
Maven  wang.harlon:loginbase-kt           核心
       wang.harlon:loginbase-kt-browser   社交登录浏览器环节（可选，Android-only，同版本）
包名   wang.harlon.loginbase
```

| 平台 | 状态 |
|---|---|
| Android | 已在生产环境验收 |
| iOS | **占位 target，不承诺可用**——只用来约束 `commonMain` 不写死 JVM API，转正条件见 [design.md 第 7 节](docs/design.md) |

核心 artifact 只依赖 `ktor-client-core` + `kotlinx-serialization-json` + `kotlinx-coroutines-core` 三个；不含 UI、不带 HTTP engine。这条红线的理由与可选模块的准入标准见 [design.md 第 6 节](docs/design.md)。

文档：[设计决策](docs/design.md) · [社交登录方案](docs/oauth-browser-design.md) · [排错](docs/troubleshooting.md) · [开发与发布](docs/contributing.md)

## 接入

**1. 建实例**——DI 单例，一个 App 一个。跟着 Activity/ViewModel 重建会让单飞刷新失效（锁是实例字段，两个实例就是两把锁）。

```kotlin
val auth = AuthClient(
    baseUrl = "https://api.example.com/auth",
    tokenStore = SharedPreferencesTokenStore(context),
) {
    httpEngine = okHttpEngine   // 可选；与业务共用 engine，连接池也共用
}
```

**2. 业务 client 装一次 `Auth` 插件**——需要 `ktor-client-auth`，本库自己不依赖它。

```kotlin
val api = HttpClient(okHttpEngine) {
    install(Auth) {
        bearer {
            // refresh token 归本库管，插件不需要知道，传 null 即可
            loadTokens { auth.accessToken()?.let { BearerTokens(it, null) } }
            refreshTokens {
                when (val r = auth.refresh()) {
                    is RefreshOutcome.Success -> BearerTokens(r.tokens.accessToken, null)
                    else -> null   // 放弃：401 返给调用方，导航交给第 3 步
                }
            }
        }
    }
}
```

`refreshTokens` 里**必须调 `auth.refresh()`，不要自己去 POST `/refresh`**：ktor 插件的单飞是 per-client 的，绕过去会并发刷新、烧掉服务端 1h/3 次的救活配额，而且全程功能正常、没有任何报错，等撞穿配额把用户强制登出时已经很难查。理由见 [design.md 第 1 节](docs/design.md)；这段接线有[可执行版本](library/src/commonTest/kotlin/wang/harlon/loginbase/ReadmeIntegrationTest.kt)（含证明绕过会刷两次的反例），改这段文档请一起改测试。

**3. 一处观察 `authState` 做导航**——放一处，散在各页面之后「什么时候该跳登录页」就没有单一答案了。

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

**4. 登录界面与登出**

```kotlin
val cooldown = auth.sendCode(email).cooldownSeconds   // 倒计时用服务端给的值，别写死
auth.verifyCode(email, code)                          // 成功即落盘，authState 自动变 SignedIn

auth.signIn(activity, OAuthProvider.GitHub)           // 社交登录，见下节
auth.signOut()                                        // 或 signOutAll() 登出该用户全部会话
```

接完之后业务代码就只剩 `api.get("$BASE/api/feed").body()`——带 token、认 401、刷新、重试都不用写。

### 接错的信号

| 业务代码里出现 | 后果 |
|---|---|
| `auth.accessToken()` | 手动带 token 绕过了插件，401 重试没人管 |
| `auth.accessToken(forceRefresh = true)` | 每次调用打一次 `/refresh`，直接烧救活配额 |
| `try { } catch (401)` | 插件已处理过，重复 |
| `AuthClient(...)` 出现在 Activity/ViewModel 里 | 多实例各刷各的，单飞失效 |

## 社交登录（可选模块）

邮箱验证码只用核心库即可。加上这个 Android-only 模块后，授权页在合规的外部 user-agent 打开（Auth Tab → Custom Tab → 系统浏览器按可用性回退），回跳捕获、登录/绑定分辨、otc 兑换、取消判定、进程被回收后的续跑都不用写；不用它的项目零感知。

```kotlin
dependencies { implementation("wang.harlon:loginbase-kt-browser:<version>") }

android.defaultConfig {
    // 自有域名反写（RFC 8252 §7.1 的 MUST，example.cn → cn.example）；忘配会直接构建失败
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example"
}
android.buildTypes.getByName("debug") {
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example.debug"   // 与 release 同装不抢回跳
}
```

中转页 intent-filter 与运行时 redirect 推导都从这一个占位符取值，不会漂移。

```kotlin
// 发起。不挂起——挂起返回值在屏幕旋转、进程回收下必然中断，结果只从唯一通道送达
auth.signIn(activity, OAuthProvider.GitHub)
auth.link(activity, OAuthProvider.GitHub)      // 已登录用户绑定第二身份

// 一处收结果，五种情况穷尽处理。replay = 1 只兜「投递早于订阅」（进程回收后冷启动），
// 不是历史记录：处理完调 consume 清掉，否则后来的订阅者会收到陈旧结果
auth.oauthResults.collect { outcome ->
    when (outcome) {
        is OAuthOutcome.SignedIn -> { auth.consumeOauthResult(); dismissLoginPanel() }
        is OAuthOutcome.Linked -> { auth.consumeOauthResult(); refreshIdentity() }
        is OAuthOutcome.Failed -> { auth.consumeOauthResult(); showError(outcome.reason) }
        OAuthOutcome.Cancelled -> { auth.consumeOauthResult(); resetLoading() }
        is OAuthOutcome.Unrecognized -> Unit   // 配置类异常输入，报开发者、不打扰用户
    }
}
```

用户关掉授权页会收到确定的 `Cancelled`，不需要再写 `ON_RESUME` 启发式去猜人是不是空手回来了。

同一个 redirect 要在服务端白名单、App manifest、运行时推导三处一致，`Loginbase.redirectUri(context)` 一行可查该填给服务端什么。**本模块建议只由 App 模块依赖**（持有 Activity 的那层），中间模块直接依赖会把含 placeholder 的 manifest 合并进它们的单测 manifest，导致 test 任务构建失败。

配置对不齐的排错、五条已知限制见 [排错](docs/troubleshooting.md)；双 Activity 拓扑与 AppAuth 对照见 [社交登录方案](docs/oauth-browser-design.md)。

## API 速查

### `authState: StateFlow<AuthState>`

| 态 | 含义 | UI 处置 |
|---|---|---|
| `Unknown` | 还没 `restore()` | 什么都别做 |
| `SignedIn` | 有可用会话 | 正常用 |
| `RefreshFailed` | 刷新失败但会话还在 | **别踢到登录页**，多半只是弱网；刷新成功会自动回到 `SignedIn` |
| `SignedOut(reason)` | 无会话 | 跳登录页；只有 `SessionEnded` 该提示「登录已失效」，`NoSession`（冷启动没令牌）与 `UserInitiated`（用户自己点的）不该提示任何东西 |

### 异常

本库抛出的一切都挂在 `LoginbaseException` sealed 根下，**包括传输层失败**——ktor 只是实现细节，不该逼调用方去 catch `IOException`：

```kotlin
try { auth.verifyCode(email, code) }
catch (e: LoginbaseException.Api) { }               // 服务端明确拒绝，按 e.error 提示
catch (e: LoginbaseException.Network) { }           // 没连上，可重试；e.cause 是原始异常
catch (e: LoginbaseException.MalformedResponse) { } // 两端对不上，重试无用，报开发者
```

`refresh()` 是唯一例外，返回 `RefreshOutcome` 而不抛——三种失败的处置方式不同，sealed 的穷尽 `when` 能逼调用方各自想清楚（[design.md 第 4 节](docs/design.md)）。`RefreshOutcome.Failed.cause` 同样是 `LoginbaseException`，两边共用一套词汇。

### `OAuthProvider`

[value class 不是枚举](library/src/commonMain/kotlin/wang/harlon/loginbase/OAuthProvider.kt)：服务端启用了哪几个由服务端 App 配置，本库不知道也不校验，所以没列进常量的直接写 `OAuthProvider("google")` 即可，不必等客户端发版。

### 邮件语言（protocol 1.3.0）

`sendCode` 会把 App 显示给用户的语言随请求上报，服务端据此选验证码邮件的模板：

```kotlin
AuthClient(baseUrl, store)                                        // 默认跟随系统语言
AuthClient(baseUrl, store) { localeProvider = { settings.tag } }  // App 内自选
```

返回 `null`（以及空串、`und`）只有一个含义——**「我没意见」**，回落系统语言，不是「不要发」；想一律某种语言就返回定值如 `{ "en" }`。服务端对未知语言静默回落，故这条链路不产生任何新的错误分支。取值也单独暴露成 `Loginbase.appLanguageTag()`，方便拼自己的回落链。

### 定制 engine

`HttpClient` 始终由本库自建，engine 不由本库提供——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`）。要证书固定、走代理、加 OkHttp 拦截器，把 engine 传进来即可：

```kotlin
AuthClient(baseUrl, store) { httpEngine = OkHttp.create { addInterceptor(...) } }
```

engine 的生命周期仍归你，`AuthClient.close()` 不会关它。为什么只收 engine 不收整个 `HttpClient`，见 [design.md 第 3 节](docs/design.md)。

## 协议版本

协议的唯一权威是服务端仓的 [`docs/protocol.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md)，本仓不留副本。两仓独立版本线，靠 `PROTOCOL_VERSION` 常量声明实现的是哪一版：

| 本库版本 | 实现的协议版本（= 服务端包版本） |
|---|---|
| 0.1.x | `loginbase@1.3.0` |

## License

Apache 2.0
