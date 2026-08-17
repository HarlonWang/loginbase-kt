# 设计方案：社交登录的浏览器环节收进库内（三级回退链 + 库内双 Activity）

> 状态：**设计定稿**——正文已按第 11 节的校准结论（差异 #1–#9）统一重写。
> 对应 `docs/todo.md` 第 26 条。落地前须完成第 8 节的三项真机 spike。
>
> 第 11 节保留为**校准记录**（取证过程、被推翻的初裁、引证更正），回答
> 「当初为什么这么定」；正文与第 11 节冲突时说明重写有误，应报修。
>
> 目标读者：维护者。决策背景见第 1 节。

## 1. 为什么做

**RFC 8252** 要求原生 App 只能用外部 user-agent 做 OAuth 授权，禁止嵌入式 WebView。
现在库的做法是 `signInUrl()` 返回一个字符串、在注释里写「不要用 WebView」——**规范要求被
降级成了一句劝告**，调用方把它塞进 WebView 库既不知道也拦不住。

业界参考实现无一例外自己拥有这一步：AppAuth-Android（OpenID 基金会参考实现，作者即
RFC 8252 作者）、Auth0.Android 的 `WebAuthProvider`、以及同类 KMP 库 supabase-kt 的
`Auth` 模块（`androidMain` 里 `api(libs.androidx.browser)`）。

顺带解决今天接入体验上最扎眼的一块：接入方要自己写 manifest intent-filter、自己从
deep link 抠参数，而且**登录与绑定的回跳参数是两套**（`otc` vs `linked=<provider>` /
`error=<reason>`），要自己分辨；用户关掉浏览器的「取消」还得自己写约 20 行生命周期
启发式去兜（消费方 TrendingAI 实测踩过「面板永远转圈」）。

## 2. 目标与非目标

**目标**

- 授权页由库在合规的外部 user-agent 里打开，调用方拿不到「塞进 WebView」的机会
- 回跳的捕获与解析归库，登录/绑定两套参数的差异对调用方不可见
- **进程在浏览器停留期间被系统回收后，流程仍能走完**
- 用户取消能被识别：Auth Tab 与 Custom Tab 通路是确定信号，系统浏览器兜底是
  「迟到的确定信号」（见 §6.2）

**非目标**

- 不做 iOS。iOS 是占位（见 README），`ASWebAuthenticationSession` 等 iOS 转正后再说
- 不做 UI。登录界面仍归 App
- 不接管 PKCE / state / code 交换——本库的 OAuth 是**服务端流**，客户端只负责「开页面」
  和「拿 otc」，这些环节本来就不在客户端

## 3. 核心设计：三级回退链，所有结果汇进一个漏斗

库内两个 Activity 分工（消费方对两者都无感知）：

| | 中转页 `LoginbaseCallbackActivity` | 管理页 `LoginbaseAuthActivity` |
|---|---|---|
| 谁创建 | 系统（回跳 intent 触发） | 库（`signIn()/link()` 启动） |
| 出生时机与位置 | 流程结尾，浏览器页之**上** | 流程开头，浏览器页之**下**（锚点） |
| manifest 姿态 | `standard`、`exported`、intent-filter、`excludeFromRecents` | `singleTask`、**不** exported、透明 |
| 职责 | 收回跳 intent，带 `CLEAR_TOP or SINGLE_TOP` 转发给管理页，finish（~15 行，无状态） | 按三级链开浏览器、收结果、判取消、决定去向、投递 |

```
signIn() ──→ 管理页（透明，压进栈里当锚点）
               │ 按可用性选一级：
               ├─ Auth Tab（Chrome 137+）→ 回跳被浏览器捕获 → ActivityResult 回调 ─┐
               ├─ Custom Tab             → 回跳 → 中转页 → CLEAR_TOP 转发 ────────┤
               └─ 系统浏览器              → 回跳 → 中转页 → CLEAR_TOP 转发 ────────┤
                                                                                ↓
                                        管理页统一投递 → handleOAuthCallback(url)
                                                                                ↓
                                        oauthResults（消费方唯一的结果通道）
```

三条通路的差别**只在「结果怎么回到管理页」**。回来之后只有一份逻辑：解析 URL →
判断登录还是绑定 → 登录则 `exchangeOtc` → 发到 `oauthResults`。

为什么必须有管理页、不能只留中转页：清除浏览器页（CLEAR_TOP）需要一个**事先压在它
下面**的锚点；取消检测需要一个「用户放弃时必然 resume」的观察者。中转页出生在流程
结尾、位置在浏览器页之上，两件事都干不了；合成一个 Activity 则必须把 singleTask 暴露
给浏览器的外部 intent、并把有状态组件放上 exported 面（论证见 §11 差异 #3）。

## 4. 不需要持久化「我在等什么」

进程被回收后冷启动，App 是全新进程，直觉上需要知道「这次回跳属于哪个流程」。

**但回跳参数自带这个信息**：

| 回跳参数 | 流程 |
|---|---|
| `otc=<code>` | 登录 |
| `linked=<provider>` | 绑定成功 |
| `error=<reason>` | 绑定失败（登录失败也走这个） |

所以**不需要任何 pending 状态持久化**，也不需要第二个存储。这条让方案的复杂度掉了
一大截——没有「状态写坏了怎么办」「什么时候过期清理」这类问题。

校准还证明它兑现了一笔额外红利：AppAuth/Auth0 在 Android 14 上的 #977 断裂（管理页
被系统重建后配不上存储的 pending request）对本库**不成立**——回跳 URL 自带全部信息，
重建的管理页照常投递（§6.4）。

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

    /** 用户主动放弃。三条通路都能给出，系统浏览器通路迟到（见 §6.2） */
    data object Cancelled : OAuthOutcome

    /** 回跳 URL 不是本库认得的形状（含畸形 percent 编码）——大概率是接入配置错了
        或外部塞了异常输入，该报给开发者 */
    data class Unrecognized(val url: String) : OAuthOutcome
}

/**
 * 处理一次 OAuth 回跳。**所有通路的唯一汇合点。**
 *
 * 幂等：同一 otc 只兑换一次，重复送入返回缓存结果、不打服务端（§6.1）。
 * 结果除返回外**必发一份到 [oauthResults]**——UI 只看后者；返回值给直接调用方
 * （自定义流程、iOS 占位、测试）。
 * 解析用 ktor 的 `parseQueryString()`（既有依赖）；任何畸形输入不抛异常，
 * 收编为 [OAuthOutcome.Unrecognized]。
 */
suspend fun handleOAuthCallback(url: String): OAuthOutcome

/**
 * 消费方唯一的结果通道（`replay = 1`）。
 *
 * replay 只为兜「投递早于订阅」的时序（进程回收后冷启动，发起协程已不存在），
 * **不是历史记录**：处理完结果调 [consumeOauthResult] 清掉，防陈旧结果重放给
 * 后来的订阅者（§6.1）。
 */
val oauthResults: SharedFlow<OAuthOutcome>

