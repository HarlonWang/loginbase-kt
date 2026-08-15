package wang.harlon.loginbase

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OAuth 回跳处理（design：oauth-browser 方案 §5.1 / §6.1）。
 *
 * 幂等与 consume 两条做过**反向验证**：
 * - 注释掉 [AuthClient] `exchangeOtcIdempotent` 里的缓存命中分支 →
 *   「同一 otc 重复送入」两条变红（第二次打了服务端）
 * - 把 [AuthClient.consumeOauthResult] 改成空实现 → 「consume 之后」一条变红
 */
class OAuthCallbackTest {

    private val callback = "cn.example:/loginbase/callback"

    private fun tokensJson(suffix: String) =
        """{"accessToken":"a$suffix","refreshToken":"r$suffix"}"""

    // ---- 三种协议形态 ----

    @Test
    fun `otc 回跳走兑换并落盘`() = authTest {
        val store = InMemoryTokenStore()
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.handleOAuthCallback("$callback?otc=abc123")

        assertIs<OAuthOutcome.SignedIn>(outcome)
        assertEquals(1, calls)
        assertEquals("r1", store.load()?.refreshToken)
        assertEquals(AuthState.SignedIn, client.authState.value)
    }

    @Test
    fun `linked 回跳返回 Linked 且不打网络不动会话`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.handleOAuthCallback("$callback?linked=github")

