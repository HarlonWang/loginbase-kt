package wang.harlon.loginbase

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

internal const val BASE = "https://api.example.com/auth"

internal fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

/** 超时在测试里调小，否则「请求挂住」的用例要真等 15 秒 */
internal const val TEST_TIMEOUT_MS = 2_000L

internal fun clientWith(
    store: TokenStore,
    handler: MockRequestHandler,
): Pair<AuthClient, MockEngine> {
    val engine = MockEngine(handler)
    return AuthClient(BASE, store) {
        httpEngine = engine
        timeoutMillis = TEST_TIMEOUT_MS
    } to engine
}

/**
 * 跑在**真实时钟**上的测试。
 *
 * 库的超时走协程的时钟，而 `runTest` 的虚拟时钟在测试协程一挂起时就会把时间跳到下一个
 * 定时事件——MockEngine 又跑在真实 dispatcher 上，于是每个请求都会「瞬间」超时。切到
 * 真实 dispatcher 后行为与生产一致，副产品是并发调用真的并行，单飞逻辑得到更硬的验证。
 */
internal fun authTest(body: suspend CoroutineScope.() -> Unit) = runTest {
    withContext(Dispatchers.Default) { body() }
}