/** 声明结果已处理，清 replay 缓存。 */
fun consumeOauthResult()
```

`signInUrl` / `linkUrl` **保留但降级**：仍然公开（自定义流程、非 Android 平台需要），
文档上标注「一般不要直接用，用 `signIn()`」。

### 5.2 可选浏览器模块（Android-only，独立 artifact）

浏览器环节整体住在**独立的可选 artifact**（暂名 `loginbase-kt-browser`，同仓同版本
发布）：两个 Activity、manifest 声明、三级回退链、下面的扩展函数、`androidx.browser`
依赖都在这里。**不用社交登录的消费方不引它，零感知**——没有 placeholder 要配、没有
多余 Activity 合并进来（裁决过程见 §11 差异 #10，含 AppAuth/Auth0 单 artifact 形态的
反面教训）。

模块化按**机制**分，不按 provider 分：客户端是服务端流、provider 无关，走浏览器流的
所有 provider（GitHub、将来任何一家）共享这一套零增量代码；若未来引入需要原生 SDK
的通路（如 Google Credential Manager 一键登录），各自独立成可选模块，不进本模块。

```kotlin
/**
 * 发起社交登录。**不挂起**——启动管理页即返回，结果从 [oauthResults] 送达。
 * （挂起返回值在屏幕旋转、进程回收下必然中断，是引诱消费方漏写 flow 路径的
 * 陷阱，见 §11 差异 #5。）
 *
 * 浏览器按三级回退链选择：Auth Tab（Chrome 137+，回跳不经 Intent、无劫持暴露面）
 * → Custom Tab（任何实现 CustomTabsService 的浏览器）→ 系统浏览器。
 *
 * redirect 由 manifest 的 meta-data 读回（§5.3），与 intent-filter 同源，不可能漂移。
 */
fun AuthClient.signIn(activity: Activity, provider: OAuthProvider)

fun AuthClient.link(activity: Activity, provider: OAuthProvider)

/** 需要自定义 redirect 的重载。只收 private-use scheme，不支持 https app-link。 */
fun AuthClient.signIn(activity: Activity, provider: OAuthProvider, redirect: String)
```

### 5.3 scheme、redirect 与两个 Activity：每变体一行配置

#### scheme 是什么

`cn.example:/loginbase/callback` 里的 `cn.example` 就是 scheme，地位等同 `https`。
区别是浏览器不认识它，会问系统「谁认领了这个 scheme」——**它是浏览器把控制权还给 App
的唯一线索**：

```
5. 服务端 302 → cn.example:/loginbase/callback?otc=abc123
6. 浏览器：这个 scheme 我不认识 → 问 Android
7. Android：翻所有 App 的 manifest，找谁声明了 <data android:scheme="cn.example" />
8. 拉起那个 Activity（本库的中转页），intent.data = 上面那个 URL
```

同一个 scheme 必须在**三处**一致，而且写错的地方和报错的地方对不上，很难查：

| 出现在哪 | 谁负责 | 写错的症状 |
|---|---|---|
| 服务端 redirect 白名单 | 服务端 App 配置 | 授权还没开始就被拒（`invalid_redirect`） |
| App 的 manifest（经 placeholder） | Android 构建 | 第 7 步没人接，**用户授权完卡在打不开的页面** |
| 运行时拼 redirect 用的值（经 meta-data 读回） | 库 | 同上两种之一 |

#### 用 placeholder + `<meta-data>` 收进库里

两个 Activity 都声明在**库自己的 manifest** 里（AppAuth、Auth0 同做法），消费方一行
XML 都不写：

```xml
<!-- 中转页：对外的哑门铃。standard——singleTask 收浏览器外部 intent 是
     onNewIntent 双路径与任务分裂的坑源（§11 差异 #2） -->
<activity
    android:name=".LoginbaseCallbackActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="${loginbaseRedirectScheme}" />
    </intent-filter>
</activity>

<!-- 管理页：库内锚点，外界摸不到。姿态与 AppAuth/Auth0 逐字一致 -->
<activity
    android:name=".LoginbaseAuthActivity"
    android:exported="false"
    android:launchMode="singleTask"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />

<!-- 同一占位符再喂一份给运行时（经 PackageManager 读回），物理上不可能漂移。
     这是消费方 buildConfigField 双喂在库场景下的等价物——库读不到消费方的 BuildConfig -->
<meta-data
    android:name="loginbase.redirectScheme"
    android:value="${loginbaseRedirectScheme}" />
```

消费方 `build.gradle.kts`，每变体一行：

```kotlin
defaultConfig {
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example"
}
buildTypes.getByName("debug") {
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example.debug"
}
```

scheme 取**自有域名反写**（RFC 8252 §7.1 的 MUST）。不能自动用 applicationId 推导——
反写未必对应真实域名（校准实证：TrendingAI 的 applicationId `whl.trending.ai` 反写
不是任何域名，他们实际用 `cn.trendingai` ← trendingai.cn）；更根本的是服务端白名单
永远要人工配，自动推导只是把「两处人工必须一致」变成「一处人工 + 一处自动必须碰巧
一致」，改包名就悄悄漂移（§11 差异 #1）。

忘配 placeholder → **构建期直接失败**。这是选 placeholder 而非字符串资源的核心理由：
字符串资源忘配是静默回落默认值，症状「用户授权完卡在浏览器」离病因「少写一行 gradle」
极远；构建失败花机器时间换人的排查时间，值。

debug/release 用**独立 scheme** 隔离（而非 path）：Android 的 `<data>` 规则是「没有
host 时所有 path 属性都被忽略」，无 host 形态下 scheme 是唯一过滤维度；两个变体装同一台
机器物理隔离，不会弹「用哪个应用打开」。代价：服务端白名单两条。

#### redirect 形态：无 host 单斜杠

库默认 redirect 为 `<scheme>:/loginbase/callback`（单斜杠，无 host）——RFC 8252 §7.1
的示例形态。host 位在 private-use scheme 里**没有所有权语义**（纯字符串，任何截胡 App
照抄即可），带上它只会让「几个斜杠」成为必须记对的细节：`scheme://x/cb` 与
`scheme:/x/cb` 在服务端 `new URL()` 眼里 host 一个是 `"x"` 一个是空串，精确匹配直接
失败，症状是授权未开始就 400 `invalid_redirect`，而两个字符串肉眼几乎相同（§11 差异 #4）。

#### 跨仓库不变式的感知（四层）

跨仓库的「三处一致」靠文档一定会腐化，主要手段是**错的时候尽早、就地、带修法报错**：

1. **构建期**：placeholder 缺失 → 构建失败
2. **发起前自检**（~15 行，最高价值）：拉起浏览器**之前**检查 meta-data 是否存在、
   `Intent(ACTION_VIEW, redirect)` 能否被解析、解析到的是否是本 App。三种失败分别对应
   「没给 placeholder」「scheme 写错或变体没配」「**别的 App 抢了同一个 scheme**」——
   最后一条顺带是安全信号
3. **错误消息带上「另一半」**：客户端报错时必须提醒服务端白名单要填同一个值，并说明
   「两边不一致时浏览器停在 `invalid_redirect`，App 侧收不到任何信号」
4. **让「该填给服务端什么」随手可查**：`Loginbase.redirectUri(context)` 一行返回
   `cn.example:/loginbase/callback`；debug 构建（`FLAG_DEBUGGABLE`）首次发起时自动打印

README 需要一个独立小节，含「三处一致」表与**症状 → 原因**映射表（含「invalid_redirect
且字符串肉眼相同 → 数斜杠」）。

**明确不做**：debug 构建预检服务端（发起前探一次 start 端点）。浏览器里的
`invalid_redirect` 本身并不隐蔽（地址栏能看到发出去的 redirect 值），不值得每次登录多
一次请求 + 一条只在 debug 存在的代码路径。

### 5.4 接线成本对比

| | 今天（消费方手写，TrendingAI 实测） | 本方案 |
|---|---|---|
| manifest | 手写 intent-filter | — |
| build.gradle | scheme 常量 + `buildConfigField` 双喂 | 每变体一行 placeholder |
| 生命周期接线 | `onCreate` + `onNewIntent` | — |
| 解析回跳参数 | 手写（含 ~20 行 percent-decoder） | — |
| 分辨登录/绑定 | 自己写 | — |
| 调 `exchangeOtc` | 自己写 | — |
| 取消检测 | ~20 行 `ON_RESUME` 启发式（含防竞态标志，易错） | — |
| **消费方新增** | **7 项** | **一行依赖（browser 模块）+ 每变体一行 placeholder** |

