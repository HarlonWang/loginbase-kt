package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * loginbase 客户端。协议权威见服务端仓 `docs/protocol.md`；本类实现 [PROTOCOL_VERSION]。
 *
 * ## 请把它当单例持有
 *
 * 单飞 refresh 的互斥锁是**实例字段**——两个实例就是两把锁，并发刷新会同时打到服务端，
 * 正是要避免的事。Logto 时代的教训更狠：那时 client 跟着 Activity 重建，旧实例的在途
 * 刷新与新实例互不互斥，轮换竞态跨实例复活。所以：**一个 App 一个 AuthClient**。
 *
 * ## 为什么不解析 JWT
 *
 * 库拿到 access token 后原样存、原样用，不读 `exp`、不校验签名。Logto 时代客户端要
 * 本地校验 id_token 的 `iat`/`exp`，设备时钟偏差超过容差就直接登录失败、重试无用
 * （2026-07-22 实测过模拟器慢 63 秒的坑）。改由服务端的 401 驱动刷新后，**时钟偏差
 * 这一整类问题在客户端消失了**——代价是过期时多一次往返，换来的是没有本地时间依赖。
 *
 * 用法：业务请求带 [accessToken] 的返回值，收到 401 就 `accessToken(forceRefresh = true)`
 * 再重试一次；仍失败则看 [refresh] 的结果决定是否引导重新登录。
 *
 * @param baseUrl 服务端挂载点，如 `https://api.example.com/auth`（末尾斜杠会被去掉）
 * @param tokenStore 令牌持久化，见 [TokenStore] 对同步落盘的要求
 * @param httpClient 可注入自己的实例以复用连接池/日志；缺省时库自建。
 *   **不含 engine**——消费方 classpath 里要有（Android: okhttp / iOS: darwin），
 *   库不替你选（依赖最小集，且消费方通常已有）。
 * @param localeProvider 验证码邮件用什么语言（protocol 1.3.0）。缺省跟随系统语言。
 *   App 内有自己的语言设置时传它，**返回 `null` 表示「我没意见」→ 回落系统语言**，
 *   不是「不要发」。想一律某种语言就返回定值，如 `{ "en" }`。
 */
