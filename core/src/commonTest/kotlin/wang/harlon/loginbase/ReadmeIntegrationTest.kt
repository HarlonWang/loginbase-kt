package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * README「接入指南」第 2 步那段 `Auth` 插件接线的**可执行版本**。
 *
 * 那段代码是整份指南里唯一「写错了不会有任何报错」的地方——绕过 `auth.refresh()` 自己
 * 去 POST `/refresh` 照样能跑通、功能完全正常，只是每次 token 过期偷偷烧一格服务端的
 * 救活配额。既然文档里专门警告了这件事，文档给出的正确版本就不能只是「看起来对」。
 *
 * 本文件里的接线**与 README 保持逐字一致**，改 README 时请一起改。它证明三件事：
 *
 * 1. 那段代码**能编译**（`BearerTokens` 的可空 refreshToken、两个 lambda 的 suspend
 *    与 receiver 签名）
 * 2. 401 时插件确实会调 `refreshTokens`、确实会用新令牌重试原请求
 * 3. **两个各自装了 `Auth` 插件的业务 client 同时 401 时，服务端只被刷新一次**——
 *    ktor 插件的单飞是 per-client 的，跨 client 收敛只有本库的锁能做；同时这也实证了
 *    「装了 `Auth` 插件的 client 与本库共存不会死锁」（若死锁，本用例会直接挂住）
 */
class ReadmeIntegrationTest {

    /**
     * 假服务端：`a0` 已过期，只认刷新之后发的 `a1`。
     *
     * 两个业务请求各自到达时先互相等一下再返回 401，把「同时 401」变成确定性的——
     * 否则先到的那个可能已经刷完并重试成功，后到的就成了新的一轮，服务端会被刷两次
     * （那是 README 里提到的「401 错开」情形，不烧配额，但不是本用例要验的东西）。
     */
    private class FakeServer {
        var refreshCalls = 0
        private val firstArrived = CompletableDeferred<Unit>()
        private val secondArrived = CompletableDeferred<Unit>()

        suspend fun rendezvous() {
            if (!firstArrived.complete(Unit)) secondArrived.complete(Unit)
            secondArrived.await()
        }

        val handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData =
            { request ->
                if (request.url.encodedPath.endsWith("/refresh")) {
                    refreshCalls++
                    respond(
                        """{"accessToken":"a1","refreshToken":"r1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                } else {
                    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                    if (token == "a1") {
                        respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders())
                    } else {
                        rendezvous()
                        respond("", HttpStatusCode.Unauthorized)
                    }
                }
            }
    }

    // ↓↓↓ 与 README「接入指南」第 2 步逐字一致 ↓↓↓
    private fun businessClient(server: FakeServer, auth: AuthClient) = HttpClient(MockEngine(server.handler)) {
        install(Auth) {
            bearer {
                loadTokens {
                    // refresh token 归本库管，插件不需要知道，传 null 即可
                    auth.accessToken()?.let { BearerTokens(it, null) }
                }
                refreshTokens {
                    when (val r = auth.refresh()) {
                        is RefreshOutcome.Success -> BearerTokens(r.tokens.accessToken, null)
                        else -> null // 放弃：请求把 401 返给调用方，导航交给第 4 步
                    }
                }
            }
        }
    }
    // ↑↑↑ 与 README「接入指南」第 2 步逐字一致 ↑↑↑

    @Test
    fun `两个业务 client 同时 401，服务端只被刷新一次`() = authTest {
        val server = FakeServer()
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val auth = AuthClient(BASE, store) {
            httpEngine = MockEngine(server.handler)
            timeoutMillis = TEST_TIMEOUT_MS
        }
        auth.restore()

        // App 里的两个 client：业务 API 与图片 CDN，各装各的 Auth 插件
        val api = businessClient(server, auth)
        val images = businessClient(server, auth)

        val responses = listOf(
            async { api.get("https://api.example.com/feed") },
            async { images.get("https://cdn.example.com/avatar") },
        ).awaitAll()

        assertTrue(
            responses.all { it.status == HttpStatusCode.OK },
            "插件应当在 401 后用新令牌重试原请求：${responses.map { it.status }}",
        )
        assertEquals(
            1,
            server.refreshCalls,
            "ktor 插件的单飞只覆盖单个 client，跨 client 收敛要靠本库的锁",
        )
        assertEquals("a1", auth.accessToken(), "刷新结果应当已落盘")
        assertEquals(AuthState.SignedIn, auth.authState.value)
    }

    @Test
    fun `绕过 auth_refresh 自己打刷新接口，服务端会被刷两次`() = authTest {
        // README 警告的那种错接法的**可执行反例**。它跑得通、功能完全正常、没有任何
        // 报错——用户什么都察觉不到，只是每次 token 过期偷偷烧一格服务端的救活配额
        // （1h/3 次，超了整条会话按盗用撤销）。
        //
        // 这个用例的作用是把上面那个正确用例的断言变得有意义：证明 refreshCalls == 1
        // 不是碰巧，而是确实由 `auth.refresh()` 那一层的锁挣来的。
        val server = FakeServer()
        val store = InMemoryTokenStore(TokenPair("a0", "r0"))
        val auth = AuthClient(BASE, store) {
            httpEngine = MockEngine(server.handler)
            timeoutMillis = TEST_TIMEOUT_MS
        }
        auth.restore()

        fun wrongClient() = HttpClient(MockEngine(server.handler)) {
            install(Auth) {
                bearer {
                    loadTokens { auth.accessToken()?.let { BearerTokens(it, null) } }
                    refreshTokens {
                        // ✗ 绕过本库，自己打刷新接口
                        val body = HttpClient(MockEngine(server.handler))
                            .post("$BASE/refresh").bodyAsText()
                        val token = Json.parseToJsonElement(body)
                            .jsonObject["accessToken"]!!.jsonPrimitive.content
                        BearerTokens(token, null)
                    }
                }
            }
        }

        listOf(
            async { wrongClient().get("https://api.example.com/feed") },
            async { wrongClient().get("https://cdn.example.com/avatar") },
        ).awaitAll()

        assertEquals(
            2,
            server.refreshCalls,
            "两个 client 各刷各的——ktor 插件的单飞覆盖不到跨 client，这正是要用 auth.refresh() 的原因",
        )
    }
}