`restore()` 与观察 `oauthResults` 不计入——它们本来就在接入指南里。

消掉的恰是最易错的两块：漏写 `onNewIntent` 导致「App 在后台时授权回来没反应」；
取消启发式的调度竞态导致「成功被误判成取消、loading 闪断」（均为消费方实测踩过）。

### 5.5 库组件怎么找到 `AuthClient`

管理页由库启动、中转页由系统实例化，都够不到 App 的 DI 图。**把两条路径拆开看，
各自都有现成的抓手**：

| 路径 | 谁把 URL 交给 `AuthClient` |
|---|---|
| **App 还活着**（多数情况） | `signIn()` 发起时把 `this` 放进库内静态槽——**发起方就是它自己，不需要外人注册** |
| **进程被回收** | 静态槽随进程消失 → 管理页把 URL **停泊**在静态里 → 起 launcher → App 冷启动 → **`restore()` 把停泊的 URL 排空** |

模块拆分带来一条依赖方向约束：停泊槽须住在**核心**（browser 模块写入、核心的
`restore()` 排空），保持 browser → core 单向依赖，具体形态落地时定。

`restore()` 本来就是接入指南第 3 步、本来就必须在启动时调；而管理页的处理**必然早于**
App 的 `restore()`（它是先被拉起的那个 Activity），时序天然成立。

> 也考虑过「`AuthClient` 构造时自动注册到静态」——否掉了：构造函数带隐式全局副作用，
> 多实例时行为不可预测，还会干扰测试。上面的分路做法既没有这个问题，也不需要消费方参与。

「处理完怎么把用户送回 App」不再是独立问题：App 活着时管理页 `finish()` **原地露出
发起页**（深层页面的位置不丢）；冷启动分支起 launcher（`getLaunchIntentForPackage`）。
不用 `REORDER_TO_FRONT`（多 Activity 消费方里会把深层页埋掉）、不用消费方提供
`PendingIntent`（把接线成本还回去）——论证见 §11 差异 #3。

## 6. 关键机制

### 6.1 幂等（两层，各司其职）

**otc 层（防重复打服务端）**：otc 单次有效、兑换即销毁，而回跳 URL 的送达次数库控制
不了（用户点浏览器历史里的链接、ROM 重放 intent、回退判定失误双送）。库在进程内记住
最近处理过的 otc，重复送入返回上一次的结果，**不再打服务端**——否则第二次 exchange
返回 `invalid_otc`，与「真过期」无法区分，用户刚登录成功就看到一条假失败。

**通道层（防重复送 UI）**：`replay = 1` 的缓存会把已处理的结果重发给**任何**后来的
订阅者（有第二个订阅页就必现，如登录后再打开绑定页收到陈年 `SignedIn`）。处理完调
`consumeOauthResult()` 清掉。**replay 只兜「投递早于订阅」的时序，不是历史记录。**

两层互不覆盖：otc 去重管不到 `Linked`/`Cancelled` 的重放，consume 拦不住自己执行前的
双送（§11 差异 #7，含时间线）。

### 6.2 取消（分层）

| 通路 | 信号 | 性质 |
|---|---|---|
| Auth Tab | launcher 回调 `onCancel` | 确定，即时 |
| Custom Tab | 管理页 `onResume` 且无 data（关闭动作直接触发 resume） | 确定，即时 |
| 系统浏览器 | 同上，但 resume 要等**用户自行回到 App** | 确定但迟到；语义 =「用户放弃后回到了 App」 |

三条全部经 `oauthResults` 投递 `Cancelled`，消费方零代码——今天消费方手写的
`ON_RESUME` 启发式（约 20 行，还要防「成功被误判成取消」的调度竞态）整体消失：
成功路径的 data 由 `onNewIntent` 先于 `onResume` 就位，竞态被系统的调用顺序结构性
消掉（§11 差异 #6）。

残余窄竞态只有一个：用户授权后抢在回跳送达前手动切回 App，会产生 `Cancelled` →
`SignedIn` 的**序列**，UI 按序处理即自愈（已列入限制 #1）。

### 6.3 进程被回收

```
App 开授权页（管理页 → 浏览器）→ 系统回收 App 进程 → 用户完成授权 → 回跳
  → 中转页冷启动，onCreate 拿到 intent.data → 照常转发
  → 栈里没有管理页实例 → singleTask 新建一个
  → 管理页：有 data，但静态槽里没有在途 AuthClient → 把 URL 停泊进库内静态
  → 起 App 的 launcher intent（getLaunchIntentForPackage）→ finish()
  → App 冷启动 → auth.restore() 顺带把停泊的 URL 排空
  → handleOAuthCallback → exchangeOtc → 落盘
  → authState 变 SignedIn（全局导航自然跳转）
  → oauthResults 发出 SignedIn
```

发起 `signIn()` 时的 UI 早已随进程消失，所以**结果必须能从 `oauthResults` 拿到**——
这就是它存在的理由，也是为什么 `replay = 1`（`restore()` 里的处理可能早于 UI 订阅）。

### 6.4 管理页状态机（data-first，#977 免疫）

`onResume` 状态机，**顺序即优先级**：

1. `intent.data != null` → 投递（冷启动则停泊）→ 决定去向 → `finish()`
2. 未开过浏览器（`intentLaunched` 标志，存 `savedInstanceState`）且带授权参数 →
   置位、按三级链开浏览器
3. 未开过浏览器且无授权参数 → 意外启动，静默 `finish()`
4. 开过浏览器、无 data → 取消，投递 `Cancelled`，`finish()`

data-first 是刻意的：Android 14 按 #977 的行为把管理页**重建而非复用**时，新实例
`savedInstanceState` 与 extras 都是空的，但转发 intent 里有完整回跳 URL——分支 1
照常投递。AppAuth 在同一情形流程断裂、Auth0 静默丢结果；本库免疫的根源是 §4 的
「不持久化 pending 状态」。

已知陷阱（落地时反向验证）：`onNewIntent` 里**必须 `setIntent(intent)`**，否则
`getIntent()` 永远返回启动时的旧 intent、data 恒为 null，每次 Custom Tab 成功回跳都
被判成取消，而单实例直跑的测试还全绿。各条裁决预记的反向验证点汇总见 §11。

### 6.5 与现有并发机制的关系

`handleOAuthCallback` 内部走 `exchangeOtc` → `persist`，和 `refresh` 一样受 `storeMutex`
保护。与在途刷新、并发登出的竞态由已有机制覆盖，不引入新的锁。

## 7. 已知限制（必须写进 README）

1. **系统浏览器兜底通路的取消信号迟到**——要等用户自行回到 App 才能判定；且极端时序
   下（授权后抢在回跳送达前切回）可能出现 `Cancelled` → `SignedIn` 序列，UI 按序处理
   即自愈
2. **服务端 redirect 白名单仍要人工配**，且 debug/release 变体各一条
   （`<scheme>:/loginbase/callback`）。这是安全控制，不能由客户端决定；该填什么用
   `Loginbase.redirectUri(context)` 随手可查
3. **只有 Android**。iOS 转正前，那边仍是 `signInUrl()` + 自己开浏览器
4. `link` 流程的失败原因由服务端 App 定义（`already_linked` 是典型值但不是协议保证），
   库只能原样透传给 `Failed.reason`
5. **自定义 scheme 谁都能声明**——别的 App 装上去声明同一个 scheme 就能截胡回跳。这是
   RFC 8252 明确承认的固有弱点，也是优先走 Auth Tab（不发 Intent）的理由之一；另外 otc
   60 秒单次有效，即便被截也只有一次窗口。发起前自检（§5.3 第 2 层）能就地报出抢注

## 8. 三项真机验证（原 spike 清单，已于 2026-08-15 验收）

