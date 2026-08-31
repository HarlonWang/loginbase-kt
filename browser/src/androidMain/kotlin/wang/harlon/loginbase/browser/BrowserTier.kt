package wang.harlon.loginbase.browser

import androidx.browser.auth.AuthTabIntent

/** 三级回退链的一级（论证见 docs/oauth-browser-design.md）。 */
internal enum class BrowserTier { AUTH_TAB, CUSTOM_TAB, SYSTEM_BROWSER }

/** 按可用性选一级；`cctPackage == null` 时 authTabSupported 按防御处理，不选出 AUTH_TAB。 */
internal fun selectBrowserTier(authTabSupported: Boolean, cctPackage: String?): BrowserTier = when {
    authTabSupported && cctPackage != null -> BrowserTier.AUTH_TAB
    cctPackage != null -> BrowserTier.CUSTOM_TAB
    else -> BrowserTier.SYSTEM_BROWSER
}

/**
 * 把客户端自述参数拼进 start URL（协议见 loginbase 仓 docs/protocol.md 的 start 节）。
 * 仅登录轨可用：link 的授权 URL 是服务端换取的 GitHub 地址，参数没处挂。
 */
internal fun appendClientProbe(
    url: String,
    tier: BrowserTier,
    cctPackage: String?,
    clientFlowId: String?,
): String = buildString {
    append(url)
    fun param(key: String, value: String) {
        append(if (contains('?')) '&' else '?')
        append(key).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"))
    }
    param("browser_tier", tier.name.lowercase())
    cctPackage?.takeIf { it.isNotBlank() }?.let { param("browser_pkg", it) }
    clientFlowId?.takeIf { it.isNotBlank() }?.let { param("client_flow_id", it) }
}

/**
 * Auth Tab 结果码 → 管理页动作（纯函数）。**任何结果都必须给出去，不能静默吞**；
 * VERIFICATION_* 码属 https app-link 形态、custom scheme 下理论不可达，仍映射 Failed 防万一。
 */
internal fun mapAuthTabResult(resultCode: Int, resultUri: String?): FlowAction = when {
    resultCode == AuthTabIntent.RESULT_OK && resultUri != null -> FlowAction.Deliver(resultUri)
    resultCode == AuthTabIntent.RESULT_OK -> FlowAction.DeliverFailed("auth_tab_missing_uri")
    resultCode == AuthTabIntent.RESULT_CANCELED -> FlowAction.DeliverCancelled
    resultCode == AuthTabIntent.RESULT_VERIFICATION_FAILED ->
        FlowAction.DeliverFailed("auth_tab_verification_failed")
    resultCode == AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT ->
        FlowAction.DeliverFailed("auth_tab_verification_timed_out")
    else -> FlowAction.DeliverFailed("auth_tab_unknown")
}
