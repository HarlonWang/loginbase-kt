package wang.harlon.loginbase

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 响应体取值。**一个字段取不到就是 `null`，永不抛异常。**
 *
 * 不引 `ContentNegotiation` 是本库的依赖红线（见 README「设计红线」），但**手工解析
 * 不等于不封装**。此前 `body["x"]?.jsonPrimitive?.int` 这类写法散在六处，各自都有两个
 * 抛点：
 *
 * - `jsonPrimitive` 在元素不是 primitive 时抛 `IllegalStateException`（服务端把某字段
 *   改成对象或数组就中招）
 * - `.int` / `.boolean` 在内容不是那个类型时抛 `NumberFormatException` 之类
 *
 * 最别扭的一处在错误响应的解析里：`retryAfterSeconds` 类型不对时，异常在**构造
 * `LoginbaseException.Api` 的参数求值期**抛出，把真正的 API 错误整个掩盖掉——调用方
 * 看到的是 `NumberFormatException`，而不是「限流了」。
 *
 * 这与 `Json { ignoreUnknownKeys = true }` 想表达的立场（服务端加字段不该炸老客户端）
 * 是同一个道理，只是那条只管多出来的字段，管不了类型变了的字段。
 *
 * `JsonNull` 一律按「没有」处理：它的 `content` 是字符串 `"null"`，不拦住的话
 * `"null".toInt()` 会炸。
 */
private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/**
 * 取字符串；字段缺失、是 `null`、或不是 primitive 时返回 `null`。
 *
 * 数字/布尔也会按其字面量取到（`content` 本就是未加引号的原文）——协议里这几个字段
 * 都是字符串，真收到别的类型时按字面量透传比抛异常有用：`rawError` 里能看见服务端
 * 到底发了什么。
 */
internal fun JsonObject.stringOrNull(key: String): String? =
    primitiveOrNull(key)?.content

/** 取整数；取不到或内容不是整数时返回 `null`，**不抛**。 */
internal fun JsonObject.intOrNull(key: String): Int? =
    primitiveOrNull(key)?.content?.toIntOrNull()

/** 取布尔；取不到或内容不是 `true`/`false` 时返回 `null`，**不抛**。 */
internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    when (primitiveOrNull(key)?.content?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