> 应决策，spike 未单独先行，改为随 2a 实现一起在**生产环境验收**（验收载体 =
> TrendingAI 实际接入，模拟器 Pixel 9 / Android 16 / Play 镜像，真实 GitHub 账号）。

| # | 要验证什么 | 结果 |
|---|---|---|
| 1 | **管理页冷启动分支的真机行为**：进程被回收后回跳 → 停泊 → 起 launcher | ✅ force-stop 后发回跳：中转页冷启动（新进程）→ 管理页新建、静态槽空 → 停泊 → `getLaunchIntentForPackage` **正确解析到动态图标的 activity-alias**（MainActivityBerry）→ MainActivity 冷启动（+1.66s）→ `restore()` 排空。用户观感就是一次干净的冷启动 |
| 2 | **「不保留活动」验证重建路径**：#977 场景下 data-first 状态机真实走通 | ✅ 开「不保留活动」跑完整真实登录：管理页在浏览器覆盖期间被系统销毁，转发时**重建新实例**（logcat `Displayed +474ms`）→ data-first 投递 → 登录成功落盘。#977 免疫实测成立 |
| 3 | **Auth Tab 的 callback 能否扛住进程死亡**，以及可用性检测 API | 🔶 2b 已落地：检测 API 确定为 `CustomTabsClient.isAuthTabSupported`，launcher 注册在管理页构造期（Auth0 同款）；**激活路径与进程死亡行为待 Chrome 137+ 环境补验**（验收模拟器 Chrome 133）。风险仍被中转页/管理页兜住 |

同场验收通过的其余路径：真实 GitHub 登录成功链（服务端 302 → 授权 → 回跳 →
中转页 → CLEAR_TOP 转交 → 兑换落盘 → UI 就位）；取消（用户停留浏览器后返回 →
管理页 resume 判 `Cancelled` → 登录面板按钮复位，旧实现的「永远转圈」坑位）；
陈旧回跳链接（伪 otc 静默 `Failed`，不崩、不打扰、登录态不动）；发起前自检与
debug 日志打印。

**遗留项进展（2b 期同日更新）**：

- ~~CCT 呈现为完整 Chrome 标签页~~ → **原因定位并修复**：库 manifest 缺 Android 11+
  包可见性的 `<queries>` 声明，`CustomTabsClient.getPackageName` 恒返回 null，三级链
  **静默退化**到系统浏览器（AppAuth/Auth0 的库 manifest 都带这块，此前逐字核对只盯了
  activity 部分）。补上后实测 tier = CUSTOM_TAB、CCT 成功链含 CLEAR_TOP 回位走通。
  教训：静默降级类故障要靠把选择结果打出来暴露——已给 debug 构建加 tier 日志
- ~~系统浏览器兜底补验~~ → **已验**：queries 修复前的形态恰好就是纯 ACTION_VIEW 通路，
  真实登录成功与「返回判取消」均实测通过
- link 绑定流程未验：验收账号已绑定 GitHub，入口不出现；待解绑或用第二账号补验

**国产 ROM 真机补验（小米 13 / HyperOS(Android 16) / 无 Chrome，2b 期同日）**：

- **tier 探测**：设备无 Chrome，但第三方轻量浏览器 Via（`mark.via`）实现了
  CustomTabsService——探测正确命中 `CUSTOM_TAB (provider = mark.via)`。修正差异 #9
  的预设：「无 Chrome 国产机」不必然落系统浏览器，第三方浏览器可能供 CCT
- **MIUI 链式启动管控**：拉起浏览器前系统插一道「启动应用」用户弹窗。对库透明——
  允许则流程照常；拒绝的表现等同用户取消（返回 App 时管理页判 `Cancelled`），语义恰好正确
- **CCT 关闭取消**：Via 的 CCT 框（× + 只读地址栏）点 × → 回 App 收到确定
  `Cancelled`、面板复位——此前模拟器上因 GitHub 已授权自动跳转太快无法验到的形态
- **成功链**：CCT → 302 → GitHub 授权 → 回跳 → 兑换落盘全链走通（需设备侧代理：
  无代理时 GitHub TLS 层被干扰、302 链停在空白页——对照实验证实与库/服务端无关，
  属 GitHub OAuth 对国内用户的既有现实）

原「AGP 允不允许库 manifest 用 `${applicationId}`」随差异 #1 撤回该方案而消失——
placeholder 是 AppAuth/Auth0 在海量设备上验证过的同款机制，无 AGP 未知数（实测
构建通过）。原「`ActivityResultLauncher` 能否在 suspend 里注册」随差异 #3 消失。

## 9. 依赖红线怎么处理

`androidx.browser` 只进可选浏览器模块（§5.2），**核心 artifact 的依赖仍是那三个、
一个不多**。红线仍需修订表述（可选平台模块也是本库的一部分），但修订幅度更小了：
因为红线的理由（「auth 库是供应链攻击的最高价值目标」）对它不成立：

| | 第三方库 | `androidx.browser` |
|---|---|---|
| 维护方 | 任意 | Google / AndroidX，与 Android SDK 同一信任级别 |
| 是否已在消费方 classpath | 未必 | 几乎所有 Android App 都有 |

修订后的表述：**核心 artifact 仍是那三个；可选平台模块只允许该平台的一等公民 API
（AndroidX、Apple 系统框架）**。supabase-kt 的 `Auth` 模块就是这个分法。

（回跳解析用的 ktor `parseQueryString` 不涉红线：`ktor-client-core` 本就是三依赖之一，
`io.ktor.http` 随它在 classpath 上，且解码是纯内部实现、不进公开契约。）

## 10. 分期

| 期 | 内容 | 产出 |
|---|---|---|
| **0** | 第 8 节的三项 spike | 一份结论，决定 1/2 期的实现细节 |
| **1** | `commonMain`：`OAuthOutcome`、`handleOAuthCallback`（含 otc 幂等 + ktor 解析）、`oauthResults` + `consumeOauthResult` | 零新依赖，纯单测（喂 URL 断言结果；幂等与 consume 按预记的反向验证点先破坏再恢复），**先于任何 Android 代码落地** |
| **2a** | **新建 browser 模块**（§5.2）：中转页 + 管理页 + Custom Tab / 系统浏览器回退 + 取消分层 + placeholder/meta-data | 引入 `androidx.browser`（仅此模块），红线同步修订。**这一步即可独立跑通除 Auth Tab 外的全部路径**（§11 差异 #3 的七条推演） |
| **2b** | Auth Tab 优先级（管理页内 `ActivityResultLauncher`） | 纯增强：少一次任务切换、少一个 Intent 暴露面。做不成不影响可用性 |
| **3** | README 接入指南更新 + 限制说明 + 症状→原因映射表 | |

**1 期能独立交付且独立有价值**：即使 2 期不做，接入方自己开浏览器时也已经不用再解析
参数、不用分辨流程了。而它零新依赖、可完整单测——先做它，风险最低。

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
| 参考实现 | GitHub | Auth0.Android 的 `AuthenticationActivity.kt`、`RedirectActivity.kt`、`auth0/AndroidManifest.xml`（master @ 2026-08，已逐字核对） |

**TrendingAI 已经手写了本方案的大部分**：中转页、进程级回调总线（`replay = 1`）、
三态回跳解析。所以 #26 的性质是「把已验证的实现下沉进库并补齐库特有的部分」，
不是从零设计——但也**不是照抄**：它是 App 层实现，若干选择在库层不成立。

## 差异与裁决状态（对照发现八条 + 校准/评审中新发现两条）

