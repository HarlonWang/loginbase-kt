package wang.harlon.loginbase

/**
 * 平台实现，**不对外**——对外的是 [Loginbase.appLanguageTag]。
 *
 * expect/actual 只能是顶层声明（object 成员要写成 `expect object`，得在每个平台
 * 重复一遍 object 本身），所以这里保留顶层形态但降 `internal`，由 [Loginbase]
 * 转发一层。这样公开面上就只有一个 object 成员，将来演进不受顶层函数的束缚。
 *
 * 契约：取不到返回 `null`（已过 [usableTag]），消费方可据此拼回落链。
 */
internal expect fun platformLanguageTag(): String?

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
