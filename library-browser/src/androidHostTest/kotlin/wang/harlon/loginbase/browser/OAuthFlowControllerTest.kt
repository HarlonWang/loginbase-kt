package wang.harlon.loginbase.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 管理页状态机（design：oauth-browser 方案 §6.4）。
 *
 * 两条做过**反向验证**（先破坏、确认变红、再恢复）：
 * - 把 [OAuthFlowController.onResume] 的 data-first 检查挪到 intentLaunched 检查之后
 *   （退化成 Auth0 的顺序）→ 「Android 14 重建」「回跳 data 优先于一切状态」两条变红
 * - 让 [OAuthFlowController.onIntent] 忽略后续 intent（只记第一个）→
 *   「onNewIntent 送达的回跳」一条变红（红的形态正是「成功被判成取消」）
 */
class OAuthFlowControllerTest {

    private val url = "cn.example:/loginbase/callback?otc=abc"

    @Test
    fun `正常发起 - 首次 resume 开浏览器`() {
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(null) // signIn 的启动 intent 没有 data

        assertEquals(FlowAction.LaunchBrowser, c.onResume(hasLaunchRequest = true))
    }

    @Test
    fun `onNewIntent 送达的回跳在下一次 resume 投递`() {
        // CCT 路径的正常成功：中转页 CLEAR_TOP 转发 → 既有实例 onNewIntent → onResume。
        // 这是 Auth0 靠「记得写 setIntent」才能走对的路径——控制器自己持有最新 intent，
        // 陷阱结构上不存在（design §11 差异 #6 的反向验证点）
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(null)
        assertEquals(FlowAction.LaunchBrowser, c.onResume(hasLaunchRequest = true))

        c.onIntent(url) // 转发 intent 到达
        assertEquals(FlowAction.Deliver(url), c.onResume(hasLaunchRequest = true))
    }

    @Test
    fun `开过浏览器后空手 resume 判取消`() {
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(null)
        c.onResume(hasLaunchRequest = true) // LaunchBrowser

        // 用户点 × 关掉 CCT：本页 resume，手里没有任何回跳
        assertEquals(FlowAction.DeliverCancelled, c.onResume(hasLaunchRequest = true))
    }

    @Test
    fun `进程死亡后重建 - intentLaunched 从 savedInstanceState 恢复`() {
        // 浏览器停留期间进程被杀但任务还在、系统恢复了本页：不能再开一遍授权页
        val c = OAuthFlowController(intentLaunched = true)
        c.onIntent(null)

        assertEquals(FlowAction.DeliverCancelled, c.onResume(hasLaunchRequest = true))
    }

    @Test
    fun `Android 14 重建 - 状态全丢但 data 还在必须投递`() {
        // AppAuth #977 的场景：转发时系统重建而非复用管理页。新实例 savedInstanceState
        // 为空（intentLaunched=false）、启动 intent 无 extras（hasLaunchRequest=false），
        // 只有 data——Auth0 在这里判「意外启动」静默 finish，唯一的结果丢失。
        // data-first 保证照常投递（design §6.4 的核心场景）
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(url)

        assertEquals(FlowAction.Deliver(url), c.onResume(hasLaunchRequest = false))
    }

    @Test
    fun `回跳 data 优先于一切状态`() {
        // 冷启动通路：中转页转发新建管理页，data 与「无发起参数」并存
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(url)
        assertEquals(FlowAction.Deliver(url), c.onResume(hasLaunchRequest = true))
    }

    @Test
    fun `意外启动静默收场`() {
        // 无参数、无回跳：不是库发起的（外部乱拉 exported=false 拉不动，但防御到位）
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(null)

        assertEquals(FlowAction.FinishUnexpected, c.onResume(hasLaunchRequest = false))
    }

    @Test
    fun `null intent 不覆盖已有的回跳 data`() {
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent(url)
        c.onIntent(null) // 后续非回跳 intent（如系统重投递）不该抹掉结果

        assertEquals(FlowAction.Deliver(url), c.onResume(hasLaunchRequest = false))
    }

    @Test
    fun `后到的回跳覆盖先到的`() {
        // 重复回跳双送：控制器只持最新一份，重复份的收敛由核心的 otc 幂等负责
        val c = OAuthFlowController(intentLaunched = false)
        c.onIntent("cn.example:/loginbase/callback?otc=old")
        c.onIntent(url)

        assertEquals(FlowAction.Deliver(url), c.onResume(hasLaunchRequest = false))
    }
}

/**
 * 三级回退链选择与 Auth Tab 结果映射（design：§11 差异 #9 / 2b 期）。
 * 纯函数，Activity 只是执行者。
 */
class BrowserTierTest {

    @Test
    fun `Auth Tab 可用即第一级`() {
        assertEquals(BrowserTier.AUTH_TAB, selectBrowserTier(authTabSupported = true, cctPackage = "com.android.chrome"))
    }

    @Test
    fun `无 Auth Tab 落 CCT`() {
        assertEquals(BrowserTier.CUSTOM_TAB, selectBrowserTier(authTabSupported = false, cctPackage = "org.mozilla.firefox"))
    }

    @Test
    fun `无 provider 落系统浏览器`() {
        // 防御维度：authTabSupported 不该在无 provider 时为 true，真发生也不能选出 AUTH_TAB
        assertEquals(BrowserTier.SYSTEM_BROWSER, selectBrowserTier(authTabSupported = false, cctPackage = null))
        assertEquals(BrowserTier.SYSTEM_BROWSER, selectBrowserTier(authTabSupported = true, cctPackage = null))
    }

    @Test
    fun `Auth Tab 成功结果映射为投递`() {
        val url = "cn.example:/loginbase/callback?otc=abc"
        assertEquals(
            FlowAction.Deliver(url),
            mapAuthTabResult(androidx.browser.auth.AuthTabIntent.RESULT_OK, url),
        )
    }

    @Test
    fun `Auth Tab 取消是确定的结果码`() {
        assertEquals(
            FlowAction.DeliverCancelled,
            mapAuthTabResult(androidx.browser.auth.AuthTabIntent.RESULT_CANCELED, null),
        )
    }

    @Test
    fun `异常结果码全部映射为 Failed 不静默吞`() {
        // OK 但没有 uri / verification 两码 / 未知码——任何结果都必须给出去
        assertEquals(
            FlowAction.DeliverFailed("auth_tab_missing_uri"),
            mapAuthTabResult(androidx.browser.auth.AuthTabIntent.RESULT_OK, null),
        )
        assertEquals(
            FlowAction.DeliverFailed("auth_tab_verification_failed"),
            mapAuthTabResult(androidx.browser.auth.AuthTabIntent.RESULT_VERIFICATION_FAILED, null),
        )
        assertEquals(
            FlowAction.DeliverFailed("auth_tab_unknown"),
            mapAuthTabResult(androidx.browser.auth.AuthTabIntent.RESULT_UNKNOWN_CODE, null),
        )
    }
}
