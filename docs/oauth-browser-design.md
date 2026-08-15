# 设计方案：社交登录的浏览器环节收进库内（Auth Tab + intent-filter 双通路）

> 状态：**校准中**。对应 `docs/todo.md` 第 26 条。
>
> ⚠️ **正文第 5 节起的部分内容已被第 11 节的校准结论推翻，以第 11 节为准。**
> 校准完成后再统一重写正文。
>
> 目标读者：维护者。决策背景见本文第 1 节，落地前必须先做第 8 节的 spike。

## 1. 为什么做

**RFC 8252** 要求原生 App 只能用外部 user-agent 做 OAuth 授权，禁止嵌入式 WebView。
现在库的做法是 `signInUrl()` 返回一个字符串、在注释里写「不要用 WebView」——**规范要求被
降级成了一句劝告**，调用方把它塞进 WebView 库既不知道也拦不住。

业界参考实现无一例外自己拥有这一步：AppAuth-Android（OpenID 基金会参考实现）、
Auth0.Android 的 `WebAuthProvider`、以及同类 KMP 库 supabase-kt 的 `Auth` 模块
（`androidMain` 里 `api(libs.androidx.browser)`）。

顺带解决今天接入体验上最扎眼的一块：接入方要自己写 manifest intent-filter、自己从
deep link 抠参数，而且**登录与绑定的回跳参数是两套**（`otc` vs `linked=<provider>` /
`error=<reason>`），要自己分辨。

## 2. 目标与非目标

**目标**

- 授权页由库在合规的外部 user-agent 里打开，调用方拿不到「塞进 WebView」的机会
- 回跳的捕获与解析归库，登录/绑定两套参数的差异对调用方不可见
- **进程在浏览器停留期间被系统回收后，流程仍能走完**
- 用户取消能被识别（尽最大努力，见第 7 节限制）

**非目标**

- 不做 iOS。iOS 是占位（见 README），`ASWebAuthenticationSession` 等 iOS 转正后再说
- 不做 UI。登录界面仍归 App
- 不接管 PKCE / state / code 交换——本库的 OAuth 是**服务端流**，客户端只负责「开页面」
  和「拿 otc」，这些环节本来就不在客户端

## 3. 核心设计：两条通路汇聚到同一个入口

这是整个方案的支点。

```
                 ┌─ Auth Tab 捕获回跳，callback 直接回到进程内 ─┐
授权页在外部打开 ─┤                                              ├─→ handleOAuthCallback(url)
                 └─ 回退：系统浏览器 + intent-filter 拉起 Activity ┘
```

两条通路的差别**只在「结果怎么回到进程里」**。回来之后要做的事完全一样：解析 URL →
判断是登录还是绑定 → 登录则 `exchangeOtc` → 发结果。

所以库在 `commonMain` 暴露**一个**入口：

```kotlin
suspend fun AuthClient.handleOAuthCallback(url: String): OAuthOutcome
```

Auth Tab 通路由库在 callback 里调用它；回退通路由库自己的中转页（§5.3）调用它。
**两条通路之后的所有逻辑只有一份实现，且消费方都不用参与。**

## 4. 不需要持久化「我在等什么」

进程被回收后冷启动，App 是全新进程，直觉上需要知道「这次回跳属于哪个流程」。

**但回跳参数自带这个信息**：

| 回跳参数 | 流程 |
|---|---|
| `otc=<code>` | 登录 |
| `linked=<provider>` | 绑定成功 |
| `error=<reason>` | 绑定失败（登录失败也走这个） |

所以**不需要任何 pending 状态持久化**，也不需要第二个存储。这条让方案的复杂度掉了一大截——
没有「状态写坏了怎么办」「什么时候过期清理」这类问题。

## 5. API 设计

### 5.1 `commonMain`（零新依赖）

