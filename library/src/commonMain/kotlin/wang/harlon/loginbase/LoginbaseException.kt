package wang.harlon.loginbase

/**
 * 本库抛出的所有异常的根。
 *
 * 存在的理由有两条：
 *
 * 1. **调用方要能一网打尽**。此前 `AuthApiException` 与 `NotAuthenticatedException`
 *    各自直接继承 `Exception`，写不出 `catch (e: LoginbaseException)`；再算上传输层
 *    失败是裸的 ktor 异常，调用方为了接住「这次登录没成」得同时认识三套不相干的类型。
 * 2. **不把 ktor 泄漏进公开契约**。engine 由消费方提供、ktor 只是本库的实现细节，
 *    却让调用方去 catch `IOException` / `HttpRequestTimeoutException`，等于把实现细节
 *    写成了对外承诺——将来换传输层就是一次 breaking change。故传输层异常统一包成
 *    [Network]，原始异常放在 `cause` 里，要排查的照样拿得到。
 *
 * 用 sealed：新增一类失败会让调用方已有的穷尽 `when` 编译失败。这是刻意的——
 * 多一种失败就是多一种要想清楚怎么处置的情况，不该悄悄溜过去。
 *
 * **协程取消不在这里**：`CancellationException` 不是失败，必须原样穿透，
 * 否则破坏结构化并发。
 */
sealed class LoginbaseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * 服务端按协议返回的错误（`{"error": ...}`）。
     *
     * 只有服务端**明确拒绝**才是这个：响应形状对不上是 [MalformedResponse]，
     * 根本没连上是 [Network]。三者的处置方式完全不同，所以不能混成一类。
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
     * 传输层失败：连不上、超时、TLS、被中间设备掐断……
     *
     * 对调用方的含义是「这次没打通，会话本身没有任何结论」——**不要据此清会话**，
     * 弱网用户（漫游、地铁）会被无谓踢下线。
     */
    class Network(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase network failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /**
     * HTTP 说成功，但响应体不是协议约定的形状（不是 JSON、缺字段、字段类型不对）。
     *
     * 与 [Api] 分开是因为处置方式不同：[Api] 是「服务端说不行」，调用方按错误码给用户
     * 提示；这个是「两端对不上」，用户重试多少次都一样，该报给开发者。
     */
    class MalformedResponse(
        /** 缺失或无法解析的字段 */
        val field: String,
    ) : LoginbaseException("loginbase malformed response: missing or invalid `$field`")

    /**
     * [TokenStore] 实现抛了异常。
     *
     * 单列一类是因为它有个很具体的后果：刷新拿到的新令牌**没存住**。此时必须当作刷新
     * 失败（见 [AuthClient.refresh]），否则下次会拿旧令牌去刷、白白消耗服务端的救活配额。
     */
    class Storage(
        cause: Throwable,
    ) : LoginbaseException(
        "loginbase token store failure: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /**
     * 需要已登录的操作（如绑定第二身份）在无会话时抛出。
     *
     * 独立类型而非 `IllegalStateException`：未登录时点「绑定第三方账号」不一定是编程
     * 错误——UI 状态与实际会话可能短暂不同步——调用方需要能单独 catch 它并引导登录。
     * 也不用返回 null 表达：那会和「网络失败」混在一起无法区分。
     */
    class NotAuthenticated(
        message: String = "operation requires an authenticated session",
    ) : LoginbaseException(message)
}
