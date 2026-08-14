# 待办清单（代码审查产出）

> 来源：2026-08-14 全仓代码审查（架构 / 复杂度 / 可维护性 / 接入体验四个维度，对照 supabase-kt、Auth0、Kotlin 官方库指南）。
>
> **范围约定**：iOS 长期只做占位，不承诺可用，故 iOS 侧的 Swift 互操作、`NSUserDefaultsTokenStore`、`PlatformLocale.ios.kt` 的功能性问题一律不计；`explicitApi()` 视为风格选择，不作为建议项。
>
> 共 37 条。P0 是「只有发 1.0 前这一个窗口」的破坏性变更，优先级高于 P1 的正确性 bug。

---

## P0 — 破坏性 API 变更，发版后再改就是 breaking change（8 条）

### [x] 1. provider 硬编码在方法名里 — 已完成

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/AuthClient.kt:167` `:186`
- **问题**：`githubSignInUrl` / `githubLinkUrl` 把 provider 焊死在方法名上，而协议路径本身是 `/oauth/{provider}/start`。加 Google/Apple 就是复制两个方法 + 两段文档。
- **修法**：改成 `signInUrl(provider: OAuthProvider, redirect: String)` / `linkUrl(provider, redirect)`。`OAuthProvider` 用带 wire 值的枚举或 value class，留 `Custom(String)` 口子，避免服务端加 provider 时客户端非升级不可。
- **落地**：新增 `OAuthProvider`（value class，非枚举——服务端 provider 集合由服务端 App 配置，枚举等于每次配置变化都 breaking）；两个方法改名并泛化，provider 走 `encodeURLPathPart()` 编码；库未发布，不留 deprecated 兼容壳。

### [x] 2. 一个类里两套错误惯例，且异常无根类型 — 已完成

- **位置**：`AuthClient.kt:132` `:153` `:171` `:186`（抛异常）对 `:234`（返回 sealed）；`AuthModels.kt:71` `:79`
- **问题**：`refresh()` 返回 `RefreshOutcome`，其余四个方法抛异常，调用方要在同一个类上同时写 `when` 和 `try/catch`。`AuthApiException` 与 `NotAuthenticatedException` 各自直接继承 `Exception`，没有公共基类，写不出 `catch (e: LoginbaseException)`。
- **修法**：加 `public sealed class LoginbaseException`，下挂 `Api` / `Network` / `NotAuthenticated`；两种惯例二选一（保留异常更省改动，`refresh()` 的三态可做成子类型 + `Result`）。
- **落地**：新增 `LoginbaseException` sealed 根，下挂 `Api` / `Network` / `MalformedResponse` / `Storage` / `NotAuthenticated`；`AuthApiException`、`NotAuthenticatedException` 撤销。
- **与原修法的偏差（有意）**：**没有**把 `refresh()` 收进异常。原修法写的「二选一」在动手后判断是错的——`refresh()` 的三态区分的是**处置方式**（`SessionEnded` 必须重新登录 / `Failed` 该重试 / `NoSession` 没会话）而不是失败原因，sealed 的穷尽 `when` 能逼调用方各自想清楚，换成 `catch` 就不会；而且刷新时网络失败是**预期结果**，不是异常情况。真正的病是「两套惯例没有共同词汇」，已通过把 `Failed.cause` 收窄成 `LoginbaseException` 治掉。

### [x] 3. 网络层裸露 Ktor 异常 — 已完成

- **位置**：`AuthModels.kt:77` 的注释、`AuthClient.kt:258`
- **问题**：网络失败时调用方接到的是 `IOException` / `HttpRequestTimeoutException` 等 Ktor 类型。这与「engine 不由本库提供、Ktor 只是实现细节」的定位矛盾——调用方被迫认识 Ktor 的异常层次，而库不承诺不换传输层。
- **修法**：统一包成第 2 条的 `LoginbaseException.Network(cause)`，原始异常放 `cause`。
- **落地**：`request()` 与 `refresh()` 的传输层出口都包成 `Network`，原始异常放 `cause`；`request()` 新增的 catch 从一开始就先 rethrow `CancellationException`（不新造第 11 条那类 bug）。

### [ ] 4. 构造函数直接扩展会破二进制兼容

- **位置**：`AuthClient.kt:69-74`
- **问题**：4 参构造函数，往后加 `logger` / `retryPolicy` / `oauthProviders` 只能继续加默认参数。Kotlin 默认参数在 JVM 上编译成 synthetic constructor + bitmask，**加一个参数就破二进制兼容**——库已配好 Maven Central 发布链路，这是硬伤。
- **修法**：`AuthClient(config: LoginbaseConfig)`，或 `AuthClient.build { }` DSL。

### [ ] 5. `fromWire()` 不该是公开 API

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/Protocol.kt:55` `:87`
- **问题**：这两个是响应解码器，只有 `AuthClient` 内部解析 JSON 时用；消费方拿到的已经是枚举，永远不需要它。公开它等于把 wire 字符串写进公开契约，服务端改错误码字符串就成了客户端的 breaking change。
- **修法**：降 `internal`。KMP 里 test source set 是 main 的 friend module（`usableTag()` 现在就是这么用的），`ProtocolTest` 那 4 个测试一行都不用改。