| # | 决策点 | 本文原设计 | TrendingAI | AppAuth | 裁决 | 状态 |
|---|---|---|---|---|---|---|
| 1 | scheme 怎么传 | `${applicationId}` | placeholder + `buildConfigField` 双喂 | `${appAuthRedirectScheme}` placeholder | **placeholder + `<meta-data>`** | ✅ 已对齐 |
| 2 | 中转页 launchMode | `singleTask` | `standard` | **无（默认 standard）** | **不写**（standard） | ✅ 已对齐 |
| 3 | 怎么回到 App | `isTaskRoot` 分支 | `REORDER_TO_FRONT` | 转交管理 Activity → `PendingIntent` | **库内管理页 + 中转页转发**（AppAuth/Auth0 拓扑，消费方零接线） | ✅ 已对齐 |
| 4 | redirect 形态 | 带 host | `scheme:/path` 无 host | 不限定 | 无 host（RFC 8252 §7.1 示例形态） | ✅ 已对齐 |
| 5 | 结果通道 | 双通道（返回值 + flow） | 单通道（bus） | 单通道（PendingIntent） | **单通道**，`signIn()` 不挂起 | ✅ 已对齐 |
| 6 | 取消检测 | 「做不到」 | ON_RESUME + `hasPending` | `canceledIntent` | **分层**：Auth Tab/CCT 确定信号，系统浏览器迟到的确定信号 | ✅ 已对齐 |
| 7 | 幂等 / replay | 记住最后 otc | `resetReplayCache()` | — | **两个机制各司其职**（前者防重复打服务端，后者防重复送 UI） | ✅ 已对齐 |
| 8 | URL 解码 | 未提 | 手写 20 行 percent-decoder | — | 用 ktor 的 `parseQueryString()`（既有依赖），畸形输入裁为 `Unrecognized` | ✅ 已对齐 |
| 9 | 浏览器回退链（校准 #3 时新发现） | Auth Tab → 系统浏览器（两极，未论证） | CCT 探测失败落 ACTION_VIEW | CCT 优先 | **Auth Tab → CCT → 系统浏览器** | ✅ 已对齐 |
| 10 | 浏览器环节的分发形态（评审中新发现） | 单 artifact（隐含） | 单 App 无此问题 | 单 artifact + FAQ 教手动 remove | **独立可选 artifact** | ✅ 已对齐 |

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
   `cn.example:/loginbase/callback`；debug 构建（`FLAG_DEBUGGABLE`）首次发起时自动打印

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

## 差异 #9 的完整结论（校准 #3 时新发现，作为 #3 的前置）

### 裁决

**回退链 = Auth Tab → Custom Tab → 系统浏览器。** 修订正文 §3 / §5.2 / 分期 2a 的
「Auth Tab 不可用即系统浏览器」两极表述。

### 为什么

正文从未论证过为什么跳过 CCT 层——盲写期把「外部 user-agent」简化成了两极。而三个
对照全都在跑含 CCT 的链：AppAuth、Auth0 的主通路就是 CCT（Auth Tab 只是它的 auth
特化版，同在 androidx.browser，Chrome 137+ 才有）；TrendingAI 的 `openUrl()` 也是
CCT provider 探测失败才落 ACTION_VIEW。「Auth Tab 不可用」的设备分三类，只有一类
连 CCT 也没有：

| 设备 | CCT 可用？ | 两极链 vs 三级链 |
|---|---|---|
| 旧 Chrome / Firefox / 三星浏览器 | ✅ | 两极链把这批用户降级成整应用切换 |
| 无 Chrome 的国产机（不实现 CustomTabsService） | 多数 ❌ | 两案等价，都落系统浏览器 |
| 新 Chrome（137+） | 走 Auth Tab | 不涉及 |

### 场景

三星浏览器用户绑定 GitHub：三级链下授权页以 CCT 形式盖在 App 上，授权完原地回到
绑定页；两极链下被甩进三星浏览器全屏、来回两次整应用任务切换、最近任务里多一条
浏览器记录。功能等价、观感差一截，而这批用户在海外 Android 里占比不小。

## 差异 #3 的完整结论（已对齐）

### 裁决

**采用 AppAuth / Auth0 同款的双 Activity 拓扑。撤回表格初裁「`getLaunchIntentForPackage`
+ `REORDER_TO_FRONT`」；正文原案的 `isTaskRoot` + launcher intent 逻辑不废弃，收编进
管理页的冷启动分支。**

| 部件 | manifest 姿态 | 职责 |
|---|---|---|
| 中转页（#2 已裁） | `standard`、`exported="true"`、intent-filter、`excludeFromRecents` | 收回跳 intent，带 `CLEAR_TOP or SINGLE_TOP` 转发给管理页，finish。~15 行，无状态 |
| **管理页（新增）** | `singleTask`、`exported="false"`、透明主题 | `signIn()/link()` 启动；按 #9 三级链开浏览器；收结果（转发 intent / ActivityResult / onResume 判取消）；决定去向；投递 |

两家参考实现这两个 Activity 的 manifest 姿态**逐字一致**（Auth0 连 `configChanges`
清单都与 AppAuth 相同），转发 flags 同为 `CLEAR_TOP or SINGLE_TOP` 且只带 `data`。
照抄已被海量设备验证的组合，不自创。

### 初裁为什么被推翻

`REORDER_TO_FRONT` 是 TrendingAI 给「CCT 落在 App 任务内、中转页 finish 只会露出
CCT」打的补丁，且它无害的前提是**单 Activity**（manifest 证实，连 launcher 入口都是
activity-alias）。库要服务任意结构的消费方——场景：多 Activity App 用户在
`[首页, 设置, 绑定页]` 的绑定页发起绑定，REORDER 会把首页 Activity 提到栈顶，用户绑完
被丢回首页、绑定页被埋，恰是 §5.5 明文警告的「把用户的位置弄丢」。管理页拓扑下 CCT
由 CLEAR_TOP 清掉、管理页 finish 原地露出绑定页，无需 REORDER。

### `singleTask` 装回来了？与 #2 不矛盾

#2 反对的是「**接收浏览器外部 intent** 的 Activity 用 singleTask」。管理页不带
intent-filter、`exported="false"`，只收库自己转发的内部 intent——flags 与时机全在库的
掌控内。对外暴露面仍然只有 standard 的哑中转页。

顺带记录评审时的真实疑问「为什么不把两个 Activity 合成一个」：合并后必须给合体
Activity 配 singleTask 才能让浏览器 intent 命中栈内既有实例（standard 下回跳会新建第二个
实例，CLEAR_TOP 按类匹配会命中新实例自己、清不掉 CCT），等于把 singleTask 重新请回
外部 intent 路径，#2 的全部边角回归；且持有流程状态的 Activity 变成 exported 面，任意
App 可向它发伪造 intent。两家都拆成两个，不是巧合。

### #977 免疫：data-first 状态机（§4 的红利兑现）

管理页 `onResume` 状态机，**顺序即优先级**：

1. `intent.data != null` → 投递（冷启动则停泊）→ 决定去向 → finish
2. 未开过浏览器（`intentLaunched` 标志，存 `savedInstanceState`）且带授权参数 → 置位、开浏览器
3. 未开过浏览器且无授权参数 → 意外启动，静默 finish
4. 开过浏览器、无 data → 取消，投递 `Cancelled`，finish

**本条的核心场景**：Android 14 按 #977 的行为把管理页**重建而非复用**——新实例的
`savedInstanceState` 与 extras 都是空的，但转发 intent 里有完整回跳 URL：

| 实现 | 重建实例的处境 | 结果 |
|---|---|---|
| AppAuth | pending request 随旧实例消失，响应配不上 | 流程断裂（#977 原文，open 三年） |
| Auth0 | `intentLaunched=false` + extras 空 → 判「意外启动」 | 静默 finish，**结果丢失、无报错** |
| 本库 | data-first 分支命中，URL 自带全部信息 | 照常投递 ✅ |

