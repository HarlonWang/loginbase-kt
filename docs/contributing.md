# 开发与发布

## 构建与测试

```bash
./gradlew :library:testAndroidHostTest :library-browser:testAndroidHostTest   # CI 跑的（ubuntu 编不了 iOS）
./gradlew :library:compileKotlinIosSimulatorArm64                             # iOS 侧编译需 macOS
```

**改动 `commonMain` 的形状后请本地跑一次 iOS 编译**（改 `expect` 签名、改 `TokenStore`
之类接口时，`iosMain` 的实现要跟着改）。CI 不做这件事：ubuntu runner 编不了 iOS，而为一个
占位 target 在每个 PR 上起 macOS runner 不划算。漏了也不会出坏产物——打 tag 时
`publish.yml` 跑在 macOS 上，编译不过就失败在构建阶段、早于任何上传，改完删 tag 重打即可。

## 未用 import 清理

别信文本级 lint（ktlint 该规则漏报、纯文本扫描误报，均实证过）。用
`scripts/unused_imports.py` 的编译器裁判循环：`remove` 宽松删候选 → 编译 →
`restore <日志>` 按 unresolved 恢复误删 → 循环至绿。日常靠 IDE 提交前 Optimize Imports 兜底。

## 发布

打裸版本号 tag（如 `0.1.0`）触发 CI 在 macos runner 上 `publishAndReleaseToMavenCentral`。
核心与 `loginbase-kt-browser` 同版本发布。

## 协议变更纪律

协议的唯一权威是服务端仓的
[`docs/protocol.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md)，
本仓不留副本。两仓独立版本线，版本号不追求相等——客户端靠 `PROTOCOL_VERSION` 常量声明
实现的是哪一版协议。

分仓版的纪律：**服务端实现 + `protocol.md` 同 commit**，同时在本仓开跟进 issue，
客户端版本落地前不关。分仓决策与理由见服务端仓
[`docs/design.md`](https://github.com/HarlonWang/loginbase/blob/main/docs/design.md)
的「两个仓库」节。
