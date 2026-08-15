package wang.harlon.loginbase

import io.ktor.http.parseQueryString
import kotlin.concurrent.Volatile

/**
 * 一次社交登录/绑定的结果，一律从 [AuthClient.oauthResults] 送达（消费方唯一该看的
 * 通道）。sealed：新增一种结果要让调用方重新想一遍怎么处置。
 */
sealed interface OAuthOutcome {
    /** 登录成功，会话已建立并落盘（[AuthClient.authState] 同时变为 SignedIn） */
    data class SignedIn(val session: AuthSession) : OAuthOutcome

    /** 绑定成功。不产生新会话，authState 不变 */
    data class Linked(val provider: OAuthProvider) : OAuthOutcome

    /**
     * 流程失败。[reason] 尽量透传原始错误码（回跳 `error` 参数或兑换失败的服务端码，
     * 值由服务端 App 定义、非协议保证）；本地失败用固定串 `network` / `storage` /
     * `malformed_response`。
     */
    data class Failed(val reason: String) : OAuthOutcome

    /** 用户主动放弃（关掉授权页）。由浏览器模块判定投递，不从 [AuthClient.handleOAuthCallback] 产生。 */
    data object Cancelled : OAuthOutcome

    /**
     * 回跳 URL 不是本库认得的形状（含畸形 percent 编码）——接入配置错误或外部程序
     * 塞了异常输入，该报给开发者。
     */
    data class Unrecognized(val url: String) : OAuthOutcome
}

/** 回跳 URL 的解析结果——[OAuthOutcome] 去掉需要网络往返的部分，单独成类型以便独立测试。 */
internal sealed interface OAuthCallbackParams {
    data class Login(val otc: String) : OAuthCallbackParams
    data class Linked(val provider: String) : OAuthCallbackParams
    data class Failed(val reason: String) : OAuthCallbackParams
    data object Unrecognized : OAuthCallbackParams

    companion object {
        /**
         * 按 `otc` > `linked` > `error` 识别（同时出现属异常输入，取语义最强的）。
         * 解码走 ktor 的 [parseQueryString]；刻意不用 `Url()` 整串解析（对无 host
         * 自定义 scheme 的行为未验证）。任何解析失败收编为 [Unrecognized]、**不抛**——
         * 输入可能来自任意外部程序。
         */
        fun parse(url: String): OAuthCallbackParams {
            val query = url.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return Unrecognized
            val params = try {
                parseQueryString(query)
            } catch (_: Exception) {
                return Unrecognized
            }
            params["otc"]?.takeIf { it.isNotEmpty() }?.let { return Login(it) }
            params["linked"]?.takeIf { it.isNotBlank() }?.let { return Linked(it) }
            params["error"]?.takeIf { it.isNotEmpty() }?.let { return Failed(it) }
            return Unrecognized
        }
    }
}

/** [OAuthOutcome.Failed.reason] 的本地失败映射：能透传服务端错误码就透传。 */
@LoginbaseInternalApi
fun LoginbaseException.oauthFailureReason(): String = when (this) {
    is LoginbaseException.Api -> rawError
    is LoginbaseException.Network -> "network"
    is LoginbaseException.Storage -> "storage"
    is LoginbaseException.MalformedResponse -> "malformed_response"
    is LoginbaseException.NotAuthenticated -> "not_authenticated"
}

/**
 * 进程被回收后的回跳**停泊槽**：浏览器模块的管理页写入，[AuthClient.restore] 排空
 * （中转页必先于 App 启动，时序天然成立）。槽住核心以保持 browser → core 单向依赖；
 * 单槽后到覆盖先到——同一时刻至多一个在途流程。
 */
internal object OAuthCallbackParking {
    @Volatile
    private var parked: String? = null

    fun park(url: String) {
        parked = url
    }

    /** 取出并清空。写读各在一方（管理页写、restore 读），[Volatile] 保证可见性。 */
    fun take(): String? = parked.also { parked = null }
}

/** 配套模块的跨 artifact 挂点（见 [LoginbaseInternalApi]）。消费方代码不该引用它。 */
@LoginbaseInternalApi
object LoginbaseModuleBridge {

    /** 浏览器模块冷启动通路专用：把回跳 URL 停进 [OAuthCallbackParking]。 */
    fun parkOAuthCallback(url: String) {
        OAuthCallbackParking.park(url)
    }
}
