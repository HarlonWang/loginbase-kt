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

    /** 服务端按协议明确拒绝（`{"error": ...}`）；形状对不上是 [MalformedResponse]，没连上是 [Network]。 */
    class Api(
        val status: Int,
        val error: AuthError,
        /** `too_many_requests` 才有 */
        val retryAfterSeconds: Int? = null,
        /** 服务端原始 error 串，排查未认识的新错误码用 */
        val rawError: String = error.wire,
    ) : LoginbaseException("loginbase api error: $rawError (HTTP $status)")

    /** 传输层失败。含义是「这次没打通，会话本身没有结论」——**不要据此清会话**。 */
    class Network(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase network failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /** HTTP 说成功但响应体不是协议形状——重试无用，该报给开发者。 */
    class MalformedResponse(
        val field: String,
    ) : LoginbaseException("loginbase malformed response: missing or invalid `$field`")

    /** [TokenStore] 抛了异常——新令牌**没存住**，必须当刷新失败处理。 */
    class Storage(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase token store failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /** 需要已登录的操作在无会话时抛出；单独 catch 并引导登录。 */
    class NotAuthenticated(
        message: String = "operation requires an authenticated session",
    ) : LoginbaseException(message)
}