免疫不是运气：§4 已裁定「回跳参数自带流程信息，不持久化 pending 状态」，两家会死
恰因它们的响应必须与存储的请求配对。**分支顺序是两个场景的共同依赖**：重复回跳时
新建的管理页同样「无 extras、有 data」，「意外启动」检查若放在前面，在重复场景吞掉的
是无害的重复份，在重建场景吞掉的是**唯一的一份结果**。

### 七条路径推演（均走通）

| 路径 | 关键走向 | 结局 |
|---|---|---|
| Auth Tab 主路径 | launcher 回调直达管理页，中转页不出场 | 原地回发起页；关闭 = 确定的取消信号 |
| CCT 回退 | 中转页转发，CLEAR_TOP 清掉 CCT | 原地回发起页；用户关 CCT → onResume 判取消，同为确定信号 |
| 系统浏览器兜底 | 回跳把 App 任务带回前台，走同一漏斗 | 原地回发起页；用户自行切回 App → 判取消（启发式，优于 §6.2 的「永远挂着」） |
| 进程被回收 | 中转页照常转发 → singleTask 新建管理页 → 静态槽无在途流程 → 停泊 URL → 起 launcher | §6.3 时序不变；`restore()` 排空停泊，结果从 `oauthResults` 送达 |
| Android 14 重建（#977） | data-first 直接投递 | 见上表 ✅ |
| 重复回跳（§6.1） | 首份处理后管理页已 finish → 新建 → otc 幂等命中缓存 | 用户无感 |
| 浏览器历史陈旧链接 | 同上，otc 已失效 → `Failed` / `Unrecognized` | 不崩、不开浏览器、不留怪 Activity |

取消检测因此形成分层（Auth Tab / CCT 确定，纯浏览器启发式）——收益归 #6，对齐时展开。

### 对 §8 spike 清单的修订（正文待重写，先记在此）

- **spike 2 撤销**：「`ActivityResultLauncher` 能否在 suspend 里临时注册」的悬案不存在——
  launcher 注册在**管理页自己的 `onCreate`**（Auth0 实证：`AuthTabIntent.registerActivityResultLauncher(this)`），
  与消费方 Activity、suspend 函数均无关
- **spike 3 改述**：验证对象从「中转页的 isTaskRoot + 起 launcher」变为「管理页冷启动
  分支 + 起 launcher 的真机行为」
- **新增 spike**：Android 14+ 真机开「不保留活动」，验证管理页重建路径（#977 场景）真实走通
- spike 4（Auth Tab 扛不扛进程死亡）不变，验不过仍由停泊机制兜底

### 落地时的反向验证点（会话惯例：先破坏、确认变红、再恢复）

1. 把 data-first 分支挪到 `intentLaunched` 检查之后（即退化成 Auth0 的顺序）→ 模拟
   管理页重建的测试必须变红
2. 把停泊判定从「静态槽里有无在途 AuthClient」改成任务/栈状态判断 → 进程回收冷启动
   的测试必须变红

## 差异 #4 的完整结论（已对齐）

### 裁决

**维持初裁：无 host 单斜杠形态。** 库的默认 redirect 为 `<scheme>:/loginbase/callback`，
撤回正文的 `cn.example://loginbase/callback`（双斜杠，`loginbase` 落在 host 位）。
库 manifest 的 filter 只声明 scheme（#1/#2 草图已如此）；debug/release 靠独立 scheme
隔离（§5.3 已有，与 TrendingAI 注释互证）。

### 为什么

四方证据同向：

- **RFC 8252 §7.1 的示例形态**就是单斜杠：`com.example.app:/oauth2redirect/...`
- **host 位在 private-use scheme 里没有所有权语义**（TrendingAI gradle 注释原文）——
  纯字符串，任何截胡 App 照抄整个 URI 即可，它带来的全部「可以收窄」的安全感都是假的
- **客户端 filter 本来就只认 scheme**：Android `<data>` 规则是「没有 host 时所有 path
  属性都被忽略」（TrendingAI manifest 注释原文），无 host 时 scheme 是唯一过滤维度；
  AppAuth 的 filter 同样只按 scheme（已逐字核对）——参考实现也没给 host 位任何角色。
  真正的白名单校验只发生在服务端
- **服务端零成本**（`github.ts` 的 `redirectAllowed()` 已核对）：结构化校验
  `new URL()` + protocol/host 精确 + path 前缀，无 host 形态解析出空 host，精确匹配
  照常成立；TrendingAI 的 `AUTH_DEEPLINKS` 生产白名单里存的已全是无 host 条目

迁移零负担：TrendingAI 的手写实现（`/auth/callback`）未发布过，接库时直接改用库默认
redirect、服务端白名单同步替换即可，无新旧共存问题。

### 场景

接入方照文档给服务端配白名单，手一抖把库上报的 `cn.example:/loginbase/callback`
抄成双斜杠 `cn.example://loginbase/callback`。服务端结构化校验里前者 host 为空、
后者 host 是 `loginbase`——精确匹配失败，用户点登录，**授权还没开始就 400
`invalid_redirect`**，而开发者盯着两个肉眼几乎相同的字符串看不出差别。

无 host 裁决把「几个斜杠」从「必须记对的细节」变成「只有一种写法」；配合 #1 的
`redirectUri()` 一键可查，这个坑只剩复制粘贴层面的残余概率。#1 的「症状 → 原因」
映射表落地时补一行：**invalid_redirect 且字符串肉眼相同 → 数斜杠**。

## 差异 #5 的完整结论（已对齐）

> 从本条起，结论小节统一按「背景 → 问题 → 方案 → 场景」组织（#1–#4 保持原格式不回改）。

### 背景

- **正文（§5.1/5.2）**：双通道。`suspend fun signIn(...): OAuthOutcome` 挂起到有结果，
  同时 `oauthResults: SharedFlow`（replay=1）广播——且正文自己承认后者是必需的
  （KDoc 原文：进程回收后「结果只能从这里拿」）
- **TrendingAI**：单通道。`OauthCallbackBus`（`MutableSharedFlow(replay = 1,
  extraBufferCapacity = 1)`），发起侧开完浏览器即返回，**没有任何挂起等结果的调用**；
  登录面板、绑定页全部 `collect` 总线收结果，连「面板还开着」的热路径也不例外
  （注释原文：「面板此时通常还开着，故由面板自己收尾最自然」）
- **AppAuth**：单通道。结果只经 PendingIntent 投递，发起调用即返回
- **#3 的管理页拓扑**：所有路径的结果汇进管理页的单一投递漏斗，投递目标天然是一个

### 问题

双通道的实质是「一条必需 + 一条糖」，而糖在两个常见情形下是坏的：

1. **config change 就能打断返回值通道**：挂起的 `signIn()` 活在页面协程作用域里，
   用户在浏览器授权期间屏幕一转（或切深色模式、分屏），Activity 重建、作用域取消、
   挂起点随之取消——返回值永远不来。连进程死亡都不需要
2. **进程回收下它必死**（§6.3 已论证）

于是消费方无论如何都必须正确实现 flow 路径，返回值通道唯一的作用是引诱人只写
`val outcome = signIn(...)` 这条好看的路、漏写 flow 路径——又是「多数时候能跑、
分岔只在边角出现」的陷阱（#2 同款）。同一结果走两条通道还带来 UI 重复反应的可能。

### 方案

1. **单通道，撤回双通道**：消费方结果面只有 `oauthResults: SharedFlow<OAuthOutcome>`
   （replay=1）。观察它本来就在接入指南里（§5.4 已注明不计入接线成本）
2. **`signIn()/link()` 改为非挂起**、fire-and-forget：启动管理页即返回 `Unit`
   （TrendingAI 与 AppAuth 的发起侧同形）
