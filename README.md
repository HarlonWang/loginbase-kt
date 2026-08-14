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
Maven      wang.harlon:loginbase-kt      （Maven Central，本仓 tag 触发 CI 发布）
包名        wang.harlon.loginbase
```

## 用法

```kotlin
// 一个 App 一个实例——单飞 refresh 的锁是实例字段，两个实例就是两把锁
val auth = AuthClient(
    baseUrl = "https://api.example.com/auth",
    tokenStore = SharedPreferencesTokenStore(context),
) {
    httpClient = myKtorClient                            // 可选，见 LoginbaseConfig；不写则库自建
}
auth.restore()                                           // 启动时恢复登录态

auth.sendCode("a@b.com")                                 // 邮箱验证码（自动带上 App 显示语言）
auth.verifyCode("a@b.com", "123456")                     // 成功即落盘

openInBrowser(auth.signInUrl(OAuthProvider.GitHub, "app://cb"))   // 社交登录，回跳带 otc
auth.exchangeOtc(otc)

openInBrowser(auth.linkUrl(OAuthProvider.GitHub, "app://cb"))     // 已登录用户绑定第二身份

auth.accessToken()                                       // 业务请求带上
auth.accessToken(forceRefresh = true)                    // 收到 401 时刷新重试
```

`HttpClient` 的 **engine 不由本库提供**——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`），多数 KMP App 本来就有。

### 单飞 refresh 的边界：每进程一个实例

服务端每次 refresh 必轮换，并对「拿已作废令牌来刷」做救活判定——**救活有 1h/3 次护栏，超了按盗用撤销整条会话**（用户被登出）。并发刷新会白白烧这个配额，所以本库用互斥锁把并发收敛成一次真实请求：进锁后若发现「等锁期间已经有人跑完了一整轮」，直接复用那一轮的结果。

**成功和失败一视同仁地共享**。只共享成功是不够的——刷新失败时本地存储纹丝不动，等待者看到的世界和「一次刷新都没发生过」一模一样，于是各自又发一次。而服务端若已经处理掉那次请求（回执丢在回程），后续每一次都是拿**已作废的令牌**去刷，每次烧掉一格救活配额，几轮就撞穿护栏。

**锁是实例字段，作用域是「一个进程」**：

| | 覆盖 |
|---|---|
| 同进程多协程并发 401 | ✅ 收敛成 1 次 |
| 进程被杀后重启重试 | ❌ 锁随进程消失（设计如此：走服务端的诚实重试救活，正常消耗 1 格配额） |
| **多进程**（Android `:remote`、iOS App + Widget/Extension 各建实例） | ❌ 各刷各的，会烧配额 |

有多进程结构时，跨进程互斥需消费方自己保证。**本库刻意不做跨进程锁**——失败代价不对称：没有它最坏是多刷一次，有了它最坏是认证彻底卡死（Supabase 用 Web Locks 做跨标签页锁，换来的正是一串孤儿锁与死锁故障）。业界同形：Auth0 的 `CredentialsManager` 同样只保证实例内串行，文档里明写「不要从多个实例调用续期方法」。

> Ktor 的 `Auth` 插件也内建单飞，但它只协调**装了该插件的那一个 `HttpClient`**。App 通常有多个 client（业务 API、图片、第三方），各刷各的照样烧配额——所以两者是叠加不是替代：可以在自己的 client 上装 `Auth` 插件、`refreshTokens` 回调里调 `authClient.refresh()`，由本库的锁保证全局只刷一次。
>
> ⚠️ **装了 `Auth` 插件的那个 client 不要注入给 `AuthClient`**（即不要设成 `LoginbaseConfig.httpClient`）。否则本库的 `POST /refresh` 也会过该插件：服务端返回 401 时插件回调里再调 `authClient.refresh()`，而当前协程已经持有单飞锁——`Mutex` 不可重入，会**永久挂起**。业务 client 与注入给本库的 client 分开即可（复用同一个 engine 没问题）。

## 状态

核心已实现：`AuthClient`（邮箱验证码 / 社交 OAuth / link / refresh / 登出）、`TokenStore` 与两个平台实现、`AuthState`、**单飞 refresh**、**邮件语言上报**。49 个测试。

### 登录态

`authState: StateFlow<AuthState>` 四态，每一态对应一个不同的 UI 处置：

| 态 | UI 该做什么 |
|---|---|
| `Unknown` | 等 `restore()`，别急着跳转 |
| `SignedIn` | 正常用 |
| `RefreshFailed(cause)` | **别踢到登录页**——会话还在，多半只是弱网；刷成功会自动回到 `SignedIn` |
| `SignedOut(reason)` | 去登录页；是否提示看 `reason` |

`SignOutReason` 三种，区别只在**文案**：`NoSession`（冷启动没令牌）和 `UserInitiated`（用户自己点的登出）都不该提示任何东西，只有 `SessionEnded(reason)` 才该弹「登录已失效，请重新登录」——它携带的 `RefreshFailure` 可用来细化文案。

把这三种混成一个「已登出」信号的后果：用户自己点登出却被弹「登录已失效」（骚扰），或者会话被服务端撤销了却一声不吭跳回登录页（用户以为 App 有 bug）。

### 错误处理

本库抛出的一切都挂在 `LoginbaseException` 这个 sealed 根下，**包括传输层失败**——engine 由消费方提供、ktor 只是实现细节，不该逼调用方去 catch `IOException`：

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

待办见 [issue](https://github.com/HarlonWang/loginbase-kt/issues)：TrendingAI 接入（composite build）、iOS 真机链路验证、首版发布。

## 设计红线

**依赖最小集**：`ktor-client-core` + `kotlinx-serialization-json` + `kotlinx-coroutines-core`，**仅此三个**。

- 不用 `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json`：请求体手工序列化、响应手工解析，注入的 `HttpClient` 因此不需要任何插件配置
- 不用 `multiplatform-settings`：存两个字符串而已，平台实现各十几行（Android `SharedPreferences`、iOS `NSUserDefaults`），且**落盘的同步性是与服务端救活机制配套的关键语义，不该藏在第三方库的默认参数里**
- 不带 HTTP engine：消费方提供
- **不含 UI**：登录界面归各 App 实现

加任何新依赖前先停下来问一遍值不值——auth 库是供应链攻击的最高价值目标。

## 开发

```bash
./gradlew :library:testAndroidHostTest              # CI 跑的（ubuntu 编不了 iOS）
./gradlew :library:compileKotlinIosSimulatorArm64   # iOS 侧编译需 macOS
```

**改动 `commonMain` 的形状后请本地跑一次 iOS 编译**（改 `expect` 签名、改 `TokenStore` 之类接口时，`iosMain` 的实现要跟着改）。CI 不做这件事：ubuntu runner 编不了 iOS，而为一个占位 target 在每个 PR 上起 macOS runner不划算。漏了也不会出坏产物——打 tag 时 `publish.yml` 跑在 macOS 上，编译不过就直接失败在构建阶段、早于任何上传，改完删 tag 重打即可。

发布：打裸版本号 tag（如 `0.1.0`）触发 CI 在 macos runner 上 `publishAndReleaseToMavenCentral`。

## License

Apache 2.0
