package wang.harlon.loginbase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
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
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
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
 * ## 请把它当单例持有（每进程一个）
 *
 * 单飞 refresh 的互斥锁是**实例字段**——两个实例就是两把锁，并发刷新会同时打到服务端，
 * 正是要避免的事。Logto 时代的教训更狠：那时 client 跟着 Activity 重建，旧实例的在途
 * 刷新与新实例互不互斥，轮换竞态跨实例复活。所以：**一个 App 一个 AuthClient**。
 *
 * 准确的边界是**每进程一个**：锁随进程存在，跨进程不生效。
 * Android 的 `:remote` 进程、iOS 的 App + Widget/Extension 若各自建实例，
 * 就是各刷各的，会消耗服务端的救活配额（1h/3 次护栏，超了整条会话被判盗用撤销）。
 * 有这种结构时，跨进程互斥要由消费方自己保证——库刻意不做跨进程锁，
 * 因为失败代价不对称：没有它最坏是多刷一次，有了它最坏是认证彻底卡死
 * （Supabase 用 Web Locks 做跨标签页锁，换来的正是一串孤儿锁/死锁故障）。
 * 业界同形：Auth0 的 CredentialsManager 同样只保证实例内串行，并在文档里
 * 明写「不要从多个实例调用续期方法」。
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
 * ```kotlin
 * val auth = AuthClient(baseUrl, tokenStore)                 // 全默认
 * val auth = AuthClient(baseUrl, tokenStore) {               // 带可选配置
 *     httpClient = myClient
 *     localeProvider = { settings.languageTag }
 * }
 * ```
 *
 * @param baseUrl 服务端挂载点，如 `https://api.example.com/auth`（末尾斜杠会被去掉）
 * @param tokenStore 令牌持久化，见 [TokenStore] 对同步落盘的要求
 * @param configure 可选配置，见 [LoginbaseConfig]——选项放在那里而不是做成构造函数
 *   参数，是为了将来加选项时不破二进制兼容（理由见该类文档）
 */
