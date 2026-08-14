# 待办清单（代码审查产出）

> 来源：2026-08-14 全仓代码审查（架构 / 复杂度 / 可维护性 / 接入体验四个维度，对照 supabase-kt、Auth0、Kotlin 官方库指南）。
>
> **范围约定**：iOS 长期只做占位，不承诺可用，故 iOS 侧的 Swift 互操作、`NSUserDefaultsTokenStore`、`PlatformLocale.ios.kt` 的功能性问题一律不计。`explicitApi()` 的取舍见第 37 条（已决定不开，代价与缓解手段记录在案）。
>
> 共 34 条。P0 是「只有发 1.0 前这一个窗口」的破坏性变更，优先级高于 P1 的正确性 bug。

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

### [x] 4. 构造函数直接扩展会破二进制兼容 — 已完成

- **位置**：`AuthClient.kt:69-74`
- **问题**：4 参构造函数，往后加 `logger` / `retryPolicy` / `oauthProviders` 只能继续加默认参数。Kotlin 默认参数在 JVM 上编译成 synthetic constructor + bitmask，**加一个参数就破二进制兼容**——库已配好 Maven Central 发布链路，这是硬伤。
- **修法**：`AuthClient(config: LoginbaseConfig)`，或 `AuthClient.build { }` DSL。
- **落地**：`AuthClient(baseUrl, tokenStore) { ... }`——必填的留位置参数，可选的进 `LoginbaseConfig` DSL。
- **注意（原修法里没写对的一点）**：`AuthClient(config: LoginbaseConfig)` 配 **data class** 是解决不了问题的——data class 的构造函数默认参数同样编译成 bitmask synthetic constructor，加字段照样破兼容。真正起作用的是让 `LoginbaseConfig` **可变**（`var` 属性）：加一个选项等于加一个字段 + getter/setter，这才是二进制兼容的。代价是配置对象可被调用方存出去后修改，故 `AuthClient` 在构造期把值读成不可变字段，并补了测试锁住这个语义。
- **参照**：ktor `HttpClient(engine) {}`、supabase-kt `createSupabaseClient(url, key) {}` 都是「必填位置参数 + 可选 DSL」这个分法。

### [x] 5. `fromWire()` 不该是公开 API — 已完成

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/Protocol.kt:55` `:87`
- **问题**：这两个是响应解码器，只有 `AuthClient` 内部解析 JSON 时用；消费方拿到的已经是枚举，永远不需要它。公开它等于把 wire 字符串写进公开契约，服务端改错误码字符串就成了客户端的 breaking change。
- **修法**：降 `internal`。KMP 里 test source set 是 main 的 friend module（`usableTag()` 现在就是这么用的），`ProtocolTest` 那 4 个测试一行都不用改。
- **落地**：两个 `companion object` 连同 `fromWire` 一起降 `internal`；`ProtocolTest` 果然一行未改，全绿。

### [x] 6. `AuthError.wire` / `RefreshFailure.wire` 属性同理 — 已完成

- **位置**：`Protocol.kt:17` `:67`
- **问题**：同第 5 条。消费方要原始串的话 `AuthApiException.rawError` 已经给了。
- **修法**：降 `internal`。
- **落地**：已降。`LoginbaseException.Api.rawError` 的默认值 `= error.wire` 引用了 internal 属性，编译无碍（默认参数不受公开 API 可见性约束，那条限制只针对 inline 函数）。

### [x] 7. `platformLanguageTag()` 是顶层 public expect 函数，锁死太早 — 已完成

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/PlatformLocale.kt:11`
- **问题**：公开它的用例是真实的（README 那条 `settings.tag ?: platformLanguageTag()` 回落链），但作为**顶层 expect 函数**一旦发版就固定了：将来想加参数、想换成 `LocaleProvider` 接口都是破坏性变更，而且它占了包的顶层命名空间。
- **修法**：挪进 object，如 `Loginbase.systemLanguageTag()`。
- **落地**：新增 `Loginbase` object，对外只有 `Loginbase.appLanguageTag()`；`platformLanguageTag()` 保留顶层形态但降 `internal`，由 object 转发一层。**expect/actual 不能直接做 object 成员**（那要写成 `expect object`，得在每个平台重复 object 本身），所以是转发而不是搬家。
- **命名偏差**：待办里举的例子是 `systemLanguageTag()`，实际用了 `appLanguageTag()`——原 KDoc 明确写的是「这个 App 显示给用户的语言」而非系统首选语言（Android 的 `Locale.getDefault()` 已跟随 per-app language，iOS 取的是 `preferredLocalizations`），叫 system 名不副实。
- **顺带**：`Loginbase` object 也给后续不需要实例的库级工具留了位置（如第 26 条的 `parseCallback(uri)`）。

