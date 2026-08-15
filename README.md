# loginbase-kt

> Kotlin Multiplatform client for [loginbase](https://github.com/HarlonWang/loginbase) — email OTP + social OAuth + session management.

[loginbase](https://github.com/HarlonWang/loginbase)（Cloudflare Workers 服务端库）的 KMP 客户端。

**目标平台：Android。** iOS（arm64 + simulator arm64）的 target 存在，但**长期只作占位，不承诺可用**——见下。

### iOS 是占位

保留这两个 target 的实际作用只有一个：让 `commonMain` 在编译期就被约束住，不会悄悄写死 JVM API。除此之外**不要按「支持 iOS」来接入**：

- `NSUserDefaultsTokenStore` 与 iOS 侧的语言取值**从未在真机链路上验证过**
- **没有做 Swift 互操作保障**：public suspend 函数没标 `@Throws`，Swift 侧遇到 `LoginbaseException` 是**直接崩溃**而不是抛 Swift error；`authState` 是 Kotlin `StateFlow`，Swift 里也拿不到
- CI 不跑 iOS 测试（`commonTest` 只在 Android 上跑过）

要在 iOS 上用，先把上面三条补齐。

## 协议契约

**协议的唯一权威是服务端仓的 [`docs/protocol.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md)，本仓不留副本。**

两仓独立版本线，版本号不追求相等——客户端有自己的版本，靠 `PROTOCOL_VERSION` 常量声明实现的是哪一版协议：

| 本库版本 | 实现的协议版本（= 服务端包版本） |
|---|---|
| 0.1.x（未发布） | `loginbase@1.3.0` |

协议变更纪律（分仓版）：服务端实现 + `protocol.md` 同 commit，同时在本仓开跟进 issue，客户端版本落地前不关。分仓决策与理由见服务端仓 [`docs/design.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/design.md) 的「两个仓库」节。

## 坐标

```
Maven      wang.harlon:loginbase-kt              核心（Maven Central，本仓 tag 触发 CI 发布）
           wang.harlon:loginbase-kt-browser      社交登录浏览器环节（可选，Android-only，同版本发布）
包名        wang.harlon.loginbase
```

## 接入指南

按这六步接完，**业务代码里不会再出现任何 token 相关的东西**。

### 1. 建实例（DI 单例，一个 App 一个）

```kotlin
val auth = AuthClient(
    baseUrl = "https://api.example.com/auth",
    tokenStore = SharedPreferencesTokenStore(context),
) {
    httpEngine = okHttpEngine          // 可选；和业务共用一个 engine，连接池也共用
}
```

跟着 Activity/ViewModel 重建会**让单飞失效**——锁是实例字段，两个实例就是两把锁。

### 2. 业务 client 装一次 `Auth` 插件

```kotlin
val api = HttpClient(okHttpEngine) {
    install(Auth) {
        bearer {
            loadTokens {
                // refresh token 归本库管，插件不需要知道，传 null 即可
                auth.accessToken()?.let { BearerTokens(it, null) }
            }
            refreshTokens {
                when (val r = auth.refresh()) {
                    is RefreshOutcome.Success -> BearerTokens(r.tokens.accessToken, null)
                    else -> null       // 放弃：请求把 401 返给调用方，导航交给第 4 步
                }
            }
        }
    }
}
```

**`refreshTokens` 里一定要调 `auth.refresh()`，不要自己去 POST `/refresh`。** ktor 插件的单飞是 **per-client** 的，App 里有几个 `HttpClient` 就会并发刷几次；而并发刷新会烧掉服务端的救活配额（1h/3 次，超了整条会话按盗用撤销）。绕过本库照样能跑通，**功能完全正常、没有任何报错**，只是每次 token 过期偷偷烧一格——等撞穿配额把用户强制登出时已经很难查了。走 `auth.refresh()` 则由本库的锁做**跨 client** 收敛。

需要 `ktor-client-auth`（本库自己不依赖它，见「设计红线」）。

> 上面这段接线有[可执行版本](library/src/commonTest/kotlin/wang/harlon/loginbase/ReadmeIntegrationTest.kt)：两个各装了 `Auth` 插件的 client 同时 401，断言服务端**只被刷新一次**；配套一个反例用例证明绕过 `auth.refresh()` 会刷两次。改这段文档时请一起改那个测试。

### 3. 启动时恢复

```kotlin
auth.restore()
```

### 4. 一处观察 `authState` 做导航

```kotlin
auth.authState.collect { state ->
    when (state) {
        AuthState.Unknown          -> Unit                 // 还没 restore，别急着跳转
        AuthState.SignedIn         -> Unit                 // 正常用
        is AuthState.RefreshFailed -> showOfflineBadge()   // 别踢到登录页！会话还在，多半只是弱网
        is AuthState.SignedOut     -> {
            navigateToLogin()
            if (state.reason is SignOutReason.SessionEnded) {
                toast("登录已失效，请重新登录")             // 只有这一种才提示
            }
        }
    }
}
```

放一处，别散在各页面——散开之后「什么时候该跳登录页」就没有单一答案了。

### 5. 登录界面

```kotlin
// 邮箱验证码
val cooldown = auth.sendCode(email).cooldownSeconds   // 倒计时用服务端给的值，别写死
auth.verifyCode(email, code)                          // 成功即落盘，authState 自动变 SignedIn

// 社交登录：浏览器环节（授权页打开、回跳捕获、登录/绑定分辨、otc 兑换、
// 取消判定、进程被回收后的续跑）整体归可选模块 loginbase-kt-browser，
// 接入见下方「社交登录」一节。发起就这一行：
auth.signIn(activity, OAuthProvider.GitHub)      // 不挂起，结果从 auth.oauthResults 送达
```

### 6. 登出

```kotlin
auth.signOut()        // 或 signOutAll() 登出该用户全部会话
```

---

### 接完之后，业务代码长这样

```kotlin
class FeedApi(private val api: HttpClient) {
    suspend fun feed(): Feed = api.get("$BASE/api/feed").body()
}
```

带 token、认 401、刷新、重试——**一个字都不用写**。

### 出现下面这些，就是接错了

| 业务代码里出现 | 说明 |
|---|---|
| `auth.accessToken()` | 有人在手动带 token，绕过了插件——401 重试就没人管了 |
| `auth.accessToken(forceRefresh = true)` | **最危险**：手动刷新，每次调用都打一次 `/refresh`，直接烧配额 |
| `try { } catch (401)` | 插件已经处理过了，重复 |
| `AuthClient(...)` 出现在 Activity/ViewModel 里 | 单飞失效，多实例各刷各的 |

> 多个业务 client 且 401 错开发生时，会多一次令牌轮换（不烧配额，因为用的是有效令牌）。想连这次也省掉，可以在 `refreshTokens` 里先比对 `oldTokens?.accessToken` 与 `auth.accessToken()`：不一样就说明别人已经刷过了，直接用新的。

## 社交登录（可选模块 `loginbase-kt-browser`）

邮箱验证码只用核心库即可；要用社交登录再加这个 Android-only 模块，**不用它的项目
零感知**（没有 placeholder 要配、不会合并进多余的 Activity）。加上之后，授权页在
合规的外部 user-agent 打开（Auth Tab → Custom Tab → 系统浏览器按可用性回退），回跳捕获、登录/绑定
分辨、otc 兑换、取消判定、进程被回收后的续跑——**一个都不用写**。

### 接线：一行依赖 + 每变体一行 placeholder

```kotlin
dependencies {
    implementation("wang.harlon:loginbase-kt-browser:<version>")   // 与核心同版本
}

android {
    defaultConfig {
        // scheme 用自有域名反写（RFC 8252 §7.1 的 MUST，example.cn → cn.example）。
        // 中转页 intent-filter 与运行时 redirect 推导都从这一个占位符取值，不会漂移；
        // 忘配会直接构建失败（这是刻意的：静默回落的症状离病因太远）
        manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example"
    }
    buildTypes.getByName("debug") {
        // debug 变体独立 scheme：与 release 同装一台设备时互不抢回跳
        manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example.debug"
    }
}
```

```kotlin
// 发起。不挂起——挂起返回值在屏幕旋转、进程回收下必然中断，结果只从唯一通道送达
auth.signIn(activity, OAuthProvider.GitHub)
auth.link(activity, OAuthProvider.GitHub)        // 已登录用户绑定第二身份

// 一处收结果，五种情况穷尽处理。replay = 1 只兜「投递早于订阅」（进程回收后
// 冷启动），不是历史记录：处理完调 consume 清掉，否则后来的订阅者会收到陈旧结果
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

用户关掉授权页会收到**确定的 `Cancelled`**——不需要再写 `ON_RESUME` 启发式去猜
「人是不是从浏览器空手回来了」。

### 服务端白名单：三处一致

同一个 redirect 要在三处一致，而写错的地方和报错的地方对不上：

| 出现在哪 | 谁负责 | 写错的症状 |
|---|---|---|
| 服务端 redirect 白名单 | 服务端 App 配置 | 授权还没开始就被拒（`invalid_redirect`） |
| App manifest（经 placeholder） | Android 构建 | 回跳没人接，用户授权完卡在打不开的页面 |
| 运行时拼 redirect（经 meta-data 读回） | 库 | 与 manifest 物理同源，**不会单独错** |

该填给服务端什么，`Loginbase.redirectUri(context)` 一行可查（形如
`cn.example:/loginbase/callback`，单斜杠无 host）；debug 构建首次发起时也会自动打进
日志（tag `loginbase`）。

**症状 → 原因**：

| 症状 | 原因 |
|---|---|
| 构建失败 `requires a placeholder substitution` | 没配 `loginbaseRedirectScheme`，或当前变体没配。不用社交登录就不要引本模块 |
| 浏览器停在 `invalid_redirect`，App 无任何反应 | 服务端白名单缺这条 redirect；两边字符串肉眼相同时**数斜杠**——`scheme:/path` 单斜杠才是对的，`scheme://path` 会把 path 段解析成 host，精确匹配直接失败 |
| 发起即抛「没有任何 Activity 认领」 | scheme 写错，或当前构建变体没配 placeholder |
| 发起即抛「scheme 被其他应用抢注」 | 别的 App 声明了同一 scheme——这同时是安全信号，换独占的自有域名反写 |

### 已知限制

1. **系统浏览器兜底通路的取消信号迟到**：要等用户自己回到 App 才能判定；极端时序下
   可能先收到 `Cancelled` 再收到 `SignedIn`，按序处理即自愈
2. **服务端白名单要人工配**，debug/release 变体各一条——这是安全控制，不能由客户端决定
3. **自定义 scheme 谁都能声明**（RFC 8252 承认的固有弱点）：发起前自检会就地报出抢注，
   且 otc 60 秒单次有效，即便被截也只有一次兑换窗口
4. `Failed.reason` 由服务端 App 定义（`already_linked` 是典型值），不是协议保证
5. **只有 Android**。iOS 转正前仍是 `signInUrl()` + 自己开浏览器（见「iOS 是占位」）

设计全貌（双 Activity 拓扑、AppAuth #977 免疫、与 AppAuth/Auth0 的逐条对照）见
[`docs/oauth-browser-design.md`](docs/oauth-browser-design.md) 。

## engine 与 `HttpClient`

`HttpClient` 的 **engine 不由本库提供**——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`），多数 KMP App 本来就有。

要证书固定、走代理、加 OkHttp 拦截器，把 **engine** 传进来即可，`HttpClient` 始终由本库自建：

```kotlin
AuthClient(baseUrl, store) {
    httpEngine = OkHttp.create { addInterceptor(...) }   // 连接池同 engine，不会多一个池
}
```

engine 的生命周期仍归你，`AuthClient.close()` 不会关它。**为什么只收 engine 不收整个 `HttpClient`**（简短版：注入 client 会让 `POST /refresh` 跑在一套未知插件上，ktor 的 `Auth` 会死锁、`HttpRequestRetry` 会把一次刷新放大成四次）见 [`docs/design.md`](docs/design.md) 第 3 节。

## 单飞 refresh 的边界：每进程一个实例

服务端每次 refresh 必轮换，并对「拿已作废令牌来刷」做救活判定——**救活有 1h/3 次护栏，超了按盗用撤销整条会话**（用户被登出）。并发刷新会白白烧这个配额，所以本库用互斥锁把并发收敛成一次真实请求：进锁后若发现「等锁期间已经有人跑完了一整轮」，直接复用那一轮的结果。

**成功和失败一视同仁地共享**。只共享成功是不够的——刷新失败时本地存储纹丝不动，等待者看到的世界和「一次刷新都没发生过」一模一样，于是各自又发一次。而服务端若已经处理掉那次请求（回执丢在回程），后续每一次都是拿**已作废的令牌**去刷，每次烧掉一格救活配额，几轮就撞穿护栏。

**锁是实例字段，作用域是「一个进程」**：

| | 覆盖 |
|---|---|
| 同进程多协程并发 401 | ✅ 收敛成 1 次 |
| 进程被杀后重启重试 | ❌ 锁随进程消失（设计如此：走服务端的诚实重试救活，正常消耗 1 格配额） |
| **多进程**（Android `:remote`、iOS App + Widget/Extension 各建实例） | ❌ 各刷各的，会烧配额 |

有多进程结构时，跨进程互斥需消费方自己保证——**本库刻意不做跨进程锁**。这个取舍的理由、以及与 ktor `Auth` 插件怎么叠加使用，见 [`docs/design.md`](docs/design.md) 第 1 节。

## 状态

核心已实现：`AuthClient`（邮箱验证码 / 社交 OAuth / link / refresh / 登出）、`TokenStore` 与两个平台实现、`AuthState`、**单飞 refresh**、**邮件语言上报**、**OAuth 回跳处理**（`handleOAuthCallback` / `oauthResults`，含 otc 幂等与通道 consume）。浏览器环节在可选模块 `loginbase-kt-browser`（中转页 + 管理页双 Activity、CCT/系统浏览器回退、取消判定、冷启动停泊），已在 TrendingAI 生产环境验收。89 个测试。

### 登录态

`authState: StateFlow<AuthState>` 四态，怎么用见[接入指南第 4 步](#4-一处观察-authstate-做导航)。

两个容易接错的点：

- **`RefreshFailed` 不是登出**。会话没被清、服务端也没说它死了，多半只是弱网。当登出处理就是把漫游、地铁里的用户踢下线。刷新成功会自动回到 `SignedIn`。
- **`SignOutReason` 三种的区别只在文案**：`NoSession`（冷启动没令牌）和 `UserInitiated`（用户自己点的登出）都不该提示任何东西，只有 `SessionEnded(reason)` 才该弹「登录已失效」。混成一个信号的后果是：用户自己点登出却被弹「登录已失效」（骚扰），或者会话被撤销了却一声不吭跳回登录页（用户以为 App 有 bug）。`SessionEnded` 携带的 `RefreshFailure` 可用来细化文案。

### 错误处理

本库抛出的一切都挂在 `LoginbaseException` 这个 sealed 根下，**包括传输层失败**——ktor 只是实现细节，不该逼调用方去 catch `IOException`：

```kotlin
try {
    auth.verifyCode(email, code)
} catch (e: LoginbaseException.Api) {          // 服务端明确拒绝，按 e.error 给用户提示
} catch (e: LoginbaseException.Network) {      // 没连上，可重试；e.cause 是原始异常
} catch (e: LoginbaseException.MalformedResponse) {  // 两端对不上，重试无用，报给开发者
}
```

`refresh()` 是唯一的例外，它返回 `RefreshOutcome` 而不抛——因为它的三种失败**处置方式不同**（`SessionEnded` 必须引导重新登录、`Failed` 该重试、`NoSession` 压根没会话），sealed 的穷尽 `when` 能逼调用方各自想清楚，而 `catch` 不会。`RefreshOutcome.Failed.cause` 同样是 `LoginbaseException`，两边共用一套词汇。

社交登录的 provider 是 [`OAuthProvider`](library/src/commonMain/kotlin/wang/harlon/loginbase/OAuthProvider.kt)（value class，不是枚举）：服务端启用了哪几个由服务端 App 配置，本库不知道也不校验，所以没列进 `OAuthProvider.GitHub` 这类常量的直接写 `OAuthProvider("google")` 即可，不必等客户端发版。

### 邮件语言（protocol 1.3.0）

`sendCode` 会把 **App 显示给用户的语言**随请求上报，服务端据此选验证码邮件的模板。规则只有两条：

```kotlin
AuthClient(baseUrl, store)                                  // 什么都不写 = 跟随系统语言
AuthClient(baseUrl, store) { localeProvider = { settings.tag } }  // App 内自选；返回 null = 没意见 → 回落系统语言
```

`null`（以及空串、`und`）只有一个含义——**「我没意见」**，回落系统语言，**不是「不要发」**。想一律某种语言就返回定值，如 `{ "en" }`。服务端对未知语言静默回落，故这条链路**不产生任何新的错误分支**。

平台取值：Android `Locale.getDefault().toLanguageTag()`（已跟随 per-app language）；iOS `Bundle.main.preferredLocalizations.first`（App 实际显示的语言，而非系统首选语言）。两端取不到时字段整个省略，交服务端兜底。

这个取值也单独暴露成 `Loginbase.appLanguageTag()`，方便拼自己的回落链：

```kotlin
localeProvider = { settings.languageTag ?: Loginbase.appLanguageTag() }
```

设计决策与被否掉的方案见 [`docs/design.md`](docs/design.md)；待办清单见 [`docs/todo.md`](docs/todo.md)。

待办见 [issue](https://github.com/HarlonWang/loginbase-kt/issues)：TrendingAI 接入（composite build）、iOS 真机链路验证、首版发布。

## 设计红线

**依赖最小集**：核心 artifact = `ktor-client-core` + `kotlinx-serialization-json` +
`kotlinx-coroutines-core`，**仅此三个**；可选平台模块只允许**该平台的一等公民 API**
（`loginbase-kt-browser` 的 `androidx.browser` / `kotlinx-coroutines-android`——AndroidX
与 JetBrains，信任级别同系统 SDK；supabase-kt 的 `Auth` 模块同此分法）。

- 不用 `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json`：请求体手工序列化、响应手工解析
- 不用 `multiplatform-settings`：存两个字符串而已，平台实现各十几行（Android `SharedPreferences`、iOS `NSUserDefaults`），且**落盘的同步性是与服务端救活机制配套的关键语义，不该藏在第三方库的默认参数里**
- 不带 HTTP engine：消费方提供（`HttpClient` 由库自建，见上）
- **不含 UI**：登录界面归各 App 实现

加任何新依赖前先停下来问一遍值不值——auth 库是供应链攻击的最高价值目标。

## 开发

```bash
./gradlew :library:testAndroidHostTest :library-browser:testAndroidHostTest   # CI 跑的（ubuntu 编不了 iOS）
./gradlew :library:compileKotlinIosSimulatorArm64   # iOS 侧编译需 macOS
```

**改动 `commonMain` 的形状后请本地跑一次 iOS 编译**（改 `expect` 签名、改 `TokenStore` 之类接口时，`iosMain` 的实现要跟着改）。CI 不做这件事：ubuntu runner 编不了 iOS，而为一个占位 target 在每个 PR 上起 macOS runner不划算。漏了也不会出坏产物——打 tag 时 `publish.yml` 跑在 macOS 上，编译不过就直接失败在构建阶段、早于任何上传，改完删 tag 重打即可。

发布：打裸版本号 tag（如 `0.1.0`）触发 CI 在 macos runner 上 `publishAndReleaseToMavenCentral`。

## License

Apache 2.0
