package wang.harlon.loginbase

import kotlinx.serialization.json.JsonElement

/**
 * 登录态。「登录中」不在这里——那是 UI 状态、归 App。每一态对应一个不同的 UI 处置：
 *
 * | 态 | UI 该做什么 |
 * |---|---|
 * | [Unknown] | 等 [AuthClient.restore]，别急着跳转 |
 * | [SignedIn] | 正常用 |
 * | [RefreshFailed] | **别踢到登录页**——会话还在，多半只是弱网 |
 * | [SignedOut] | 去登录页；是否提示看 [SignOutReason] |
 */
sealed interface AuthState {
    /** 尚未从存储恢复（[AuthClient.restore] 之前的初始态） */
    data object Unknown : AuthState

    /** 有令牌在手。不带 userId——库不解析 JWT，用户档案由 App 从自己的端点取。 */
    data object SignedIn : AuthState

    /**
     * 令牌还在，但最近一次刷新没打通——会话没被清，**当登出处理会把弱网用户踢下线**。
     * 单独成态是让调用方能做「显示离线角标、暂缓后台同步」这类决定；
     * 下一次刷新成功自动回到 [SignedIn]。
     */
    data class RefreshFailed(val cause: LoginbaseException) : AuthState

    /**
     * 没有可用会话。[reason] 说明**为什么**——UI 的文案完全取决于它，见 [SignOutReason]。
     */
    data class SignedOut(val reason: SignOutReason) : AuthState
}

/**
 * 登出的成因。分开是因为 UI 文案不同：自己点的登出弹「登录已失效」是骚扰，
 * 被服务端撤销却一声不吭又会被当成 App 的 bug。
 */
sealed interface SignOutReason {
    /** 本地压根没有令牌（冷启动最常见）。不要提示任何东西。 */
    data object NoSession : SignOutReason

    /** 用户主动登出（[AuthClient.signOut] / [AuthClient.signOutAll]）。同样不必提示。 */
    data object UserInitiated : SignOutReason

    /**
     * 服务端明确判定会话已死，被动登出——**只有这一种才该提示**「登录已失效」。
     * [reason] 可细化文案。
     */
    data class SessionEnded(val reason: RefreshFailure) : SignOutReason
}

/**
 * 一次成功认证的结果。`isNewUser` 与 `user` 由服务端 App 的 `onVerified` 钩子返回值
 * **原样透传**，形状归 App 所有——故 [user] 是未解析的 [JsonElement]，由消费方按
 * 自己的模型反序列化；协议只保证 [tokens] 两个字段。
 */
data class AuthSession(
    val tokens: TokenPair,
    val isNewUser: Boolean? = null,
    val user: JsonElement? = null,
)

/** `POST /code/send` 成功结果 */
data class SendCodeResult(
    /** 服务端给的重发冷却秒数，UI 倒计时用这个值，别写死 */
    val cooldownSeconds: Int,
)

/** 刷新结果。三种失败刻意分开：处置方式不同，只有 [SessionEnded] 才可以清会话。 */
sealed interface RefreshOutcome {
    data class Success(val tokens: TokenPair) : RefreshOutcome

    /**
     * 服务端明确判定会话已死（401 + [reason]）——**本地会话已被清除**，引导重新登录。
     * 重试没有意义：refresh token 在服务端已不存在（吊销/过期/重用检测撤销）。
     */
    data class SessionEnded(val reason: RefreshFailure) : RefreshOutcome

    /**
     * 网络失败 / 超时 / 5xx / 响应畸形 / 没存住——**会话未被清除**，可以重试。
     * [cause] 收窄成 [LoginbaseException]：调用方能按失败类别分流，又不把 ktor 写进契约。
     */
    data class Failed(val cause: LoginbaseException) : RefreshOutcome

    /** 本地压根没有令牌 */
    data object NoSession : RefreshOutcome
}

