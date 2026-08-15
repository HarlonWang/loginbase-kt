package wang.harlon.loginbase

/**
 * 平台实现，不对外——对外的是 [Loginbase.appLanguageTag]（expect/actual 只能是
 * 顶层声明，故顶层 `internal` + object 转发）。契约：取不到返回 `null`（已过 [usableTag]）。
 */
internal expect fun platformLanguageTag(): String?

/**
 * 空白与 `und`（未确定语言）一律视为「没有语言信息」。不把 `und` 发出去：会把
 * 「取不到」伪装成「传了不支持的语言」，污染服务端对客户端上报健康度的观测。
 */
internal fun String?.usableTag(): String? {
    val tag = this?.trim().orEmpty()
    if (tag.isEmpty()) return null
    val lower = tag.lowercase()
    if (lower == "und" || lower.startsWith("und-")) return null
    return tag
}
