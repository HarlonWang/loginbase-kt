package wang.harlon.loginbase

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 响应体取值的统一收口：**取不到就是 `null`，永不抛**——服务端字段类型变化不该炸
 * 老客户端，更不该在构造异常的参数求值期把真正的错误掩盖掉。
 * `JsonNull` 按「没有」处理（其 `content` 是字符串 `"null"`，会毒害 `toInt()`）。
 */
private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/**
 * 取字符串；取不到返回 `null`。数字/布尔按字面量透传——`rawError` 里能看见服务端
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
