package wang.harlon.loginbase

import io.ktor.client.HttpClient

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
     * 注入自己的 [HttpClient] 以复用连接池/日志；缺省时库自建。
     *
     * **不含 engine**——消费方 classpath 里要有（Android: okhttp / iOS: darwin），
     * 库不替你选（依赖最小集，且消费方通常已有）。
     *
     * ⚠️ 装了 ktor `Auth` 插件的那个 client **不要**注入进来：本库的 `POST /refresh`
     * 也会过该插件，服务端 401 时插件回调里再调 `refresh()` 会撞上不可重入的单飞锁。
     */
    var httpClient: HttpClient? = null

    /**
     * 验证码邮件用什么语言（protocol 1.3.0）。缺省跟随系统语言。
     *
     * App 内有自己的语言设置时设它，**返回 `null` 表示「我没意见」→ 回落系统语言**，
     * 不是「不要发」。想一律某种语言就返回定值，如 `{ "en" }`。
     */
    var localeProvider: () -> String? = Loginbase::appLanguageTag

    /**
     * 单飞锁保险丝的时长，见 [AuthClient.refresh]。**`internal`，不对外开放**——
     * 它的职责是「别让锁永远握着」，不是替消费方决定超时值，那件事该由消费方在自己的
     * `HttpClient` 上配 `requestTimeoutMillis`。
     *
     * 存在的唯一理由是**让测试能把它调小**：保险丝走 `withTimeout`，用的是协程的时钟，
     * 测试里必须跑在真实时钟上（`runTest` 的虚拟时钟会直接跳过 45 秒），若不能调小，
     * 一个熔断用例就要真等 45 秒。
     */
    internal var lockFuseMillis: Long = DEFAULT_LOCK_FUSE_MILLIS

    internal companion object {
        // 刻意宽松：它是兜底，不是超时策略
        const val DEFAULT_LOCK_FUSE_MILLIS: Long = 45_000L
    }
}