3. **`handleOAuthCallback(url)` 保留 suspend + 返回值**（给直接调用方：自定义流程、
   iOS 占位、测试），但**必同时发一份到 `oauthResults`**——投递点全库只此一处，
   两条通路 + 冷启动 + 管理页漏斗全部汇于此。文档写明：UI 只看 flow，返回值是给
   调用它的那一方的
4. `Cancelled` 同样走这条通道（管理页判取消后投递），不设第二套取消处理
5. TrendingAI 的 `hasPending` / `consume()`（防误判、防陈旧 replay）是通道的运营语义，
   归 #7 裁

### 场景

用户点「GitHub 登录」跳去浏览器，授权期间手机竖屏转横屏，回跳送达：

| 设计 | 返回值通道 | flow 通道 | 消费方为写对要做的事 |
|---|---|---|---|
| 双通道（正文） | 协程随 Activity 重建被取消，**永远不返回** | 正常送达 | 两条都处理，还要防 UI 反应两次 |
| 单通道（裁决） | 不存在 | 正常送达（replay=1 兜住重建后再订阅） | 只写一处 collect |

TrendingAI 的实测佐证：其全部三个消费场景（登录面板热路径、绑定页、进程回收冷启动）
全走总线，没有一处需要挂起返回值——「挂起等结果」在真实 App 里是个没有需求的 API。

## 差异 #6 的完整结论（已对齐）

### 背景

- **正文**：Auth Tab 通路可映射 `Cancelled`；回退通路「做不到」——App 什么都收不到，
  `signIn()` 一直挂着，由调用方协程作用域负责收尸（§6.2、限制 #1）
- **TrendingAI**：App 层启发式约 20 行——`awaitingOauth` 标志（「人跑到浏览器去了」）
  + `ON_RESUME` 兜底复位 + `hasPending` 防竞态。注释记录实测教训：CCT 关闭没有任何
  回调，不兜底则「面板永远转圈、按钮全禁用（实测踩到）」；emit 与收集隔一次协程调度，
  `ON_RESUME` 可能插在中间，不看 `hasPending` 会把成功误判成取消
- **AppAuth / Auth0**：确定信号——管理页 `onResume` 时无响应数据即判取消（AppAuth 的
  `canceledIntent`、Auth0 的状态机分支 4，均已逐字核对）；Auth0 的 Auth Tab 通路另有
  launcher 的 `onCancel` 回调
- **前置已变**：#3 的管理页状态机分支 4 就是取消分支；#5 撤掉挂起 `signIn()` 后，
  正文「由调用方作用域负责」的机制已不存在，取消必须成为结果通道的一等公民

### 问题

1. 正文的「做不到」是错的——前提是「库只有中转页」。管理页压在浏览器页下面，用户
   放弃时它必然 resume，这就是信号，两家参考实现用的正是它
2. TrendingAI 的启发式是在 App 层重新发明管理页的 `onResume`，还要自己扛调度竞态
   （`hasPending`）——这段最易错的代码正是库该下沉的
3. 隐蔽陷阱须显式记录：管理页若漏写 `onNewIntent` 里的 `setIntent(intent)`（Auth0
   专门覆写了这三行），`getIntent()` 永远返回启动时的旧 intent、data 恒为 null——
   **每一次 CCT 成功回跳都被判成取消**，且单实例直跑的测试不触发 `onNewIntent`，极易全绿

### 方案

1. **撤回「做不到」与「作用域负责」**，改为按通路分层的取消检测，全部经
   `oauthResults` 投递 `Cancelled`（#5 单通道）：

| 通路 | 信号 | 性质 |
|---|---|---|
| Auth Tab | launcher 回调 `onCancel` | 确定，即时 |
| CCT | 管理页 `onResume` 且无 data（关闭动作直接触发 resume） | 确定，即时 |
| 系统浏览器 | 同上，但 resume 要等用户自行回到 App | 确定但迟到；语义 =「用户放弃后回到了 App」 |

2. **成功/取消竞态被结构性消掉**：Android 保证 `onNewIntent` 先于 `onResume`，成功
   路径的 data 总是先就位——TrendingAI 用 `hasPending` 手工防的竞态在管理页拓扑里
   不存在。残余窄竞态仅一个：用户授权后抢在回跳送达前手动切回 App，产生
   `Cancelled` → `SignedIn` 序列，UI 按序处理即自愈；写进 README 限制（替换原限制 #1）
3. **消费方代码归零**：`awaitingOauth`/`ON_RESUME`/`hasPending` 约 20 行整体消失，
   §5.4 接线成本表再减一项
4. **落地反向验证点**：删掉管理页 `onNewIntent` 里的 `setIntent(intent)` → 「CCT 成功
   回跳」测试必须变红（红的形态正是「成功被判成取消」），守的就是问题 3 的陷阱

### 场景

用户点「GitHub 登录」，CCT 打开授权页，用户点 × 关掉：

| 实现 | 发生什么 | 用户看到 |
|---|---|---|
| 正文设计 | 回退通路无信号，挂起的 `signIn()` 永不返回 | **面板永远转圈、按钮全禁用**——TrendingAI 实测踩到的正是这个 |
| TrendingAI | App 层 `ON_RESUME` 启发式复位，`hasPending` 防误伤成功路径 | 恢复正常，代价是 20 行易错代码 × 每个消费方 |
| 本库裁决 | CCT 关闭 → 管理页 resume → 分支 4 → 发 `Cancelled` | 消费方在唯一的 `collect` 里收到 `Cancelled`，loading 复位；20 行不用写 |

## 差异 #7 的完整结论（已对齐）

### 背景

这条实际是**两个独立问题**，正文和 TrendingAI 各自被真实问题逼出了其中一半：

- **正文（§6.1）**：otc 去重——进程内记住最近处理过的 otc，重复送入返回缓存结果、
  不打服务端。理由：服务端契约是 otc 单次有效、兑换即销毁，第二次 exchange 返回的
  `invalid_otc` 与「真过期」无法区分
- **TrendingAI**：通道 `consume()`——处理完事件后 `resetReplayCache()` 清 replay 缓存，
  防陈旧事件重放给后来的订阅者。**没有 otc 去重**（重复送达时两次 `exchangeOtc` 照打）
- **AppAuth**：无对应物（PendingIntent 单发即毁，无重放面，也不做 code 交换）
- **前置**：#5 定了 `oauthResults` 是 replay=1 的**广播** SharedFlow（TrendingAI 实证
  需要广播：登录面板与绑定页两个宿主同时收听，不能换成 Channel 的单收者语义）；
  #6 已让 `hasPending` 的用途（取消启发式防竞态）消失

### 问题

两个问题防的是不同方向的重复，单独哪一半都不完整：

**问题一：同一回跳被送入两次（重复打向服务端）。** 送达次数库控制不了——用户点浏览器
历史里的回跳链接、某些 ROM 重放 intent、回退判定失误致双送（§6.1 已列）。无去重时：

```
T0    exchangeOtc("abc") 成功 → 会话落盘 → UI「登录成功」
T+10s 同一 URL 再送达 → exchangeOtc("abc") 再打 → 服务端查无此 otc
      → invalid_otc → 只能发 Failed → UI 紧跟一条「登录失败」
```

用户刚成功就看到失败，会话其实好好的；开发者收到「登录时好时坏」的反馈且永远
复现不了。

**问题二：已处理的结果被重放给后来的订阅者（重复送向 UI）。** replay=1 的本意是兜
冷启动时序（结果先于订阅产生），副作用是缓存里一直躺着最后的结果：

```
T0     登录成功，面板处理完 SignedIn、关闭 —— replay 里躺着这条 SignedIn
T+5min 用户打开绑定页，它也 collect oauthResults（等 Linked）
       → 订阅瞬间收到五分钟前的 SignedIn → 弹莫名的「登录成功」/ 误触发导航
```

