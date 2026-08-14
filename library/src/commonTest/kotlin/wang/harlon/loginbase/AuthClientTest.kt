package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://api.example.com/auth"

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

private fun clientWith(
    store: TokenStore,
    handler: MockRequestHandler,
): Pair<AuthClient, MockEngine> {
    val engine = MockEngine(handler)
    return AuthClient(BASE, store, HttpClient(engine)) to engine
}

class AuthClientTest {

    // ---- 单飞 refresh：护栏预算的客户端前提 ----

    @Test
    fun `并发 refresh 只打一次服务端`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            delay(50) // 让并发真的重叠
            respond(
                """{"accessToken":"a1","refreshToken":"r1"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        // 8 个并发调用——没有单飞的话就是 8 次刷新，服务端 1h/3 次救活配额会被打爆
        val results = (1..8).map { async { client.refresh() } }.awaitAll()

        assertEquals(1, calls, "并发刷新必须收敛成一次真实请求")
        assertTrue(results.all { it is RefreshOutcome.Success })
        // 后到者复用先到者的结果，拿到的是同一对新令牌
        results.forEach { assertEquals("r1", (it as RefreshOutcome.Success).tokens.refreshToken) }
        assertEquals("r1", store.load()?.refreshToken)
    }

    @Test
    fun `刷新成功先落盘再宣告成功`() = runTest {
        // save 抛异常 = 没存住。此时必须报失败：若宣告成功而实际没存，
        // 下次会拿旧令牌去刷，正好触发服务端救活（有护栏）
        val store = object : TokenStore {
            var saved = false
            override suspend fun load() = TokenPair("a0", "r0")
            override suspend fun save(tokens: TokenPair) {
                saved = true
                throw IllegalStateException("disk full")
            }
            override suspend fun clear() {}
        }
        val (client, _) = clientWith(store) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.refresh()
        assertTrue(store.saved, "应该尝试过落盘")
        assertIs<RefreshOutcome.Failed>(outcome)
    }

    // ---- 会话失效判定：只有服务端明说才清 ----

    @Test
    fun `401 invalid_refresh_token 才清会话`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond(
                """{"error":"invalid_refresh_token","reason":"session_revoked"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.SessionEnded>(outcome)
        assertEquals(RefreshFailure.SESSION_REVOKED, outcome.reason)
        assertNull(store.load(), "会话已死，本地令牌应清除")
        assertEquals(AuthState.SignedOut, client.authState.value)
    }

    @Test
    fun `网络失败不清会话——弱网用户不该被踢下线`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { throw RuntimeException("network down") }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.Failed>(outcome)
        assertNotNull(store.load(), "暂时性失败绝不能清会话")
    }

    @Test
    fun `请求挂住时保险丝熔断，且锁必须放开`() = runTest {
        // 消费方注入的 HttpClient 没配超时（库自建的才装 HttpTimeout）→ 请求久久不返回。
        // 没有这道保险丝的话，挂住的请求会永久持有单飞锁，把所有等锁的调用一起拖死
        // ——Supabase 的孤儿锁故障就是这个形态。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var hang = true
        val (client, _) = clientWith(store) {
            if (hang) delay(120_000) // 比 REFRESH_TIMEOUT_MS 长
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }

        assertIs<RefreshOutcome.Failed>(client.refresh())
        assertNotNull(store.load(), "只是这次没刷成，会话好好的，绝不能清")

        // 锁真的放开了才能有下一次——这才是这道保险丝的意义
        hang = false
        assertIs<RefreshOutcome.Success>(client.refresh())
        assertEquals("r1", store.load()?.refreshToken)
    }

    @Test
    fun `5xx 不清会话`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { respondError(HttpStatusCode.InternalServerError) }

