package wang.harlon.loginbase

/**
 * 本库抛出的所有异常的根，**包括传输层失败**——ktor 是实现细节，不该逼调用方去
 * catch `IOException`；原始异常在 `cause` 里。sealed：新增一类失败让调用方的穷尽
 * `when` 编译失败，这是刻意的。协程取消不在此列：`CancellationException` 必须原样穿透。
 */
sealed class LoginbaseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * 服务端按协议明确拒绝（`{"error": ...}`）。响应形状对不上是 [MalformedResponse]，
     * 没连上是 [Network]——三者处置方式不同，不能混成一类。
     */
    class Api(
        val status: Int,
        val error: AuthError,
        /** `too_many_requests` 才有：还要等多少秒 */
        val retryAfterSeconds: Int? = null,
        /** `invalid_refresh_token` 才有 */
        val refreshFailure: RefreshFailure? = null,
        /** 服务端原始 error 串，便于排查客户端尚未认识的新错误码 */
        val rawError: String = error.wire,
    ) : LoginbaseException("loginbase api error: $rawError (HTTP $status)")

    /**
     * 传输层失败（连不上、超时、TLS……）。含义是「这次没打通，会话本身没有结论」——
     * **不要据此清会话**。
     */
    class Network(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase network failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /**
     * HTTP 说成功但响应体不是协议形状。与 [Api] 分开：那是「服务端说不行」，
     * 这是「两端对不上」——重试无用，该报给开发者。
     */
    class MalformedResponse(
        /** 缺失或无法解析的字段 */
        val field: String,
    ) : LoginbaseException("loginbase malformed response: missing or invalid `$field`")

    /**
     * [TokenStore] 实现抛了异常。单列是因为后果具体：刷新拿到的新令牌**没存住**，
     * 必须当刷新失败处理，否则下次拿旧令牌去刷会白耗服务端救活配额。
     */
    class Storage(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase token store failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /**
     * 需要已登录的操作在无会话时抛出。独立类型：UI 与实际会话可能短暂不同步，
     * 调用方需要能单独 catch 并引导登录。
     */
    class NotAuthenticated(
        message: String = "operation requires an authenticated session",
    ) : LoginbaseException(message)
}
