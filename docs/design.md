# 设计决策

> 面向**维护者**。README 讲「怎么用」，这里讲「为什么这么设计、以及否掉了什么」。
>
> 代码里的注释只留局部的 why 和指向本文的指针——同一段论证在三个地方重复过，
> 改一处要同步三处，那种维护负担本身就是缺陷。

## 1. 单飞 refresh：锁的作用域是「一个进程」

### 为什么必须单飞

服务端每次 refresh 必轮换，并对「拿已作废令牌来刷」做**救活判定**——但救活有
**1h / 3 次护栏，超了按盗用撤销整条会话**，用户被强制登出。

并发刷新会白白烧这个配额。所以客户端必须保证同一时刻至多一条真实刷新在飞：互斥锁
串行化，进锁后若发现「等锁期间已经有人跑完了一整轮」，直接复用那一轮的结果。

**成功和失败一视同仁地共享**，这一点不能省。只共享成功的话，刷新失败时本地存储纹丝
不动，等待者看到的世界和「一次刷新都没发生过」一模一样，于是各自又发一次——而服务端
若已经处理掉那次请求（回执丢在回程），后续每一次都是拿已作废的令牌去刷，每次烧掉一格
配额，几轮就撞穿护栏。

### 为什么是「每进程一个实例」

锁是**实例字段**，两个实例就是两把锁。

| 场景 | 覆盖 |
|---|---|
| 同进程多协程并发 401 | ✅ 收敛成 1 次 |
| 进程被杀后重启重试 | ❌ 锁随进程消失（设计如此：走服务端的诚实重试救活，正常消耗 1 格配额） |
| 多进程（Android `:remote`、iOS App + Widget/Extension 各建实例） | ❌ 各刷各的，会烧配额 |

Logto 时代的教训更狠：那时 client 跟着 Activity 重建，旧实例的在途刷新与新实例互不
互斥，轮换竞态跨实例复活。所以文档反复强调**一个 App 一个 `AuthClient`**。

### 被否掉的方案：跨进程锁

**刻意不做。** 失败代价不对称：

- 没有它，最坏是多刷一次
- 有了它，最坏是**认证彻底卡死**

Supabase 用 Web Locks 做跨标签页锁，换来的正是一串孤儿锁与死锁故障。业界同形：
Auth0 的 `CredentialsManager` 同样只保证实例内串行，文档里明写「不要从多个实例调用
续期方法」。

有多进程结构时，跨进程互斥由消费方自己保证。

### 与 ktor `Auth` 插件的关系

ktor 的 `Auth` 插件也内建单飞，但它只协调**装了该插件的那一个 `HttpClient`**。App
通常有多个 client（业务 API、图片、第三方），各刷各的照样烧配额——所以两者是叠加不是
替代：可以在自己的 client 上装 `Auth` 插件、`refreshTokens` 回调里调
`authClient.refresh()`，由本库的锁保证全局只刷一次。

这样用是安全的：本库只收 engine、`HttpClient` 自建（见第 3 节），装了 `Auth` 插件的
那个 client 不会参与本库的 `POST /refresh`，不存在递归调用撞上不可重入锁的问题。

残留的一点浪费：多个业务 client 且 401 **错开**发生时，会多一次令牌轮换（不烧救活配额，
用的是有效令牌）。想连这次也省掉，可以在 `refreshTokens` 里先比对 `oldTokens?.accessToken`
与 `auth.accessToken()`——不一样就说明别人已经刷过了，直接用新的。属于可选优化，
不做也只是多一次正常轮换。

## 2. 为什么不解析 JWT

库拿到 access token 后原样存、原样用，不读 `exp`、不校验签名。

Logto 时代客户端要本地校验 id_token 的 `iat`/`exp`，设备时钟偏差超过容差就直接登录
失败、重试无用（2026-07-22 实测过模拟器慢 63 秒的坑）。改由服务端的 401 驱动刷新后，
**时钟偏差这一整类问题在客户端消失了**。

代价是过期时多一次往返，换来的是没有本地时间依赖。

### 被推迟的优化：主动刷新

反应式刷新的代价是**首个业务请求要走 3 个 RTT**（请求 → 401 → 刷新 → 重试），而不是 1 个。

服务端的 access TTL 是 **3600 秒**（`src/token.ts` 的 `ACCESS_TTL_SECONDS`，可用
`jwt.accessTtlSeconds` 覆盖），而 App 的典型使用间隔（早/午/晚）都超过一小时——
**几乎每次冷启动都会撞上**。

> 注意：这个代价**只是延迟**。401 驱动的刷新用的是有效 refresh token，正常轮换，
> **不消耗服务端的救活配额**（那个配额只在拿已作废令牌去刷时才动）。

想消掉它，得让服务端在 verify / exchange / refresh 的响应里返回 `expiresIn`（**相对秒数**，
不受设备时钟影响），客户端据此在启动阶段并行刷掉，把刷新从首个请求的关键路径上挪走。

**「把 access TTL 调长」不是免费的替代方案。** 服务端受保护端点只验 JWT 的签名与 `exp`，
**不查会话表**（`src/middleware.ts`）——所以 `DELETE /sessions` 之后旧 access token 在剩余
TTL 内照样有效，即

> 撤销窗口 = access TTL

