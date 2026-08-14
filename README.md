# loginbase-kt

> Kotlin Multiplatform client for [loginbase](https://github.com/HarlonWang/loginbase) — email OTP + social OAuth + session management.

[loginbase](https://github.com/HarlonWang/loginbase)（Cloudflare Workers 服务端库）的 KMP 客户端。目标平台：Android、iOS（arm64 + simulator arm64）。

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
    tokenStore = SharedPreferencesTokenStore(context),   // iOS: NSUserDefaultsTokenStore()
)
auth.restore()                                           // 启动时恢复登录态

auth.sendCode("a@b.com")                                 // 邮箱验证码（自动带上 App 显示语言）
auth.verifyCode("a@b.com", "123456")                     // 成功即落盘

openInBrowser(auth.githubSignInUrl("app://cb"))          // GitHub 登录，回跳带 otc
auth.exchangeOtc(otc)

openInBrowser(auth.githubLinkUrl("app://cb"))            // 已登录用户绑定 GitHub

auth.accessToken()                                       // 业务请求带上
auth.accessToken(forceRefresh = true)                    // 收到 401 时刷新重试
```

`HttpClient` 的 **engine 不由本库提供**——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`），多数 KMP App 本来就有。

### 单飞 refresh 的边界：每进程一个实例

服务端每次 refresh 必轮换，并对「拿已作废令牌来刷」做救活判定——**救活有 1h/3 次护栏，超了按盗用撤销整条会话**（用户被登出）。并发刷新会白白烧这个配额，所以本库用互斥锁 + 进锁后重读把并发收敛成一次真实请求。

**锁是实例字段，作用域是「一个进程」**：

| | 覆盖 |
|---|---|
| 同进程多协程并发 401 | ✅ 收敛成 1 次 |
| 进程被杀后重启重试 | ❌ 锁随进程消失（设计如此：走服务端的诚实重试救活，正常消耗 1 格配额） |
| **多进程**（Android `:remote`、iOS App + Widget/Extension 各建实例） | ❌ 各刷各的，会烧配额 |

有多进程结构时，跨进程互斥需消费方自己保证。**本库刻意不做跨进程锁**——失败代价不对称：没有它最坏是多刷一次，有了它最坏是认证彻底卡死（Supabase 用 Web Locks 做跨标签页锁，换来的正是一串孤儿锁与死锁故障）。业界同形：Auth0 的 `CredentialsManager` 同样只保证实例内串行，文档里明写「不要从多个实例调用续期方法」。

> Ktor 的 `Auth` 插件也内建单飞，但它只协调**装了该插件的那一个 `HttpClient`**。App 通常有多个 client（业务 API、图片、第三方），各刷各的照样烧配额——所以两者是叠加不是替代：可以在自己的 client 上装 `Auth` 插件、`refreshTokens` 回调里调 `authClient.refresh()`，由本库的锁保证全局只刷一次。

## 状态

核心已实现：`AuthClient`（邮箱验证码 / GitHub OAuth / link / refresh / 登出）、`TokenStore` 与两个平台实现、`AuthState`、**单飞 refresh**、**邮件语言上报**。34 个测试。

### 邮件语言（protocol 1.3.0）

`sendCode` 会把 **App 显示给用户的语言**随请求上报，服务端据此选验证码邮件的模板。规则只有两条：

```kotlin
AuthClient(baseUrl, store)                                  // 什么都不写 = 跟随系统语言
AuthClient(baseUrl, store, localeProvider = { settings.tag })  // App 内自选；返回 null = 没意见 → 回落系统语言
```

`null`（以及空串、`und`）只有一个含义——**「我没意见」**，回落系统语言，**不是「不要发」**。想一律某种语言就返回定值，如 `{ "en" }`。服务端对未知语言静默回落，故这条链路**不产生任何新的错误分支**。

平台取值：Android `Locale.getDefault().toLanguageTag()`（已跟随 per-app language）；iOS `Bundle.main.preferredLocalizations.first`（App 实际显示的语言，而非系统首选语言）。两端取不到时字段整个省略，交服务端兜底。

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

发布：打裸版本号 tag（如 `0.1.0`）触发 CI 在 macos runner 上 `publishAndReleaseToMavenCentral`。

## License

Apache 2.0
