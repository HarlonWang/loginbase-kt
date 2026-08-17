package wang.harlon.loginbase
import io.ktor.http.headersOf
import io.ktor.client.HttpClient

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthClientTest {

    // ---- 单飞 refresh：护栏预算的客户端前提 ----

    @Test
    fun `并发 refresh 只打一次服务端`() = authTest {
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
    fun `并发刷新失败时也只打一次服务端`() = authTest {
        // 单飞不能只共享成功。失败时存储纹丝不动，等待者看到的世界和「一次刷新都没
        // 发生过」一样——若据此各自再发一次，服务端在回执丢失的情况下会把每一次都
        // 判成「拿已作废令牌来刷」，几轮就撞穿 1h/3 次救活护栏、整条会话按盗用撤销。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            delay(50) // 让并发真的重叠
            respondError(HttpStatusCode.InternalServerError)
        }

        val results = (1..8).map { async { client.refresh() } }.awaitAll()

        assertEquals(1, calls, "失败也必须收敛成一次真实请求")
        assertTrue(results.all { it is RefreshOutcome.Failed }, "八个调用方都该拿到同一个失败")
        assertNotNull(store.load(), "失败不清会话")
    }

    @Test
    fun `会话被撤销时，等待者拿到的是 SessionEnded 而不是降级的 NoSession`() = authTest {
        // 复用判断必须排在「读存储」之前。排在之后的话，第一个调用方清掉存储后，
        // 等待者会掉进锁内的 NoSession 早返回，撤销归因就丢了——UI 分不清
        // 「会话被撤销」和「本来就没登录」
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var calls = 0
        val (client, _) = clientWith(store) {
            calls++
            delay(50)
            respond(
                """{"error":"invalid_refresh_token","reason":"session_revoked"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }

        val results = (1..8).map { async { client.refresh() } }.awaitAll()

        assertEquals(1, calls)
        results.forEachIndexed { i, outcome ->
            val ended = assertIs<RefreshOutcome.SessionEnded>(outcome, "第 ${i + 1} 个调用方")
            assertEquals(RefreshFailure.SESSION_REVOKED, ended.reason)
        }
    }

    @Test
    fun `一轮结束之后才来的调用方仍会发起真实刷新`() = authTest {
        // 共享的边界是「同一批等待者」，不是「缓存上次结果」。否则一次失败会把后续
        // 所有刷新永久钉死在那个失败上，网络恢复了也起不来
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var calls = 0
        var down = true
        val (client, _) = clientWith(store) {
            calls++
            if (down) respondError(HttpStatusCode.ServiceUnavailable)
            else respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }

        assertIs<RefreshOutcome.Failed>(client.refresh())
        assertEquals(1, calls)

        down = false
        assertIs<RefreshOutcome.Success>(client.refresh(), "上一轮的失败不该被缓存给新来的调用方")
        assertEquals(2, calls)
        assertEquals("r1", store.load()?.refreshToken)
    }

    @Test
    fun `刷新成功先落盘再宣告成功`() = authTest {
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
        // 归到 Storage 而不是笼统的 Failed：调用方能看出「刷到了但没存住」，
        // 这和「没连上」的处置完全不同
        val cause = assertIs<LoginbaseException.Storage>(outcome.cause)
        assertIs<IllegalStateException>(cause.cause, "原始异常要留在 cause 里可排查")
    }

    @Test
    fun `retryAfterSeconds 类型不对时，不许掩盖真正的 API 错误`() = authTest {
        // 老写法 `body["retryAfterSeconds"]?.jsonPrimitive?.int` 在这里抛
        // NumberFormatException，而且是在**构造 LoginbaseException.Api 的参数求值期**
        // 抛出——调用方看到的是数字解析失败，不是「限流了」，真正的错误整个被掩盖。
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(
                """{"error":"too_many_requests","retryAfterSeconds":"soon"}""",
                HttpStatusCode.TooManyRequests,
                jsonHeaders(),
            )
        }

        val e = assertFailsWith<LoginbaseException.Api> { client.sendCode("a@b.com") }
        assertEquals(AuthError.TOO_MANY_REQUESTS, e.error, "错误码必须照常送达")
        assertNull(e.retryAfterSeconds, "取不到就是取不到，不该因此炸掉整个错误")
    }

    // ---- 保险丝与取消语义 ----

    @Test
    fun `只提供 engine 时，库自己的超时必然生效`() = authTest {
        // 只收 engine、client 由库自建，带来的确定性就在这里：超时行为不取决于消费方
        // 配了什么。此前收整个 client 时，得靠 pluginOrNull(HttpTimeout) 去猜，而
        // HttpTimeoutConfig 三个字段互相独立——只配 connectTimeout 的 client 插件是
        // 装着的，判据失效，请求连上之后照样能无限挂住、把单飞锁永久握死。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val client = AuthClient(BASE, store) {
            httpEngine = MockEngine {
                delay(60_000) // 服务端永远不回
                respond("", HttpStatusCode.OK)
            }
            timeoutMillis = 300
        }

        assertIs<RefreshOutcome.Failed>(client.refresh())
        assertNotNull(store.load(), "超时只是这次没刷成，会话好好的")
    }

    @Test
    fun `client 侧的取消归为 Failed，不冒充调用方取消`() = authTest {
        // ktor 在 client 的 job 被 cancel 时会连带取消在途请求——消费方退登/重建 DI 时
        // close() 掉自己的 HttpClient 就是这个形态。那个 CancellationException 与调用方
        // 无关，原样抛出会让调用方的 launch 当成「自己被取消」而静默丢弃，UI 永远等不到
        // 结果。判据是「当前协程还活着吗」。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { throw CancellationException("client closed") }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.Failed>(outcome)
        assertIs<LoginbaseException.Network>(outcome.cause)
        assertNotNull(store.load(), "绝不因此清会话")
    }

    @Test
    fun `signOut 被取消时本地登出照样完成，取消如实向上传播`() = authTest {
        // 用户点了登出，中途界面销毁、协程被取消，结果还登录着——最难排查的一类状态
        // 不一致。本地清除包在 NonCancellable 里必定完成；取消随后照常传播，调用方的
        // 协程正常结束，而不是被伪装成「登出成功」。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val deleteArrived = CompletableDeferred<Unit>()
        val (client, _) = clientWith(store) {
            deleteArrived.complete(Unit)
            delay(60_000) // 服务端永远不回
            respond("", HttpStatusCode.OK)
        }

        val job = launch { client.signOut() }
        deleteArrived.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled, "取消要如实传播，不能被吞成登出成功")
        assertNull(store.load(), "取消也拦不住本地登出")
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
        )
    }

    // ---- 登出与在途刷新的竞态 ----

    /**
     * 让刷新请求停在「已发出、未返回」的状态，把并发窗口变成确定性的。
     * `arrived` 在服务端收到刷新请求时完成，`release` 由测试决定何时放行响应。
     */
    private class RefreshGate {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private fun gatedClient(
        store: TokenStore,
        gate: RefreshGate,
        refreshResponse: MockRequestHandleScope.() -> HttpResponseData,
    ): AuthClient = clientWith(store) { request ->
        if (request.url.encodedPath.endsWith("/refresh")) {
            gate.arrived.complete(Unit)
            gate.release.await()
            refreshResponse()
        } else {
            respond("", HttpStatusCode.OK) // DELETE /sessions
        }
    }.first

    @Test
    fun `在途刷新的响应不得复活刚登出的会话`() = authTest {
        // 原 bug：refresh 请求在飞 → 用户点登出、存储被清 → 响应到达 → persist 把新
        // 轮换的令牌写回去并置 SignedIn。用户点了登出，半秒后又登录着，手里还是一对
        // 服务端刚发的有效令牌。
        //
        // 这个测试同时守着另一条纪律：signOut 只取 storeMutex、绝不取 refreshMutex。
        // 若有人「顺手」给 signOut 加上 refreshMutex，此处会直接死锁——刷新正持着它。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val gate = RefreshGate()
        val client = gatedClient(store, gate) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.restore()

        val refreshing = async { client.refresh() }
        gate.arrived.await() // 刷新请求确实在飞了
        client.signOut()
        gate.release.complete(Unit)

        assertIs<RefreshOutcome.NoSession>(refreshing.await())
        assertNull(store.load(), "登出后绝不能被在途刷新的响应复活")
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
        )
    }

    @Test
    fun `登出与刷新竞争时，401 不得把「主动登出」改写成「登录已失效」`() = authTest {
        // signOut 的 DELETE /sessions 与在途的 POST /refresh 是并发的。DELETE 先到服务端
        // 把会话删掉，这次刷新就会收到 401——但用户是**自己点的登出**，把原因改写成
        // SessionEnded 会让 UI 弹「登录已失效，请重新登录」，是骚扰。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val gate = RefreshGate()
        val client = gatedClient(store, gate) {
            respond(
                """{"error":"invalid_refresh_token","reason":"session_not_found"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }
        client.restore()

        val refreshing = async { client.refresh() }
        gate.arrived.await()
        client.signOut()
        gate.release.complete(Unit)

        assertIs<RefreshOutcome.SessionEnded>(refreshing.await())
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
            "谁先置的登出态，谁的原因算数",
        )
    }

    @Test
    fun `没有并发登出时，刷新照常落盘`() = authTest {
        // 重读比对的反面：会话没被动过就必须正常落盘，别把守卫写成永远丢弃
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val gate = RefreshGate()
        val client = gatedClient(store, gate) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.restore()

        val refreshing = async { client.refresh() }
        gate.arrived.await()
        gate.release.complete(Unit)

        assertIs<RefreshOutcome.Success>(refreshing.await())
        assertEquals("r1", store.load()?.refreshToken)
        assertEquals(AuthState.SignedIn, client.authState.value)
    }

    // ---- 会话失效判定：只有服务端明说才清 ----

    @Test
    fun `401 invalid_refresh_token 才清会话`() = authTest {
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
        // 原因必须是 SessionEnded 而非 NoSession——这是全库唯一让 UI 弹「登录已失效」的路径
        val state = assertIs<AuthState.SignedOut>(client.authState.value)
        assertEquals(
            SignOutReason.SessionEnded(RefreshFailure.SESSION_REVOKED),
            state.reason,
        )
    }

    @Test
    fun `网络失败不清会话——弱网用户不该被踢下线`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { throw RuntimeException("network down") }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.Failed>(outcome)
        assertNotNull(store.load(), "暂时性失败绝不能清会话")
        val cause = assertIs<LoginbaseException.Network>(outcome.cause)
        assertEquals("network down", cause.cause?.message, "原始异常要留在 cause 里")
        // 状态是 RefreshFailed 而不是 SignedOut：会话还在，UI 不该把弱网用户踢到登录页
        assertEquals(AuthState.RefreshFailed(cause), client.authState.value)
    }

    @Test
    fun `刷新失败置 RefreshFailed，下次刷成功自动回到 SignedIn`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var down = true
        val (client, _) = clientWith(store) {
            if (down) throw RuntimeException("offline")
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.restore()
        assertEquals(AuthState.SignedIn, client.authState.value)

        client.refresh()
        assertIs<AuthState.RefreshFailed>(client.authState.value)

        down = false
        assertIs<RefreshOutcome.Success>(client.refresh())
        assertEquals(AuthState.SignedIn, client.authState.value, "刷成功就该自己恢复")
    }

    @Test
    fun `会话被撤销后，迟到的刷新失败不能把原因盖掉`() = authTest {
        // 幂等防御路径的坑：SessionEnded 若被改写成 NoSession 或 RefreshFailed，
        // UI 就再也弹不出「登录已失效」，用户只会莫名其妙被扔回登录页
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond(
                """{"error":"invalid_refresh_token","reason":"session_revoked"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders(),
            )
        }
        assertIs<RefreshOutcome.SessionEnded>(client.refresh())

        // 会话已清，再刷一次走的是「没令牌」早返回分支
        assertIs<RefreshOutcome.NoSession>(client.refresh())
        val state = assertIs<AuthState.SignedOut>(client.authState.value)
        assertEquals(
            SignOutReason.SessionEnded(RefreshFailure.SESSION_REVOKED),
            state.reason,
            "更精确的原因不该被后来的 NoSession 覆盖",
        )
    }

    @Test
    fun `请求挂住时超时生效，且锁必须放开`() = authTest {
        // 服务端久久不返回。没有超时的话，挂住的请求会永久持有单飞锁，把所有等锁的
        // 调用一起拖死——Supabase 的孤儿锁故障就是这个形态。
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        var hang = true
        val (client, _) = clientWith(store) {
            if (hang) delay(120_000) // 远长于 TEST_TIMEOUT_MS
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }

        assertIs<RefreshOutcome.Failed>(client.refresh())
        assertNotNull(store.load(), "只是这次没刷成，会话好好的，绝不能清")

        // 锁真的放开了才能有下一次——这才是这条超时的意义
        hang = false
        assertIs<RefreshOutcome.Success>(client.refresh())
        assertEquals("r1", store.load()?.refreshToken)
    }

    @Test
    fun `5xx 不清会话`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { respondError(HttpStatusCode.InternalServerError) }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.Failed>(outcome)
        assertEquals(500, assertIs<LoginbaseException.Api>(outcome.cause).status)
        assertNotNull(store.load())
    }

    @Test
    fun `refresh 响应缺 token 字段——两端对不上，不是「服务端说不行」`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders())
        }

        val outcome = client.refresh()
        assertIs<RefreshOutcome.Failed>(outcome)
        assertEquals(
            "accessToken/refreshToken",
            assertIs<LoginbaseException.MalformedResponse>(outcome.cause).field,
        )
        assertNotNull(store.load(), "两端对不上也不是会话失效，绝不能清")
    }

    @Test
    fun `未知 reason 仍按会话终结处理，落到 UNKNOWN`() = authTest {
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
    fun `无本地令牌时 refresh 返回 NoSession`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) { respondError(HttpStatusCode.BadRequest) }
        assertIs<RefreshOutcome.NoSession>(client.refresh())
    }

    // ---- 邮箱验证码 ----

    @Test
    fun `verifyCode 成功即落盘并置登录态，透传 isNewUser 与 user`() = authTest {
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
    fun `sendCode 用服务端给的 cooldownSeconds`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"cooldownSeconds":90}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals(90, client.sendCode("a@b.com").cooldownSeconds)
    }

    @Test
    fun `限流错误带 retryAfterSeconds`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond(
                """{"error":"too_many_requests","retryAfterSeconds":42}""",
                HttpStatusCode.TooManyRequests,
                jsonHeaders(),
            )
        }
        val e = assertFailsWith<LoginbaseException.Api> { client.sendCode("a@b.com") }
        assertEquals(AuthError.TOO_MANY_REQUESTS, e.error)
        assertEquals(42, e.retryAfterSeconds)
    }

    @Test
    fun `验证码错误映射到协议错误码`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"error":"invalid_code"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }
        val e = assertFailsWith<LoginbaseException.Api> { client.verifyCode("a@b.com", "000000") }
        assertEquals(AuthError.INVALID_CODE, e.error)
    }

    @Test
    fun `服务端新增错误码不炸，落 UNKNOWN 且保留原串`() = authTest {
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"error":"some_future_error"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }
        val e = assertFailsWith<LoginbaseException.Api> { client.sendCode("a@b.com") }
        assertEquals(AuthError.UNKNOWN, e.error)
        assertEquals("some_future_error", e.rawError)
    }

    // ---- OAuth ----

    @Test
    fun `signInUrl 对 deepLink 做 URL 编码`() {
        val client = AuthClient(BASE, InMemoryTokenStore())
        val url = client.signInUrl(OAuthProvider.GitHub, "cn.example://auth/callback")
        assertEquals(
            "$BASE/oauth/github/start?redirect=cn.example%3A%2F%2Fauth%2Fcallback",
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
    fun `exchangeOtc 换到令牌即落盘`() = authTest {
        val store = InMemoryTokenStore()
        val (client, _) = clientWith(store) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.exchangeOtc("otc-x")
        assertEquals("a1", store.load()?.accessToken)
    }

    @Test
    fun `linkUrl 需要已登录，且带 Bearer`() = authTest {
        // 未登录直接抛，不白打一次服务端。用专用类型而非 IllegalStateException：
        // UI 状态与实际会话可能短暂不同步，调用方要能单独 catch 它去引导登录
        val anonymous = AuthClient(BASE, InMemoryTokenStore())
        assertFailsWith<LoginbaseException.NotAuthenticated> { anonymous.linkUrl(OAuthProvider.GitHub, "app://cb") }

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
    fun `非协议响应体（网关 HTML 错误页）仍给出可诊断信息`() = authTest {
        // Cloudflare 5xx 错误页、空体等——解析不出 error 字段时不能只留个空串
        val (client, _) = clientWith(InMemoryTokenStore()) {
            respond("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val e = assertFailsWith<LoginbaseException.Api> { client.sendCode("a@b.com") }
        assertEquals(AuthError.UNKNOWN, e.error)
        assertEquals(502, e.status)
        assertEquals("http_502", e.rawError, "至少要能看出是哪个状态码")
    }

    // ---- 异常契约：一个根，且不泄漏 ktor ----

    @Test
    fun `传输层异常包成 Network，不把 ktor 类型泄漏给调用方`() = authTest {
        // engine 由消费方提供、ktor 只是实现细节，不该逼调用方去 catch ktor 的异常层次
        val (client, _) = clientWith(InMemoryTokenStore()) { throw RuntimeException("dns fail") }

        val e = assertFailsWith<LoginbaseException.Network> { client.sendCode("a@b.com") }
        assertEquals("dns fail", e.cause?.message, "原始异常留在 cause 里，排查照样拿得到")
    }

    @Test
    fun `2xx 但响应形状不对，归 MalformedResponse 而非 Api`() = authTest {
        // Api = 服务端明确拒绝，用户看错误提示；MalformedResponse = 两端对不上，
        // 用户重试多少次都一样，该报给开发者。混成一类调用方就没法分流
        val (verify, _) = clientWith(InMemoryTokenStore()) {
            respond("""{"isNewUser":true}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals(
            "accessToken/refreshToken",
            assertFailsWith<LoginbaseException.MalformedResponse> {
                verify.verifyCode("a@b.com", "123456")
            }.field,
        )

        val (link, _) = clientWith(InMemoryTokenStore(TokenPair("a0", "r0"))) {
            respond("""{}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals(
            "authorizeUrl",
            assertFailsWith<LoginbaseException.MalformedResponse> {
                link.linkUrl(OAuthProvider.GitHub, "app://cb")
            }.field,
        )
    }

    @Test
    fun `所有失败都能被 LoginbaseException 一网打尽`() = authTest {
        // 这个测试就是根类型存在的理由：调用方写一个 catch 就能兜住「这次没成」，
        // 不必同时认识 Api、Network、NotAuthenticated 三套不相干的类型
        val api = clientWith(InMemoryTokenStore()) {
            respond("""{"error":"invalid_email"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }.first
        val network = clientWith(InMemoryTokenStore()) { throw RuntimeException("offline") }.first
        val anonymous = AuthClient(BASE, InMemoryTokenStore())

        val caught = listOf<suspend () -> Unit>(
            { api.sendCode("bad") },
            { network.sendCode("a@b.com") },
            { anonymous.linkUrl(OAuthProvider.GitHub, "app://cb") },
        ).map { call ->
            try {
                call()
                null
            } catch (e: LoginbaseException) {
                e
            }
        }
        assertTrue(caught.all { it != null }, "三种失败都该被根类型接住：$caught")
    }

    @Test
    fun `等锁期间会话被登出，refresh 返回 NoSession 且状态为登出`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) {
            respond("""{"accessToken":"a1","refreshToken":"r1"}""", HttpStatusCode.OK, jsonHeaders())
        }
        client.restore()
        store.clear() // 模拟并发 signOut 在 refresh 进锁前清掉了会话

        assertIs<RefreshOutcome.NoSession>(client.refresh())
        assertIs<AuthState.SignedOut>(client.authState.value)
    }

    // ---- 登出与恢复 ----

    @Test
    fun `signOut 即使服务端失败也清本地`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { throw RuntimeException("offline") }

        client.signOut()
        assertNull(store.load(), "用户点了登出就该立刻是登出的")
        // 主动登出不该让 UI 弹「登录已失效」——那是骚扰
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
        )
    }

    @Test
    fun `没调 restore 时，accessToken 顺手把状态补齐`() = authTest {
        // 否则两个对外信号会互相矛盾：authState 说 Unknown，而 accessToken 已经能
        // 返回有效令牌，且没有任何机制提醒调用方漏了 restore()
        val signedIn = AuthClient(BASE, InMemoryTokenStore(TokenPair("a0", "r0")))
        assertEquals(AuthState.Unknown, signedIn.authState.value)
        assertEquals("a0", signedIn.accessToken())
        assertEquals(AuthState.SignedIn, signedIn.authState.value)

        val anonymous = AuthClient(BASE, InMemoryTokenStore())
        assertNull(anonymous.accessToken())
        assertEquals(
            AuthState.SignedOut(SignOutReason.NoSession),
            anonymous.authState.value,
        )
    }

    @Test
    fun `补状态只在 Unknown 时动手，不覆盖别处写下的更准确的原因`() = authTest {
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val (client, _) = clientWith(store) { respond("", HttpStatusCode.OK) }

        client.signOut()
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
        )

        client.accessToken() // 读存储，但状态已不是 Unknown
        assertEquals(
            AuthState.SignedOut(SignOutReason.UserInitiated),
            client.authState.value,
            "主动登出的原因不该被补状态逻辑改写成 NoSession",
        )
    }

    @Test
    fun `close 不碰消费方提供的 engine`() = authTest {
        // 注入的 client 归消费方所有，关掉它等于替对方做主——对方可能还在用同一个
        // engine 发业务请求
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val client = AuthClient(BASE, InMemoryTokenStore()) { httpEngine = engine }

        client.close()

        // engine 归消费方所有：ktor 的 HttpClient(engine) 走 manageEngine = false，
        // 关掉库的 client 不该波及它——对方可能还在用同一个 engine 发业务请求
        assertNull(client.accessToken())
        assertEquals(
            HttpStatusCode.OK,
            HttpClient(engine).get("https://example.com/ping").status,
            "消费方的 engine 必须还活着",
        )
    }

    @Test
    fun `restore 从存储恢复登录态`() = authTest {
        val signedIn = AuthClient(BASE, InMemoryTokenStore(TokenPair("a", "r")))
        assertEquals(AuthState.Unknown, signedIn.authState.value)
        assertEquals(AuthState.SignedIn, signedIn.restore())

        val signedOut = AuthClient(BASE, InMemoryTokenStore())
        assertEquals(AuthState.SignedOut(SignOutReason.NoSession), signedOut.restore())
    }

    @Test
    fun `accessToken forceRefresh 走刷新，失败返回 null`() = authTest {
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
