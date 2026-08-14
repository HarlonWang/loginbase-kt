# 设计方案：社交登录的浏览器环节收进库内（Auth Tab + intent-filter 双通路）

> 状态：**待评审**。对应 `docs/todo.md` 第 26 条。
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
 * 优先 Auth Tab（Chrome 137+）：回跳由浏览器直接 callback 回来，不经过 Intent，
 * 因此不需要 intent-filter，也没有 Intent 被其他 App 劫持的暴露面。
 * 不可用时回退到 Custom Tabs + intent-filter，此时结果从 [oauthResults] 送达。
 */
suspend fun AuthClient.signIn(
    activity: ComponentActivity,
    provider: OAuthProvider,
    redirect: String,
): OAuthOutcome

suspend fun AuthClient.link(
    activity: ComponentActivity,
    provider: OAuthProvider,
    redirect: String,
): OAuthOutcome
```

### 5.3 中转页：把接线成本吃掉

回退通路需要一个能接 `VIEW` Intent 的 Activity。**由库提供**，而不是让消费方在自己的
Activity 上挂 intent-filter——这是 AppAuth（`RedirectUriReceiverActivity`）和 Auth0
（`RedirectActivity`）的共同做法：库在自己的 `AndroidManifest.xml` 里声明它，scheme 由
消费方通过 `manifestPlaceholders` 注入，manifest merger 负责合并。

库侧 manifest：

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
        <data android:scheme="${loginbaseScheme}" />
    </intent-filter>
</activity>
```

消费方**只剩两行**：

```kotlin
// build.gradle.kts
android { defaultConfig { manifestPlaceholders["loginbaseScheme"] = "cn.trendingai" } }

// Application.onCreate —— 让中转页找得到 AuthClient
Loginbase.registerCallbackHandler(auth)
```

之后：

```kotlin
val outcome = auth.signIn(this, OAuthProvider.GitHub, "cn.trendingai://auth/callback")
auth.oauthResults.collect { outcome -> ... }   // 进程被回收后走这条
```

### 5.4 接线成本对比

| | 没有中转页 | 有中转页 |
|---|---|---|
| manifest | **手写一段 intent-filter** 挂在自己的 Activity 上 | build.gradle 一行 placeholder |
| 生命周期接线 | `onCreate` + `onNewIntent` **各调一次** | 无 |
| 找到 `AuthClient` | 天然有（在自己的 Activity 里） | 一行注册 |
| 观察结果 | `oauthResults` | `oauthResults` |

净变化：从「一段 manifest XML + 两个生命周期钩子」变成「一行 gradle + 一行注册」。

**消掉的那两个钩子恰恰是最容易出错的地方**——漏写 `onNewIntent` 会导致「App 在后台时
授权回来没反应」，而这个 bug 只在特定启动模式下复现，很难查。

对比今天的接入方式，则是**参数解析、流程分辨、`exchangeOtc` 全部消失**，发起从四步
（开浏览器 + 等 deep link + 抠参数 + 换令牌）变成一句挂起调用。

### 5.5 中转页引入的两个新问题及解法

**问题 1：库的 Activity 怎么找到 `AuthClient`？**

它由系统实例化，够不到 App 的 DI 图。解法是**显式进程级注册**：

```kotlin
Loginbase.registerCallbackHandler(auth)
```

也考虑过让 `AuthClient` 构造时自动注册到一个 static——否掉了：构造函数带隐式全局副作用，
多实例时行为不可预测，而且会干扰测试。宁可要一行显式代码。

**问题 2：处理完怎么把用户送回 App？**

分两种情况，用 `isTaskRoot` 区分：

| 情况 | `isTaskRoot` | 动作 |
|---|---|---|
| App 还活着，中转页落在 App 的任务栈里 | `false` | 直接 `finish()`，露出下面原来的界面 |
| App 已被回收，中转页是新任务的根 | `true` | 起 App 的 launcher intent（`packageManager.getLaunchIntentForPackage`），再 `finish()` |

