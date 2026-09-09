package wang.harlon.loginbase

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 客户端标识（protocol 1.9.0）：配了 [LoginbaseConfig.client] 就每个请求带头、start URL 带参数；
 * 不配就一个字都不发（服务端按「未上报世代」处理）。版本 / 平台只走结构化字段，UA 只是附带。
 */
class ClientIdentityTest {

    private class Sent {
        var request: HttpRequestData? = null
    }

    private fun client(sent: Sent, info: ClientInfo?): AuthClient {
        val engine = MockEngine { request ->
            sent.request = request
            respond("""{"cooldownSeconds":60}""", HttpStatusCode.OK, jsonHeaders())
        }
        return AuthClient(BASE, InMemoryTokenStore()) {
            httpEngine = engine
            client = info
        }
    }

    private val info = ClientInfo("TestApp", "1.5.0", ClientPlatform.ANDROID, "Android 14; Pixel 7")

    @Test
    fun `配了 client 就每个请求带两个头与 UA`() = runTest {
        val sent = Sent()
        client(sent, info).sendCode("u@x.com")
        val headers = sent.request!!.headers
        assertEquals("1.5.0", headers[CLIENT_VERSION_HEADER])
        assertEquals("android", headers[CLIENT_PLATFORM_HEADER])
        assertEquals("TestApp/1.5.0 (Android 14; Pixel 7) loginbase-kt/$LIBRARY_VERSION", headers[HttpHeaders.UserAgent])
    }

    @Test
    fun `不配 client 一个字都不发`() = runTest {
        val sent = Sent()
        client(sent, null).sendCode("u@x.com")
        val headers = sent.request!!.headers
        assertNull(headers[CLIENT_VERSION_HEADER])
        assertNull(headers[CLIENT_PLATFORM_HEADER])
    }

    @Test
    fun `signInUrl 只在配了 client 时带版本与平台参数`() {
        val with = client(Sent(), info).signInUrl(OAuthProvider("github"), "app:/cb")
        assertEquals("$BASE/oauth/github/start?redirect=app%3A%2Fcb&client_version=1.5.0&client_platform=android", with)
        val without = client(Sent(), null).signInUrl(OAuthProvider("github"), "app:/cb")
        assertEquals("$BASE/oauth/github/start?redirect=app%3A%2Fcb", without)
    }

    @Test
    fun `deviceInfo 缺省时 UA 没有括号段`() {
        val ua = ClientInfo("TestApp", "2.0", ClientPlatform.IOS).userAgent()
        assertTrue(ua.startsWith("TestApp/2.0 loginbase-kt/"), ua)
    }

    @Test
    fun `不合规的版本、appName 与 deviceInfo 在构造期就拦下`() {
        assertFailsWith<IllegalArgumentException> { ClientInfo("TestApp", "1.5.0 beta", ClientPlatform.ANDROID) }
        assertFailsWith<IllegalArgumentException> { ClientInfo("Test App", "1.5.0", ClientPlatform.ANDROID) }
        assertFailsWith<IllegalArgumentException> { ClientInfo("TestApp", "1.5.0", ClientPlatform.ANDROID, "a (b)") }
    }
}