TTL 调到 4 小时，登出后旧令牌就能再活 4 小时。那是拿安全换延迟。

**暂不做的理由**：收益方向确认了，但「每次冷启动 +2 RTT」到底有多难受**没有任何实测
数据**（TrendingAI 尚未接入）；而代价是确定的——`TokenPair` 加字段是破坏性 API 变更
（`TokenStore` 由消费方实现）、要引入时间依赖、协议要推到 1.4.0。等有真实体感数据再定。

## 3. 为什么只收 `HttpClientEngine`，不收整个 `HttpClient`

注入整个 client 意味着本库最安全敏感的那条请求（`POST /refresh`）要跑在一套**未知的
插件**上。已知的两颗地雷：

- ktor `Auth`：401 时它的回调里再调 `refresh()`，而当前协程已持有单飞锁，`Mutex`
  不可重入 → **永久挂起**
- ktor `HttpRequestRetry`：默认配置就是「5xx 与 IOException 重试 3 次」
  （`HttpRequestRetry.kt:75-78`），单飞辛苦收敛成的 1 次刷新会在库看不见的地方被放大
  成 4 次 → 一轮撞穿救活护栏

而注入真正值钱的能力（证书固定、代理、自定义 DNS、OkHttp 拦截器）**全在 engine 级**。
交出 engine 就够了，不必交出 client。连接池也是 engine 级的，所以这样并不会多出一个池。

**也评估过「完全内置、不给注入」**：它确实更少一条不变式，但会关掉证书固定——对 auth
库来说那是最不该关掉的能力——而且库自己的测试仍需要一个 `internal` 注入口，
`injected ?: 自建` 那个分支根本删不掉，省下的几乎全是文档。真正的复杂度断崖在
「注入 client → 注入 engine」，不在「注入 engine → 完全内置」。

## 4. 为什么 `refresh()` 返回 outcome，其余方法抛异常

看起来是两套惯例，但它们区分的东西不同：

- 其余方法的失败区分的是**原因**（服务端拒绝 / 没连上 / 响应畸形）→ 异常合适
- `refresh()` 的失败区分的是**处置方式**：`SessionEnded` 必须引导重新登录、`Failed`
  该重试、`NoSession` 压根没会话。sealed 的穷尽 `when` 能逼调用方各自想清楚，`catch`
  不会。而且刷新时网络失败是**预期结果**，不是异常情况。

两边共用一套词汇：`RefreshOutcome.Failed.cause` 也是 `LoginbaseException`。

## 5. 为什么登录态是四态

每一态对应一个**不同的 UI 处置**，多一态就是多一种要想清楚的情况：

- `RefreshFailed` 与 `SignedOut` 差得很远——会话没被清、服务端也没说它死了，多半只是
  弱网。把它当登出处理就是把漫游、地铁里的用户踢下线（Logto 时代的原事故）
- `SignOutReason` 三分是因为**文案不同**：用户自己点的登出弹「登录已失效」是骚扰，
  而被服务端撤销了却一声不吭跳回登录页，用户会以为是 App 出了 bug

参照：supabase-kt 当年也是把 `SessionStatus.NetworkError` 重设计成 `RefreshFailure`，
并用 `NotAuthenticated(isSignOut: Boolean)` 区分登出来源。

## 6. 依赖红线

**核心 artifact = `ktor-client-core` + `kotlinx-serialization-json` +
`kotlinx-coroutines-core`，仅此三个。** 可选平台模块只允许**该平台的一等公民 API**
（`loginbase-kt-browser` 的 `androidx.browser` / `kotlinx-coroutines-android`——AndroidX
与 JetBrains，信任级别同系统 SDK；supabase-kt 的 `Auth` 模块同此分法）。

具体地：

- 不用 `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json`：请求体手工
  序列化、响应手工解析
- 不用 `multiplatform-settings`：存两个字符串而已，平台实现各十几行（Android
  `SharedPreferences`、iOS `NSUserDefaults`），且**落盘的同步性是与服务端救活机制配套的
  关键语义**，不该藏在第三方库的默认参数里
- 不带 HTTP engine：消费方提供（`HttpClient` 由库自建，见第 3 节）
- **不含 UI**：登录界面归各 App 实现

加任何新依赖前先停下来问一遍值不值——auth 库是供应链攻击的最高价值目标。

## 7. iOS target 为什么是占位

`iosArm64` / `iosSimulatorArm64` 两个 target 存在，但**长期只作占位，不承诺可用**。保留
它们的实际作用只有一个：让 `commonMain` 在编译期就被约束住，不会悄悄写死 JVM API。

除此之外不要按「支持 iOS」来接入，三条缺口都还在：

- `NSUserDefaultsTokenStore` 与 iOS 侧的语言取值**从未在真机链路上验证过**
- **没有做 Swift 互操作保障**：public suspend 函数没标 `@Throws`，Swift 侧遇到
  `LoginbaseException` 是直接崩溃而不是抛 Swift error；`authState` 是 Kotlin
  `StateFlow`，Swift 里也拿不到
- CI 不跑 iOS 测试（`commonTest` 只在 Android 上跑过）

转正条件就是把这三条补齐。在那之前，iOS 侧的社交登录仍是 `signInUrl()` + 自己开浏览器。