### [x] 8. `AuthState` 只有 3 态，缺「刷新失败但会话还在」

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/AuthModels.kt:9-20`
- **问题**：`RefreshOutcome.Failed` 时 `authState` 仍是 `SignedIn`，UI 无从知道「令牌可能已过期、下个请求会 401」。另外 `SignedOut` 不区分「用户主动登出」和「会话被撤销」，走 `authState` 这条链路时信息永久丢失，UI 弹不出「登录已失效」。
- **参照**：supabase-kt 为此专门把早期的 `SessionStatus.NetworkError` 重设计成 `RefreshFailure`，并用 `NotAuthenticated(isSignOut: Boolean)` 区分来源。
- **修法**：补 `RefreshFailure` 态；`SignedOut` 加 `isUserInitiated` 或拆两态。
- **为什么是 P0**：往公开 sealed interface 加子类型会让消费方已有的穷尽 `when` 编译失败，属破坏性变更。
- **落地**：`AuthState` 从三态变四态——新增 `RefreshFailed(cause)`；`SignedOut` 从 `data object` 变 `data class SignedOut(reason: SignOutReason)`，`SignOutReason` 是 sealed（`NoSession` / `UserInitiated` / `SessionEnded(RefreshFailure)`）。
- **比原修法多做的一点（不是 `isUserInitiated` 布尔）**：`SessionEnded` 需要携带 `RefreshFailure` 才能细化 UI 文案（`SESSION_REVOKED` 对应「账号在别处登录」），布尔装不下。
- **实现时发现的一个坑**：`refresh()` 里有条幂等防御路径会无条件置登出态。会话被撤销后置了 `SessionEnded`，另一个在等锁的 refresh 醒来发现存储空了，会把它改写成 `NoSession`——UI 就再也弹不出「登录已失效」。故新增 `signedOutUnlessAlready()`：已经是登出态就不覆盖。`refreshFailed()` 同理，登出态优先，不被迟到的刷新失败改回「还登着」。两条都有测试锁住。

---

## P1 — 正确性缺陷，生产会咬人（7 条）

### [x] 9. `signOut` 与在途 refresh 无互斥，登出会被复活 — 已完成

- **位置**：`AuthClient.kt:303`（`signOutAll` `:313` 同）
- **问题**：`signOut()` 不走 `refreshMutex`。时序：refresh 持锁、请求在飞 → 用户点登出 → `tokenStore.clear()` + `SignedOut` → 刷新响应到达 → `persist(tokens)` 把新轮换的令牌**写回存储**并置 `SignedIn`。用户点了登出，几百毫秒后又变回登录态，手里还是一对服务端刚发的有效令牌。
- **现状**：`AuthClientTest.kt:280` 只覆盖了「signOut 发生在 refresh **进锁前**」，锁内注释也只讨论了这一种。
- **修法**：`signOut` 也进锁，或加会话 epoch，`persist` 前校验没被换过。
- **落地**：三件事——
  0. （另记）`verifyCode` / `exchangeOtc` 有同源竞态，但本条的修法覆盖不到——它们是**新建**会话，出发时存储本就是空的，没有比对基准。已开 [issue #7](https://github.com/HarlonWang/loginbase-kt/issues/7) 单独跟踪，判断是代价与收益不匹配、暂不做。
  1. 新增 `storeMutex`（只保护本地存储读改写，从不跨 HTTP），`signOut` / `signOutAll` 的清本地操作放进去；
  2. `refresh` 落盘前**重读存储比对**，令牌对不上就丢弃、返回 `NoSession` 且不碰 `authState`；检查与写入在同一把 `storeMutex` 里原子完成；
  3. 401 分支改用 `signedOutUnlessAlready`——见下。
- **两个方案都没采纳，理由**：
  - **不让 `signOut` 抢 `refreshMutex`**：那把锁可能被在途刷新占 45 秒（熔断值），登出等它就违背了 `signOut` 自己文档里「用户点了登出就该立刻是登出的」。`storeMutex` 只在本地存储读写期间持有，毫秒级。
  - **不用会话 epoch 计数器**：登出后存储为空，「空」本身就是可比对的既有状态，重读比对够用，不必新增计数器。
- **走场景时发现的额外问题（已一并修）**：`signOut` 的 `DELETE /sessions` 与在途 `POST /refresh` 并发，DELETE 先到服务端时这次刷新会收到 401，401 分支原本无条件 `signedOut(SessionEnded)`，会把 `UserInitiated` 改写掉 → UI 弹「登录已失效」，而用户明明是自己点的登出。改用 `signedOutUnlessAlready`：谁先置的登出态谁的原因算数。
- **测试**：3 条（复活防护、401 不覆盖原因、无并发时照常落盘）。前两条经过**反向验证**：临时拆掉守卫后确实变红。第一条还兼做死锁哨兵——若有人给 `signOut` 加上 `refreshMutex`，它会直接死锁。

### [x] 10. 单飞只共享「成功」不共享「失败」 — 已完成

- **位置**：`AuthClient.kt:245`
- **问题**：进锁后只在 `current.refreshToken != before.refreshToken`（别人刷成功）时复用。刷新**失败**时 token 没变，排队的 N−1 个等待者会各自再打一次服务端。8 个并发 401 → 第一个 45s 熔断（服务端很可能已完成轮换）→ 后 7 个依次拿已作废的 r0 去刷 → 每次触发救活判定 → 第 4 次撞穿 1h/3 次护栏 → **整条会话按盗用撤销**。这正是单飞要防的事。附带：最后一个等待者最坏排队 8×45s ≈ 6 分钟。
- **修法**：缓存本轮 outcome（含 `Failed`）给同一批等待者复用，用 generation 计数标记「本轮」。
- **落地**：分两个 commit——先纯抽取 `runRound()`（零行为变化），再加轮次逻辑。判据从「存储里的令牌变了没有」换成「轮次号推进了没有」：前者只在成功时才响，后者对所有结果都响。**是替换不是叠加**，原来的令牌比对分支已删。
- **三个实现要点**：
  1. 轮次号**必须在锁外读**——记的是「我何时来排队」。挪进锁里就永远相等，单飞静默退化成各刷各的，不报错不崩。
  2. 复用判断**必须排在读存储之前**——排后面的话，会话被 401 清掉时等待者会先掉进 `NoSession` 早返回，撤销归因就丢了。
  3. 只有**打过服务端**的尝试才算一轮。锁内那条「进锁发现存储空了」的 `NoSession` 不记轮次；抽方法之后这条由代码位置保证（在 `runRound()` 外面），不靠人记。
- **不需要 TTL**：共享边界是「同一批等待者」，一轮结束后才进来的调用方读到的已是新编号，自然会发起真实刷新。
- **没走「经典单飞」（共享 `Deferred` + 内部 CoroutineScope）**：那要引入一个自建 scope，刷新任务脱离调用方协程、取消语义整个变掉，还牵出至今没有的 `close()`。轮次计数一把新锁都不用加。
- **测试**：3 条（失败也只打一次、等待者拿到 `SessionEnded` 而非降级的 `NoSession`、一轮结束后新调用方仍发真实请求）。**要点 1 和 2 都做了反向验证**：分别破坏后，对应测试精确变红（要点 1 还连带弄红了原有的成功用例）。

### [x] 11. `CancellationException` 在 4 处被吞 — 已完成

- **位置**：~~`AuthClient.kt:289`（`persist` 外的 `catch (e: Exception)`）~~ 已随第 2 条修掉；剩 `parseJsonOrNull` 的 `runCatching`、`signOut` / `signOutAll` 的两处 `runCatching`
- **问题**：`:255` 刚立下「取消必须如实传播」的规矩，同一函数里 `persist(tokens)` 的 `catch (e: Exception)` 却会捕获它并转成 `Failed`——已取消的协程正常返回，破坏结构化并发。`runCatching` 捕获 `Throwable`，问题相同。
- **修法**：各处补 `catch (e: CancellationException) { throw e }`，或换成显式 `try/catch` 只捕获具体类型。

- **落地**：`parseJsonOrNull` 改成显式 `try/catch`，只吞 `SerializationException` 与读体失败；`signOut`/`signOutAll` 合并成 `signOutInternal`，DELETE 的取消如实传播，而**本地清除放进 `finally` + `NonCancellable`**——用户点了登出、中途协程被取消却还登录着，是最难排查的一类状态不一致。取消随后照常向上传播，调用方协程正常结束而非被伪装成「登出成功」。
### [x] 12. 无条件 rethrow 把两种取消混为一谈 — 已完成

- **位置**：`AuthClient.kt:255`
- **问题**：ktor 的 `HttpRequestLifecycle` 会在 client job 被 cancel 时连带取消在途请求。消费方退登/重建 DI 时 `cancel()` 自己的 HttpClient，此刻在飞的 refresh 会抛出一个**与调用方无关**的 `CancellationException`，被原样抛出后调用方的 `launch` 当成自己被取消而静默丢弃，UI 永远等不到 `Failed`。
- **修法**：加 `currentCoroutineContext().ensureActive()` 守卫——只有当前协程确实被取消才 rethrow，否则归 `Failed`。

- **落地**：`runRound` 的 HTTP 分支加 `currentCoroutineContext().ensureActive()` 守卫，当前协程还活着就归 `Failed(Network)`。`persist` 那处**刻意不加**同样的守卫：`TokenStore` 是我们直接调用的，没有第三方能从旁边取消它，那里的取消必然来自调用方本身。
### [x] 13. `injected.config {}` 让消费方的 `client.close()` 关不掉 engine — 已完成

- **位置**：`AuthClient.kt:103`
- **问题**：ktor 的 `HttpClient.config()` 把 `manageEngine` 原样传给派生 client，构造时 `engine.clientRefCount.incrementAndGet()`。消费方注入 `HttpClient(OkHttp){}` 后 refcount 1→2，消费方 close 只降到 1，engine 永不关闭；派生 client 从不 close，refcount 回不到 0。DI 重建/登出关 client 时 OkHttp 线程泄漏。
- **修法**：改成在 `refresh()` 里用 `withTimeout` 包一层，与消费方的 client 配置正交，同时顺带解决第 14 条。

- **落地**：不再派生 client，注入的 `HttpClient` 原样使用；保险丝改在 `runRound` 里用 `withTimeout` 实现。与第 14 条是同一处修改。
### [x] 14. `pluginOrNull(HttpTimeout)` 判据太粗 — 已完成

- **位置**：`AuthClient.kt:100`
- **问题**：`HttpTimeoutConfig` 三个字段互相独立且默认 null，只有 `requestTimeoutMillis` 约束整次调用总时长。消费方只配了 `connectTimeoutMillis`（很常见）时插件存在、保险丝被跳过，连上之后服务端不回照样无限挂住、**锁永久被持有**——正是这段代码声称要消灭的形态。
- **修法**：同第 13 条改 `withTimeout` 一并解决；若保留现方案，至少改判 `requestTimeoutMillis` 而非插件是否存在。

- **落地**：随第 13 条一起——`withTimeout` 与消费方的 client 配置完全正交，既不派生 client，也不依赖对方装了什么插件、配了哪几个字段。
- **代价（意外发现）**：`withTimeout` 用的是协程时钟，而 `runTest` 的虚拟时钟会在测试协程一挂起时跳到下一个定时事件，MockEngine 又跑在真实 dispatcher 上——**所有 refresh 测试都会瞬间熔断**。故新增 `authTest {}` 把用例切到真实 dispatcher，并把保险丝时长做成 `internal` 可配（`LoginbaseConfig.lockFuseMillis`）。副产品：整套测试 2.7 秒，最慢用例 45 秒 → 2 秒，第 29 条一并解决；且 8 个并发调用现在是真并行，单飞逻辑得到更硬的验证。
### [x] 15. README 建议的 Auth 插件用法会死锁 — 已完成

- **位置**：`README.md:64`
- **问题**：建议消费方在自己的 client 上装 ktor `Auth` 插件、`refreshTokens` 回调里调 `authClient.refresh()`；而文档另一处鼓励注入同一个 client 复用连接池。两者组合后，本库的 `POST /refresh` 也会过该插件，服务端返回 401 时插件回调 `authClient.refresh()`，当前协程已持有 `refreshMutex`——`Mutex` 不可重入，`withLock` 永久挂起。
- **修法**：文档里明写「装了 `Auth` 插件的那个 client 不要注入给 AuthClient」；根治方案见第 25 条。

---

## P2 — 健壮性与一致性（9 条）

- **落地**：README 那段加了 ⚠️ 块，明确「装了 `Auth` 插件的 client 不要注入给 `AuthClient`」并说明死锁成因（`Mutex` 不可重入）。`LoginbaseConfig.httpClient` 的 KDoc 里已有同样的警告。
### [x] 16. `.jsonPrimitive.int` 会抛错并掩盖真实的 API 错误 — 已完成

- **位置**：`AuthClient.kt:139` `:353`（另 `:266` `:346` `:373` `:384` 同类）
- **问题**：`jsonPrimitive` 在元素非 primitive 时抛 `IllegalStateException`，`.int` 在内容非数字时抛 `NumberFormatException`。`:353` 尤其别扭——它在**构造 `AuthApiException` 的参数求值期**抛出，会把真正的协议错误整个掩盖掉。这与 `:78`「服务端加字段不该炸老客户端」的立场相悖。
- **修法**：全部改 `intOrNull` / 安全取值。

- **落地**：随第 17 条一起收口，所有取值走 `intOrNull` / `stringOrNull` / `booleanOrNull`。
### [x] 17. 手工 JSON 解析散落 6 处，没有收口 — 已完成

- **位置**：`AuthClient.kt:139` `:193` `:266` `:346` `:353` `:373` `:384`
- **问题**：第 16 条是症状，这是病因。「不引 ContentNegotiation」这个决策成立（依赖最小集是核心红线），但**手工解析 ≠ 不封装**。
- **修法**：加 20 行内部扩展 `JsonObject.stringOrNull(key)` / `intOrNull(key)` / `boolOrNull(key)`，一次性收口，不增加任何依赖。

- **落地**：新增 `JsonAccess.kt`（三个 `internal` 扩展，零新增依赖）。契约只有一条：**取不到就是 `null`，永不抛异常**——覆盖字段缺失、`JsonNull`（`content` 是字符串 `"null"`，不拦住 `toInt()` 会炸）、类型变成对象/数组、以及内容与类型对不上四种情形。
- **测试**：`JsonAccessTest` 6 条 + 一条端到端（`retryAfterSeconds` 类型不对时错误码必须照常送达）。做过**反向验证**：把 `intOrNull` 退回 `.toInt()` 后三条精确变红。
### [x] 18. `PROTOCOL_VERSION` 用 `const val`，恰好破坏它自己的用途 — 已完成

- **位置**：`Protocol.kt:10`
- **问题**：`const val` 是编译期常量，**会内联进调用方字节码**。消费方编译时是 1.3.0，之后库升到实现 1.4.0 协议、消费方只换 jar 不重编——读到的还是 `"1.3.0"`。而这个常量存在的唯一理由就是「声明我实现的是哪一版协议」。
- **修法**：去掉 `const`，改普通 `val`。
- **落地**：已改，并在 KDoc 里写明为什么不能是 `const`——否则后人很容易「顺手优化」回去。

### [x] 19. `InMemoryTokenStore` 公开、非线程安全、却写着「生产可用」 — 已完成

- **位置**：`library/src/commonMain/kotlin/wang/harlon/loginbase/TokenStore.kt:38-53`
- **问题**：`private var tokens` 无任何同步，类注释却写「用于测试，**以及「本次启动内有效即可」的场景**」——后半句是在说生产可用。而 `AuthClient` 文档反复强调「多协程并发调 refresh」，两件事凑一起就是数据竞争。
- **修法**：加 Mutex，或删掉「生产可用」措辞、明确它只是测试替身。

- **落地**：加了 `Mutex`，文档措辞改成「测试替身，生产别用」。加锁不是形式主义——它会被并发测试直接使用，而 `AuthClient` 的整个并发模型建立在「多协程同时 refresh」之上。
### [x] 20. `AuthApiException(200, ...)` 混淆「服务端说不行」与「响应体畸形」 — 已完成（随第 2 条）

- **位置**：`AuthClient.kt:194` `:381`
- **问题**：HTTP 200 但响应体缺 `authorizeUrl` / 缺 token 时，抛的是 `AuthApiException(status=200, error=UNKNOWN)`。而这个类的文档说的是「服务端按协议返回的错误」。调用方无法区分「服务端拒绝」和「响应不符合协议」。
- **修法**：单列一个 `LoginbaseException.MalformedResponse(field)`（挂在第 2 条的根类型下）。
- **为什么提前做**：`LoginbaseException` 是 sealed，晚一步加子类型就是又一次破坏性变更（同第 8 条的道理）。顺带把 `refresh()` 里两处 `IllegalStateException("empty refresh response")` 也归了进来。

### [x] 21. 忘记调 `restore()` 会让 `authState` 与实际行为不同步 — 已完成

- **位置**：`AuthClient.kt:116` 对 `:205` `:212`
- **问题**：`accessToken()` 直接读存储，与 `authState` 无关。所以没调 `restore()` 时，`authState` 是 `Unknown` 而 `accessToken()` 已经能返回有效令牌——两个对外信号互相矛盾，且没有任何机制提醒调用方漏了这一步。
- **修法**：要么 `restore()` 在首次 `accessToken()` / `refresh()` 时惰性自动执行，要么在 `Unknown` 态下让读取路径先补一次恢复。

- **落地**：选了「读取路径顺手补齐」而不是惰性自动 restore——`accessToken` 与 `refresh` 反正都要读一次存储，顺手把还停在 `Unknown` 的状态补上，零额外 IO。
- **只在 `Unknown` 时动手**：别的状态都是别处根据更完整的信息写下的（比如 `SignedOut(UserInitiated)`），不该被这条逻辑覆盖。有测试守着。
### [x] 22. 自建的 `HttpClient` 没有关闭出口 — 已完成

- **位置**：`AuthClient.kt:92-107`
- **问题**：默认路径下库自己 `HttpClient { }`，但 `AuthClient` 没有 `close()`，这个 client 及其 engine 随进程存活。单例定位下影响有限，但测试和 DI 重建场景会积累。
- **修法**：加 `close()`（只关自建的，不碰注入的），或在类文档里明说不需要关闭及其理由。

- **落地**：加了 `close()`，只关**库自建**的 client；注入的归消费方所有，碰都不碰（对方可能还用同一个 engine 发业务请求）。自建是惰性的，没走过 HTTP 的实例不会因此白建一个。KDoc 里写明单例场景通常一辈子用不到它，它是给测试和 DI 图重建准备的。
### [x] 23. 私有 `isSuccess()` 与 ktor 内建重复 — 已完成

- **位置**：`AuthClient.kt:399`
- **问题**：ktor 已有 `io.ktor.http.isSuccess()`（`HttpStatusCode.kt:195`，实现完全相同 `value in (200 until 300)`）。
- **修法**：删掉私有版本，改 import。

- **落地**：删掉私有版本，改 import ktor 的 `io.ktor.http.isSuccess`。
### [x] 24. `encodeDefaults = true` 是死配置 — 已完成

- **位置**：`AuthClient.kt:79`
- **问题**：全程只用 `JsonObject.serializer()` 手工序列化，没有任何带默认值的 `@Serializable` 类，这行不起作用。
- **修法**：删掉，或留注释说明为什么预留。

---

## P3 — 架构、工程与文档（12 条）

- **落地**：删掉，并补一句注释说明这个 `Json` 实例的职责边界（只做 `JsonObject` 与文本的互转，没有任何 `@Serializable` 类经过它），免得后人再往里加配置。
### [x] 25. 缺 HTTP 集成件，401 重试样板留给每个调用点 — 已完成（重新定义了交付物）

- **位置**：新增
- **问题**：库只给 `accessToken(forceRefresh = true)`，没有任何 HTTP 集成件；README 只好建议消费方自己装 ktor `Auth` 插件，而那条建议会死锁（第 15 条）。对比：supabase-kt 由 `SupabaseClient` 内部统一挂载；Auth0 文档直接给 OkHttp Interceptor 范式。
- **修法**：官方提供 `AuthClient.installOn(HttpClient)`，内部给自身请求打标记以规避递归刷新。

- **原修法不可实现**：`AuthClient.installOn(client)` 做不到——ktor 的插件只能在 client 构造时装，装不到已建好的 client 上；唯一绕法是 `client.config { }`，而那正是第 13 条的 engine refcount 泄漏。
- **查了业界之后，结论是不该自己写插件**：
  - ktor 的 `Auth` + `bearer {}` 已经把「带头 / 401 自动重试 / 单 client 内并发收敛」全做了（[官方文档](https://ktor.io/docs/client-bearer-auth.html) 明写「multiple requests fail with 401 at the same time → refresh only once」）
  - OkHttp 那边对应的是专为此设计的 `Authenticator` 接口
  - Auth0.Android 的 `EXAMPLES.md` 里**只有示例代码，没有 shipped 的 interceptor/authenticator**——通用模式就是「SDK 暴露取 token / 刷 token，接线交给 HTTP 栈自带的机制」
  - 而且 `BearerTokens` 在 `ktor-client-auth` 里，本库只依赖 `ktor-client-core`，发任何 ktor-Auth 风味的辅助函数都会破依赖最小集红线
- **本库唯一不可替代的那一块已经在了**：ktor 插件的单飞是 **per-client**，App 有几个 `HttpClient` 就并发刷几次；跨 client 收敛只有本库的锁能做。所以正确形态是「插件的 `refreshTokens` 里调 `auth.refresh()`」，两层单飞叠加。
- **落地**：README 的「用法」改写成**接入指南**，六步走完 + 业务代码长什么样 + 「出现这些就是接错了」的反模式表。重点标注了 `refreshTokens` 必须调 `auth.refresh()`——绕过它照样能跑通、功能完全正常、没有任何报错，只是每次悄悄烧一格救活配额，等撞穿配额把用户强制登出时已经很难查。
- **补做的集成测试**：`ReadmeIntegrationTest` 把指南第 2 步的接线**逐字**放进测试，两个各装 `Auth` 插件的 client 同时 401，断言服务端只被刷新一次；同时实证了「装了 `Auth` 插件的 client 与本库共存不会死锁」（死锁的话用例会直接挂住）。配一个**反例用例**：绕过 `auth.refresh()` 自己打刷新接口 → 服务端被刷两次。反例的作用是让正例那个 `== 1` 的断言有意义，而不是碰巧成立。
- `ktor-client-auth` 只作 `testImplementation`，不进产物。库自己绝不引它——`BearerTokens` 在那个包里，引了就等于把 ktor 的插件 API 写进本库的公开契约。

### [ ] 26. OAuth 浏览器环节与回调解析都甩给了 App

- **位置**：`AuthClient.kt:160-168`
- **问题**：`githubSignInUrl()` 只返回字符串，README 让消费方自己 `openInBrowser(...)`。业界（Auth0、Firebase、AppAuth、Clerk）都把这步收进 SDK，Android 用 Custom Tabs / 新的 AuthTab——不是为省事，而是回调拦截、用户取消这些点每个接入方都会踩。`:161` 的注释自己强调了「不要用内置 WebView」，**但把执行这条纪律的责任推给了调用方**。另外 `exchangeOtc` 要 App 自己从 deep link 抠 `otc`，而 link 流程回跳的是 `linked=github` / `error=already_linked`，完全另一套，库连 `parseCallback(uri)` 都没提供。
- **修法**：Android 侧提供 Custom Tabs 启动器 + `parseCallback(uri)`；至少先补 `parseCallback`，成本最低收益最直接。

### [x] 29. 有个测试真实耗时 45 秒 — 已完成（随第 14 条）

- **位置**：`library/src/commonTest/kotlin/wang/harlon/loginbase/AuthClientTest.kt:116`
- **问题**：其余 33 个测试合计约 0.2s。`MockEngine` 与 `HttpTimeout` 的 killer 协程都跑在 `ioDispatcher()`，不是 `runTest` 的虚拟时间调度器，所以是墙钟时间；已占掉 `runTest` 默认 60s 超时的 75%，机器抖动时会假失败。
- **修法**：把熔断值做成 `internal` 可注入参数，测试传小值。

- **落地**：保险丝时长做成 `internal` 可配后，熔断用例从 45 秒降到 2 秒；整套测试合计 2.7 秒。原修法（把 fuse 值做成可注入参数）正是这么做的，只是触发它的是第 14 条的改动。
### [x] 30. 补两个缺失的并发测试 — 已完成

- **位置**：`AuthClientTest.kt`
- **问题**：第 9 条（signOut vs 在途 refresh）和第 10 条（单飞失败共享）都没有测试覆盖，修完必须锁住。
- **修法**：各补一个——前者用可控延迟的 MockEngine 在请求在飞时调 `signOut`，断言最终态是 `SignedOut` 且存储为空；后者断言 N 个并发调用在服务端失败时只打一次。

- **落地**：两条测试已随第 9、10 条一起补上（`在途刷新的响应不得复活刚登出的会话`、`并发刷新失败时也只打一次服务端`），且都做过反向验证。本条只是核对确认。
### [x] 32. 删掉 XCFramework 配置 — 已完成

- **位置**：`library/build.gradle.kts:12-13` `:35-42`
- **问题**：XCFramework 是给原生 Swift App 的分发格式。iOS 只做占位的话没人消费它，但它逼着 `publish.yml` 必须跑 macos runner（比 ubuntu 慢且贵，timeout 开到 60 分钟）。
- **修法**：保留 `iosArm64()` / `iosSimulatorArm64()` 两个 target（这才是「防止 common 代码写死 JVM API」的实际作用），删掉 `XCFramework` / `isStatic` / `binaryOption` 那段。

- **落地**：删掉 `XCFramework` / `isStatic` / `binaryOption` 与两个 import，留一条注释说明「占位阶段不产出 framework」。跑过 `publishToMavenLocal` 核对：四个产物（common / android / iosarm64 / iossimulatorarm64）都还在，iOS klib 照常发布。
- **原描述里有一处判断错了**：说 XCFramework「逼着 publish.yml 必须跑 macos runner」——不对，**是 iOS target 本身逼的**（Kotlin/Native 的 iOS 编译只能在 macOS 上做），删掉 XCFramework 并不能让发布回到 ubuntu。这条的实际收益只是「删掉没人消费的产物和一段配置」。
### [x] 34. README 会误导使用者以为 iOS 可用 — 已完成

- **位置**：`README.md:5` `:32`
- **问题**：第 5 行写「目标平台：Android、iOS（arm64 + simulator arm64）」，第 32 行用法示例里直接给了 `// iOS: NSUserDefaultsTokenStore()`。接入方按示例接了才会发现是占位。
- **修法**：明写 iOS target 当前仅为占位/编译保障，不承诺可用。
- **落地**：开头改成「目标平台：Android」，新增「iOS 是占位」一节，把三条不可用的具体理由摆出来（真机未验证、无 Swift 互操作保障即未标 `@Throws`/Flow 不可消费、CI 不跑 iOS 测试）；用法示例里那行 `// iOS: NSUserDefaultsTokenStore()` 删掉。开发一节补了「改 commonMain 形状后本地跑一次 iOS 编译」的提醒与理由。