public class AuthClient(
    baseUrl: String,
    private val tokenStore: TokenStore,
    httpClient: HttpClient? = null,
    private val localeProvider: () -> String? = ::platformLanguageTag,
) {
    private val base: String = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true // 服务端加字段不该炸老客户端
        encodeDefaults = true
    }

    // 请求体手工序列化、响应手工解析——故不需要 ContentNegotiation 插件：
    // 少两个 ktor 依赖，且注入的 HttpClient 无需任何插件配置即可工作。
    //
    // 惰性初始化：engine 由消费方提供，无参构造 HttpClient 在 classpath 没有 engine 时
    // 会直接抛异常。不发 HTTP 的 API（githubSignInUrl 拼串、restore 读存储）不该被这个
    // 要求连坐——真正发请求时才需要 engine。
    private val http: HttpClient by lazy {
        httpClient ?: HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    /** 刷新互斥：见 [refresh] 对单飞的说明 */
    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    public val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** 从存储恢复登录态。App 启动时调一次，之后 [authState] 才有意义。 */
    public suspend fun restore(): AuthState {
        val state = if (tokenStore.load() != null) AuthState.SignedIn else AuthState.SignedOut
        _authState.value = state
        return state
    }

    // ---- 邮箱验证码 ----

    /**
     * `POST /code/send`。无论账号是否存在都成功（防枚举）。
     *
     * 邮件语言由 [localeProvider] 决定并随请求上报（protocol 1.3.0）；服务端对未知语言
     * **静默回落**，故这里不存在与语言相关的错误分支。取不到语言时字段整个省略——
     * 不发 `und`：服务端虽然也把它当未传，但省略字段才能让服务端事件区分
     * 「客户端没传」与「传了但不支持」，那是排查客户端链路故障的唯一信号。
     */
    public suspend fun sendCode(email: String): SendCodeResult {
        val payload = buildMap {
            put("email", email)
            resolveLocale()?.let { put("locale", it) }
        }
        val body = request("$base/code/send", payload)
        return SendCodeResult(
            cooldownSeconds = body["cooldownSeconds"]?.jsonPrimitive?.int ?: DEFAULT_COOLDOWN
        )
    }

    // provider 返回 null/空/und 一律是「我没意见」→ 回落系统语言；系统也给不出才真的不发。
    // 每次现取不缓存：用户可能刚在 App 里切了语言。
    //
    // 只对 provider 的返回值做 usableTag()——[platformLanguageTag] 的契约里已经保证
    // 「取不到返回 null」，这条保证是给消费方拼回落链用的（`settings.tag ?: platformLanguageTag()`），
    // 不能挪到这里来，否则那种写法会把 "und" 原样发出去。
    private fun resolveLocale(): String? =
        localeProvider().usableTag() ?: platformLanguageTag()

    /** `POST /code/verify`，成功即建立会话并落盘。 */
    public suspend fun verifyCode(email: String, code: String): AuthSession =
        request("$base/code/verify", mapOf("email" to email, "code" to code))
            .toSession()
            .also { persist(it.tokens) }

    // ---- GitHub OAuth ----

    /**
     * 拼登录用的授权入口，交给**系统浏览器**打开（不要用内置 WebView：OAuth 授权页
     * 需要用户能看见地址栏，且 GitHub 对嵌入式 WebView 有限制）。
     *
     * 授权完成后 GitHub 回跳到 [redirect]，参数带 `otc`，再调 [exchangeOtc] 换令牌。
     * 令牌不进 URL——otc 60 秒单次有效，即便被日志记下也只有一次兑换窗口。
     */
    public fun githubSignInUrl(redirect: String): String =
        "$base/oauth/github/start?redirect=${redirect.encodeURLParameter()}"

    /** `POST /oauth/exchange`：拿 deepLink 回来的 otc 换令牌对，成功即落盘。 */
    public suspend fun exchangeOtc(otc: String): AuthSession =
        request("$base/oauth/exchange", mapOf("otc" to otc))
            .toSession()
            .also { persist(it.tokens) }

    /**
     * `POST /oauth/github/link/start`：**已登录用户**绑定 GitHub 身份，返回授权 URL
     * 交给系统浏览器打开。
     *
     * 与登录流程的区别：绑定不产生新会话、不发令牌，回跳参数是 `linked=github`
     * 或 `error=<reason>`（冲突时典型为 `already_linked`，具体由服务端 App 定义）。
     *
     * 之所以要先 POST 换 URL 而不能直接开浏览器：这一步需要 Bearer 鉴权，
     * 而浏览器导航带不了 Authorization 头。
     */
    public suspend fun githubLinkUrl(redirect: String): String {
        val token = accessToken() ?: throw NotAuthenticatedException("GitHub linking requires an authenticated session")
        val body = request(
            url = "$base/oauth/github/link/start",
            payload = mapOf("redirect" to redirect),
            bearer = token,
        )
        return body["authorizeUrl"]?.jsonPrimitive?.content
            ?: throw AuthApiException(200, AuthError.UNKNOWN, rawError = "missing authorizeUrl")
    }

    // ---- 会话 ----

    /**
     * 取当前 access token。
     *
     * @param forceRefresh 业务请求收到 401 时传 true——走单飞刷新拿新的再重试一次。
     * @return 无会话或刷新失败时为 null
     */
    public suspend fun accessToken(forceRefresh: Boolean = false): String? {
        if (forceRefresh) {
            return when (val outcome = refresh()) {
                is RefreshOutcome.Success -> outcome.tokens.accessToken
                else -> null
            }
        }
        return tokenStore.load()?.accessToken
    }

    /**
     * `POST /refresh`，**单飞**。
     *
     * 服务端每次 refresh 必轮换，并对「拿已作废令牌来刷」做救活判定——但救活有
     * 1h/3 次护栏，超了按盗用撤销整条链。并发刷新会白白消耗这个配额，所以客户端
     * 必须保证同一时刻至多一条真实刷新在飞：
     *
     * 1. 互斥锁串行化；
     * 2. **进锁后重读存储**——等锁期间若别人已经刷成功（refresh token 变了），
     *    直接复用其结果，不再打一次服务端。少了这一步，N 个并发调用会变成 N 次刷新。
     *
     * 与 Logto 时代的一处差异：那时要给锁加 30 秒超时守卫，因为刷新藏在 SDK 的异步
     * 回调里、解析炸掉时 completion 永远不来。这里 HTTP 调用是我们自己的，ktor 的
     * 超时保证它必定返回，`withLock` 的 finally 保证锁必定释放，故不需要额外守卫。
     */
    public suspend fun refresh(): RefreshOutcome {
        val before = tokenStore.load() ?: return RefreshOutcome.NoSession.also { signedOut() }

        return refreshMutex.withLock {
            val current = tokenStore.load() ?: run {
                // 等锁期间会话没了（并发 signOut，或别人刷到 401 清了会话）。
                // 那些路径本身都已置过 SignedOut，这里再置一次是幂等的防御——
                // 与锁外的早返回分支对称，不依赖「别人一定记得置」这个不变式。
                signedOut()
                return@withLock RefreshOutcome.NoSession
            }
            if (current.refreshToken != before.refreshToken) {
                // 等锁期间别人刷新成功了，复用——单飞的关键一步
                return@withLock RefreshOutcome.Success(current)
            }

            val response = try {
                http.post("$base/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody(mapOf("refreshToken" to current.refreshToken)))
                }
            } catch (e: Exception) {
                // 网络层失败：会话可能好好的，**绝不清**
                return@withLock RefreshOutcome.Failed(e)
            }

            if (response.status.value == 401) {
                val body = response.parseJsonOrNull()
                val reason = RefreshFailure.fromWire(
                    body?.get("reason")?.jsonPrimitive?.content.orEmpty()
                )
                // 唯一允许清会话的分支：服务端明确说这个 refresh token 不存在了
                tokenStore.clear()
                signedOut()
                return@withLock RefreshOutcome.SessionEnded(reason)
            }
            if (!response.status.isSuccess()) {
                // 5xx / 其他：可能是暂时的，保留会话让调用方重试
                return@withLock RefreshOutcome.Failed(
                    AuthApiException(response.status.value, AuthError.INTERNAL)
                )
            }

            val body = response.parseJsonOrNull()
                ?: return@withLock RefreshOutcome.Failed(IllegalStateException("empty refresh response"))
            val tokens = body.toTokenPair()
                ?: return@withLock RefreshOutcome.Failed(IllegalStateException("refresh response missing token fields"))

            // 先落盘再宣告成功：没存住就当没刷成功，否则下次拿旧令牌去刷会触发
            // 服务端救活（有护栏）。TokenStore 实现保证 save 返回时已落盘。
            try {
                persist(tokens)
            } catch (e: Exception) {
                return@withLock RefreshOutcome.Failed(e)
            }
            RefreshOutcome.Success(tokens)
        }
    }

    /**
     * 登出当前会话：`DELETE /sessions` + 清本地。
     *
     * 服务端调用是**尽力而为**——失败也照清本地并置登出态。理由：用户点了登出就该
     * 立刻是登出的，网络问题不该把人卡在登录态；服务端那条会话最坏留到 refresh
     * token 自然失效或被重用检测清掉。
     */
    public suspend fun signOut() {
        val token = tokenStore.load()?.accessToken
        if (token != null) {
            runCatching { http.delete("$base/sessions") { header(HttpHeaders.Authorization, "Bearer $token") } }
        }
        tokenStore.clear()
        signedOut()
    }

    /** 登出该用户全部会话：`DELETE /sessions/all` + 清本地。同样尽力而为。 */
    public suspend fun signOutAll() {
        val token = tokenStore.load()?.accessToken
        if (token != null) {
            runCatching { http.delete("$base/sessions/all") { header(HttpHeaders.Authorization, "Bearer $token") } }
        }
        tokenStore.clear()
        signedOut()
    }

    // ---- 内部 ----

    private suspend fun persist(tokens: TokenPair) {
        tokenStore.save(tokens)
        _authState.value = AuthState.SignedIn
    }

    private fun signedOut() {
        _authState.value = AuthState.SignedOut
    }

    /** POST JSON，2xx 返回响应体，否则按协议抛 [AuthApiException] */
    private suspend fun request(
        url: String,
        payload: Map<String, String>,
        bearer: String? = null,
    ): JsonObject {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            setBody(jsonBody(payload))
        }
        val body = response.parseJsonOrNull()
        if (!response.status.isSuccess()) {
            val raw = body?.get("error")?.jsonPrimitive?.content.orEmpty()
            // 响应体不是协议 JSON（网关的 HTML 错误页、空体等）时 raw 为空，
            // 回退成 http_<status> 而不是留个空串——否则异常里没有任何可诊断信息
            val diagnosable = raw.ifEmpty { "http_${response.status.value}" }
            throw AuthApiException(
                status = response.status.value,
                error = AuthError.fromWire(raw),
                retryAfterSeconds = body?.get("retryAfterSeconds")?.jsonPrimitive?.int,
                refreshFailure = body?.get("reason")?.jsonPrimitive?.content
                    ?.let { RefreshFailure.fromWire(it) },
                rawError = diagnosable,
            )
        }
        return body ?: JsonObject(emptyMap())
    }

    private fun jsonBody(payload: Map<String, String>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { payload.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )

    private suspend fun HttpResponse.parseJsonOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(bodyAsText()) as? JsonObject
    }.getOrNull()

    private fun JsonObject.toTokenPair(): TokenPair? {
        val access = this["accessToken"]?.jsonPrimitive?.content
        val refresh = this["refreshToken"]?.jsonPrimitive?.content
        return if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) null
        else TokenPair(access, refresh)
    }

    private fun JsonObject.toSession(): AuthSession {
        val tokens = toTokenPair()
            ?: throw AuthApiException(200, AuthError.UNKNOWN, rawError = "missing tokens")
        return AuthSession(
            tokens = tokens,
            isNewUser = this["isNewUser"]?.jsonPrimitive?.booleanOrNull,
            user = this["user"],
        )
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val DEFAULT_COOLDOWN = 60
    }
}

private fun io.ktor.http.HttpStatusCode.isSuccess(): Boolean = value in 200..299