```kotlin
/** 一次社交登录/绑定的结果。sealed：新增一种结果要让调用方重新想一遍怎么处置。 */
sealed interface OAuthOutcome {
    /** 登录成功，会话已建立并落盘（[AuthClient.authState] 同时变为 SignedIn） */
    data class SignedIn(val session: AuthSession) : OAuthOutcome

    /** 绑定成功。不产生新会话，authState 不变 */
    data class Linked(val provider: OAuthProvider) : OAuthOutcome

    /** 服务端在回跳里给了 error，典型如 `already_linked` */
    data class Failed(val reason: String) : OAuthOutcome

    /** 用户主动放弃（关掉了授权页）。仅 Auth Tab 通路可靠，见「已知限制」 */
    data object Cancelled : OAuthOutcome

    /** 回跳 URL 不是本库认得的形状——大概率是接入配置错了，该报给开发者 */
    data class Unrecognized(val url: String) : OAuthOutcome
}

/**
 * 处理一次 OAuth 回跳。**两条通路的唯一汇合点。**
 *
 * 幂等：同一个 otc 重复送入只会被消费一次（见 §6.1）。
 */
suspend fun handleOAuthCallback(url: String): OAuthOutcome

/**
 * 结果广播。进程被回收后冷启动的场景里，发起 [signIn] 的那个协程已经不存在了，
 * 结果只能从这里拿。`replay = 1` 兜住「回调先于订阅到达」。
 */
val oauthResults: SharedFlow<OAuthOutcome>
```

`signInUrl` / `linkUrl` **保留但降级**：仍然公开（自定义流程、非 Android 平台需要），
文档上标注「一般不要直接用，用 `signIn()`」。

### 5.2 `androidMain`（+ `androidx.browser`）

```kotlin
/**
 * 在合规的外部 user-agent 里完成一次社交登录，挂起到有结果为止。
 *
 * 不需要传 redirect——它由 applicationId 推导（见 §5.3），与 manifest 里声明的
 * scheme 同源，不可能不一致。
 *
 * 优先 Auth Tab（Chrome 137+）：回跳由浏览器直接 callback 回来，不经过 Intent，
 * 因此没有 Intent 被其他 App 劫持的暴露面。不可用时回退到系统浏览器 + 中转页，
 * 结果同样从这里返回；进程若被回收，则从 [oauthResults] 送达。
 */
suspend fun AuthClient.signIn(activity: ComponentActivity, provider: OAuthProvider): OAuthOutcome

suspend fun AuthClient.link(activity: ComponentActivity, provider: OAuthProvider): OAuthOutcome

/** 需要自定义 redirect 的重载（自建 scheme、https app-link 等）。 */
suspend fun AuthClient.signIn(
    activity: ComponentActivity,
    provider: OAuthProvider,
    redirect: String,
): OAuthOutcome
```

### 5.3 scheme 与中转页：消费方零配置

#### scheme 是什么

`cn.trendingai://loginbase/callback` 里的 `cn.trendingai` 就是 scheme，地位等同 `https`。
区别是浏览器不认识它，会问系统「谁认领了这个 scheme」——**它是浏览器把控制权还给 App
的唯一线索**：

```
5. 服务端 302 → cn.trendingai://loginbase/callback?otc=abc123
6. 浏览器：这个 scheme 我不认识 → 问 Android
7. Android：翻所有 App 的 manifest，找谁声明了 <data android:scheme="cn.trendingai" />
8. 拉起那个 Activity，intent.data = 上面那个 URL
```

同一个 scheme 必须在**三处**一致，而且写错的地方和报错的地方对不上，很难查：

| 出现在哪 | 谁负责 | 写错的症状 |
|---|---|---|
| 服务端 redirect 白名单 | 服务端 App 配置 | 授权还没开始就被拒（`invalid_redirect`） |
| App 的 manifest | Android 构建 | 第 7 步没人接，**用户授权完卡在打不开的页面** |
| 运行时传给 `signInUrl` 的 `redirect` | 代码 | 表现为上面两种之一 |

#### 用 `${applicationId}` 把后两处收进库里

中转页由库提供并在**库自己的 manifest** 里声明（AppAuth 的 `RedirectUriReceiverActivity`、
Auth0 的 `RedirectActivity` 同做法），scheme 用 AGP 的内置占位符：

```xml
<activity
    android:name=".LoginbaseCallbackActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="${applicationId}" />
    </intent-filter>
</activity>
```

合并进 TrendingAI 时自动变成 `cn.trendingai`。而运行时 `activity.packageName` 返回的**就是**
applicationId，所以 `signIn()` 能自己拼出同一个 redirect。

**两处同源，不可能不一致；消费方一个字都不用写。**

剩下的服务端白名单躲不掉——那是安全控制，必须服务端显式允许。但它的值现在是**可预测的**：
就是 applicationId。

> `${applicationId}` 恰好也是 RFC 8252 §7.1 推荐的形态：「用自己控制的域名反写作为
> private-use scheme」。