### [x] 35. 注释漂移与三处重复 — 已完成

- **位置**：`AuthClientTest.kt:123`；`README.md:50-64` / `AuthClient.kt:36-48` / `AuthClient.kt:216-232`
- **问题**：测试注释引用的 `REFRESH_TIMEOUT_MS` 常量不存在（实际叫 `LOCK_FUSE_TIMEOUT_MS`）；单飞边界那段论述在 README、类 KDoc、`refresh()` KDoc 里重复了三遍，改一处要同步三处。注释质量本身很高（每个决策都有 why + 事故出处），但密度已经产生维护负担。
- **修法**：改掉那个常量名；把「设计决策」沉到 `docs/design.md`，代码注释只留一句指针。

- **核对后实际范围比记录的小**：那个不存在的常量名（`REFRESH_TIMEOUT_MS`）在第 14 条那轮已经顺带改掉了。真正还重复的只有「单飞边界 / 跨进程锁」这一段。
- **落地**：新增 `docs/design.md`（面向维护者：为什么这么设计、否掉了什么），把三处重复的论证**移**过去而不是再抄一份。README 保留消费方要看的边界表格 + 指针；代码 KDoc 保留局部的 why + 指针。`AuthClient.kt` 注释 279 → 262 行。
- **刻意没做的**：没有把代码注释整体搬空。局部的 why（为什么这个 catch 要 rethrow、为什么这个判断必须在锁外）就该待在代码里，搬走反而更难维护。只搬走了跨文件重复的那部分。
### [x] 36. POM 里作者名拼写错误 — 已完成

