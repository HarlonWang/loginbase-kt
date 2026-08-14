package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * [AuthClient] 的可选配置，通过构造时的尾随 lambda 设置。
 *
 * ## 为什么选项不是构造函数参数
 *
 * Kotlin 的默认参数在 JVM 上编译成一个带 bitmask 的 synthetic 构造函数，
 * **往里加一个参数就破二进制兼容**——消费方不重新编译就是 `NoSuchMethodError`。
 * 本库要发 Maven Central，而加选项是必然的（logger、重试策略、超时值……），
 * 所以选项必须放在一个「加字段不改签名」的地方：给这个类加一个 `var`
 * 是二进制兼容的，给构造函数加一个参数不是。
 *
 * 这个类**刻意可变**——可变正是它换来兼容性的原因。[AuthClient] 在构造期就把值
 * 读成不可变字段，之后再改这个对象不影响已建好的 client。
 *
 * ## 为什么 baseUrl / tokenStore 不在这里
 *
 * 它们没有合理默认值。放进来就只能靠运行时检查（或 `lateinit`），把编译期就能挡住的
 * 错误推迟到运行时。故必填的留作位置参数，可选的进 DSL——ktor 的 `HttpClient(engine) {}`
 * 与 supabase-kt 的 `createSupabaseClient(url, key) {}` 都是这个分法。
 */
class LoginbaseConfig internal constructor() {

    /**
     * 注入自己的 [HttpClientEngine]——证书固定、代理、自定义 DNS、OkHttp 拦截器都在
     * 这一层配。缺省时由 ktor 从 classpath 上发现一个（Android: okhttp / iOS: darwin）。
     *
     * 传 engine，[HttpClient] 由本库自建，engine 仍归你所有（ktor 的
     * `HttpClient(engine)` 走 `manageEngine = false`，[AuthClient.close] 不会关掉它）。
     * 连接池是 engine 级的，所以这样并**不会**多出一个池。
     *
     * ## 为什么是 engine 而不是整个 `HttpClient`
     *
     * 注入整个 client 意味着本库最安全敏感的那条请求（`POST /refresh`）要跑在一套
     * **未知的插件**上。已知的两颗地雷：
     *
     * - ktor `Auth` 插件：401 时它的回调里再调 `refresh()`，而当前协程已持有单飞锁，
     *   `Mutex` 不可重入 → **永久挂起**
     * - ktor `HttpRequestRetry`：默认配置就是「5xx 与 IOException 重试 3 次」，
     *   于是单飞辛苦收敛成的 1 次刷新，在 client 内部被悄悄放大成 4 次。服务端若已
     *   消费掉那个 refresh token，就是 4 次救活判定，**一轮撞穿 1h/3 次护栏**、
     *   整条会话按盗用撤销
     *
     * 而注入真正值钱的能力（证书固定、代理、抓包）全在 engine 级——**交出 engine 就够了，
     * 不必交出 client**。代价只有消费方 client 级插件（主要是 ktor `Logging`）不再作用于
     * auth 请求；engine 级的拦截器照常生效。
     */
    var httpEngine: HttpClientEngine? = null

    /**
     * 验证码邮件用什么语言（protocol 1.3.0）。缺省跟随系统语言。
     *
     * App 内有自己的语言设置时设它，**返回 `null` 表示「我没意见」→ 回落系统语言**，
     * 不是「不要发」。想一律某种语言就返回定值，如 `{ "en" }`。
     */
    var localeProvider: () -> String? = Loginbase::appLanguageTag

    /**
     * 请求超时，见 [AuthClient.refresh] 对「别让锁永远握着」的说明。
     *
     * **`internal`，不对外开放**：本库自己建 client，超时行为因此是确定的，不需要
     * 消费方参与。存在的唯一理由是**让测试能把它调小**——否则一个「请求挂住」的用例
     * 要真等 [DEFAULT_TIMEOUT_MILLIS] 那么久。
     */
    internal var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS

    internal companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}
