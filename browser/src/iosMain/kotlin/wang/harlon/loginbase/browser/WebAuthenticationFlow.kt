package wang.harlon.loginbase.browser

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionCallback
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import wang.harlon.loginbase.AuthClient
import wang.harlon.loginbase.LoginbaseException
import wang.harlon.loginbase.LoginbaseInternalApi
import wang.harlon.loginbase.OAuthOutcome
import wang.harlon.loginbase.OAuthProvider
import wang.harlon.loginbase.oauthFailureReason

/**
 * 发起社交登录。**不挂起**——拉起系统的 web 认证会话即返回，结果只从
 * [AuthClient.oauthResults] 送达（挂起返回值在屏幕旋转、进程回收下必然中断）。
 *
 * 授权页由 `ASWebAuthenticationSession` 承载，回跳直接进它的 completionHandler，
 * 不经 App 冷启动——Android 那条路要的停泊兜底在这里用不到。
 *
 * @param redirect 只支持 private-use scheme（自有域名反写，如 `cn.example:/loginbase/callback`）：
 *   `callbackURLScheme` 收的就是它的 scheme 段。https app-link 形态要 associated domains，本模块不提供
 * @param clientFlowId 消费方埋点体系的流程标识，随 start 请求进服务端统计（跨库对齐用）。
 *   **应每次流程一个值，勿传用户级稳定标识**——它会出现在 URL 与统计表里
 */
fun AuthClient.signIn(
    provider: OAuthProvider,
    redirect: String,
    clientFlowId: String? = null,
) {
    val scheme = callbackScheme(redirect)
    // browser_tier / browser_pkg 不上报：那两个描述的是 Android 的通路选择，服务端白名单也只收那三个值
    startSession(signInUrl(provider, redirect).appendClientFlowId(clientFlowId), scheme)
}

/**
 * 已登录用户绑定第二身份：登录/绑定回跳的差异由库分辨，消费方在同一个
 * [AuthClient.oauthResults] 里拿 [OAuthOutcome.Linked]。其余同 [signIn]。
 * [clientFlowId] 走 POST body 而非 URL——授权 URL 由服务端返回，拼上去服务端看不到。
 */
@OptIn(LoginbaseInternalApi::class) // oauthFailureReason：本模块就是它说的「配套模块」
fun AuthClient.link(provider: OAuthProvider, redirect: String, clientFlowId: String? = null) {
    val scheme = callbackScheme(redirect)
    WebAuthRuntime.scope.launch {
        // link 的授权 URL 要先带 Bearer POST 换取，这次往返里用户还停在原界面
        val url = try {
            linkUrl(provider, redirect, clientFlowId)
        } catch (e: LoginbaseException) {
            publishFailure(e.oauthFailureReason())
            return@launch
        }
        startSession(url, scheme)
    }
}

/**
 * 回跳只按 scheme 匹配，路径部分交给 [AuthClient.handleOAuthCallback] 解析。
 * 发起点就地校验：错在这里爆出来，比「用户授权完却回不来」早一整个往返。
 */
private fun callbackScheme(redirect: String): String {
    val scheme = redirect.substringBefore(':', "")
    require(scheme.isNotBlank() && !scheme.startsWith("http", ignoreCase = true)) {
        "[loginbase] redirect 只支持 private-use scheme（自有域名反写，如 cn.example），收到 $redirect"
    }
    return scheme
}

private fun AuthClient.startSession(url: String, callbackScheme: String) {
    val nsUrl = NSURL.URLWithString(url) ?: run {
        publishFailure("malformed_authorize_url")
        return
    }
    val session = ASWebAuthenticationSession(
        uRL = nsUrl,
        callback = ASWebAuthenticationSessionCallback.callbackWithCustomScheme(callbackScheme),
        completionHandler = { callbackUrl, error ->
            WebAuthRuntime.activeSession = null
            WebAuthRuntime.scope.launch {
                when {
                    callbackUrl != null -> handleOAuthCallback(callbackUrl.absoluteString.orEmpty())
                    // 关掉授权页，或在系统那句「想要使用…登录」上点取消——都是主动放弃
                    error.isUserCancellation() -> publishOAuthOutcomeInternal(OAuthOutcome.Cancelled)
                    else -> publishFailure("web_auth_session_${error?.code ?: -1}")
                }
            }
        },
    )
    session.presentationContextProvider = anchorProvider
    // 授权期间必须持有会话，否则它会被回收、回调永不到达
    WebAuthRuntime.activeSession = session
    if (!session.start()) {
        WebAuthRuntime.activeSession = null
        // 起不来就没有回跳，任何结果都必须给出去，不能静默吞
        publishFailure("web_auth_session_start_failed")
    }
}

private fun AuthClient.publishFailure(reason: String) {
    WebAuthRuntime.scope.launch { publishOAuthOutcomeInternal(OAuthOutcome.Failed(reason)) }
}

@OptIn(LoginbaseInternalApi::class)
private suspend fun AuthClient.publishOAuthOutcomeInternal(outcome: OAuthOutcome) {
    publishOAuthOutcome(outcome)
}

internal fun String.appendClientFlowId(flowId: String?): String =
    if (flowId.isNullOrBlank()) this
    else this + (if (contains('?')) '&' else '?') + "client_flow_id=" + flowId.encodeURLParameter()

private fun NSError?.isUserCancellation(): Boolean =
    this != null && code == ASWebAuthenticationSessionErrorCodeCanceledLogin

/** 投递要在会话关闭后继续跑完，故用进程级 scope 而非任何界面的生命周期 */
private object WebAuthRuntime {
    var activeSession: ASWebAuthenticationSession? = null
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

private val anchorProvider = AnchorProvider()

private class AnchorProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {

    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): ASPresentationAnchor = keyWindow()
}

@Suppress("DEPRECATION")
private fun keyWindow(): UIWindow =
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
        ?: UIApplication.sharedApplication.keyWindow
        ?: UIWindow()
