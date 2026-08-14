package wang.harlon.loginbase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 响应体取值的契约只有一条：**取不到就是 null，永不抛异常**。
 *
 * 服务端把某个字段的类型改了，不该让老客户端崩——这和 `Json { ignoreUnknownKeys }`
 * 是同一个立场，只是那条只管多出来的字段，管不了类型变了的字段。
 */
class JsonAccessTest {

    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `正常取值`() {
        val o = obj("""{"s":"hi","n":42,"b":true}""")
        assertEquals("hi", o.stringOrNull("s"))
        assertEquals(42, o.intOrNull("n"))
        assertEquals(true, o.booleanOrNull("b"))
    }

    @Test
    fun `字段缺失一律 null`() {
        val o = obj("""{}""")
        assertNull(o.stringOrNull("x"))
        assertNull(o.intOrNull("x"))
        assertNull(o.booleanOrNull("x"))
    }

    @Test
    fun `JSON null 按「没有」处理`() {
        // 不拦住的话 content 是字符串 "null"，"null".toInt() 会炸
        val o = obj("""{"x":null}""")
        assertNull(o.stringOrNull("x"))
        assertNull(o.intOrNull("x"))
        assertNull(o.booleanOrNull("x"))
    }

    @Test
    fun `字段变成对象或数组也不抛`() {
        // 老写法 `this["x"]?.jsonPrimitive` 在这里抛 IllegalStateException
        val o = obj("""{"o":{"a":1},"arr":[1,2]}""")
        assertNull(o.stringOrNull("o"))
        assertNull(o.intOrNull("o"))
        assertNull(o.booleanOrNull("arr"))
    }

    @Test
    fun `类型对不上返回 null 而不是抛`() {
        // 老写法 `.int` 在这里抛 NumberFormatException
        val o = obj("""{"notANumber":"abc","notABool":"yes","n":7}""")
        assertNull(o.intOrNull("notANumber"))
        assertNull(o.booleanOrNull("notABool"))
        assertNull(o.booleanOrNull("n"))
    }

    @Test
    fun `字符串字段收到数字时按字面量透传`() {
        // 协议里这些字段是字符串；真收到别的类型时，透传字面量比抛异常有用——
        // 排查的人能从 rawError 里看见服务端到底发了什么
        val o = obj("""{"error":123}""")
        assertEquals("123", o.stringOrNull("error"))
    }
}
