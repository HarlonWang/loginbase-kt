package wang.harlon.loginbase

import kotlinx.serialization.json.JsonElement

/** 登录态。「登录中」不在这里——那是 UI 状态、归 App。各态的 UI 处置见成员文档。 */
sealed interface AuthState {
    /** 尚未从存储恢复（[AuthClient.restore] 之前的初始态） */
    data object Unknown : AuthState

    /** 有令牌在手。不带 userId——库不解析 JWT，用户档案由 App 从自己的端点取。 */
    data object SignedIn : AuthState

    /**
     * 令牌还在但最近一次刷新没打通——**不是登出，别踢到登录页**（多半只是弱网）；
     * 下次刷新成功自动回 [SignedIn]。
     */
    data class RefreshFailed(val cause: LoginbaseException) : AuthState

    /** 没有可用会话；UI 文案完全取决于 [reason]。 */
    data class SignedOut(val reason: SignOutReason) : AuthState
}

/** 登出成因。分开是因为 UI 文案不同：只有 [SessionEnded] 该提示「登录已失效」。 */
sealed interface SignOutReason {
    /** 本地压根没有令牌（冷启动最常见）。不要提示任何东西。 */
    data object NoSession : SignOutReason

    /** 用户主动登出。不必提示。 */
    data object UserInitiated : SignOutReason

    /** 服务端明确判定会话已死，被动登出；[reason] 可细化文案。 */
    data class SessionEnded(val reason: RefreshFailure) : SignOutReason
}

/**
 * 一次成功认证的结果。协议只保证 [tokens]；`isNewUser` 与 `user` 由服务端 App 原样
 * 透传，形状归 App 所有，故 [user] 是未解析的 [JsonElement]。
 */
data class AuthSession(
    val tokens: TokenPair,
    val isNewUser: Boolean? = null,
    val user: JsonElement? = null,
)

data class SendCodeResult(
    /** 重发冷却秒数，UI 倒计时用这个值、别写死 */
    val cooldownSeconds: Int,
)

/** 刷新结果。三种失败刻意分开：处置方式不同，只有 [SessionEnded] 才可以清会话。 */
sealed interface RefreshOutcome {
    data class Success(val tokens: TokenPair) : RefreshOutcome

    /** 会话已死，**本地会话已被清除**，引导重新登录；重试无意义。 */
    data class SessionEnded(val reason: RefreshFailure) : RefreshOutcome

    /**
     * 网络失败 / 超时 / 5xx / 响应畸形 / 没存住——**会话未被清除**，可以重试。
     * [cause] 收窄成 [LoginbaseException]：调用方能按失败类别分流，又不把 ktor 写进契约。
     */
    data class Failed(val cause: LoginbaseException) : RefreshOutcome

    /** 本地压根没有令牌 */
    data object NoSession : RefreshOutcome
}