### [ ] 6. `AuthError.wire` / `RefreshFailure.wire` 属性同理

- **位置**：`Protocol.kt:17` `:67`
- **问题**：同第 5 条。消费方要原始串的话 `AuthApiException.rawError` 已经给了。
- **修法**：降 `internal`。

### [ ] 7. `platformLanguageTag()` 是顶层 public expect 函数，锁死太早

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/PlatformLocale.kt:11`
- **问题**：公开它的用例是真实的（README 那条 `settings.tag ?: platformLanguageTag()` 回落链），但作为**顶层 expect 函数**一旦发版就固定了：将来想加参数、想换成 `LocaleProvider` 接口都是破坏性变更，而且它占了包的顶层命名空间。
- **修法**：挪进 object，如 `Loginbase.systemLanguageTag()`。

### [ ] 8. `AuthState` 只有 3 态，缺「刷新失败但会话还在」

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/AuthModels.kt:9-20`
- **问题**：`RefreshOutcome.Failed` 时 `authState` 仍是 `SignedIn`，UI 无从知道「令牌可能已过期、下个请求会 401」。另外 `SignedOut` 不区分「用户主动登出」和「会话被撤销」，走 `authState` 这条链路时信息永久丢失，UI 弹不出「登录已失效」。
- **参照**：supabase-kt 为此专门把早期的 `SessionStatus.NetworkError` 重设计成 `RefreshFailure`，并用 `NotAuthenticated(isSignOut: Boolean)` 区分来源。
- **修法**：补 `RefreshFailure` 态；`SignedOut` 加 `isUserInitiated` 或拆两态。
- **为什么是 P0**：往公开 sealed interface 加子类型会让消费方已有的穷尽 `when` 编译失败，属破坏性变更。

---

## P1 — 正确性缺陷，生产会咬人（7 条）

### [ ] 9. `signOut` 与在途 refresh 无互斥，登出会被复活

- **位置**：`AuthClient.kt:303`（`signOutAll` `:313` 同）
- **问题**：`signOut()` 不走 `refreshMutex`。时序：refresh 持锁、请求在飞 → 用户点登出 → `tokenStore.clear()` + `SignedOut` → 刷新响应到达 → `persist(tokens)` 把新轮换的令牌**写回存储**并置 `SignedIn`。用户点了登出，几百毫秒后又变回登录态，手里还是一对服务端刚发的有效令牌。
- **现状**：`AuthClientTest.kt:280` 只覆盖了「signOut 发生在 refresh **进锁前**」，锁内注释也只讨论了这一种。
- **修法**：`signOut` 也进锁，或加会话 epoch，`persist` 前校验没被换过。

### [ ] 10. 单飞只共享「成功」不共享「失败」

- **位置**：`AuthClient.kt:245`
- **问题**：进锁后只在 `current.refreshToken != before.refreshToken`（别人刷成功）时复用。刷新**失败**时 token 没变，排队的 N−1 个等待者会各自再打一次服务端。8 个并发 401 → 第一个 45s 熔断（服务端很可能已完成轮换）→ 后 7 个依次拿已作废的 r0 去刷 → 每次触发救活判定 → 第 4 次撞穿 1h/3 次护栏 → **整条会话按盗用撤销**。这正是单飞要防的事。附带：最后一个等待者最坏排队 8×45s ≈ 6 分钟。
- **修法**：缓存本轮 outcome（含 `Failed`）给同一批等待者复用，用 generation 计数标记「本轮」。

### [ ] 11. `CancellationException` 在 4 处被吞

