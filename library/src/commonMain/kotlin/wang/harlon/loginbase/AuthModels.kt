package wang.harlon.loginbase

import kotlinx.serialization.json.JsonElement

/**
 * 登录态。
 *
 * **「登录中」不在这里**——那是 UI 状态、归 App：登录流程由 App 驱动（输邮箱、等验证码、
 * 开浏览器授权），库无从知晓用户走到哪一步。
 *
 * 这里的每一态都对应一个**不同的 UI 处置**，多一态就是多一种要想清楚的情况：
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

    /**
     * 有令牌在手。**不带 userId**——库不解析 JWT（见 [AuthClient] 关于时钟偏差的说明），
     * 用户档案由 App 从自己的 `/me` 类端点取。
     */
    data object SignedIn : AuthState

    /**
     * 令牌还在，但最近一次刷新没打通（网络失败 / 5xx / 响应畸形 / 没存住）。
     *
     * **和 [SignedOut] 差得很远**：会话没有被清，服务端也没说它死了，多半只是弱网。
     * 把这种情况当登出处理，就是把漫游、地铁里的用户踢下线——Logto 时代的原事故。
     * 下一次刷新成功会自动回到 [SignedIn]。
     *
     * 之所以要单独成一态而不是留在 [SignedIn]：调用方光看 [SignedIn] 不知道
     * 「下一个业务请求很可能 401」，做不了「显示离线角标」「暂缓后台同步」这类决定。
     * supabase-kt 早期只有 `NetworkError`，后来专门重设计成 `SessionStatus.RefreshFailure`
     * 也是这个原因。
     */
    data class RefreshFailed(val cause: LoginbaseException) : AuthState

    /**
     * 没有可用会话。[reason] 说明**为什么**——UI 的文案完全取决于它，见 [SignOutReason]。
     */
    data class SignedOut(val reason: SignOutReason) : AuthState
}

/**
 * 登出的成因。
 *
 * 分开是因为**UI 文案不同**：用户自己点的登出弹「登录已失效」是骚扰，
 * 而被服务端撤销了却一声不吭地跳回登录页，用户会以为是 App 出了 bug。
 * 光看「已登出」这一个信号，这两件事分不开。
 */
sealed interface SignOutReason {
    /**
     * 本地压根没有令牌——冷启动时最常见的情形（从没登录过，或上次已登出）。
     * **不要提示任何东西**。
     */
    data object NoSession : SignOutReason

    /** 用户主动登出（[AuthClient.signOut] / [AuthClient.signOutAll]）。同样不必提示。 */
    data object UserInitiated : SignOutReason

    /**
     * 服务端明确判定会话已死，被动登出——**这一种才该提示**「登录已失效，请重新登录」。
     * [reason] 可用来细化文案（如 [RefreshFailure.SESSION_REVOKED] 对应「账号在别处登录」）。
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

/**
 * 刷新结果。三种失败刻意分开，因为**处置方式不同**：
 * 只有 [SessionEnded] 才可以清会话。
 */
sealed interface RefreshOutcome {
    data class Success(val tokens: TokenPair) : RefreshOutcome

    /**
     * 服务端明确判定会话已死（401 + [reason]）——**本地会话已被清除**，引导重新登录。
     * 重试没有意义：refresh token 在服务端已不存在（吊销/过期/重用检测撤销）。
     */
    data class SessionEnded(val reason: RefreshFailure) : RefreshOutcome

    /**
     * 网络失败 / 超时 / 5xx / 响应畸形 / 没存住——**会话未被清除**，可以重试。
     *
     * 这个区分是 Logto 时代事故的直接教训：把暂时性失败当成会话失效来清，
     * 会把弱网（漫游、地铁）用户的好会话踢下线。
     *
     * [cause] 收窄成 [LoginbaseException] 而不是 `Throwable`：调用方想按失败类别
     * 分流（网络失败退避重试、[LoginbaseException.MalformedResponse] 上报开发者）时，
     * 一个 `Throwable` 什么也告诉不了他，而裸的 ktor 异常又把实现细节写进了契约。
     */
    data class Failed(val cause: LoginbaseException) : RefreshOutcome

    /** 本地压根没有令牌 */
    data object NoSession : RefreshOutcome
}

// 异常类型见 [LoginbaseException]——本库抛出的一切都挂在那个 sealed 根下。
