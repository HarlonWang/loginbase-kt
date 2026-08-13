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

## 状态

骨架阶段。已就位：gradle 工程（vanniktech maven-publish、android + iosArm64 + iosSimulatorArm64）、CI 两条 workflow、协议错误码与 `PROTOCOL_VERSION` 及其契约测试。

待实现（服务端仓 `docs/plan.md` 第 4 步任务 2~3）：

- `AuthClient`——send / verify / refresh / signOut / oauth exchange / link 的 Ktor 封装
- `TokenStore` 接口 + multiplatform-settings 默认实现
- `AuthState` flow
- **单飞 refresh**——服务端救活护栏（1h/3 次）按客户端有此纪律设定，并发刷新会消耗配额
- 竞态经验逐条固化：token 获取互斥串行化、丢回执重试、时钟偏差归因、`invalid_refresh_token` 判定与登出策略

## 设计红线

依赖最小集：ktor + kotlinx-serialization + multiplatform-settings。加任何新依赖前先停下来问一遍值不值——auth 库是供应链攻击的最高价值目标。**本库不含 UI**（登录界面归各 App 实现）。

## 开发

```bash
./gradlew :library:testAndroidHostTest              # CI 跑的（ubuntu 编不了 iOS）
./gradlew :library:compileKotlinIosSimulatorArm64   # iOS 侧编译需 macOS
```

发布：打裸版本号 tag（如 `0.1.0`）触发 CI 在 macos runner 上 `publishAndReleaseToMavenCentral`。

## License

Apache 2.0