- **位置**：~~`AuthClient.kt:289`（`persist` 外的 `catch (e: Exception)`）~~ 已随第 2 条修掉；剩 `parseJsonOrNull` 的 `runCatching`、`signOut` / `signOutAll` 的两处 `runCatching`
- **问题**：`:255` 刚立下「取消必须如实传播」的规矩，同一函数里 `persist(tokens)` 的 `catch (e: Exception)` 却会捕获它并转成 `Failed`——已取消的协程正常返回，破坏结构化并发。`runCatching` 捕获 `Throwable`，问题相同。
- **修法**：各处补 `catch (e: CancellationException) { throw e }`，或换成显式 `try/catch` 只捕获具体类型。

### [ ] 12. 无条件 rethrow 把两种取消混为一谈

- **位置**：`AuthClient.kt:255`
- **问题**：ktor 的 `HttpRequestLifecycle` 会在 client job 被 cancel 时连带取消在途请求。消费方退登/重建 DI 时 `cancel()` 自己的 HttpClient，此刻在飞的 refresh 会抛出一个**与调用方无关**的 `CancellationException`，被原样抛出后调用方的 `launch` 当成自己被取消而静默丢弃，UI 永远等不到 `Failed`。
- **修法**：加 `currentCoroutineContext().ensureActive()` 守卫——只有当前协程确实被取消才 rethrow，否则归 `Failed`。

### [ ] 13. `injected.config {}` 让消费方的 `client.close()` 关不掉 engine

- **位置**：`AuthClient.kt:103`
- **问题**：ktor 的 `HttpClient.config()` 把 `manageEngine` 原样传给派生 client，构造时 `engine.clientRefCount.incrementAndGet()`。消费方注入 `HttpClient(OkHttp){}` 后 refcount 1→2，消费方 close 只降到 1，engine 永不关闭；派生 client 从不 close，refcount 回不到 0。DI 重建/登出关 client 时 OkHttp 线程泄漏。
- **修法**：改成在 `refresh()` 里用 `withTimeout` 包一层，与消费方的 client 配置正交，同时顺带解决第 14 条。

### [ ] 14. `pluginOrNull(HttpTimeout)` 判据太粗

- **位置**：`AuthClient.kt:100`
- **问题**：`HttpTimeoutConfig` 三个字段互相独立且默认 null，只有 `requestTimeoutMillis` 约束整次调用总时长。消费方只配了 `connectTimeoutMillis`（很常见）时插件存在、保险丝被跳过，连上之后服务端不回照样无限挂住、**锁永久被持有**——正是这段代码声称要消灭的形态。
- **修法**：同第 13 条改 `withTimeout` 一并解决；若保留现方案，至少改判 `requestTimeoutMillis` 而非插件是否存在。

### [ ] 15. README 建议的 Auth 插件用法会死锁

- **位置**：`README.md:64`
- **问题**：建议消费方在自己的 client 上装 ktor `Auth` 插件、`refreshTokens` 回调里调 `authClient.refresh()`；而文档另一处鼓励注入同一个 client 复用连接池。两者组合后，本库的 `POST /refresh` 也会过该插件，服务端返回 401 时插件回调 `authClient.refresh()`，当前协程已持有 `refreshMutex`——`Mutex` 不可重入，`withLock` 永久挂起。
- **修法**：文档里明写「装了 `Auth` 插件的那个 client 不要注入给 AuthClient」；根治方案见第 25 条。

---

## P2 — 健壮性与一致性（9 条）

### [ ] 16. `.jsonPrimitive.int` 会抛错并掩盖真实的 API 错误

- **位置**：`AuthClient.kt:139` `:353`（另 `:266` `:346` `:373` `:384` 同类）
- **问题**：`jsonPrimitive` 在元素非 primitive 时抛 `IllegalStateException`，`.int` 在内容非数字时抛 `NumberFormatException`。`:353` 尤其别扭——它在**构造 `AuthApiException` 的参数求值期**抛出，会把真正的协议错误整个掩盖掉。这与 `:78`「服务端加字段不该炸老客户端」的立场相悖。
- **修法**：全部改 `intOrNull` / 安全取值。

### [ ] 17. 手工 JSON 解析散落 6 处，没有收口

- **位置**：`AuthClient.kt:139` `:193` `:266` `:346` `:353` `:373` `:384`
- **问题**：第 16 条是症状，这是病因。「不引 ContentNegotiation」这个决策成立（依赖最小集是核心红线），但**手工解析 ≠ 不封装**。
- **修法**：加 20 行内部扩展 `JsonObject.stringOrNull(key)` / `intOrNull(key)` / `boolOrNull(key)`，一次性收口，不增加任何依赖。