        assertIs<RefreshOutcome.Failed>(client.refresh())
        assertNotNull(store.load())
    }

    @Test
    fun `未知 reason 仍按会话终结处理，落到 UNKNOWN`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond(
                """{"error":"invalid_refresh_token","reason":"brand_new_reason"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }
        val outcome = client.refresh()
        assertIs<RefreshOutcome.SessionEnded>(outcome)
        assertEquals(RefreshFailure.UNKNOWN, outcome.reason)
    }

    @Test
    fun `无本地令牌时 refresh 返回 NoSession`() = runTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { respondError(HttpStatusCode.BadRequest) }
        assertIs<RefreshOutcome.NoSession>(client.refresh())
    }

    // ---- 邮箱验证码 ----

    @Test
    fun `verifyCode 成功即落盘并置登录态，透传 isNewUser 与 user`() = runTest {
        val store = InMemoryTokenStore()
        val (client, _) = clientWith(store) {
            respond(
                """{"accessToken":"a1","refreshToken":"r1","isNewUser":true,"user":{"id":"u-1","isPro":false}}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        val session = client.verifyCode("a@b.com", "123456")
        assertEquals("a1", session.tokens.accessToken)
        assertEquals(true, session.isNewUser)
        assertNotNull(session.user, "user 形状归 App，原样透传")
        assertEquals("r1", store.load()?.refreshToken)
        assertEquals(AuthState.SignedIn, client.authState.value)
    }

    @Test
    fun `sendCode 用服务端给的 cooldownSeconds`() = runTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"cooldownSeconds":90}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals(90, client.sendCode("a@b.com").cooldownSeconds)
    }

    @Test
    fun `限流错误带 retryAfterSeconds`() = runTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(
                """{"error":"too_many_requests","retryAfterSeconds":42}""",
                HttpStatusCode.TooManyRequests,
                jsonHeaders(),
            )
        }
        val e = assertFailsWith<AuthApiException> { client.sendCode("a@b.com") }
        assertEquals(AuthError.TOO_MANY_REQUESTS, e.error)
        assertEquals(42, e.retryAfterSeconds)
    }

    @Test
    fun `验证码错误映射到协议错误码`() = runTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"error":"invalid_code"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }
        val e = assertFailsWith<AuthApiException> { client.verifyCode("a@b.com", "000000") }
        assertEquals(AuthError.INVALID_CODE, e.error)
    }

    @Test
    fun `服务端新增错误码不炸，落 UNKNOWN 且保留原串`() = runTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"error":"some_future_error"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }
        val e = assertFailsWith<AuthApiException> { client.sendCode("a@b.com") }
        assertEquals(AuthError.UNKNOWN, e.error)
        assertEquals("some_future_error", e.rawError)
    }

    // ---- OAuth ----

    @Test
    fun `signInUrl 对 deepLink 做 URL 编码`() {
        val client = AuthClient(BASE, InMemoryTokenStore())
        val url = client.signInUrl(OAuthProvider.GitHub, "cn.trendingai://auth/callback")
        assertEquals(
            "$BASE/oauth/github/start?redirect=cn.trendingai%3A%2F%2Fauth%2Fcallback",
            url,
        )
    }

    @Test
    fun `provider 不是枚举——服务端加一个不该逼客户端发版`() {
        // value class 而非 enum 的意义就在这里：服务端 App 自己配 provider 集合，
        // 客户端连它启用了哪几个都不知道，写死成枚举等于每次配置变化都 breaking
        val client = AuthClient(BASE, InMemoryTokenStore())
        assertEquals(
            "$BASE/oauth/google/start?redirect=app%3A%2F%2Fcb",
            client.signInUrl(OAuthProvider("google"), "app://cb"),
        )
    }

    @Test
    fun `provider 被路径编码，越不出自己那一段`() {
        val client = AuthClient(BASE, InMemoryTokenStore())
        val url = client.signInUrl(OAuthProvider("../sessions"), "app://cb")
        assertTrue(url.startsWith("$BASE/oauth/..%2Fsessions/start"), url)

        // 空 provider 是调用方的编程错误，当场炸掉而不是拼出个 //start 打给服务端
        assertFailsWith<IllegalArgumentException> { OAuthProvider(" ") }
    }

    @Test
    fun `exchangeOtc 换到令牌即落盘`() = runTest {
        val store = InMemoryTokenStore()
        val (client, _) = clientWith(store) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.exchangeOtc("otc-x")
        assertEquals("a1", store.load()?.accessToken)
    }

    @Test
    fun `linkUrl 需要已登录，且带 Bearer`() = runTest {
        // 未登录直接抛，不白打一次服务端。用专用类型而非 IllegalStateException：
        // UI 状态与实际会话可能短暂不同步，调用方要能单独 catch 它去引导登录
        val anonymous = AuthClient(BASE, InMemoryTokenStore())
        assertFailsWith<NotAuthenticatedException> { anonymous.linkUrl(OAuthProvider.GitHub, "app://cb") }

        var sawBearer: String? = null
        var sawUrl: String? = null
        val (client, _) = clientWith(InMemoryTokenStore(TokenPair("a0", "r0"))) { request ->
            sawBearer = request.headers[HttpHeaders.Authorization]
            sawUrl = request.url.toString()
            respond("""{"authorizeUrl":"https://github.com/login/oauth/authorize?x=1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        val url = client.linkUrl(OAuthProvider.GitHub, "app://cb")
        assertEquals("https://github.com/login/oauth/authorize?x=1", url)
        assertEquals("Bearer a0", sawBearer)
        assertEquals("$BASE/oauth/github/link/start", sawUrl)
    }

    @Test
    fun `非协议响应体（网关 HTML 错误页）仍给出可诊断信息`() = runTest {
        // Cloudflare 5xx 错误页、空体等——解析不出 error 字段时不能只留个空串
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val e = assertFailsWith<AuthApiException> { client.sendCode("a@b.com") }
        assertEquals(AuthError.UNKNOWN, e.error)
        assertEquals(502, e.status)
        assertEquals("http_502", e.rawError, "至少要能看出是哪个状态码")
    }

    @Test
    fun `等锁期间会话被登出，refresh 返回 NoSession 且状态为登出`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.restore()
        store.clear() // 模拟并发 signOut 在 refresh 进锁前清掉了会话

        assertIs<RefreshOutcome.NoSession>(client.refresh())
        assertEquals(AuthState.SignedOut, client.authState.value)
    }

    // ---- 登出与恢复 ----

    @Test
    fun `signOut 即使服务端失败也清本地`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { throw RuntimeException("offline") }

        client.signOut()
        assertNull(store.load(), "用户点了登出就该立刻是登出的")
        assertEquals(AuthState.SignedOut, client.authState.value)
    }

    @Test
    fun `restore 从存储恢复登录态`() = runTest {
        val signedIn = AuthClient(BASE, InMemoryTokenStore(TokenPair("a", "r")))
        assertEquals(AuthState.Unknown, signedIn.authState.value)
        assertEquals(AuthState.SignedIn, signedIn.restore())

        val signedOut = AuthClient(BASE, InMemoryTokenStore())
        assertEquals(AuthState.SignedOut, signedOut.restore())
    }

    @Test
    fun `accessToken forceRefresh 走刷新，失败返回 null`() = runTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (ok, _) = clientWith(store) {
            respond("""{"accessToken":"a9","refreshToken":"r9"}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals("a0", ok.accessToken())
        assertEquals("a9", ok.accessToken(forceRefresh = true))

        val (dead, _) = clientWith(InMemoryTokenStore(TokenPair("a0", "r0"))) {
            respond("""{"error":"invalid_refresh_token","reason":"session_not_found"}""", HttpStatusCode.Unauthorized, jsonHeaders())
        }
        assertNull(dead.accessToken(forceRefresh = true))
    }
}