不用 AppAuth 那种「消费方提供 `PendingIntent`」的做法——那等于把接线成本又还回去了。
`isTaskRoot` 是标准信号，且库能自己拿到 launcher intent，不需要消费方配置。

**注意不要用 `FLAG_ACTIVITY_CLEAR_TOP` 之类去「回到首页」**：用户可能在很深的页面上发起
绑定，清栈会把他的位置弄丢。只在任务确实不存在时才起 launcher。

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
  → intent-filter 冷启动 Activity → onCreate 拿到 intent.data
  → handleOAuthCallback → exchangeOtc → 落盘
  → authState 变 SignedIn（全局导航自然跳转）
  → oauthResults 发出 SignedIn
```

发起 `signIn()` 的那个协程早已随进程消失，所以**结果必须能从 `oauthResults` 拿到**——
这就是它存在的理由，也是为什么 `replay = 1`（`onCreate` 里的 `handleOAuthCallback` 可能
早于 UI 订阅）。

### 6.4 与现有并发机制的关系

`handleOAuthCallback` 内部走 `exchangeOtc` → `persist`，和 `refresh` 一样受 `storeMutex`
保护。与在途刷新、并发登出的竞态由已有机制覆盖，不引入新的锁。

## 7. 已知限制（必须写进 README）

1. **回退通路识别不了「用户取消」**——没有任何信号会回到 App
2. **回退通路仍需在 build.gradle 里给出 scheme 占位符**，所以「零配置」做不到；但配置量已经
   压到一行，且不需要写任何 manifest XML 或生命周期代码
3. **只有 Android**。iOS 转正前，那边仍是 `signInUrl()` + 自己开浏览器
4. `link` 流程的失败原因由服务端 App 定义（`already_linked` 是典型值但不是协议保证），
   库只能原样透传给 `Failed.reason`
5. **只用邮箱验证码、不用社交登录的消费方，也被迫提供 `loginbaseScheme` 占位符**，
   否则 manifest merger 会失败。这是库声明 Activity 这个做法的通病——Auth0 有同样的
   issue。逃生口是在自己的 manifest 里 `tools:node="remove"` 掉它，README 要写明

## 8. 落地前必须先 spike 的三件事

**这三件的结论会改变实现，未验证前不要动手。**

| # | 要验证什么 | 若答案不利 |
|---|---|---|
| 1 | **Auth Tab 的 callback 能否扛住进程死亡**？App 在浏览器停留期间被回收，回调还在不在 | **风险已被中转页吃掉**：扛不住就由中转页接住，走 §6.3 的时序。这个 spike 现在只影响「多数情况下走哪条通路」，**不再影响架构**——先做中转页，这一项可以后验 |
| 2 | **`ActivityResultLauncher` 能否在 suspend 函数里临时注册**。AndroidX 要求在 `STARTED` 之前 `registerForActivityResult`；`activityResultRegistry.register(key, ...)` 理论上允许任意时刻注册并手动反注册，但**进程死亡后 registry 的 pending 恢复行为我没验证过** | 若不行，`signIn()` 的签名要改成接收一个由消费方在 `onCreate` 注册好的 launcher——接入体验会退一步 |
| 3 | **回退判定的准确 API**（`AuthTabIntent` 可用性怎么查、回退时的具体形态） | 影响回退分支的实现，不影响整体架构 |

我在文中标注 API 形态时刻意没写死具体签名——Auth Tab 是较新的 API，签名以 spike 实测为准。

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
| **2a** | `androidMain`：中转页 + 系统浏览器 | 引入 `androidx.browser`，红线同步修订。**这一步就能独立跑通完整流程**，且不依赖任何 Auth Tab 的未知数 |
| **2b** | Auth Tab 优先通路 | 纯优化：少一次任务切换、少一个 Intent 暴露面。做不成也不影响可用性 |
| **3** | README 接入指南更新 + 限制说明 | |

**1 期能独立交付且独立有价值**：即使 2 期不做，接入方自己开浏览器时也已经不用再解析参数、
不用分辨流程了。而它零新依赖、可完整单测——先做它，风险最低。