- **位置**：`library/build.gradle.kts:81`
- **问题**：`name.set("HarlanWang")`，而 `id` 是 `HarlonWang`、仓库和 URL 也都是 `HarlonWang`。这会进入 Maven Central 的永久元数据。
- **修法**：改成 `HarlonWang`。
- **落地**：已改，并跑 `generatePomFileForKotlinMultiplatformPublication` 核对了实际产出的 pom-default.xml。

---

### [x] 38. 只收 engine，不再接受注入整个 `HttpClient`（设计决策）

- **位置**：`LoginbaseConfig.httpEngine`、`AuthClient` 的 client 构建
- **背景**：注入 `HttpClient` 这一个决定，在本轮审查里直接产生了四条待办（#13 engine refcount、#14 超时判据失灵、#15 `Auth` 插件死锁、#22 `close()` 要分辨归属）。
- **讨论中发现的第五颗地雷**：ktor `HttpRequestRetry` 的默认配置就是「5xx 与 IOException 重试 3 次」（`HttpRequestRetry.kt:75-78`）。消费方只要装了它并注入那个 client，单飞辛苦收敛成的 1 次刷新会在库看不见的地方被放大成 4 次——服务端若已消费掉那个 refresh token，一轮就撞穿 1h/3 次救活护栏。**此前没有任何文档警告过。**
- **评估过的三个方案**：
  - **A 注入 client（原状）**：读代码要同时记住 6 条，其中 3 条是「消费方必须记得」
  - **B 完全内置、不给注入**：只剩 2 条，但**证书固定、代理、消费方的集成测试全部做不了**；且库自己的测试仍需要一个 `internal` 注入口，所以 `injected ?: 自建` 那个分支根本删不掉——B 相对 C 省下的几乎全是文档
  - **C 只收 engine**：3 条，且全是库内部的事
