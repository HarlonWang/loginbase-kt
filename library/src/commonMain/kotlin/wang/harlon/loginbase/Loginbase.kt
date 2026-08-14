package wang.harlon.loginbase

/**
 * 库级工具的入口。
 *
 * 存在的理由是**给公开面留腾挪余地**：顶层函数一旦发版就固定死了——想加参数、想换成
 * 接口、想改名，每一步都是破坏性变更，而且它还占着包的顶层命名空间。挂在 object 下
 * 之后，这些演进至少还有加重载、加 `@Deprecated` 过渡的余地。
 *
 * 这里只放**不需要 [AuthClient] 实例**的东西；需要实例状态的一律是 `AuthClient` 的成员。
 */
object Loginbase {

    /**
     * 当前**这个 App 显示给用户的语言**，BCP 47 标签（如 `zh-Hans-CN`）；取不到返回 `null`。
     *
     * 是 [LoginbaseConfig.localeProvider] 的缺省值，也可被消费方复用来拼自己的回落链：
     *
     * ```kotlin
     * localeProvider = { settings.languageTag ?: Loginbase.appLanguageTag() }
     * ```
     *
     * 取的是「App 显示语言」而非「系统首选语言」：这件事的起点就是 App UI 与验证码邮件
     * 语言割裂（App 英文、邮件中文），对齐用户眼睛看到的那个语言才有意义。
     *
     * 平台取值：Android `Locale.getDefault().toLanguageTag()`（已跟随 per-app language）；
     * iOS `NSBundle.mainBundle.preferredLocalizations.first`。
     */
    fun appLanguageTag(): String? = platformLanguageTag()
}
