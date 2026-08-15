package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
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
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * loginbase 客户端，实现 [PROTOCOL_VERSION] 声明的协议版本。
 * **请当单例持有（每进程一个）**：单飞锁是实例字段（docs/design.md 第 1 节）。
 * 库不解析 JWT——原样存、原样用，客户端没有本地时间依赖。
 *
 * @param baseUrl 服务端挂载点，如 `https://api.example.com/auth`
 */
class AuthClient(
    baseUrl: String,
    private val tokenStore: TokenStore,
    configure: LoginbaseConfig.() -> Unit = {},
) {
    private val base: String = baseUrl.trimEnd('/')

    // 构造期读成不可变字段：调用方事后改 config 对象不影响已建好的 client
    private val localeProvider: () -> String?
    private val injectedEngine: HttpClientEngine?
    private val timeoutMillis: Long

    init {
        val config = LoginbaseConfig().apply(configure)
        localeProvider = config.localeProvider
        injectedEngine = config.httpEngine
        timeoutMillis = config.timeoutMillis
    }

    private val json = Json {
        ignoreUnknownKeys = true // 服务端加字段不该炸老客户端
    }

    // client 一律由本库自建（消费方最多给 engine，见 [LoginbaseConfig.httpEngine]）：
    // 插件集合因此可控，超时行为确定。惰性：不发 HTTP 的 API（signInUrl、restore）
    // 不该被「classpath 必须有 engine」连坐。
    private val httpDelegate = lazy {
        val engine = injectedEngine
        // 传 engine 的重载走 manageEngine = false，close() 不会关掉消费方的 engine
        if (engine != null) HttpClient(engine) { installTimeout() }
        else HttpClient { installTimeout() }
    }

    private fun HttpClientConfig<*>.installTimeout() {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }

    private val http: HttpClient by httpDelegate

    /** 刷新互斥：见 [refresh] 对单飞的说明 */
    private val refreshMutex = Mutex()

    /**
     * 存储写入互斥（毫秒级；[refreshMutex] 跨 HTTP 往返是秒级）。[signOut] 只取这把——
     * 登出不该被在途刷新卡住。嵌套固定 refreshMutex 外、storeMutex 内。
     */
    private val storeMutex = Mutex()

    // 已完成的刷新轮次（单飞共享的载体）。MutableStateFlow：id 要在锁外读，
    // 不能依赖锁的内存语义；id 与 outcome 同对象保证一起读到
    private data class RefreshRound(val id: Int, val outcome: RefreshOutcome?)

    private val lastRound = MutableStateFlow(RefreshRound(id = 0, outcome = null))

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * 从存储恢复登录态。App 启动时调一次，之后 [authState] 才有意义。
     * 顺带排空停泊的 OAuth 回跳（进程在授权期间被回收的场景），结果照常从
     * [oauthResults] 送达；返回**排空之后**的状态。
     */
    suspend fun restore(): AuthState {
        val state = if (tokenStore.load() != null) AuthState.SignedIn
        else AuthState.SignedOut(SignOutReason.NoSession)
        _authState.value = state
        OAuthCallbackParking.take()?.let { handleOAuthCallback(it) }
        return _authState.value
    }

    // ---- 邮箱验证码 ----

    /**
     * `POST /code/send`。无论账号是否存在都成功（防枚举）。
     * 邮件语言由 [localeProvider] 决定随请求上报，服务端对未知语言静默回落；
     * 取不到时字段整个省略、不发 `und`——省略与「传了不支持的值」在服务端观测上是两回事。
     */
    suspend fun sendCode(email: String): SendCodeResult {
        val payload = buildMap {
            put("email", email)
            resolveLocale()?.let { put("locale", it) }
        }
        val body = request("$base/code/send", payload)
        return SendCodeResult(
            cooldownSeconds = body.intOrNull("cooldownSeconds") ?: DEFAULT_COOLDOWN
        )
    }

    // 每次现取不缓存：用户可能刚切了语言。usableTag 净化的是 provider 的返回值——
    // 消费方拼回落链时可能把 "und" 传进来
    private fun resolveLocale(): String? =
        localeProvider().usableTag() ?: platformLanguageTag()

    /** `POST /code/verify`，成功即建立会话并落盘。 */
    suspend fun verifyCode(email: String, code: String): AuthSession =
        request("$base/code/verify", mapOf("email" to email, "code" to code))
            .toSession()
            .also { persist(it.tokens) }

    // ---- 社交登录（OAuth） ----

    /**
     * 拼登录授权入口，交给**外部浏览器**打开（不要用内嵌 WebView）。Android 直接用
     * browser 模块的 `signIn()`，一般不必手动走这条。
     * 回跳带 `otc`（60 秒单次有效），由 [handleOAuthCallback] 或 [exchangeOtc] 兑换。
     */
    fun signInUrl(provider: OAuthProvider, redirect: String): String =
        "$base/oauth/${provider.id.encodeURLPathPart()}/start" +
            "?redirect=${redirect.encodeURLParameter()}"

    /** `POST /oauth/exchange`：拿 deepLink 回来的 otc 换令牌对，成功即落盘。 */
    suspend fun exchangeOtc(otc: String): AuthSession =
        request("$base/oauth/exchange", mapOf("otc" to otc))
            .toSession()
            .also { persist(it.tokens) }

    // ---- 社交登录（OAuth 回跳） ----

    private val _oauthResults = MutableSharedFlow<OAuthOutcome>(
        replay = 1, // 兜「投递早于订阅」：进程回收后 restore() 里的处理先于 UI 订阅
        extraBufferCapacity = 1,
    )

    /**
     * 消费方唯一的 OAuth 结果通道（发起 UI 可能随进程消失，结果不走返回值）。
     * `replay = 1` 只兜「投递早于订阅」，**不是历史记录**——处理完调 [consumeOauthResult] 清掉。
     */
    val oauthResults: SharedFlow<OAuthOutcome> = _oauthResults.asSharedFlow()

    /** 声明 [oauthResults] 的当前结果已处理完，清掉 replay 缓存。 */
    fun consumeOauthResult() {
        _oauthResults.resetReplayCache()
    }

    // 最近一次 otc 兑换及结果（oauthMutex 保护）：otc 单次有效而回跳可能重复送达，
    // 不去重则第二次兑换 invalid_otc，用户刚成功就看到假失败
    private var lastOtcExchange: Pair<String, OAuthOutcome>? = null

    // 保护 lastOtcExchange，并把重复回跳的并发兑换收敛成一次
    private val oauthMutex = Mutex()

    /**
     * 处理一次 OAuth 回跳，**所有通路的唯一汇合点**。
     * 幂等：同一 otc 只兑换一次，重复送入返回缓存结果、不重复投递。
     * 结果必发一份到 [oauthResults]（UI 只看那里），返回值给直接调用方。
     * 不抛业务异常：兑换失败映射 [OAuthOutcome.Failed]，认不出映射 [OAuthOutcome.Unrecognized]。
     */
    suspend fun handleOAuthCallback(url: String): OAuthOutcome =
        when (val parsed = OAuthCallbackParams.parse(url)) {
            is OAuthCallbackParams.Login -> exchangeOtcIdempotent(parsed.otc)
            is OAuthCallbackParams.Linked ->
                publish(OAuthOutcome.Linked(OAuthProvider(parsed.provider)))
            is OAuthCallbackParams.Failed -> publish(OAuthOutcome.Failed(parsed.reason))
            OAuthCallbackParams.Unrecognized -> publish(OAuthOutcome.Unrecognized(url))
        }

    @OptIn(LoginbaseInternalApi::class) // oauthFailureReason 的 opt-in 是给外部模块的墙，库内使用无虞
    private suspend fun exchangeOtcIdempotent(otc: String): OAuthOutcome =
        oauthMutex.withLock {
            // 失败结果同样缓存：重复送达的是同一次流程，答案不该因为问了两遍而变化
            lastOtcExchange?.takeIf { it.first == otc }?.let { return@withLock it.second }
            val outcome = try {
                OAuthOutcome.SignedIn(exchangeOtc(otc))
            } catch (e: CancellationException) {
                throw e // 取消不是结果：不缓存、不投递，下次送达照常兑换
            } catch (e: LoginbaseException) {
                OAuthOutcome.Failed(e.oauthFailureReason())
            }
            lastOtcExchange = otc to outcome
            publish(outcome)
        }

    private suspend fun <T : OAuthOutcome> publish(outcome: T): T {
        _oauthResults.emit(outcome)
        return outcome
    }

    /**
     * 浏览器模块专用投递口（见 [LoginbaseInternalApi]）：取消、开浏览器前的失败这类
     * 不经回跳 URL 的结果也必须走 [oauthResults] 唯一通道。消费方代码不该调它。
     */
    @LoginbaseInternalApi
    suspend fun publishOAuthOutcome(outcome: OAuthOutcome) {
        publish(outcome)
    }

    /**
     * `POST /oauth/{provider}/link/start`：**已登录用户**绑定第二身份，返回授权 URL。
     * 先 POST 再开浏览器：这步要 Bearer 鉴权，浏览器导航带不了头。
     * 回跳是 `linked=<provider>` / `error=<reason>`，不产生新会话。
     */
    suspend fun linkUrl(provider: OAuthProvider, redirect: String): String {
        val token = accessToken()
            ?: throw LoginbaseException.NotAuthenticated(
                "Linking an OAuth identity requires an authenticated session"
            )
        val body = request(
            url = "$base/oauth/${provider.id.encodeURLPathPart()}/link/start",
            payload = mapOf("redirect" to redirect),
            bearer = token,
        )
        return body.stringOrNull("authorizeUrl")
            ?: throw LoginbaseException.MalformedResponse("authorizeUrl")
    }

    // ---- 会话 ----

    /**
     * 取当前 access token；无会话或刷新失败返回 null。
     * 顺手把还停在 Unknown 的 [authState] 补齐——避免「状态说 Unknown、这里却有令牌」的矛盾。
     * @param forceRefresh 业务请求 401 时传 true，走单飞刷新再重试一次
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        if (forceRefresh) {
            return when (val outcome = refresh()) {
                is RefreshOutcome.Success -> outcome.tokens.accessToken
                else -> null
            }
        }
        val tokens = tokenStore.load()
        syncStateIfUnknown(tokens)
        return tokens?.accessToken
    }

    // 只在 Unknown 时动手：别的状态是别处按更完整的信息写下的，不该被覆盖
    private fun syncStateIfUnknown(tokens: TokenPair?) {
        if (_authState.value != AuthState.Unknown) return
        _authState.value = if (tokens != null) AuthState.SignedIn
        else AuthState.SignedOut(SignOutReason.NoSession)
    }

    /**
     * `POST /refresh`，**单飞**：同一时刻至多一条真实刷新在飞，排队者复用同轮结果
     * （进锁前记 [lastRound] 编号，进锁后编号变了即有人替我干完了）。
     * **失败也共享**——失败时存储纹丝不动，等待者若各自重发正是烧穿服务端救活配额
     * （1h/3 次）的路径。完整论证与事故史见 docs/design.md 第 1 节。
     */
    suspend fun refresh(): RefreshOutcome {
        val existing = tokenStore.load()
        syncStateIfUnknown(existing) // 与 accessToken 同一条不变式：读过存储就不再是 Unknown
        if (existing == null) {
            return RefreshOutcome.NoSession
                .also { signedOutUnlessAlready(SignOutReason.NoSession) }
        }

        // 【必须在锁外读】记的是「我何时来排队」。挪进锁里判断恒不成立，
        // 单飞静默退化成各刷各的——不报错，只是变慢变贵
        val entryRound = lastRound.value.id

        return refreshMutex.withLock {
            val completed = lastRound.value
            // 【必须排在读存储之前】排后面的话，会话被 401 清掉时等待者会先掉进
            // NoSession 早返回，拿不到 SessionEnded 归因
            if (completed.id != entryRound) {
                return@withLock completed.outcome
                    ?: RefreshOutcome.NoSession // 不可达：id 推进过就必然带着结果
            }

            val current = tokenStore.load() ?: run {
                // 等锁期间会话没了（并发 signOut 或别人刷到 401）。幂等防御；
                // UnlessAlready 保留别人写下的更精确归因。未发一个字节，不记轮次
                signedOutUnlessAlready(SignOutReason.NoSession)
                return@withLock RefreshOutcome.NoSession
            }

            val outcome = runRound(current)
            lastRound.value = RefreshRound(entryRound + 1, outcome)
            outcome
        }
    }

    /**
     * 一次真实的刷新尝试：发请求、判定、必要时落盘。**假定已持有 [refreshMutex]**。
     * 单独抽出让「一次尝试做什么」与并发编排分离；所有失败分支都从这里 `return`。
     */
    private suspend fun runRound(current: TokenPair): RefreshOutcome {
        val response = try {
            // 请求必有限时（client 自建、[installTimeout] 必然生效），锁不会被永久持有
            http.post("$base/refresh") {
                contentType(ContentType.Application.Json)
                setBody(jsonBody(mapOf("refreshToken" to current.refreshToken)))
            }
        } catch (e: CancellationException) {
            // 只有当前协程确实被取消才传播：ktor 在消费方 close() 自己的 client 时会
            // 连带取消在途请求，那个取消与调用方无关——原样抛会让调用方 launch 静默丢结果
            currentCoroutineContext().ensureActive()
            return refreshFailed(LoginbaseException.Network(e))
        } catch (e: Exception) {
            // 传输层失败（含超时）：会话可能好好的，**绝不清**
            return refreshFailed(LoginbaseException.Network(e))
        }

        if (response.status.value == 401) {
            val body = response.parseJsonOrNull()
            val reason = RefreshFailure.fromWire(
                body?.stringOrNull("reason").orEmpty()
            )
            // 唯一允许清会话的分支：服务端明确说 token 不存在。UnlessAlready：并发
            // signOut 可能先到，改写成 SessionEnded 会让自己登出的用户误看到「登录已失效」
            storeMutex.withLock {
                tokenStore.clear()
                signedOutUnlessAlready(SignOutReason.SessionEnded(reason))
            }
            return RefreshOutcome.SessionEnded(reason)
        }
        if (!response.status.isSuccess()) {
            // 5xx / 其他：可能是暂时的，保留会话让调用方重试
            return refreshFailed(
                LoginbaseException.Api(response.status.value, AuthError.INTERNAL)
            )
        }

        val body = response.parseJsonOrNull()
            ?: return refreshFailed(LoginbaseException.MalformedResponse("body"))
        val tokens = body.toTokenPair()
            ?: return refreshFailed(
                LoginbaseException.MalformedResponse("accessToken/refreshToken")
            )

        // 先落盘再宣告成功（没存住就当没刷成）。落盘前在 storeMutex 里重读比对：
        // 等响应期间并发 signOut 可能已清会话，缺这步就是「点了登出半秒后被刷新响应
        // 复活」的老 bug；检查与写入必须原子，只查不锁仍是概率性正确
        val persisted = try {
            storeMutex.withLock {
                if (tokenStore.load()?.refreshToken != current.refreshToken) {
                    false // 会话已被清或被换掉，这对令牌属于上一条会话，丢弃
                } else {
                    persist(tokens)
                    true
                }
            }
        } catch (e: CancellationException) {
            // 取消如实传播。这里不需要 ensureActive 守卫：TokenStore 是我们直接调的，
            // 取消必然来自调用方本身
            throw e
        } catch (e: Exception) {
            return refreshFailed(LoginbaseException.Storage(e))
        }
        if (!persisted) {
            return RefreshOutcome.NoSession // 不碰 authState：清会话那方已写下更准确的原因
        }
        return RefreshOutcome.Success(tokens)
    }

    /**
     * 登出当前会话：`DELETE /sessions` + 清本地。服务端调用尽力而为——用户点了登出
     * 就该立刻是登出的，网络失败不该拦。**只取 [storeMutex] 不取 [refreshMutex]**：
     * 后者可能被在途刷新占几十秒；与在途刷新的竞态由落盘前重读比对收敛。
     */
    suspend fun signOut(): Unit = signOutInternal("$base/sessions")

    /** 登出该用户全部会话：`DELETE /sessions/all` + 清本地。同样尽力而为。 */
    suspend fun signOutAll(): Unit = signOutInternal("$base/sessions/all")

    /**
     * 登出的取消策略：本地清除放 `finally` + [NonCancellable]——协程中途被取消、
     * 结果却还登录着，是最难排查的状态不一致；取消随后照常向上传播。
     */
    private suspend fun signOutInternal(url: String) {
        val token = tokenStore.load()?.accessToken
        try {
            if (token != null) {
                try {
                    http.delete(url) { header(HttpHeaders.Authorization, "Bearer $token") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 尽力而为：服务端那条会话最坏自然失效，不该把用户卡在登录态
                }
            }
        } finally {
            withContext(NonCancellable) { clearLocally() }
        }
    }

    // 与 refresh 的「重读比对 + 落盘」在 storeMutex 上互斥，两个顺序都给出正确结果；
    // 缺这把锁，clear 会插进重读与落盘之间，退化回会话复活 bug
    private suspend fun clearLocally() {
        storeMutex.withLock {
            tokenStore.clear()
            signedOut(SignOutReason.UserInitiated)
        }
    }

    /**
     * 关闭本库的 [HttpClient]；**不会关你注入的 engine**。按单例用法一辈子不用调，
     * 是给测试与 DI 重建场景准备的；调用后本实例不应再使用。
     */
    fun close() {
        if (httpDelegate.isInitialized()) http.close()
    }

    // ---- 内部 ----

    private suspend fun persist(tokens: TokenPair) {
        tokenStore.save(tokens)
        _authState.value = AuthState.SignedIn
    }

    private fun signedOut(reason: SignOutReason) {
        _authState.value = AuthState.SignedOut(reason)
    }

    /**
     * 幂等防御专用：已是登出态就**不覆盖**——别处可能写了更精确的原因
     * （如 SessionEnded），无条件改写会让 UI 永远弹不出「登录已失效」。
     */
    private fun signedOutUnlessAlready(reason: SignOutReason) {
        if (_authState.value !is AuthState.SignedOut) signedOut(reason)
    }

    /**
     * 刷新失败：置 [AuthState.RefreshFailed]。会话没被清、不是登出。
     * 登出态优先：迟到的刷新失败不把已登出的人改回「还登着」。
     */
    private fun refreshFailed(cause: LoginbaseException): RefreshOutcome.Failed {
        if (_authState.value !is AuthState.SignedOut) {
            _authState.value = AuthState.RefreshFailed(cause)
        }
        return RefreshOutcome.Failed(cause)
    }

    /**
     * POST JSON：2xx 返回响应体，否则按协议抛 [LoginbaseException.Api]；
     * 传输层异常包成 [LoginbaseException.Network]。
     */
    private suspend fun request(
        url: String,
        payload: Map<String, String>,
        bearer: String? = null,
    ): JsonObject {
        val response = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(jsonBody(payload))
            }
        } catch (e: CancellationException) {
            // 取消不是失败：原样穿透，否则破坏结构化并发
            throw e
        } catch (e: Exception) {
            throw LoginbaseException.Network(e)
        }
        val body = response.parseJsonOrNull()
        if (!response.status.isSuccess()) {
            val raw = body?.stringOrNull("error").orEmpty()
            // raw 为空（网关错误页、空体）时回退 http_<status>，保住诊断信息
            val diagnosable = raw.ifEmpty { "http_${response.status.value}" }
            throw LoginbaseException.Api(
                status = response.status.value,
                error = AuthError.fromWire(raw),
                retryAfterSeconds = body?.intOrNull("retryAfterSeconds"),
                refreshFailure = body?.stringOrNull("reason")
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

    // 不是协议 JSON（网关错误页、空体）返回 null。不用 runCatching：它连
    // CancellationException 一起吞，破坏结构化并发
    private suspend fun HttpResponse.parseJsonOrNull(): JsonObject? {
        val text = try {
            bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
    }

    private fun JsonObject.toTokenPair(): TokenPair? {
        val access = stringOrNull("accessToken")
        val refresh = stringOrNull("refreshToken")
        return if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) null
        else TokenPair(access, refresh)
    }

    private fun JsonObject.toSession(): AuthSession {
        val tokens = toTokenPair()
            ?: throw LoginbaseException.MalformedResponse("accessToken/refreshToken")
        return AuthSession(
            tokens = tokens,
            isNewUser = booleanOrNull("isNewUser"),
            user = this["user"],
        )
    }

    private companion object {
        const val DEFAULT_COOLDOWN = 60
    }
}