- **决定：C**。真正的复杂度断崖在 A→C（消灭了三条「消费方必须记得」），C→B 只再少一条良性的，却要关掉一个 auth 库最不该关掉的能力。
- **落地**：`httpClient` → `httpEngine`；`HttpClient` 一律由库自建（传 engine 时走 `manageEngine = false`，`close()` 不碰消费方的 engine）。
- **顺带删掉的**：`withTimeout` 保险丝与 `lockFuseMillis`——它们的前提是「注入的 client 未必配了超时」，库自建之后 `HttpTimeout` 必然生效，这个前提不存在了。README 里「不要注入装了 `Auth` 插件的 client」那段警告也删了：**好设计让警告消失，而不是把警告写得更醒目。**

---

## 可见性治理

### [x] 37. 满篇 `public` 关键字 — 已处理（决定：删掉，不开 `explicitApi()`）

`explicitApi()` 没有启用，Kotlin 默认可见性就是 public，所以那 40 多处关键字在编译器看来等于没写，IDE 也一直在报 `Redundant 'public' modifier`。两条出路都能消除提示、且都不改变任何声明的可见性：删掉关键字，或开 `explicitApi()` 让关键字变成必需。

**决定：删掉全部 `public` 关键字，不开 `explicitApi()`。**

需要留意的代价（选这条就等于接受）：