#### 顺带做对了一件容易做错的事

Android 项目常给 debug 变体加后缀（`applicationIdSuffix = ".debug"`），于是 debug 包的
scheme 自动变成 `cn.trendingai.debug`。

**若写死一个固定 scheme**，debug 与 release 同时装在一台机器上时两个都声明
`cn.trendingai`，第 7 步 Android 会弹**「用哪个应用打开」的选择器**——用户莫名其妙，
开发者也很难联想到是 scheme 撞了。

代价：服务端白名单要配两条（`cn.trendingai://…` 与 `cn.trendingai.debug://…`）。

### 5.4 接线成本对比

| | 今天 | 中转页 + `manifestPlaceholders` | 中转页 + `${applicationId}` |
|---|---|---|---|
| manifest | 手写 intent-filter | — | — |
| build.gradle | — | 一行 placeholder | — |
| 生命周期接线 | `onCreate` + `onNewIntent` | — | — |
| 注册 `AuthClient` | — | 一行 | — |
| 解析回跳参数 | 自己写 | — | — |
| 分辨登录/绑定 | 自己写 | — | — |
| 调 `exchangeOtc` | 自己写 | — | — |
| **消费方新增代码** | **6 项** | **2 行** | **0** |

`restore()` 与观察 `oauthResults` 不计入——它们本来就在接入指南里。

**消掉的那两个生命周期钩子恰恰是最易错的**：漏写 `onNewIntent` 会导致「App 在后台时授权
回来没反应」，只在特定启动模式下复现。

### 5.5 中转页引入的两个问题及解法

#### 问题 1：中转页怎么找到 `AuthClient`

它由系统实例化，够不到 App 的 DI 图。**把两条路径拆开看，各自都有现成的抓手**：

| 路径 | 谁把 URL 交给 `AuthClient` |
|---|---|
| **App 还活着**（多数情况） | `signIn()` 发起时把 `this` 放进库内静态槽——**发起方就是它自己，不需要外人注册** |
| **进程被回收** | 静态槽随进程消失 → 中转页把 URL **停泊**在静态里 → 起 launcher → App 冷启动 → **`restore()` 把停泊的 URL 排空** |

`restore()` 本来就是接入指南第 3 步、本来就必须在启动时调；而中转页的 `onCreate` **必然
早于** App 的 `restore()`（它是先被拉起的那个 Activity），时序天然成立。

**所以不需要任何新的消费方动作。**

> 也考虑过「`AuthClient` 构造时自动注册到静态」——否掉了：构造函数带隐式全局副作用，
> 多实例时行为不可预测，还会干扰测试。上面的分路做法既没有这个问题，也不需要消费方参与。

#### 问题 2：处理完怎么把用户送回 App

用 `isTaskRoot` 区分：

| 情况 | `isTaskRoot` | 动作 |
|---|---|---|
| App 还活着，中转页落在 App 的任务栈里 | `false` | 直接 `finish()`，露出下面原来的界面 |
| App 已被回收，中转页是新任务的根 | `true` | 起 App 的 launcher intent（`packageManager.getLaunchIntentForPackage`），再 `finish()` |

不用 AppAuth 那种「消费方提供 `PendingIntent`」——那等于把接线成本还回去。`isTaskRoot`
是标准信号，launcher intent 库自己就能拿到。

**不要用 `FLAG_ACTIVITY_CLEAR_TOP` 之类「回到首页」**：用户可能在很深的页面上发起绑定，
清栈会把他的位置弄丢。只在任务确实不存在时才起 launcher。

## 6. 关键机制

### 6.1 幂等

otc 是单次有效的。两条通路理论上不会同时送达（Auth Tab 捕获后浏览器不再发 Intent），
但**不能依赖这个假设**——回退判定失误、用户手动点了浏览器里的链接、系统重放 Intent 都可能
造成重复。

处理：库在进程内记住最近处理过的 otc，重复送入直接返回上一次的结果，**不再打服务端**。

为什么不能靠服务端兜：第二次 exchange 会返回 `invalid_otc`，而那与「otc 真的过期了」
无法区分——用户会看到一次莫名其妙的登录失败。

### 6.2 取消

- **Auth Tab 通路**：浏览器关闭会通过 callback 返回一个结果码，可以映射成 `Cancelled`
- **回退通路**：用户直接退出浏览器，App 什么都收不到，`signIn()` 会一直挂着

