package wang.harlon.loginbase

/** 库级工具入口：只放不需要 [AuthClient] 实例的东西（需要实例状态的是 AuthClient 成员）。 */
object Loginbase {

    /**
     * 当前 App 显示给用户的语言（BCP 47）；取不到返回 `null`。
     * 是 [LoginbaseConfig.localeProvider] 的缺省值，也可用来拼自己的回落链。
     */
    fun appLanguageTag(): String? = platformLanguageTag()
}
