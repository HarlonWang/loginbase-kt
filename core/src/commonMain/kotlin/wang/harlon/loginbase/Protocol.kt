package wang.harlon.loginbase

/**
 * 本客户端实现的服务端协议版本。协议权威是服务端仓的 `docs/protocol.md`，本仓不留副本。
 *
 * **刻意不是 `const val`**：`const` 会被内联进消费方字节码，升级本库不重编译时读到旧值，
 * 恰好毁掉本常量的用途。写成显式 getter 而非带 backing field 的 `val`，是为了让
 * 「不可内联」在语法上就成立——IDE 不会再劝改 const，也就没人会顺手采纳。
 */
val PROTOCOL_VERSION: String get() = "1.8.0"

/**
 * 协议错误码（`{"error": "..."}` 的取值），与 protocol.md 错误码总表一一对应；
 * [UNKNOWN] 兜住服务端新增而客户端尚未认识的码。
 *
 * `wire` / `fromWire` 是 `internal`：公开等于把服务端 wire 串写进对外契约。
 * 消费方要原始串用 [LoginbaseException.Api.rawError]。
 */
enum class AuthError(internal val wire: String) {
    INVALID_EMAIL("invalid_email"),

    TOO_MANY_REQUESTS("too_many_requests"),

    CODE_EXPIRED("code_expired"),

    /** 协议不返回剩余尝试次数，UI 文案不得写「还可再试 N 次」 */
    INVALID_CODE("invalid_code"),

    /** 码已焚毁，需重新发码 */
    TOO_MANY_ATTEMPTS("too_many_attempts"),

    /** 登录态终结，附带 [RefreshFailure] 归因 */
    INVALID_REFRESH_TOKEN("invalid_refresh_token"),

    INVALID_REDIRECT("invalid_redirect"),

    INVALID_STATE("invalid_state"),

    INVALID_OTC("invalid_otc"),

    NOT_CONFIGURED("not_configured"),

    INTERNAL("internal"),

    UNKNOWN("");

    internal companion object {
        internal fun fromWire(wire: String): AuthError =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * refresh 失败的归因（`invalid_refresh_token` 的 `reason` 字段）。
 * 均应视为登录态终结、引导重新登录。`wire` / `fromWire` 的 `internal` 同 [AuthError]。
 */
enum class RefreshFailure(internal val wire: String) {
    MISSING_TOKEN("missing_token"),

    SESSION_NOT_FOUND("session_not_found"),

    SESSION_REVOKED("session_revoked"),

    SESSION_EXPIRED("session_expired"),

    ROTATE_FAILED("rotate_failed"),

    UNKNOWN("");

    internal companion object {
        internal fun fromWire(wire: String): RefreshFailure =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}