后者由调用方的协程作用域负责（页面销毁则 scope 取消）。这是回退通路的固有限制，写进文档。

### 6.3 进程被回收

```
App 打开授权页 → 系统回收 App → 用户完成授权 → 回跳
  → 中转页冷启动，onCreate 拿到 intent.data
  → 把 URL 停泊进库内静态槽（此时还没有 AuthClient 可用）
  → isTaskRoot == true → 起 App 的 launcher intent → finish()
  → App 冷启动 → auth.restore() 顺带把停泊的 URL 排空
  → handleOAuthCallback → exchangeOtc → 落盘
  → authState 变 SignedIn（全局导航自然跳转）
  → oauthResults 发出 SignedIn
```

发起 `signIn()` 的那个协程早已随进程消失，所以**结果必须能从 `oauthResults` 拿到**——
这就是它存在的理由，也是为什么 `replay = 1`（`restore()` 里的处理可能早于 UI 订阅）。

**时序上中转页的 `onCreate` 必然早于 App 的 `restore()`**（它是先被拉起的那个 Activity），
所以「停泊 → 排空」不会错过。

### 6.4 与现有并发机制的关系

`handleOAuthCallback` 内部走 `exchangeOtc` → `persist`，和 `refresh` 一样受 `storeMutex`
保护。与在途刷新、并发登出的竞态由已有机制覆盖，不引入新的锁。

## 7. 已知限制（必须写进 README）

1. **回退通路识别不了「用户取消」**——没有任何信号会回到 App
2. **服务端 redirect 白名单仍要人工配**，且 debug/release 变体各一条
   （`<applicationId>://loginbase/callback`）。这是安全控制，不能由客户端决定
3. **只有 Android**。iOS 转正前，那边仍是 `signInUrl()` + 自己开浏览器
4. `link` 流程的失败原因由服务端 App 定义（`already_linked` 是典型值但不是协议保证），
   库只能原样透传给 `Failed.reason`
5. **自定义 scheme 谁都能声明**——别的 App 装上去声明同一个 scheme 就能截胡回跳。这是
   RFC 8252 明确承认的固有弱点，也是优先走 Auth Tab（不发 Intent）的理由之一；另外 otc
   60 秒单次有效，即便被截也只有一次窗口
6. 不用社交登录的消费方也会被合并进一个 `LoginbaseCallbackActivity`。因为用的是
   `${applicationId}` 而非自定义占位符，**不会导致构建失败**，只是多一个不会被触发的
   Activity；介意的可以在自己的 manifest 里 `tools:node="remove"`

## 8. 落地前必须先 spike 的四件事

**这些的结论会改变实现，未验证前不要动手。**

| # | 要验证什么 | 若答案不利 |
|---|---|---|
| 1 | **AGP 允不允许库的 manifest 用 `${applicationId}`**。历史上它在 `android:authorities` 之类属性上有过限制（库 manifest 也会被单独处理），在 `<data android:scheme>` 上行不行**未验证** | 退回 `manifestPlaceholders["loginbaseScheme"]` 一行配置。方案不受影响，只是消费方从 0 行变 1 行，且要自己处理 debug/release 变体的 scheme 隔离 |
| 2 | **`ActivityResultLauncher` 能否在 suspend 函数里临时注册**。AndroidX 要求在 `STARTED` 之前 `registerForActivityResult`；`activityResultRegistry.register(key, ...)` 理论上允许任意时刻注册并手动反注册，但**进程死亡后 registry 的 pending 恢复行为我没验证过** | `signIn()` 改成接收消费方在 `onCreate` 注册好的 launcher——接入体验退一步 |
| 3 | **中转页 `isTaskRoot` + 起 launcher 的实际行为**：App 被回收后回跳，用户看到的是不是正常的冷启动，返回键行为对不对 | 可能要调 `launchMode` / `taskAffinity`，属实现细节，不影响架构 |
| 4 | **Auth Tab 的 callback 能否扛住进程死亡**，以及回退判定的准确 API（可用性怎么查） | **风险已被中转页吃掉**：扛不住就由中转页接住，走 §6.3 的时序。这项现在只影响「多数情况下走哪条通路」，**不影响架构**，可以后验 |

前三项建议在同一个 spike App 里一次验完——它们都要真机跑一遍完整回跳。

文中标注 API 形态时刻意没写死具体签名：Auth Tab 是较新的 API，未实际编译过，以实测为准。

## 9. 依赖红线怎么处理

