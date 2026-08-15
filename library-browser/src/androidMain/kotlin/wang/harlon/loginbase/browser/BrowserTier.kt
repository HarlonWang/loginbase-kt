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
