package wang.harlon.loginbase

import io.ktor.client.engine.HttpClientEngine

/**
 * [AuthClient] 的可选配置。选项放这而非构造参数：加构造参数破二进制兼容，加 `var` 不会。
 * [AuthClient] 构造期即读走，事后再改无效。
 */
class LoginbaseConfig internal constructor() {

    /**
     * 注入自己的 [HttpClientEngine]，缺省由 ktor 从 classpath 发现；engine 归你所有。
     * 只收 engine 不收整个 `HttpClient`——注入 client 会让 refresh 跑在未知插件上
     * （docs/design.md 第 3 节）。
     */
    var httpEngine: HttpClientEngine? = null

    /**
     * 验证码邮件的语言，缺省跟随系统。返回 `null` = 「我没意见」→ 回落系统语言，
     * 不是「不要发」。
     */
    var localeProvider: () -> String? = Loginbase::appLanguageTag

    /** `internal`：存在的唯一理由是让测试把它调小。本库自建 client，超时行为不需要消费方参与。 */
    internal var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS

    internal companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}
