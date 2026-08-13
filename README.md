# loginbase-kt

> Kotlin Multiplatform client for [loginbase](https://github.com/HarlonWang/loginbase) — email OTP + social OAuth + session management.

[loginbase](https://github.com/HarlonWang/loginbase)（Cloudflare Workers 服务端库）的 KMP 客户端。目标平台：Android、iOS（arm64 + simulator arm64）。

## 协议契约

**协议的唯一权威是服务端仓的 [`docs/protocol.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md)，本仓不留副本。**

两仓独立版本线，版本号不追求相等——客户端有自己的版本，靠 `PROTOCOL_VERSION` 常量声明实现的是哪一版协议：

| 本库版本 | 实现的协议版本（= 服务端包版本） |
|---|---|
| 0.1.x | `loginbase@1.2.0` |

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

auth.sendCode("a@b.com")                                 // 邮箱验证码
auth.verifyCode("a@b.com", "123456")                     // 成功即落盘

openInBrowser(auth.githubSignInUrl("app://cb"))          // GitHub 登录，回跳带 otc
auth.exchangeOtc(otc)

openInBrowser(auth.githubLinkUrl("app://cb"))            // 已登录用户绑定 GitHub

auth.accessToken()                                       // 业务请求带上
auth.accessToken(forceRefresh = true)                    // 收到 401 时刷新重试
```

`HttpClient` 的 **engine 不由本库提供**——消费方 classpath 里要有（Android `ktor-client-okhttp` / iOS `ktor-client-darwin`），多数 KMP App 本来就有。

## 状态

核心已实现：`AuthClient`（邮箱验证码 / GitHub OAuth / link / refresh / 登出）、`TokenStore` 与两个平台实现、`AuthState`、**单飞 refresh**。23 个测试。

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