`androidx.browser` 与 README「设计红线」的「仅此三个」冲突。**建议修订红线而不是违反它**，
因为红线的理由（「auth 库是供应链攻击的最高价值目标」）对它不成立：

| | 第三方库 | `androidx.browser` |
|---|---|---|
| 维护方 | 任意 | Google / AndroidX，与 Android SDK 同一信任级别 |
| 是否已在消费方 classpath | 未必 | 几乎所有 Android App 都有 |

修订后的表述：**common 层仍是那三个；平台层只允许该平台的一等公民 API（AndroidX、
Apple 系统框架）**。supabase-kt 的 `Auth` 模块就是这个分法。

## 10. 分期

| 期 | 内容 | 产出 |
|---|---|---|
| **0** | 第 8 节的三个 spike | 一份结论，决定 1 期的形态 |
| **1** | `commonMain`：`OAuthOutcome`、`handleOAuthCallback`、`oauthResults` | 零新依赖，可单独测（喂 URL 断言结果），**先于任何 Android 代码落地** |
| **2a** | `androidMain`：中转页 + 系统浏览器 + `${applicationId}` scheme | 引入 `androidx.browser`，红线同步修订。**这一步就能独立跑通完整流程**，且不依赖任何 Auth Tab 的未知数 |
| **2b** | Auth Tab 优先通路 | 纯优化：少一次任务切换、少一个 Intent 暴露面。做不成也不影响可用性 |
| **3** | README 接入指南更新 + 限制说明 | |

**1 期能独立交付且独立有价值**：即使 2 期不做，接入方自己开浏览器时也已经不用再解析参数、
不用分辨流程了。而它零新依赖、可完整单测——先做它，风险最低。


---

# 11. 校准记录（与既有实现对照）

## 背景

本方案的正文是在**不知道消费方已有实现**的前提下写的——这是刻意的，避免设计被既有代码
锚定。写完之后再拿 TrendingAI 的实际实现做对照校准。

对照来源（都在本机）：

| 来源 | 路径 | 相关文件 |
|---|---|---|
| 消费方（Android/KMP） | `~/TrendingProjects/TrendingAI`（**分支 `feat/loginbase-auth`**，main 上没有这些文件） | `androidApp/.../AuthRedirectActivity.kt`、`shared/.../auth/LoginbaseAuthManager.kt`、`androidApp/build.gradle.kts` |
| 消费方服务端 | `~/TrendingProjects/github-ai-trending-api` | `src/lib/loginbase.js`、`wrangler.toml` 的 `AUTH_DEEPLINKS` |
| 本库服务端 | `~/loginbase` | `src/token.ts`、`src/middleware.ts` |
| 参考实现 | GitHub | AppAuth-Android 的 `RedirectUriReceiverActivity` 与 `library/AndroidManifest.xml`（已逐字核对） |

**TrendingAI 已经手写了本方案的大部分**：中转页、进程级回调总线（`replay = 1`）、
三态回跳解析。所以 #26 的性质是「把已验证的实现下沉进库并补齐库特有的部分」，
不是从零设计——但也**不是照抄**：它是 App 层实现，若干选择在库层不成立。

## 八条差异与裁决状态

| # | 决策点 | 本文原设计 | TrendingAI | AppAuth | 裁决 | 状态 |
|---|---|---|---|---|---|---|
| 1 | scheme 怎么传 | `${applicationId}` | placeholder + `buildConfigField` 双喂 | `${appAuthRedirectScheme}` placeholder | **placeholder + `<meta-data>`** | ✅ 已对齐 |
| 2 | 中转页 launchMode | `singleTask` | `standard` | **无（默认 standard）** | **不写**（standard） | ✅ 已对齐 |
| 3 | 怎么回到 App | `isTaskRoot` 分支 | `REORDER_TO_FRONT` | 转交管理 Activity → `PendingIntent` | `getLaunchIntentForPackage` + `REORDER_TO_FRONT` | ⬜ 待对齐 |
| 4 | redirect 形态 | 带 host | `scheme:/path` 无 host | 不限定 | 无 host（RFC 8252 §7.1 示例形态） | ⬜ 待对齐 |
| 5 | 结果通道 | 双通道（返回值 + flow） | 单通道（bus） | 单通道（PendingIntent） | **单通道**，且 `signIn()` 可不必挂起 | ⬜ 待对齐 |
| 6 | 取消检测 | 「做不到」 | ON_RESUME + `hasPending` | `canceledIntent` | **分层**：库拥有启动时是确定信号，纯浏览器兜底才用启发式 | ⬜ 待对齐 |
| 7 | 幂等 / replay | 记住最后 otc | `resetReplayCache()` | — | **两个机制各司其职**（前者防跨通路重复，后者防陈旧 replay） | ⬜ 待对齐 |
| 8 | URL 解码 | 未提 | 手写 20 行 percent-decoder | — | 用 ktor 的 `decodeURLQueryComponent()`（既有依赖） | ⬜ 待对齐 |