### [ ] 18. `PROTOCOL_VERSION` 用 `const val`，恰好破坏它自己的用途

- **位置**：`Protocol.kt:10`
- **问题**：`const val` 是编译期常量，**会内联进调用方字节码**。消费方编译时是 1.3.0，之后库升到实现 1.4.0 协议、消费方只换 jar 不重编——读到的还是 `"1.3.0"`。而这个常量存在的唯一理由就是「声明我实现的是哪一版协议」。
- **修法**：去掉 `const`，改普通 `val`。

### [ ] 19. `InMemoryTokenStore` 公开、非线程安全、却写着「生产可用」

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/TokenStore.kt:38-53`
- **问题**：`private var tokens` 无任何同步，类注释却写「用于测试，**以及「本次启动内有效即可」的场景**」——后半句是在说生产可用。而 `AuthClient` 文档反复强调「多协程并发调 refresh」，两件事凑一起就是数据竞争。
- **修法**：加 Mutex，或删掉「生产可用」措辞、明确它只是测试替身。

### [x] 20. `AuthApiException(200, ...)` 混淆「服务端说不行」与「响应体畸形」 — 已完成（随第 2 条）

- **位置**：`AuthClient.kt:194` `:381`
- **问题**：HTTP 200 但响应体缺 `authorizeUrl` / 缺 token 时，抛的是 `AuthApiException(status=200, error=UNKNOWN)`。而这个类的文档说的是「服务端按协议返回的错误」。调用方无法区分「服务端拒绝」和「响应不符合协议」。
- **修法**：单列一个 `LoginbaseException.MalformedResponse(field)`（挂在第 2 条的根类型下）。
- **为什么提前做**：`LoginbaseException` 是 sealed，晚一步加子类型就是又一次破坏性变更（同第 8 条的道理）。顺带把 `refresh()` 里两处 `IllegalStateException("empty refresh response")` 也归了进来。

### [ ] 21. 忘记调 `restore()` 会让 `authState` 与实际行为不同步

- **位置**：`AuthClient.kt:116` 对 `:205` `:212`
- **问题**：`accessToken()` 直接读存储，与 `authState` 无关。所以没调 `restore()` 时，`authState` 是 `Unknown` 而 `accessToken()` 已经能返回有效令牌——两个对外信号互相矛盾，且没有任何机制提醒调用方漏了这一步。
- **修法**：要么 `restore()` 在首次 `accessToken()` / `refresh()` 时惰性自动执行，要么在 `Unknown` 态下让读取路径先补一次恢复。

### [ ] 22. 自建的 `HttpClient` 没有关闭出口

- **位置**：`AuthClient.kt:92-107`
- **问题**：默认路径下库自己 `HttpClient { }`，但 `AuthClient` 没有 `close()`，这个 client 及其 engine 随进程存活。单例定位下影响有限，但测试和 DI 重建场景会积累。
- **修法**：加 `close()`（只关自建的，不碰注入的），或在类文档里明说不需要关闭及其理由。

### [ ] 23. 私有 `isSuccess()` 与 ktor 内建重复

- **位置**：`AuthClient.kt:399`
- **问题**：ktor 已有 `io.ktor.http.isSuccess()`（`HttpStatusCode.kt:195`，实现完全相同 `value in (200 until 300)`）。
- **修法**：删掉私有版本，改 import。

### [ ] 24. `encodeDefaults = true` 是死配置

- **位置**：`AuthClient.kt:79`
- **问题**：全程只用 `JsonObject.serializer()` 手工序列化，没有任何带默认值的 `@Serializable` 类，这行不起作用。
- **修法**：删掉，或留注释说明为什么预留。

---

## P3 — 架构、工程与文档（12 条）

### [ ] 25. 缺 HTTP 集成件，401 重试样板留给每个调用点

- **位置**：新增
- **问题**：库只给 `accessToken(forceRefresh = true)`，没有任何 HTTP 集成件；README 只好建议消费方自己装 ktor `Auth` 插件，而那条建议会死锁（第 15 条）。对比：supabase-kt 由 `SupabaseClient` 内部统一挂载；Auth0 文档直接给 OkHttp Interceptor 范式。
- **修法**：官方提供 `AuthClient.installOn(HttpClient)`，内部给自身请求打标记以规避递归刷新。

### [ ] 26. OAuth 浏览器环节与回调解析都甩给了 App

- **位置**：`AuthClient.kt:160-168`
- **问题**：`githubSignInUrl()` 只返回字符串，README 让消费方自己 `openInBrowser(...)`。业界（Auth0、Firebase、AppAuth、Clerk）都把这步收进 SDK，Android 用 Custom Tabs / 新的 AuthTab——不是为省事，而是回调拦截、用户取消这些点每个接入方都会踩。`:161` 的注释自己强调了「不要用内置 WebView」，**但把执行这条纪律的责任推给了调用方**。另外 `exchangeOtc` 要 App 自己从 deep link 抠 `otc`，而 link 流程回跳的是 `linked=github` / `error=already_linked`，完全另一套，库连 `parseCallback(uri)` 都没提供。
- **修法**：Android 侧提供 Custom Tabs 启动器 + `parseCallback(uri)`；至少先补 `parseCallback`，成本最低收益最直接。

### [ ] 27. `AuthClient` 是上帝类

- **位置**：`AuthClient.kt` 全文（399 行）
- **问题**：一个类同时做 URL 拼装、HTTP 传输、手工 JSON 解析、协议错误映射、会话状态机、存储编排、单飞锁、超时保险丝。直接后果是**测试只能从最外层用 MockEngine 打**，于是有了第 29 条那个 45 秒的测试。
- **修法**：拆成 `AuthApi`（传输+协议）/ `SessionManager`（状态+锁+刷新策略）/ `TokenStore`。现在规模小拆是廉价的；等加了 provider、加了主动刷新再拆就贵了。

### [ ] 28. 推动服务端在响应里返回 `expiresIn`

- **位置**：协议层，需在服务端仓开 issue
- **问题**：不解析 JWT 的决策成立（`AuthClient.kt:50` 的时钟偏差理由很扎实），但代价是**每次冷启动第一个请求必然 401 → 多一次往返 + 一次刷新配额**。Auth0 的 `CredentialsManager.getCredentials(minTtl)` 主动刷新正是为了省掉这个。
- **修法**：让服务端在 verify/refresh 响应里返回 `expiresIn`（**相对秒数**）。相对时长天然不受设备时钟偏差影响，两个好处能同时拿到。

### [ ] 29. 有个测试真实耗时 45 秒

- **位置**：`library/src/commonTest/kotlin/wang/harlon/loginbase/AuthClientTest.kt:116`
- **问题**：其余 33 个测试合计约 0.2s。`MockEngine` 与 `HttpTimeout` 的 killer 协程都跑在 `ioDispatcher()`，不是 `runTest` 的虚拟时间调度器，所以是墙钟时间；已占掉 `runTest` 默认 60s 超时的 75%，机器抖动时会假失败。
- **修法**：把熔断值做成 `internal` 可注入参数，测试传小值。

### [ ] 30. 补两个缺失的并发测试

- **位置**：`AuthClientTest.kt`
- **问题**：第 9 条（signOut vs 在途 refresh）和第 10 条（单飞失败共享）都没有测试覆盖，修完必须锁住。
- **修法**：各补一个——前者用可控延迟的 MockEngine 在请求在飞时调 `signOut`，断言最终态是 `SignedOut` 且存储为空；后者断言 N 个并发调用在服务端失败时只打一次。

### [ ] 31. 公开面收窄之后上 binary-compatibility-validator

- **位置**：`library/build.gradle.kts`
- **问题**：库已配好 Maven Central 发布链路，但没有 API dump，也没有 `apiCheck`。第 1/4/5/6/7/8 条这些收窄如果发版后再做，CI 不会提醒这是破坏性变更。
- **修法**：**顺序很重要**——先做完 P0 的公开面调整，再 `apiDump` 定基线并接进 CI。

### [ ] 32. 删掉 XCFramework 配置

- **位置**：`library/build.gradle.kts:12-13` `:35-42`
- **问题**：XCFramework 是给原生 Swift App 的分发格式。iOS 只做占位的话没人消费它，但它逼着 `publish.yml` 必须跑 macos runner（比 ubuntu 慢且贵，timeout 开到 60 分钟）。
- **修法**：保留 `iosArm64()` / `iosSimulatorArm64()` 两个 target（这才是「防止 common 代码写死 JVM API」的实际作用），删掉 `XCFramework` / `isStatic` / `binaryOption` 那段。

### [ ] 33. iOS 占位 target 在 CI 里从不编译，会静默腐烂

- **位置**：`.github/workflows/build.yml:28`
- **问题**：`build.yml` 跑在 ubuntu、只跑 `testAndroidHostTest`（编不了 iOS，合理）。于是 iOS 侧的编译错误要等到**打 tag 触发 `publish.yml`（macos runner）** 才暴露——那时 CI 已经在跑发布流程，一个占位 target 编译不过就能让一次正式发布失败。
- **修法**：在 `publish.yml` 的发布步骤前加一次 `compileKotlinIosSimulatorArm64` 前置检查（快速失败，不污染发布），或单开一个低频的 macos 定时编译 job。

### [ ] 34. README 会误导使用者以为 iOS 可用

- **位置**：`README.md:5` `:32`
- **问题**：第 5 行写「目标平台：Android、iOS（arm64 + simulator arm64）」，第 32 行用法示例里直接给了 `// iOS: NSUserDefaultsTokenStore()`。接入方按示例接了才会发现是占位。
- **修法**：明写 iOS target 当前仅为占位/编译保障，不承诺可用。