class AuthClient(
    baseUrl: String,
    private val tokenStore: TokenStore,
    configure: LoginbaseConfig.() -> Unit = {},
) {
    private val base: String = baseUrl.trimEnd('/')

    // 构造期就把配置读成不可变字段。LoginbaseConfig 刻意可变（可变才换来二进制兼容），
    // 但调用方若在 lambda 里把 this 存了出去，之后再改不该影响已经建好的 client。
    private val localeProvider: () -> String?
    private val injectedHttpClient: HttpClient?

    init {
        val config = LoginbaseConfig().apply(configure)
        localeProvider = config.localeProvider
        injectedHttpClient = config.httpClient
    }

    private val json = Json {
        ignoreUnknownKeys = true // 服务端加字段不该炸老客户端
        encodeDefaults = true
    }

    // 请求体手工序列化、响应手工解析——故不需要 ContentNegotiation 插件：
    // 少两个 ktor 依赖，且注入的 HttpClient 无需任何插件配置即可工作。
    //
    // 惰性初始化：engine 由消费方提供，无参构造 HttpClient 在 classpath 没有 engine 时
    // 会直接抛异常。不发 HTTP 的 API（signInUrl 拼串、restore 读存储）不该被这个
    // 要求连坐——真正发请求时才需要 engine。
    // 注入的 client 若一个超时都没配，一个挂住的请求会**永久持有单飞锁**，把所有等锁
    // 的调用一起拖死（Supabase 的孤儿锁故障就是这个形态）。故此处补一道保险丝：
    // 只在消费方没装 HttpTimeout 时才装，且用宽松值——绝不覆盖消费方自己的设置。
    // `config {}` 复用同一个 engine，不额外开连接池。
    private val http: HttpClient by lazy {
        val injected = injectedHttpClient ?: return@lazy HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
        if (injected.pluginOrNull(HttpTimeout) != null) {
            injected
        } else {
            injected.config {
                install(HttpTimeout) { requestTimeoutMillis = LOCK_FUSE_TIMEOUT_MS }
            }
        }
    }

    /** 刷新互斥：见 [refresh] 对单飞的说明 */
    private val refreshMutex = Mutex()

    /**
     * 存储写入互斥。**与 [refreshMutex] 的分工是这套并发设计的关键**：
     *
     * | | 持有期间 | 最坏等待 |
     * |---|---|---|
     * | [refreshMutex] | 跨整个 HTTP 往返 | [LOCK_FUSE_TIMEOUT_MS]（45 秒） |
     * | [storeMutex] | 只有本地存储读+写 | 毫秒级 |
     *
     * [signOut] 只碰这一把，所以「用户点了登出就该立刻是登出的」这条纪律不受在途刷新
     * 影响；若让 signOut 去抢 [refreshMutex]，一个挂住的刷新能把登出卡 45 秒。
     *
     * 嵌套顺序固定为 refreshMutex 外、storeMutex 内，无反向获取路径，不会死锁。
     */
    private val storeMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** 从存储恢复登录态。App 启动时调一次，之后 [authState] 才有意义。 */
    suspend fun restore(): AuthState {
        val state = if (tokenStore.load() != null) AuthState.SignedIn
        else AuthState.SignedOut(SignOutReason.NoSession)
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
    suspend fun sendCode(email: String): SendCodeResult {
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
    // 只对 provider 的返回值做 usableTag()——[Loginbase.appLanguageTag] 的契约里已经保证
    // 「取不到返回 null」，这条保证是给消费方拼回落链用的
    // （`settings.tag ?: Loginbase.appLanguageTag()`），不能挪到这里来，
    // 否则那种写法会把 "und" 原样发出去。
    private fun resolveLocale(): String? =
        localeProvider().usableTag() ?: platformLanguageTag()

    /** `POST /code/verify`，成功即建立会话并落盘。 */
    suspend fun verifyCode(email: String, code: String): AuthSession =
        request("$base/code/verify", mapOf("email" to email, "code" to code))
            .toSession()
            .also { persist(it.tokens) }

    // ---- 社交登录（OAuth） ----

    /**
     * 拼登录用的授权入口，交给**系统浏览器**打开（不要用内置 WebView：OAuth 授权页
     * 需要用户能看见地址栏，且各家 provider 对嵌入式 WebView 多有限制，GitHub 尤其）。
     *
     * 授权完成后 provider 回跳到 [redirect]，参数带 `otc`，再调 [exchangeOtc] 换令牌。
     * 令牌不进 URL——otc 60 秒单次有效，即便被日志记下也只有一次兑换窗口。
     *
     * @param provider 见 [OAuthProvider]；服务端启用了哪几个由服务端 App 配置，本库不校验
     */
    fun signInUrl(provider: OAuthProvider, redirect: String): String =
        "$base/oauth/${provider.id.encodeURLPathPart()}/start" +
            "?redirect=${redirect.encodeURLParameter()}"

    /** `POST /oauth/exchange`：拿 deepLink 回来的 otc 换令牌对，成功即落盘。 */
    suspend fun exchangeOtc(otc: String): AuthSession =
        request("$base/oauth/exchange", mapOf("otc" to otc))
            .toSession()
            .also { persist(it.tokens) }

    /**
     * `POST /oauth/{provider}/link/start`：**已登录用户**绑定第二身份，返回授权 URL
     * 交给系统浏览器打开。
     *
     * 与登录流程的区别：绑定不产生新会话、不发令牌，回跳参数是 `linked=<provider>`
     * 或 `error=<reason>`（冲突时典型为 `already_linked`，具体由服务端 App 定义）。
     *
     * 之所以要先 POST 换 URL 而不能直接开浏览器：这一步需要 Bearer 鉴权，
     * 而浏览器导航带不了 Authorization 头。
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
        return body["authorizeUrl"]?.jsonPrimitive?.content
            ?: throw LoginbaseException.MalformedResponse("authorizeUrl")
    }

    // ---- 会话 ----

    /**
     * 取当前 access token。
     *
     * @param forceRefresh 业务请求收到 401 时传 true——走单飞刷新拿新的再重试一次。
     * @return 无会话或刷新失败时为 null
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
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
     * 回调里、解析炸掉时 completion 永远不来。这里 HTTP 调用是我们自己的，`withLock`
     * 的 finally 保证锁必定释放——但**请求本身必须有限时**：库自建的 client 装了
     * `HttpTimeout`，而消费方注入的 client（文档鼓励注入以复用连接池）未必配了超时，
     * 一个挂住的请求就会永久持锁，所有等锁的调用一起卡死。故这里加一道
     * [LOCK_FUSE_TIMEOUT_MS] 保险丝（只在注入的 client 没装 HttpTimeout 时补装），
     * 它刻意宽松——职责是「别让锁永远握着」，不是替消费方决定超时值。
     */
    suspend fun refresh(): RefreshOutcome {
        val before = tokenStore.load()
            ?: return RefreshOutcome.NoSession
                .also { signedOutUnlessAlready(SignOutReason.NoSession) }

        return refreshMutex.withLock {
            val current = tokenStore.load() ?: run {
                // 等锁期间会话没了（并发 signOut，或别人刷到 401 清了会话）。
                // 那些路径本身都已置过 SignedOut，这里再置一次是幂等的防御——
                // 与锁外的早返回分支对称，不依赖「别人一定记得置」这个不变式。
                // 用 UnlessAlready：别人写下的原因比这里的 NoSession 精确得多。
                signedOutUnlessAlready(SignOutReason.NoSession)
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
            } catch (e: CancellationException) {
                // 调用方主动取消：如实传播，不吞成 Failed（否则破坏结构化并发）
                throw e
            } catch (e: Exception) {
                // 传输层失败（含超时）：会话可能好好的，**绝不清**
                return@withLock refreshFailed(LoginbaseException.Network(e))
            }

            if (response.status.value == 401) {
                val body = response.parseJsonOrNull()
                val reason = RefreshFailure.fromWire(
                    body?.get("reason")?.jsonPrimitive?.content.orEmpty()
                )
                // 唯一允许清会话的分支：服务端明确说这个 refresh token 不存在了。
                // 原因要带上：这是全库唯一会让 UI 弹「登录已失效」的路径。
                //
                // 但用 UnlessAlready：并发 signOut 的 DELETE /sessions 可能先到服务端，
                // 于是这次刷新收到 401——此时用户是**自己点的登出**，改写成 SessionEnded
                // 会让 UI 弹「登录已失效」，是骚扰。谁先置的登出态谁的原因算数。
                storeMutex.withLock {
                    tokenStore.clear()
                    signedOutUnlessAlready(SignOutReason.SessionEnded(reason))
                }
                return@withLock RefreshOutcome.SessionEnded(reason)
            }
            if (!response.status.isSuccess()) {
                // 5xx / 其他：可能是暂时的，保留会话让调用方重试
                return@withLock refreshFailed(
                    LoginbaseException.Api(response.status.value, AuthError.INTERNAL)
                )
            }

            val body = response.parseJsonOrNull()
                ?: return@withLock refreshFailed(LoginbaseException.MalformedResponse("body"))
            val tokens = body.toTokenPair()
                ?: return@withLock refreshFailed(
                    LoginbaseException.MalformedResponse("accessToken/refreshToken")
                )

            // 先落盘再宣告成功：没存住就当没刷成功，否则下次拿旧令牌去刷会触发
            // 服务端救活（有护栏）。TokenStore 实现保证 save 返回时已落盘。
            //
            // 落盘前**重读存储再比对**：等响应这段时间里，并发 signOut 可能已经把会话
            // 清了。没有这一步就是「用户点了登出，半秒后又被刚到达的刷新响应复活成登录
            // 态，手里还是一对服务端刚发的有效令牌」。检查与写入必须在 storeMutex 里原子
            // 完成——只做检查不加锁的话，窗口从几百毫秒缩到几微秒，但依然是概率性正确。
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
                // 与上面的传输层分支同规矩：取消如实传播，不吞成 Failed
                throw e
            } catch (e: Exception) {
                return@withLock refreshFailed(LoginbaseException.Storage(e))
            }
            if (!persisted) {
                // 不碰 authState：清会话那一方已经写下了更准确的原因
                return@withLock RefreshOutcome.NoSession
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
     *
     * **只取 [storeMutex]，绝不取 [refreshMutex]**：后者可能被一个在途刷新占着长达
     * 45 秒，登出等它就违背了上面那条纪律。与在途刷新的竞态由 [refresh] 落盘前的
     * 重读比对负责收敛，见那里的说明。
     */
    suspend fun signOut() {
        val token = tokenStore.load()?.accessToken
        if (token != null) {
            runCatching { http.delete("$base/sessions") { header(HttpHeaders.Authorization, "Bearer $token") } }
        }
        clearLocally()
    }

    /** 登出该用户全部会话：`DELETE /sessions/all` + 清本地。同样尽力而为。 */
    suspend fun signOutAll() {
        val token = tokenStore.load()?.accessToken
        if (token != null) {
            runCatching { http.delete("$base/sessions/all") { header(HttpHeaders.Authorization, "Bearer $token") } }
        }
        clearLocally()
    }

    // 清本地会话。放进 storeMutex 是为了和 refresh 的「重读比对 + 落盘」互斥：
    // 两个顺序都要给出正确结果——本方法先拿到锁，则刷新随后重读发现会话没了、丢弃令牌；
    // 刷新先拿到锁，则它落盘后本方法再清掉。缺了这把锁，clear() 会插进「重读」与
    // 「落盘」之间，退化成原来的复活 bug。
    private suspend fun clearLocally() {
        storeMutex.withLock {
            tokenStore.clear()
            signedOut(SignOutReason.UserInitiated)
        }
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
     * 幂等防御路径专用：已经是登出态就**不覆盖**。
     *
     * 别处可能已经写下了更精确的原因。最要命的一条：会话被服务端撤销后置了
     * [SignOutReason.SessionEnded]，另一个在等锁的 refresh 醒来发现存储空了，
     * 若无条件改写成 [SignOutReason.NoSession]，UI 就再也弹不出「登录已失效」，
     * 用户只会莫名其妙被扔回登录页。
     */
    private fun signedOutUnlessAlready(reason: SignOutReason) {
        if (_authState.value !is AuthState.SignedOut) signedOut(reason)
    }

    /**
     * 刷新失败：置 [AuthState.RefreshFailed] 并返回对应的 outcome。
     *
     * 会话**没被清**，所以不是登出——这一态存在的意义就是让 UI 能区分「还登着但刷不动」
     * 和「真登出了」，别把弱网用户踢到登录页。
     *
     * 登出态优先：并发 [signOut] 已经把人登出了，不该被一个迟到的刷新失败改回
     * 「还登着、只是没刷成」。
     */
    private fun refreshFailed(cause: LoginbaseException): RefreshOutcome.Failed {
        if (_authState.value !is AuthState.SignedOut) {
            _authState.value = AuthState.RefreshFailed(cause)
        }
        return RefreshOutcome.Failed(cause)
    }

    /**
     * POST JSON，2xx 返回响应体，否则按协议抛 [LoginbaseException.Api]。
     *
     * 传输层异常在这里就被包成 [LoginbaseException.Network]——调用方不该为了接住
     * 「没连上」去认识 ktor 的异常层次（见 [LoginbaseException] 的说明）。
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
            val raw = body?.get("error")?.jsonPrimitive?.content.orEmpty()
            // 响应体不是协议 JSON（网关的 HTML 错误页、空体等）时 raw 为空，
            // 回退成 http_<status> 而不是留个空串——否则异常里没有任何可诊断信息
            val diagnosable = raw.ifEmpty { "http_${response.status.value}" }
            throw LoginbaseException.Api(
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
            ?: throw LoginbaseException.MalformedResponse("accessToken/refreshToken")
        return AuthSession(
            tokens = tokens,
            isNewUser = this["isNewUser"]?.jsonPrimitive?.booleanOrNull,
            user = this["user"],
        )
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L

        // 单飞锁的保险丝：只在注入的 client 没配任何超时时才用，故取宽松值——
        // 它的职责是「别让锁永远握着」，不是替消费方决定请求该多久超时
        const val LOCK_FUSE_TIMEOUT_MS = 45_000L
        const val DEFAULT_COOLDOWN = 60
    }
}

private fun io.ktor.http.HttpStatusCode.isSuccess(): Boolean = value in 200..299
