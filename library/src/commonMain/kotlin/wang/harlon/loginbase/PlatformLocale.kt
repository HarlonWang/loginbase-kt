package wang.harlon.loginbase

/**
 * 当前**这个 App 显示给用户的语言**，BCP 47 标签（如 `zh-Hans-CN`）；取不到返回 null。
 *
 * 是 [AuthClient] 的 `localeProvider` 缺省值，也可被消费方复用来拼自己的回落链。
 *
 * 取的是「App 显示语言」而非「系统首选语言」：这件事的起点就是 App UI 与验证码邮件
 * 语言割裂（App 英文、邮件中文），对齐用户眼睛看到的那个语言才有意义。
 */
expect fun platformLanguageTag(): String?

/**
 * 空白与 `und`（BCP 47 的「未确定语言」）一律视为「没有语言信息」。
 *
 * 不把 `und` 发出去：服务端虽然也把它当未传，但那样事件里会留下
 * 「传了一个不支持的语言」的痕迹，把「取不到语言」伪装成「要了门服务端没有的语言」，
 * 污染「客户端是否在正常上报」的观测口径。
 */
internal fun String?.usableTag(): String? {
    val tag = this?.trim().orEmpty()
    if (tag.isEmpty()) return null
    val lower = tag.lowercase()
    if (lower == "und" || lower.startsWith("und-")) return null
    return tag
}
