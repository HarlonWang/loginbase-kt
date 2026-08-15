package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * [AuthClient] 的可选配置，构造时的尾随 lambda 设置。
 *
 * 选项放这里而不是构造参数：往默认参数构造函数里加参数会破二进制兼容，
 * 给本类加 `var` 不会。类**刻意可变**；[AuthClient] 构造期即读成不可变字段，
 * 事后再改无效。必填项（baseUrl / tokenStore）没有合理默认值，留在位置参数由编译期挡错。
 */
class LoginbaseConfig internal constructor() {

    /**
     * 注入自己的 [HttpClientEngine]（证书固定、代理、拦截器都在 engine 级配），
     * 缺省由 ktor 从 classpath 发现。engine 归你所有，[AuthClient.close] 不会关它。
     *
     * 只收 engine、不收整个 `HttpClient`：注入 client 会让 `POST /refresh` 跑在
     * 未知插件上（ktor `Auth` 重入死锁、`HttpRequestRetry` 把一次刷新放大成多次）。
     * 完整论证见 docs/design.md 第 3 节。
     */
    var httpEngine: HttpClientEngine? = null

    /**
     * 验证码邮件用什么语言（protocol 1.3.0）。缺省跟随系统语言。
     *
     * App 内有自己的语言设置时设它，**返回 `null` 表示「我没意见」→ 回落系统语言**，
     * 不是「不要发」。想一律某种语言就返回定值，如 `{ "en" }`。
     */
    var localeProvider: () -> String? = Loginbase::appLanguageTag

    /** `internal`：存在的唯一理由是让测试把它调小。本库自建 client，超时行为不需要消费方参与。 */
    internal var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS

    internal companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}