        assertEquals(OAuthOutcome.Linked(OAuthProvider.GitHub), outcome)
        assertEquals(0, calls, "绑定成功的回跳不需要任何网络往返")
        assertEquals("r0", store.load()?.refreshToken, "绑定不产生新会话")
    }

    @Test
    fun `error 回跳返回 Failed 且不打网络`() = authTest {
        var calls = 0
        val (client, _) = clientWith(InMemoryTokenStore()) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.handleOAuthCallback("$callback?error=already_linked")

        assertEquals(OAuthOutcome.Failed("already_linked"), outcome)
        assertEquals(0, calls)
    }

    // ---- 形状识别与解码 ----

    @Test
    fun `认不出的形状返回 Unrecognized`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { error("不该有网络请求") }

        // 无 query、无关参数、空值——三类都不是本库的回跳形态
        listOf(callback, "$callback?foo=bar", "$callback?linked=").forEach { url ->
            assertEquals(OAuthOutcome.Unrecognized(url), client.handleOAuthCallback(url))
        }
    }

    @Test
    fun `畸形 percent 编码返回 Unrecognized 而不抛`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { error("不该有网络请求") }

        // 中转页 exported，任何 App 都能塞任意字节串进来——解析层必须对一切输入给出结果
        listOf("$callback?error=%zz", "$callback?error=abc%2").forEach { url ->
            assertIs<OAuthOutcome.Unrecognized>(client.handleOAuthCallback(url))
        }
    }

    @Test
    fun `非 BMP 字符正确解码`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { error("不该有网络请求") }

        // TrendingAI 手写 decoder 的死角：surrogate 逐 Char 编码会把 emoji 解成乱码。
        // ktor 路径必须解出原文——这也是 #8 选 ktor 不手写的验收
        val outcome = client.handleOAuthCallback("$callback?error=%F0%9F%98%80")

        assertEquals(OAuthOutcome.Failed("😀"), outcome)
    }

    @Test
    fun `加号解码为空格`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { error("不该有网络请求") }

        val outcome = client.handleOAuthCallback("$callback?error=user+denied")

        assertEquals(OAuthOutcome.Failed("user denied"), outcome)
    }

    @Test
    fun `fragment 不参与解析`() = authTest {
        var calls = 0
        val (client, _) = clientWith(InMemoryTokenStore()) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.handleOAuthCallback("$callback?otc=abc#error=hijack")

        assertIs<OAuthOutcome.SignedIn>(outcome)
        assertEquals(1, calls)
    }

    // ---- 幂等（§6.1 otc 层：防重复打服务端） ----

    @Test
    fun `同一 otc 重复送入只打一次服务端并复用结果`() = authTest {
        var calls = 0
        val (client, _) = clientWith(InMemoryTokenStore()) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val first = client.handleOAuthCallback("$callback?otc=abc")
        // 重复来源是现实的：用户点浏览器历史里的回跳链接、ROM 重放 intent
        val second = client.handleOAuthCallback("$callback?otc=abc")

        assertEquals(1, calls, "第二次兑换会得到 invalid_otc——用户刚成功就看到假失败")
        assertEquals(first, second)
    }

    @Test
    fun `重复送入不重复投递到 oauthResults`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }
        val received = mutableListOf<OAuthOutcome>()
        val collector = launch { client.oauthResults.collect { received += it } }
        delay(50) // 等订阅建立

        client.handleOAuthCallback("$callback?otc=abc")
        client.handleOAuthCallback("$callback?otc=abc")
        delay(50)

        assertEquals(1, received.size, "重复回跳不该让 UI 反应两次")
        collector.cancelAndJoin()
    }

    @Test
    fun `不同 otc 各自兑换`() = authTest {
        var calls = 0
        val (client, _) = clientWith(InMemoryTokenStore()) {
            calls++
            respond(tokensJson("$calls"), HttpStatusCode.OK, jsonHeaders())
        }

        client.handleOAuthCallback("$callback?otc=first")
        val second = client.handleOAuthCallback("$callback?otc=second")

        assertEquals(2, calls)
        assertEquals("r2", (second as OAuthOutcome.SignedIn).session.tokens.refreshToken)
    }

    @Test
    fun `兑换失败映射为 Failed 且不落盘`() = authTest {
        val store = InMemoryTokenStore()
        val (client, _) = clientWith(store) {
            respond("""{"error":"invalid_otc"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }

        val outcome = client.handleOAuthCallback("$callback?otc=expired")

        assertEquals(OAuthOutcome.Failed("invalid_otc"), outcome)
        assertNull(store.load())
        // 失败也从唯一通道送达——UI 只需要一处 collect
        assertEquals(listOf<OAuthOutcome>(outcome), client.oauthResults.replayCache)
    }

    @Test
    fun `兑换途中被取消不缓存结果`() = authTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val (client, _) = clientWith(InMemoryTokenStore()) {
            calls++
            if (calls == 1) gate.await() // 第一次挂住，等着被取消
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        val hung = launch { client.handleOAuthCallback("$callback?otc=abc") }
        delay(100) // 让请求真的在飞
        hung.cancelAndJoin()

        // 取消不是结果：不该被幂等缓存挡住，重新送达要照常兑换
        val outcome = client.handleOAuthCallback("$callback?otc=abc")

        assertIs<OAuthOutcome.SignedIn>(outcome)
        assertEquals(2, calls)
    }

    // ---- 结果通道（§6.1 通道层：replay 兜底与 consume） ----

    @Test
    fun `结果先于订阅产生也能收到`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        // 进程回收后冷启动：restore() 里的处理先于任何 UI 订阅——replay=1 的存在理由
        client.handleOAuthCallback("$callback?otc=abc")

        assertIs<OAuthOutcome.SignedIn>(client.oauthResults.first())
    }

    @Test
    fun `consume 之后新订阅者收不到旧结果`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }
        client.handleOAuthCallback("$callback?otc=abc")

        client.consumeOauthResult()

        // 场景：登录面板处理完 SignedIn 后关闭；五分钟后用户打开绑定页（第二个订阅点）。
        // 不清 replay 的话，绑定页会收到陈旧的 SignedIn、弹一条莫名的「登录成功」
        assertTrue(client.oauthResults.replayCache.isEmpty(), "replay 不是历史记录")
    }

    // ---- 停泊排空（§6.3 进程被回收） ----

    @Test
    fun `restore 排空停泊的回跳`() = authTest {
        val store = InMemoryTokenStore()
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            respond(tokensJson("1"), HttpStatusCode.OK, jsonHeaders())
        }

        // 管理页在冷启动通路里停泊 URL（届时进程里还没有 AuthClient），
        // restore() 是接入指南的启动必调项，顺带排空
        OAuthCallbackParking.park("$callback?otc=parked")
        val state = client.restore()

        assertEquals(1, calls)
        assertEquals(AuthState.SignedIn, state, "restore 返回排空之后的状态")
        assertEquals("r1", store.load()?.refreshToken)
        assertIs<OAuthOutcome.SignedIn>(client.oauthResults.first())

        // 槽已清空：再 restore 不会重复兑换
        client.restore()
        assertEquals(1, calls)
    }
}