**这不是边角**：只要存在第二个订阅点就必然发生。TrendingAI 正是踩了才写 `consume()`。
otc 去重管不到 `Linked`/`Cancelled` 的重放，consume 也拦不住自己执行前的双送——
两半互不覆盖。

### 方案

1. **两个都要，各司其职**（维持初裁）：
   - **otc 幂等**在 `handleOAuthCallback` 内部：同一 otc 只 exchange 一次，重复返回
     缓存结果。护网络层，覆盖 #3 推演的「重复回跳」「陈旧链接」路径
   - **通道 consume** 在 `oauthResults` 层：处理完清 replay，防陈旧重放。护 UI 层。
     语义裁死：**replay 只为兜「投递早于订阅」的时序，不是历史记录**；API 形态落地定，
     可探索库内自动清，但消费方显式 consume（TrendingAI 现状）是保底形态
2. **`hasPending` 不下沉**：其用途已随 #6 的启发式消失
3. **收益比（本条在 #26 中最高一档）**：总代价 20 行内纯 commonMain 逻辑 + 2 条 JVM
   单测、零依赖；总收益是消掉两类**用户可见的假状态**，其中问题二在既定通道形态下
   是结构性必现
4. **落地反向验证点**（两条都是时序性质，按会话惯例先破坏再恢复）：
   - 移除 otc 去重 → 「同一 otc 双送」测试必须变红（红的形态：第二次打了服务端）
   - 处理后不清 replay → 「新订阅者不重收已处理结果」测试必须变红

### 场景

见问题节的两条时间线（本条的问题本身就是场景驱动的）。合并后的对照：

| 情形 | 只有去重（正文） | 只有 consume（TrendingAI） | 两个都有（裁决） |
|---|---|---|---|
| 浏览器历史重放同一 otc | 静默吞掉 ✅ | 双打服务端，成功后弹假失败 ❌ | 静默吞掉 ✅ |
| 绑定页订阅到陈旧 SignedIn | 照常重放 ❌ | 通道已清，安静 ✅ | 安静 ✅ |

## 差异 #8 的完整结论（已对齐）

### 背景

- **正文**：未提。`handleOAuthCallback` 要解析 query，解码环节是空白
- **TrendingAI**：手写 ~20 行 percent-decoder（逐字节扫描、`%XX`、`+`→空格、UTF-8
  字节积累后 `decodeToString`），query 切分用 `substringAfter('?')` + `split('&')`
- **ktor（既有依赖）**：`ktor-client-core` 3.5.1 是 commonMain 三依赖之一，
  `io.ktor.http` 随之进 classpath；`parseQueryString()` 一步完成切分 + 解码，KMP
  全平台、带官方测试
- **红线关系**：解码是纯内部实现，不把 ktor 类型写进公开契约——与
  `libs.versions.toml` 里拒绝 `ktor-client-auth` 的红线注释不冲突

### 问题

1. 手写字节级解码是无谓的表面积：classpath 上已有成熟实现，重写违反库自己的
   不重复发明纪律
2. 手写版有真实潜伏缺陷（已逐行核对）：else 分支**逐 Char** 做
   `toString().encodeToByteArray()`——非 BMP 字符（emoji 等）是两个 surrogate Char，
   分开编码各自变成 U+FFFD——**未编码的非 BMP 字符被解成乱码**，且没有测试会抓到
3. 畸形输入行为要显式裁决：中转页 exported，任何 App 都能塞任意 URL。手写版对非法
   `%` 序列宽容放行（当字面量），ktor 抛异常——库必须保证 `handleOAuthCallback`
   对任何输入不崩

### 方案

1. **维持初裁：用 ktor，不手写**。query 提取沿用 `substringAfter('?')`——刻意不用
   ktor 的 `Url()` 整串解析，它对 `scheme:/path` 无 host 自定义形态（#4）的行为未验证，
   不引入这个未知数；解码用 `parseQueryString()`
2. **畸形输入裁为 `Unrecognized`**：ktor 解码抛异常时 `runCatching` 收编为
   `Unrecognized(url)`——比手写版的静默放行诚实（`Unrecognized` 的设计意图就是
   「报给开发者的配置/输入错误」，§5.1）
3. 配畸形输入测试三类（`%zz`、截断的 `%2`、非 BMP 字符）。常规单测，非时序性质，
   无反向验证义务

### 场景

限制 #4 已写明 `Failed.reason` 由服务端 App 自定义、非协议保证。某服务端把用户可读的
失败说明塞进 error 参数（含中文或 emoji，percent-encoded）：ktor 路径正确解出原文；
手写路径把非 BMP 字符逐 surrogate 编码，UI 显示 `??` 乱码，且没人会想到测 emoji。
反向的恶意输入：别的 App 向中转页塞 `?error=%zz`——手写版静默当字面量放行（问题被
掩盖），裁决版返回 `Unrecognized`，开发者在结果通道里直接看到异常输入。

## 差异 #10 的完整结论（评审中新发现，已对齐）

### 背景

差异 #1 裁定 placeholder「忘配即构建失败」时只论证了**使用者**视角。正文重写时发现
被漏掉的另一半：本库的社交登录是**可选功能**（核心是邮箱验证码），而中转页的
intent-filter + placeholder 会合并进**所有**消费方——不用社交登录的消费方也会因
placeholder 缺失构建失败。

参考实现的实证（Auth0 官方 FAQ 第 3 条，已核对原文）：Auth0 与本库处境相同（web
认证可选、placeholder 在库 manifest），他们的单 artifact 形态迫使官方写 FAQ 教
不用 web 认证的消费方**手动在 manifest 里 `tools:node="remove"` 两个 Activity**——
一个「可选功能」需要不使用者动手排雷，这本身就是形态错误的症状。

### 问题

单 artifact 把浏览器环节强加给所有消费方，不用社交登录的人要么撞上与自己毫无关系的
构建报错（`requires a placeholder substitution`），要么去学 FAQ 手动移除库的内部
Activity。「一行占位值」的文档逃生舱可以缓解，但 Auth0 的 FAQ 证明这条路的终点就是
FAQ 本身。

### 方案

1. **浏览器环节拆独立可选 artifact**（暂名 `loginbase-kt-browser`，Android-only，
   同仓同版本发布）：两个 Activity、manifest 声明 + placeholder + meta-data、三级
   回退链、`signIn()/link()` 扩展、`androidx.browser` 依赖全在其中。核心 artifact
   零变化，不用社交登录的消费方零感知
2. **依赖方向约束**：停泊槽住核心（browser 模块写入、核心 `restore()` 排空），
   保持 browser → core 单向
3. **红线收益**：核心 artifact 的依赖仍是那三个、一个不多；§9 的红线修订幅度缩小为
   「可选平台模块允许平台一等公民 API」
4. **模块化原则**（一并裁定）：按**机制**分模块，不按 provider 分。客户端是服务端流、
   provider 无关，走浏览器流的所有 provider 共享同一套零增量代码（接入方只接 GitHub
   不会「带进」任何 Google 的东西——库里本就不存在 per-provider 代码）；将来若引入
   需要原生 SDK 的通路（如 Google Credential Manager），各自独立成可选模块
5. 消费方成本变化：用社交登录者 +1 行依赖声明；不用者从「构建失败/学 FAQ」变为零

### 场景

只用邮箱验证码的消费方引入核心库：

| 形态 | 他的遭遇 |
|---|---|
| 单 artifact（AppAuth/Auth0 现状） | 构建失败，报错指向一个他从没听说过的 placeholder；搜索半天找到 FAQ，往自己的 manifest 里抄两段 `tools:node="remove"` |
| 独立可选 artifact（裁决） | 什么都不发生——他根本不知道浏览器模块存在 |

反向核对拆分的代价：用社交登录的消费方多写一行依赖声明；库多一个发布单元（同仓
同版本，CI 一起发）。两边都可接受。
