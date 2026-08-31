package wang.harlon.loginbase.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class AppendClientProbeTest {

    private val base = "https://api.example.com/auth/oauth/github/start?redirect=app%3A%2Fcb"

    @Test
    fun `三个参数齐全时全部拼上`() {
        assertEquals(
            "$base&browser_tier=custom_tab&browser_pkg=com.android.chrome&client_flow_id=cf-1",
            appendClientProbe(base, BrowserTier.CUSTOM_TAB, "com.android.chrome", "cf-1"),
        )
    }

    @Test
    fun `无 cct 包与无 clientFlowId 时只拼 tier`() {
        assertEquals(
            "$base&browser_tier=system_browser",
            appendClientProbe(base, BrowserTier.SYSTEM_BROWSER, null, null),
        )
    }

    @Test
    fun `空白 cctPackage 视同未传`() {
        assertEquals(
            "$base&browser_tier=custom_tab",
            appendClientProbe(base, BrowserTier.CUSTOM_TAB, " ", null),
        )
    }

    @Test
    fun `空白 clientFlowId 视同未传`() {
        assertEquals(
            "$base&browser_tier=auth_tab&browser_pkg=com.android.chrome",
            appendClientProbe(base, BrowserTier.AUTH_TAB, "com.android.chrome", "  "),
        )
    }

    @Test
    fun `无既有 query 时用问号起头`() {
        assertEquals(
            "https://x/start?browser_tier=auth_tab",
            appendClientProbe("https://x/start", BrowserTier.AUTH_TAB, null, null),
        )
    }

    @Test
    fun `clientFlowId 含保留字符时 URL 编码`() {
        assertEquals(
            "$base&browser_tier=auth_tab&client_flow_id=a%26b%3Dc",
            appendClientProbe(base, BrowserTier.AUTH_TAB, null, "a&b=c"),
        )
    }
}
