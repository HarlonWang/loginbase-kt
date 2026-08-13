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
     * 网络失败 / 超时 / 5xx——**会话未被清除**，可以重试。
     *
     * 这个区分是 Logto 时代事故的直接教训：把暂时性失败当成会话失效来清，
     * 会把弱网（漫游、地铁）用户的好会话踢下线。
     */
    public data class Failed(val cause: Throwable) : RefreshOutcome

    /** 本地压根没有令牌 */
    public data object NoSession : RefreshOutcome
}

/**
 * 需要已登录的操作（如绑定第二身份）在无会话时抛出。
 *
 * 独立类型而非 `IllegalStateException`：未登录时点「绑定 GitHub」不一定是编程错误
 * ——UI 状态与实际会话可能短暂不同步——调用方需要能单独 catch 它并引导登录。
 * 也不用返回 null 表达：那会和「网络失败」混在一起无法区分。
 */
public class NotAuthenticatedException(
    message: String = "operation requires an authenticated session",
) : Exception(message)

/**
 * 服务端按协议返回的错误（`{"error": ...}`）。
 * 网络层异常不走这里——那是 ktor 自己的异常，调用方据此区分「服务端说不行」与「没连上」。
 */
public class AuthApiException(
    public val status: Int,
    public val error: AuthError,
    /** `too_many_requests` 才有：还要等多少秒 */
    public val retryAfterSeconds: Int? = null,
    /** `invalid_refresh_token` 才有 */
    public val refreshFailure: RefreshFailure? = null,
    /** 服务端原始 error 串，便于排查未知码 */
    public val rawError: String = error.wire,
) : Exception("loginbase error: $rawError (HTTP $status)")
