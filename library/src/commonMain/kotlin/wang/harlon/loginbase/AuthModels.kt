package wang.harlon.loginbase

import kotlinx.serialization.json.JsonElement

/**
 * 登录态。**只有两态**——「登录中」是 UI 状态、归 App（登录流程由 App 驱动：
 * 输邮箱、等验证码、开浏览器授权，库无从知晓用户走到哪一步）。
 */
public sealed interface AuthState {
    /** 尚未从存储恢复（[AuthClient.restore] 之前的初始态） */
    public data object Unknown : AuthState

    public data object SignedOut : AuthState

    /**
     * 有令牌在手。**不带 userId**——库不解析 JWT（见 [AuthClient] 关于时钟偏差的说明），
     * 用户档案由 App 从自己的 `/me` 类端点取。
     */
    public data object SignedIn : AuthState
}

/**
 * 一次成功认证的结果。`isNewUser` 与 `user` 由服务端 App 的 `onVerified` 钩子返回值
 * **原样透传**，形状归 App 所有——故 [user] 是未解析的 [JsonElement]，由消费方按
 * 自己的模型反序列化；协议只保证 [tokens] 两个字段。
 */
public data class AuthSession(
    val tokens: TokenPair,
    val isNewUser: Boolean? = null,
    val user: JsonElement? = null,
)

/** `POST /code/send` 成功结果 */
public data class SendCodeResult(
    /** 服务端给的重发冷却秒数，UI 倒计时用这个值，别写死 */
    val cooldownSeconds: Int,
)

/**
 * 刷新结果。三种失败刻意分开，因为**处置方式不同**：
 * 只有 [SessionEnded] 才可以清会话。
 */
public sealed interface RefreshOutcome {
    public data class Success(val tokens: TokenPair) : RefreshOutcome

    /**
     * 服务端明确判定会话已死（401 + [reason]）——**本地会话已被清除**，引导重新登录。
     * 重试没有意义：refresh token 在服务端已不存在（吊销/过期/重用检测撤销）。
     */
    public data class SessionEnded(val reason: RefreshFailure) : RefreshOutcome

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
    public data class Failed(val cause: LoginbaseException) : RefreshOutcome

    /** 本地压根没有令牌 */
    public data object NoSession : RefreshOutcome
}

// 异常类型见 [LoginbaseException]——本库抛出的一切都挂在那个 sealed 根下。
