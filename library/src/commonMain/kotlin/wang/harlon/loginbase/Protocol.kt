package wang.harlon.loginbase

/**
 * 本客户端实现的服务端协议版本。
 *
 * 协议的唯一权威是**服务端仓**的 `docs/protocol.md`（https://github.com/HarlonWang/loginbase），
 * 本仓不留副本。分仓后两端版本线独立——客户端有自己的版本号，靠这个常量声明
 * 「我实现的是哪一版协议」，两端版本号不追求相等。
 *
 * **刻意不是 `const val`**：`const` 是编译期常量，取值会被**内联进调用方的字节码**。
 * 消费方升级本库却不重新编译时，读到的仍是编译当时那个版本号——恰好毁掉这个常量
 * 存在的唯一理由。普通 `val` 走 getter，永远反映实际链接上的那个版本。
 */
val PROTOCOL_VERSION: String = "1.3.0"

/**
 * 协议错误码（`{"error": "..."}` 的取值）。与 protocol.md 的错误码总表一一对应，
 * 契约测试据此断言；[UNKNOWN] 兜住服务端将来新增而客户端尚未认识的码——
 * 客户端不能因为多了一个错误码就崩，未知一律按通用失败处理。
 *
 * `wire` 与 `fromWire` 是 `internal`：它们是**解码器**，只有本库解析响应时用得上。
 * 公开它们等于把服务端的 wire 字符串写进本库的对外契约——服务端换个错误码字符串
 * 就成了客户端的破坏性变更，而那本该是纯粹的实现细节。消费方要原始串的话，
 * [LoginbaseException.Api.rawError] 已经给了。
 */
enum class AuthError(internal val wire: String) {
    /** code/send：邮箱格式非法 */
    INVALID_EMAIL("invalid_email"),

    /** code/send：限流命中，响应带 retryAfterSeconds */
    TOO_MANY_REQUESTS("too_many_requests"),

    /** code/verify：无有效验证码（未发过 / 已过期 / 已焚毁） */
    CODE_EXPIRED("code_expired"),

    /** code/verify：验证码不匹配。注意协议**不返回剩余尝试次数**，UI 文案不得写「还可再试 N 次」 */
    INVALID_CODE("invalid_code"),

    /** code/verify：累计 5 次错误，码即焚，需重新发码 */
    TOO_MANY_ATTEMPTS("too_many_attempts"),

    /** refresh：登录态终结，附带 [RefreshFailure] 归因 */
    INVALID_REFRESH_TOKEN("invalid_refresh_token"),

    /** oauth start / link start：redirect 缺失或不在服务端白名单 */
    INVALID_REDIRECT("invalid_redirect"),

    /** oauth callback：state 缺失 / 无效 / 已使用 */
    INVALID_STATE("invalid_state"),

    /** oauth exchange：otc 无效 / 已使用 / 过期（60s、单次） */
    INVALID_OTC("invalid_otc"),

    /** oauth 端点：服务端未配置该插件（link 端点还需 onLinked 已提供） */
    NOT_CONFIGURED("not_configured"),

    /** 服务端内部错误 */
    INTERNAL("internal"),

    /** 服务端返回了本客户端尚未认识的错误码 */
    UNKNOWN("");

    internal companion object {
        internal fun fromWire(wire: String): AuthError =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * refresh 失败的归因（`invalid_refresh_token` 的 `reason` 字段）。
 *
 * 除非实现了与服务端救活机制配合的重试，**均应视为登录态终结、引导重新登录**。
 * 服务端对丢回执（客户端没存住新 token 就断了）有救活判定，所以客户端的
 * 单飞 refresh 纪律很重要——并发刷新会消耗救活配额（1h/3 次护栏）。
 *
 * `wire` / `fromWire` 为何是 `internal`：同 [AuthError]。
 */
enum class RefreshFailure(internal val wire: String) {
    /** 请求体缺 refreshToken */
    MISSING_TOKEN("missing_token"),

    /** token 无对应会话（伪造 / 已清理） */
    SESSION_NOT_FOUND("session_not_found"),

    /** 重用检测触发（真重用或救活护栏超限），整条 family 已撤销 */
    SESSION_REVOKED("session_revoked"),

    /** 会话已过 refreshTtlMs（服务端配置了才会出现） */
    SESSION_EXPIRED("session_expired"),

    /** 轮换内部失败（罕见） */
    ROTATE_FAILED("rotate_failed"),

    /** 服务端返回了本客户端尚未认识的归因 */
    UNKNOWN("");

    internal companion object {
        internal fun fromWire(wire: String): RefreshFailure =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}
