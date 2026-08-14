package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 邮件语言上报（protocol 1.3.0）。规则只有两条：不配 provider = 跟随系统；
 * 配了 provider = 有值用值、**没值（null/空/und）回落系统语言**。
 * 没有「关闭上报」的开关——想一律某语言就返回定值。
 */
class LocaleReportingTest {

    private class Sent {
        var body: String = ""
        /** 请求体里的 locale；字段缺席时为 null */
        fun locale(): String? =
            Json.parseToJsonElement(body).jsonObject["locale"]?.jsonPrimitive?.content
    }

    private fun clientReporting(sent: Sent, localeProvider: (() -> String?)? = null): AuthClient {
        val engine = MockEngine { request ->
            sent.body = (request.body as TextContent).text
            respond(
                """{"cooldownSeconds":60}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine)
        return if (localeProvider == null) {
            AuthClient(BASE_URL, InMemoryTokenStore(), http)
        } else {
            AuthClient(BASE_URL, InMemoryTokenStore(), http, localeProvider)
        }
    }

    @Test
    fun `provider 给定值就发定值`() = runTest {
        val sent = Sent()
        clientReporting(sent) { "zh-Hans" }.sendCode("u@x.com")
        assertEquals("zh-Hans", sent.locale())
    }

    @Test
    fun `不配 provider 时跟随系统语言`() = runTest {
        val sent = Sent()
        clientReporting(sent).sendCode("u@x.com")
        // 平台给不出语言时字段缺席（而非 null 值），两种情形一起断言
        assertEquals(platformLanguageTag(), sent.locale())
    }

    @Test
    fun `provider 返回 null 是「没意见」——回落系统语言而不是关闭上报`() = runTest {
        val sent = Sent()
        clientReporting(sent) { null }.sendCode("u@x.com")
        assertEquals(platformLanguageTag(), sent.locale())
    }

    @Test
    fun `provider 返回空串或 und 同样回落系统语言`() = runTest {
        val blank = Sent()
        clientReporting(blank) { "   " }.sendCode("u@x.com")
        assertEquals(platformLanguageTag(), blank.locale())

        val und = Sent()
        clientReporting(und) { "und" }.sendCode("u@x.com")
        assertEquals(platformLanguageTag(), und.locale())
    }

    @Test
    fun `想一律某语言就返回定值——这是「关闭跟随」的表达方式`() = runTest {
        val sent = Sent()
        clientReporting(sent) { "en" }.sendCode("u@x.com")
        assertEquals("en", sent.locale())
    }

    @Test
    fun `usableTag 把没有语言信息的值统一成 null`() {
        assertNull(null.usableTag())
        assertNull("".usableTag())
        assertNull("   ".usableTag())
        assertNull("und".usableTag())
        assertNull("UND".usableTag())
        assertNull("und-US".usableTag())
        assertEquals("zh-Hans-CN", " zh-Hans-CN ".usableTag())
        // 大小写不做归一：服务端会转小写，客户端保持 toLanguageTag() 的原样
        assertEquals("en-GB", "en-GB".usableTag())
    }

    private companion object {
        const val BASE_URL = "https://api.example.com/auth"
    }
}