- 今后新增声明**忘写 `internal` 会静默变成公开 API**，编译器不拦——第 5、6 条就是这么来的
- 读代码时「有意公开」和「忘标 internal」长得一模一样，只能靠 review 兜
- **这两条风险目前没有任何自动化兜底**：曾考虑用 binary-compatibility-validator 在 CI 里拦「公开面意外变大」，已决定不引入。也就是说公开面的正确性完全依赖 review

> Kotlin 官方库作者指南推荐库开 explicit API mode（[api-guidelines-simplicity](https://kotlinlang.org/docs/api-guidelines-simplicity.html)）。这里是明知推荐而选另一条，理由是代码观感，记录在案便于日后重议。

### 公开面判定规则（长期适用）

1. 消费方要**调用**的 → 公开：`AuthClient` 的方法、`TokenStore`、`OAuthProvider`、`LoginbaseConfig` 的选项
2. 消费方会**收到**的 → 公开：`LoginbaseException` 各类、`AuthError`、`RefreshFailure`、`RefreshOutcome`、模型类
3. 只有库自己编解码 wire 格式用的 → **`internal`**：`fromWire`、`wire`、`usableTag`
4. 测试要用但消费方不用的 → **`internal` 就够**：KMP 的 test source set 是 main 的 friend module

元规则：**默认应当是 `internal`，公开要能说出理由**。注意 Kotlin 的默认可见性是 `public`，所以「什么都不写」= 公开 = 发到 Maven Central 后撤不回来——`internal` 必须显式写。

---

## 落地顺序

| 步骤 | 内容 | 提交方式 |
|---|---|---|
| 1 | **API 重构**：第 1–8 条 + 第 18、20 条（都改公开面，一起改省得反复破坏） | feature 分支 + PR |
| 2 | **并发修复**：第 9–15 条 + 第 30 条测试 | feature 分支 + PR |
| 3 | **清理**：第 16、17、19、21–24 条 | 可直接提交 |
| 4 | **工程收尾**：第 32、34、35、36 条 | 可直接提交 |
| 5 | **单独排期**：第 25–29 条（架构与协议层，需与服务端仓协同） | 各自 PR |

---

## 参考

- [supabase-kt SessionStatus.kt](https://github.com/supabase-community/supabase-kt/blob/master/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/status/SessionStatus.kt) — 第 8 条的状态模型参照
- [supabase-kt CHANGELOG（RefreshFailure 重设计）](https://github.com/supabase-community/supabase-kt/blob/master/CHANGELOG.md)
- [Auth0.Android CredentialsManager.kt](https://github.com/auth0/Auth0.Android/blob/main/auth0/src/main/java/com/auth0/android/authentication/storage/CredentialsManager.kt) — 第 28 条的 `minTtl` 主动刷新参照
- [Backward compatibility guidelines for library authors | Kotlin Docs](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html) — 第 4 条
- [Building a Kotlin library for multiplatform | Kotlin Docs](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html)
- [Simplify authentication using Auth Tab | Chrome for Developers](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) — 第 26 条
- [OAuth for Mobile Apps — Best Practices | Curity](https://curity.io/resources/learn/oauth-for-mobile-apps-best-practices/) — 第 26 条