> 逐条对齐时都要**带一个具体场景**说明校准方案，不要只给结论。

## 差异 #1 的完整结论（已对齐）

### 裁决

**采用 `manifestPlaceholders` + `<meta-data>`。撤回 `${applicationId}` 与「字符串资源」两个方案。**

### 为什么 `${applicationId}` 不成立

`applicationId` 反写未必是自有域名——TrendingAI 是 `whl.trending.ai`，反写不对应任何真实
域名，RFC 8252 §7.1 不合规；他们实际用 `cn.trendingai`（← `trendingai.cn`），且在
`build.gradle.kts` 里明确注释过「不能用 applicationId」。

更根本的理由：**服务端 redirect 白名单永远要人工配**（那是安全控制）。自动推导并没有消掉
人工配置，只是把「两处人工必须一致」变成「一处人工 + 一处自动必须碰巧一致」——改包名、
加渠道后缀就会悄悄漂移。

**所以「消费方零配置」在 scheme 这一项上不成立，实际是每变体一行。** 其余各项（manifest
XML、生命周期钩子、参数解析、流程分辨、`exchangeOtc`）仍是零。

### 为什么不是字符串资源

字符串资源能做到单一来源，但**忘配时是静默失败**（用库的默认值），症状「用户授权完卡在
浏览器」离原因「少写一行 gradle」极远。placeholder 忘配则**构建期直接失败**——花机器时间
换人的排查时间，值。

### 为什么加 `<meta-data>`

`<meta-data>` 是 `buildConfigField` 在库场景下的等价物：TrendingAI 用 `BuildConfig` 把同一个
值喂给运行时代码，但**库读不到消费方的 `BuildConfig`**。同一个 placeholder 同时填进
`<data android:scheme>` 与 `<meta-data>`，运行时经 `PackageManager` 读回，物理上不可能漂移。

```xml
<data android:scheme="${loginbaseRedirectScheme}" />
<meta-data android:name="loginbase.redirectScheme"
           android:value="${loginbaseRedirectScheme}" />
```

### 配套：怎么让接入方感知这条跨仓库不变式

跨仓库的不变式靠文档一定会腐化，主要手段是**错的时候尽早、就地、带修法报错**。四层：

1. **构建期**：placeholder 缺失 → 构建失败（采用 placeholder 而非字符串资源的核心理由）
2. **发起前自检**（~15 行，最高价值）：拉起浏览器**之前**检查 meta-data 是否存在、
   `Intent(ACTION_VIEW, redirect)` 能否被解析、解析到的是否是本 App。三种失败分别对应
   「没给 meta-data」「scheme 写错或变体没配」「**别的 App 抢了同一个 scheme**」——
   最后一条顺带是安全信号
3. **错误消息带上「另一半」**：客户端报错时必须提醒服务端白名单要填同一个值，并说明
   「两边不一致时浏览器停在 `invalid_redirect`，App 侧收不到任何信号」
4. **让「该填给服务端什么」随手可查**：`Loginbase.redirectUri(context)` 一行返回
   `cn.trendingai:/loginbase/callback`；debug 构建（`FLAG_DEBUGGABLE`）首次发起时自动打印

README 需要一个独立小节，含「三处一致」表与**症状 → 原因**映射表。

**明确不做**：debug 构建预检服务端（发起前探一次 start 端点）。浏览器里的
`invalid_redirect` 本身并不隐蔽（地址栏能看到发出去的 redirect 值），不值得每次登录多一次
请求 + 一条只在 debug 存在的代码路径。

## 差异 #2 的完整结论（已对齐）

### 裁决

**中转页不写 `launchMode`（即默认 `standard`）。撤回正文 §5.3 草图里的 `singleTask`。**
顺带采纳 TrendingAI 的 `android:excludeFromRecents="true"`（见文末）。

