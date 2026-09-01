# 接入指南

README 讲的是「接上去要写什么」，这里讲「为什么必须这么写，以及写错了会怎样」。

<a id="refresh"></a>
## 刷新必须走 `auth.refresh()`

`refreshTokens` 里**必须调 `auth.refresh()`，不要自己去 POST `/refresh`**：ktor 插件的单飞是 per-client 的，绕过去会并发刷新、烧掉服务端 1h/3 次的救活配额，而且全程功能正常、没有任何报错，等撞穿配额把用户强制登出时已经很难查。理由见 [design.md 第 1 节](design.md)；这段接线有[可执行版本](../core/src/commonTest/kotlin/wang/harlon/loginbase/ReadmeIntegrationTest.kt)（含证明绕过会刷两次的反例），改这段文档请一起改测试。

**不装插件也行**——红线是「刷新走 `auth.refresh()`」，不是「必须用插件」。自己捕 401 再调 `auth.refresh()` 同样安全，但插件免费覆盖的三处，手写壳要自己想到：

- 把 401 转成返回值（`false` / `null` / 自定义错误对象）而不抛异常的接口，捕不到
- 靠 `cause` 链匹配异常类型时，中间每层都要保留原始异常，包一层换了类型就断
- 匿名可用的端点没 token 也要照发，套不了「无会话就短路」的包装器

## 接错的信号

| 业务代码里出现 | 后果 |
|---|---|
| `auth.accessToken()` | 手动带 token 绕过了插件，401 重试没人管 |
| `auth.accessToken(forceRefresh = true)` | 每次调用打一次 `/refresh`，直接烧救活配额 |
| `try { } catch (401)` | 插件已处理过，重复 |
| `AuthClient(...)` 出现在 Activity/ViewModel 里 | 多实例各刷各的，单飞失效 |

## 令牌存储

`TokenStore` 是个两方法接口，库内提供三个实现：

| 实现 | 位置 | 用途 |
|---|---|---|
| `SharedPreferencesTokenStore` | Android | App 私有目录，**未加密**（非 root 不可读）；同步落盘，进程被杀不丢刚轮换的令牌 |
| `NSUserDefaultsTokenStore` | iOS | 同上语义 |
| `InMemoryTokenStore` | common | 测试 |

要 Keystore / Keychain 级别的保护，自己实现 `TokenStore` 即可——唯一的硬要求是**同步落盘**：异步写在进程被杀时会丢掉刚轮换出来的 refresh token，而旧的那个已经作废，用户直接掉线。

## 异常

本库抛出的一切都挂在 `LoginbaseException` sealed 根下，**包括传输层失败**——ktor 只是实现细节，不该逼调用方去 catch `IOException`：

```kotlin
try { auth.verifyCode(email, code) }
catch (e: LoginbaseException.Api) { }               // 服务端明确拒绝，按 e.error 提示
catch (e: LoginbaseException.Network) { }           // 没连上，可重试；e.cause 是原始异常
catch (e: LoginbaseException.MalformedResponse) { } // 两端对不上，重试无用，报开发者
```

`refresh()` 是唯一例外，返回 `RefreshOutcome` 而不抛——三种失败的处置方式不同，sealed 的穷尽 `when` 能逼调用方各自想清楚（[design.md 第 4 节](design.md)）。`RefreshOutcome.Failed.cause` 同样是 `LoginbaseException`，两边共用一套词汇。

## 登录态四态

| 态 | 含义 | UI 处置 |
|---|---|---|
| `Unknown` | 还没 `restore()` | 什么都别做 |
| `SignedIn` | 有可用会话 | 正常用 |
| `RefreshFailed` | 刷新失败但会话还在 | **别踢到登录页**，多半只是弱网；刷新成功会自动回到 `SignedIn` |
| `SignedOut(reason)` | 无会话 | 跳登录页；只有 `SessionEnded` 该提示「登录已失效」，`NoSession`（冷启动没令牌）与 `UserInitiated`（用户自己点的）不该提示任何东西 |

## `OAuthProvider`

[value class 不是枚举](../core/src/commonMain/kotlin/wang/harlon/loginbase/OAuthProvider.kt)：服务端启用了哪几个由服务端 App 配置，本库不知道也不校验，所以没列进常量的直接写 `OAuthProvider("google")` 即可，不必等客户端发版。

## 邮件语言

`sendCode` 会把 App 显示给用户的语言随请求上报，服务端据此选验证码邮件的模板：

```kotlin
AuthClient(baseUrl, store)                                        // 默认跟随系统语言
AuthClient(baseUrl, store) { localeProvider = { settings.tag } }  // App 内自选
```

返回 `null`（以及空串、`und`）只有一个含义——**「我没意见」**，回落系统语言，不是「不要发」；想一律某种语言就返回定值如 `{ "en" }`。服务端对未知语言静默回落，故这条链路不产生任何新的错误分支。取值也单独暴露成 `Loginbase.appLanguageTag()`，方便拼自己的回落链。

## 定制 engine

`HttpClient` 始终由本库自建，engine 不由本库提供——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`）。要证书固定、走代理、加 OkHttp 拦截器，把 engine 传进来即可：

```kotlin
AuthClient(baseUrl, store) { httpEngine = OkHttp.create { addInterceptor(...) } }
```

engine 的生命周期仍归你，`AuthClient.close()` 不会关它。为什么只收 engine 不收整个 `HttpClient`，见 [design.md 第 3 节](design.md)。

<a id="social-sign-in"></a>
## 社交登录接线

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

iOS 没有 manifest 可推导，redirect 由调用方直接传，scheme 段就是 `callbackURLScheme`
（同样只支持 private-use scheme，传 https 会在发起点就报错）：

```kotlin
auth.signIn(OAuthProvider.GitHub, redirect = "cn.example:/loginbase/callback")
auth.link(OAuthProvider.GitHub, redirect = "cn.example:/loginbase/callback")
```

两端之后的收口完全一样：

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

同一个 redirect 要在服务端白名单、App manifest、运行时推导三处一致，`Loginbase.redirectUri(context)` 一行可查该填给服务端什么。**Android 侧建议只由 App 模块依赖本模块**（持有 Activity 的那层），中间模块直接依赖会把含 placeholder 的 manifest 合并进它们的单测 manifest，导致 test 任务构建失败；iOS 侧没有 manifest，随便哪层依赖都行。

配置对不齐的排错、五条已知限制见 [排错](troubleshooting.md)；双 Activity 拓扑与 AppAuth 对照见 [社交登录方案](oauth-browser-design.md)。
