# 排错

> 面向**接入方**。README 讲怎么接，这里讲接不上时去哪儿看。

## 社交登录：redirect 要在三处一致

写错的地方和报错的地方对不上，所以先对照这张表定位是哪一处：

| 出现在哪 | 谁负责 | 写错的症状 |
|---|---|---|
| 服务端 redirect 白名单 | 服务端 App 配置 | 授权还没开始就被拒（`invalid_redirect`） |
| App manifest（经 placeholder） | Android 构建 | 回跳没人接，用户授权完卡在打不开的页面 |
| 运行时拼 redirect（经 meta-data 读回） | 库 | 与 manifest 物理同源，**不会单独错** |

该填给服务端什么，`Loginbase.redirectUri(context)` 一行可查（形如
`cn.example:/loginbase/callback`，单斜杠无 host）；debug 构建首次发起时也会自动打进
日志（tag `loginbase`）。

## 症状 → 原因

| 症状 | 原因 |
|---|---|
| 构建失败 `requires a placeholder substitution` | 没配 `loginbaseRedirectScheme`，或当前变体没配。不用社交登录就不要引 `loginbase-kt-browser` |
| 浏览器停在 `invalid_redirect`，App 无任何反应 | 服务端白名单缺这条 redirect；两边字符串肉眼相同时**数斜杠**——`scheme:/path` 单斜杠才对，`scheme://path` 会把 path 段解析成 host，精确匹配直接失败 |
| 发起即抛「没有任何 Activity 认领」 | scheme 写错，或当前构建变体没配 placeholder |
| 发起即抛「scheme 被其他应用抢注」 | 别的 App 声明了同一 scheme——这同时是安全信号，换独占的自有域名反写 |
| **其他模块**的单测任务构建失败、报同一 placeholder 错误 | 直接依赖 `loginbase-kt-browser` 的 Gradle 模块，其 test manifest 合并同样需要该占位符：经典 library 模块在 `defaultConfig` 给一行任意值；KMP android 模块的 DSL 没有 `manifestPlaceholders`，在 hostTest 源集放一个把本库三个节点 `tools:node="remove"` 掉的 manifest。根治办法是只由 App 模块依赖本模块，共享逻辑层用注入点解耦（`var launcher: ((AuthClient, Mode) -> Boolean)?`，App 启动时注入） |

## 社交登录的已知限制

1. **系统浏览器兜底通路的取消信号迟到**：要等用户自己回到 App 才能判定；极端时序下
   可能先收到 `Cancelled` 再收到 `SignedIn`，按序处理即自愈
2. **服务端白名单要人工配**，debug / release 变体各一条——这是安全控制，不能由客户端决定
3. **自定义 scheme 谁都能声明**（RFC 8252 承认的固有弱点）：发起前自检会就地报出抢注，
   且 otc 60 秒单次有效，即便被截也只有一次兑换窗口
4. `Failed.reason` 由服务端 App 定义（`already_linked` 是典型值），不是协议保证
5. **只有 Android**。iOS 转正前仍是 `signInUrl()` + 自己开浏览器，见
   [`design.md`](design.md) 第 7 节

设计全貌（双 Activity 拓扑、AppAuth #977 免疫、与 AppAuth / Auth0 的逐条对照）见
[`oauth-browser-design.md`](oauth-browser-design.md)。
