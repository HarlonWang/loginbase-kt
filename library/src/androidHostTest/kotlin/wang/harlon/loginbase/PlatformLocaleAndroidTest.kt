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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Android 侧平台取值。放在 host test 是因为这里能真的改 `Locale.getDefault()`——
 * commonTest 里没法构造「系统给不出语言」这个状态。
 */
class PlatformLocaleAndroidTest {

    private inline fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    private suspend fun sendCodeBody(): JsonObject {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond(
                """{"cooldownSeconds":60}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AuthClient("https://api.example.com/auth", InMemoryTokenStore(), HttpClient(engine))
            .sendCode("u@x.com")
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `跟随 Locale getDefault 并给出 BCP 47 形态`() {
        withDefaultLocale(Locale.SIMPLIFIED_CHINESE) {
            // toString() 会给 zh_CN（下划线，非 BCP 47），必须是 toLanguageTag()
            assertEquals("zh-CN", platformLanguageTag())
        }
    }

    @Test
    fun `系统语言取不到时不发 locale 字段（而不是发 und 或 null）`() = runTest {
        withDefaultLocale(Locale.ROOT) {
            // Locale.ROOT.toLanguageTag() == "und"
            assertNull(platformLanguageTag())
            val body = sendCodeBody()
            assertFalse("locale" in body, "取不到语言时 locale 字段应整个缺席：$body")
        }
    }
}