### 场景一：重复回跳落在 `finish()` 尚未完成的窗口里

§6.1 已把重复送达当作必须处理的现实（系统重放 Intent、用户手动点浏览器里的链接、
回退判定失误）。设想：

1. 用户授权完成，回跳拉起中转页，`onCreate` 拿到 URL、投递、调 `finish()`
2. `finish()` 不是瞬时的——实例要走完生命周期才销毁；就在这个窗口里，第二个重复 intent 到达

| | `standard`（裁决） | `singleTask`（正文原案） |
|---|---|---|
| 第二个 intent 的去向 | 新建一个实例，照常走 `onCreate` | 复用旧实例，改走 **`onNewIntent`** |
| 中转页要实现的入口 | 只有 `onCreate` | `onCreate` **和** `onNewIntent` 两条 |
| 漏写第二条的症状 | 不存在第二条 | 回跳被**静默吞掉**，用户停在浏览器里 |

`standard` 下重复送达由 §6.1 的 otc 幂等兜住（第二个实例送进同一个 otc，拿到缓存结果）——
去重本来就必须做，不因 launchMode 改变。`singleTask` 买到的「任务内唯一实例」对一个
存活几百毫秒、无状态、见谁都 `finish` 的转发 Activity 毫无价值，却强加了一条只在边角
时序里才会被走到的代码路径。**多数时候它表现与 `standard` 完全一样，这正是危险所在**：
测试全绿，分岔只在真机的边角时序里出现。

### 场景二：`singleTask` 与正文自己的 `isTaskRoot` 判据打架

AppAuth issue #170（已核对原文）：多进程 + 自定义 taskAffinity 的 App 里，`singleTask`
会把授权 Activity 劈进**独立任务**。套到正文设计上：中转页独立成任务根 →
`isTaskRoot == true` → §5.5 误判「进程被回收」→ 起 launcher intent——App 明明活着，
在深层绑定页上的用户被踢回首页，恰好是 §5.5 自己明文要避免的事故。正文的 `singleTask`
和 `isTaskRoot` 单看各自都像对的，合在一起互相拆台。

### AppAuth 的 `singleTask` 装在哪，为什么本库连那个位置都没有

AppAuth 的 manifest（已逐字核对）里 launchMode 是有分工的：

| Activity | launchMode | 用途 |
|---|---|---|
| `RedirectUriReceiverActivity`（接收器，= 我们的中转页） | **不写** | 收 intent、转交、finish |
| `AuthorizationManagementActivity`（等在栈下面的管理者） | `singleTask` | 回跳时把自己提回栈顶，顺带清掉上面的 Custom Tab |

正文把参考实现里**管理者**的属性安到了**接收器**头上。而本库按 #3 的裁决不设管理
Activity（launcher intent + `REORDER_TO_FRONT` 直达），`singleTask` 在本方案里没有
出场位置。

况且 AppAuth 自己的 `singleTask` 也是持续的坑源，两条 issue 已核对原文：

- **#170**：多进程 + 自定义 taskAffinity 时，`singleTask` 把「管理者 → Custom Tab」劈进
  独立任务，活动栈无法管理。closed，维护方只有解释、没有解法
- **#977**：Android 14 上 `singleTask` 的管理者在回跳时被**重建而非复用**，`onResume`
  里等结果的逻辑失效、登录断裂；报告者把 launchMode 改成 `singleInstance` 才绕过，
  issue 至今 open

> 引证更正：TrendingAI 注释里「他们改用了 singleInstance」的「他们」是 #977 的
> **报告者**（一个使用 AppAuth 的 App），不是 AppAuth 本身——master 的 manifest 至今
> 仍是 `singleTask`。顺手记下，防以后误引。

结论：这条链路上任何非默认的 launchMode 都是行为分岔的来源；库能选的最可预测的值就是
「不写」。TrendingAI 的 manifest 注释同判：「刻意保持默认 launchMode（同 AppAuth 的
RedirectUriReceiverActivity）——不把 OAuth 的特殊需求写进主 Activity 的全局启动语义」。

### 顺带：`excludeFromRecents="true"`

TrendingAI 的中转页有、正文草图没有。**采纳。** 场景：冷启动通路（§6.3）里中转页是新
任务的根，不加的话「最近任务」列表会闪出一个无名的透明条目；它转瞬即逝，不该留痕。
App 存活的通路里它落在 App 既有任务栈顶、不单独成条目，此属性无副作用。