### [ ] 35. 注释漂移与三处重复

- **位置**：`AuthClientTest.kt:123`；`README.md:50-64` / `AuthClient.kt:36-48` / `AuthClient.kt:216-232`
- **问题**：测试注释引用的 `REFRESH_TIMEOUT_MS` 常量不存在（实际叫 `LOCK_FUSE_TIMEOUT_MS`）；单飞边界那段论述在 README、类 KDoc、`refresh()` KDoc 里重复了三遍，改一处要同步三处。注释质量本身很高（每个决策都有 why + 事故出处），但密度已经产生维护负担。
- **修法**：改掉那个常量名；把「设计决策」沉到 `docs/design.md`，代码注释只留一句指针。

### [ ] 36. POM 里作者名拼写错误

- **位置**：`library/build.gradle.kts:81`
- **问题**：`name.set("HarlanWang")`，而 `id` 是 `HarlonWang`、仓库和 URL 也都是 `HarlonWang`。这会进入 Maven Central 的永久元数据。
- **修法**：改成 `HarlonWang`。

---

## 可选（不作为建议）

### [ ] 37. 满篇 `public` 关键字

`explicitApi()` 没有启用，Kotlin 默认可见性就是 public，所以这 40 多处关键字在编译器看来等于没写；interface 成员、`companion object`、嵌套在 public sealed interface 里的 `data object` 更是双重冗余。要么开 `explicitApi()` 让它有意义，要么全删（行为完全不变）。**纯风格选择，两个方向都成立，不做推荐。**

