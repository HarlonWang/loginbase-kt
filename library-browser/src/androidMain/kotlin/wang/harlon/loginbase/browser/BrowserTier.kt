package wang.harlon.loginbase.browser

import androidx.browser.auth.AuthTabIntent

/** 三级回退链（design：oauth-browser 方案 §11 差异 #9）的一级。 */
internal enum class BrowserTier { AUTH_TAB, CUSTOM_TAB, SYSTEM_BROWSER }

/**
 * 按可用性选一级。Auth Tab 的检测走 `CustomTabsClient.isAuthTabSupported`（需先有
 * CustomTabsService provider，故 `cctPackage == null` 时它必然为 false——参数上
 * 用两个布尔维度表达，保持纯函数可单测）。
 */
internal fun selectBrowserTier(authTabSupported: Boolean, cctPackage: String?): BrowserTier = when {
    authTabSupported && cctPackage != null -> BrowserTier.AUTH_TAB
    cctPackage != null -> BrowserTier.CUSTOM_TAB
    else -> BrowserTier.SYSTEM_BROWSER
}

/**
 * Auth Tab 结果码 → 管理页动作的映射（纯函数，可单测）。
 *
 * 浏览器在 Auth Tab 内捕获回跳、经 ActivityResult 返回——**不经 Intent、不经中转页**，
 * 这正是它「无劫持暴露面」的来源；取消是确定的结果码，不需要 onResume 启发式。
 * 两个 VERIFICATION_* 码只属于 https app-link 形态，custom scheme 下理论不可达，
 * 仍映射成 Failed 以防万一——任何结果都必须给出去，不能静默吞。
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
