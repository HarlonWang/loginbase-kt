package wang.harlon.loginbase

import io.ktor.http.parseQueryString
import kotlin.concurrent.Volatile

/**
 * 一次社交登录/绑定的结果。sealed：新增一种结果要让调用方重新想一遍怎么处置。
 *
 * 所有结果都会从 [AuthClient.oauthResults] 送达（那是消费方唯一该看的通道，见其文档）；
 * [AuthClient.handleOAuthCallback] 的返回值只服务于直接调用它的一方。
 */
sealed interface OAuthOutcome {
    /** 登录成功，会话已建立并落盘（[AuthClient.authState] 同时变为 SignedIn） */
    data class SignedIn(val session: AuthSession) : OAuthOutcome

    /** 绑定成功。不产生新会话，authState 不变 */
    data class Linked(val provider: OAuthProvider) : OAuthOutcome

    /**
     * 流程失败。[reason] 尽量透传原始错误码：来自回跳 `error` 参数（典型如
     * `already_linked`，具体值由服务端 App 定义、不是协议保证），或 otc 兑换失败时的
     * 服务端错误码（如 `invalid_otc`）；网络/存储这类本地失败用固定串
     * `network` / `storage` / `malformed_response`。
     */
    data class Failed(val reason: String) : OAuthOutcome

    /**
     * 用户主动放弃（关掉了授权页）。由浏览器模块判定并投递，
     * 不会从 [AuthClient.handleOAuthCallback] 产生。
     */
    data object Cancelled : OAuthOutcome

    /**
     * 回跳 URL 不是本库认得的形状——没有 `otc` / `linked` / `error` 任何一个参数，
     * 或 query 含畸形 percent 编码。大概率是接入配置错了，或有外部程序向中转页塞了
     * 异常输入（中转页是 exported 的，谁都能发），该报给开发者。
     */
    data class Unrecognized(val url: String) : OAuthOutcome
}

/**
 * 回跳 URL 的解析结果——[OAuthOutcome] 去掉需要网络往返才能得出的部分。
 *
 * 单独成类型是为了让解析可以独立测试（喂 URL 断言形状），并把「URL 长什么样」
 * 与「拿到 otc 之后做什么」分开。
 */
internal sealed interface OAuthCallbackParams {
    data class Login(val otc: String) : OAuthCallbackParams
    data class Linked(val provider: String) : OAuthCallbackParams
    data class Failed(val reason: String) : OAuthCallbackParams
    data object Unrecognized : OAuthCallbackParams

    companion object {
        /**
         * 三种协议形态按 `otc` > `linked` > `error` 的顺序识别（与服务端回跳约定一致，
         * 同时出现属于异常输入，取语义最强的）。
         *
         * 解码走 ktor 的 [parseQueryString]（既有依赖，KMP 全平台、带官方测试），
         * 不手写 percent-decoder——手写版的典型死角是非 BMP 字符按 surrogate 逐个
         * 编码产生乱码，没有测试会想到去抓它。
         *
         * 刻意不用 ktor 的 `Url()` 整串解析：它对 `scheme:/path` 无 host 自定义形态的
         * 行为未验证过，query 的提取用截串就够了。
         *
         * 任何解析失败（畸形 percent 序列等）都收编为 [Unrecognized]，**不抛异常**——
         * 输入可能来自任意外部程序，解析层必须对任何字节串给出结果。
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
internal fun LoginbaseException.oauthFailureReason(): String = when (this) {
    is LoginbaseException.Api -> rawError
    is LoginbaseException.Network -> "network"
    is LoginbaseException.Storage -> "storage"
    is LoginbaseException.MalformedResponse -> "malformed_response"
    is LoginbaseException.NotAuthenticated -> "not_authenticated"
}

/**
 * 进程被回收后的回跳**停泊槽**（design：oauth-browser 方案 §6.3）。
 *
 * 冷启动链路：浏览器模块的管理页拿到回跳 URL 时，进程里还没有可用的 [AuthClient]
 * （静态槽随旧进程消失），于是把 URL 停在这里、拉起 App；App 启动时
 * [AuthClient.restore] 顺带排空——`restore()` 本来就是接入指南要求启动必调的，
 * 时序天然成立，消费方零参与。
 *
 * 槽住在核心而不是浏览器模块：排空方（`restore()`）在核心，依赖方向必须保持
 * browser → core 单向。单槽后到覆盖先到——同一时刻至多一个在途流程，无需队列。
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