---

## 落地顺序

| 步骤 | 内容 | 提交方式 |
|---|---|---|
| 1 | **API 重构**：第 1–8 条 + 第 18、20 条（都改公开面，一起改省得反复破坏） | feature 分支 + PR |
| 2 | **并发修复**：第 9–15 条 + 第 30 条测试 | feature 分支 + PR |
| 3 | **清理**：第 16、17、19、21–24 条 | 可直接提交 |
| 4 | **工程收尾**：第 31–36 条（第 31 条 BCV 必须排在步骤 1 之后） | 可直接提交 |
| 5 | **单独排期**：第 25–29 条（架构与协议层，需与服务端仓协同） | 各自 PR |

---

## 参考

- [supabase-kt SessionStatus.kt](https://github.com/supabase-community/supabase-kt/blob/master/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/status/SessionStatus.kt) — 第 8 条的状态模型参照
- [supabase-kt CHANGELOG（RefreshFailure 重设计）](https://github.com/supabase-community/supabase-kt/blob/master/CHANGELOG.md)
- [Auth0.Android CredentialsManager.kt](https://github.com/auth0/Auth0.Android/blob/main/auth0/src/main/java/com/auth0/android/authentication/storage/CredentialsManager.kt) — 第 28 条的 `minTtl` 主动刷新参照
- [Backward compatibility guidelines for library authors | Kotlin Docs](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html) — 第 4、31 条
- [Building a Kotlin library for multiplatform | Kotlin Docs](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html)
- [Simplify authentication using Auth Tab | Chrome for Developers](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) — 第 26 条
- [OAuth for Mobile Apps — Best Practices | Curity](https://curity.io/resources/learn/oauth-for-mobile-apps-best-practices/) — 第 26 条
