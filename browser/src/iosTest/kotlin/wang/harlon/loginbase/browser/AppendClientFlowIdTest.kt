package wang.harlon.loginbase.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class AppendClientFlowIdTest {

    @Test
    fun appendsAsExtraQueryParam() {
        assertEquals(
            "https://x.test/start?redirect=a&client_flow_id=cf-1_A",
            "https://x.test/start?redirect=a".appendClientFlowId("cf-1_A"),
        )
    }

    @Test
    fun startsQueryWhenUrlHasNone() {
        assertEquals(
            "https://x.test/start?client_flow_id=cf-1",
            "https://x.test/start".appendClientFlowId("cf-1"),
        )
    }

    @Test
    fun encodesValue() {
        assertEquals(
            "https://x.test/start?client_flow_id=a%20b%26c",
            "https://x.test/start".appendClientFlowId("a b&c"),
        )
    }

    @Test
    fun leavesUrlUntouchedWhenIdMissing() {
        val url = "https://x.test/start?redirect=a"
        assertEquals(url, url.appendClientFlowId(null))
        assertEquals(url, url.appendClientFlowId("  "))
    }
}
