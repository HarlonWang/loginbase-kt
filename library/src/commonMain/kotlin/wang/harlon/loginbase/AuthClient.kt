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
 * loginbase 客户端。协议权威见服务端仓 `docs/protocol.md`；本类实现 [PROTOCOL_VERSION]。
 *
 * ## 请把它当单例持有（每进程一个）
 *
 * 单飞 refresh 的互斥锁是**实例字段**，两个实例就是两把锁，并发刷新会同时打到服务端。
 * 准确的边界是**每进程一个**：锁随进程存在，跨进程不生效；本库刻意不做跨进程锁。
 *
 * 完整论证（Logto 时代的跨实例复活事故、为什么否掉跨进程锁、与 ktor `Auth` 插件的
 * 关系）见 `docs/design.md` 第 1 节。
 *
 * ## 为什么不解析 JWT
 *
 * 库拿到 access token 后原样存、原样用，不读 `exp`、不校验签名——**客户端因此没有任何
 * 本地时间依赖**，设备时钟偏差这一整类问题消失。代价是过期时多一次往返。
 * 事故来源见 `docs/design.md` 第 2 节。
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
    private val injectedEngine: HttpClientEngine?
    private val timeoutMillis: Long

    init {
        val config = LoginbaseConfig().apply(configure)
        localeProvider = config.localeProvider
        injectedEngine = config.httpEngine
        timeoutMillis = config.timeoutMillis
    }

    // 只用来把 JsonObject 序列化成请求体、把响应体解析成 JsonObject——没有任何
    // @Serializable 类经过它，所以除 ignoreUnknownKeys 外不需要别的配置。
    private val json = Json {
        ignoreUnknownKeys = true // 服务端加字段不该炸老客户端
    }

    // 请求体手工序列化、响应手工解析——故不需要 ContentNegotiation 插件：
    // 少两个 ktor 依赖。
    //
    // **client 一律由本库自建**，消费方最多提供 engine（见 [LoginbaseConfig.httpEngine]）。
    // 这样插件集合完全由库掌控：ktor `Auth` 的重入死锁、`HttpRequestRetry` 把一次刷新
    // 悄悄放大成四次这类问题，从根上不存在；超时行为也是确定的，不必去猜消费方配了什么。
    //
    // 惰性初始化：没给 engine 时由 ktor 从 classpath 发现一个，classpath 上没有会直接
    // 抛异常。不发 HTTP 的 API（signInUrl 拼串、restore 读存储）不该被这个要求连坐——
    // 真正发请求时才需要 engine。
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
     * 存储写入互斥。**与 [refreshMutex] 的分工是这套并发设计的关键**：
     *
     * | | 持有期间 | 最坏等待 |
     * |---|---|---|
     * | [refreshMutex] | 跨整个 HTTP 往返 | 保险丝时长（默认 45 秒） |
     * | [storeMutex] | 只有本地存储读+写 | 毫秒级 |
     *
     * [signOut] 只碰这一把，所以「用户点了登出就该立刻是登出的」这条纪律不受在途刷新
     * 影响；若让 signOut 去抢 [refreshMutex]，一个挂住的刷新能把登出卡 45 秒。
     *
     * 嵌套顺序固定为 refreshMutex 外、storeMutex 内，无反向获取路径，不会死锁。
     */
    private val storeMutex = Mutex()

    /**
     * 已完成的刷新轮次。**单飞共享的载体**——见 [refresh] 对「轮次」的说明。
     *
     * 用 [MutableStateFlow] 而不是普通 `var`：[id] 要在锁**外**读（那正是它的意义），
     * 不能依赖 [refreshMutex] 提供的内存语义。id 与 outcome 装在同一个对象里，
     * 保证两者被一起读到，不会读出错配的组合。
     */
    private data class RefreshRound(val id: Int, val outcome: RefreshOutcome?)

    private val lastRound = MutableStateFlow(RefreshRound(id = 0, outcome = null))

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * 从存储恢复登录态。App 启动时调一次，之后 [authState] 才有意义。
     *
     * 顺带排空停泊的 OAuth 回跳（进程在浏览器授权期间被系统回收的场景，
     * 见 [handleOAuthCallback]）：有停泊的 URL 时这里会发起一次 otc 兑换，
     * 其结果照常从 [oauthResults] 送达，本方法返回**排空之后**的状态。
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
            cooldownSeconds = body.intOrNull("cooldownSeconds") ?: DEFAULT_COOLDOWN
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

    // ---- 社交登录（OAuth 回跳） ----

    private val _oauthResults = MutableSharedFlow<OAuthOutcome>(
        replay = 1, // 兜「投递早于订阅」：进程回收后 restore() 里的处理先于 UI 订阅
        extraBufferCapacity = 1,
    )

    /**
     * 消费方唯一的 OAuth 结果通道。
     *
     * 发起登录的 UI 可能在浏览器往返期间被重建（屏幕旋转）甚至随进程消失（系统回收），
     * 所以结果不走「发起调用的返回值」，只从这里广播——UI 在一处 `collect`，一处
     * 处理全部五种 [OAuthOutcome]。
     *
     * `replay = 1` 只为兜「投递早于订阅」的时序，**不是历史记录**：处理完结果调
     * [consumeOauthResult] 清掉，否则后来的订阅者（如登录后才打开的绑定页）会收到
     * 早已处理过的陈旧结果。
     */
    val oauthResults: SharedFlow<OAuthOutcome> = _oauthResults.asSharedFlow()

    /** 声明 [oauthResults] 的当前结果已处理完，清掉 replay 缓存。 */
    fun consumeOauthResult() {
        _oauthResults.resetReplayCache()
    }

    /**
     * 最近一次 otc 兑换及其结果，[oauthMutex] 保护。
     *
     * otc 单次有效、兑换即销毁，而回跳 URL 的送达次数库控制不了（用户点浏览器历史里
     * 的链接、ROM 重放 intent）。不去重的话，重复送达的第二次兑换会得到
     * `invalid_otc`——与「真过期」无法区分，用户刚登录成功就看到一条假失败。
     */
    private var lastOtcExchange: Pair<String, OAuthOutcome>? = null

    /** 保护 [lastOtcExchange]，并把重复回跳的并发兑换收敛成一次（同 refresh 的单飞思路） */
    private val oauthMutex = Mutex()

    /**
     * 处理一次 OAuth 回跳。**所有通路的唯一汇合点**：浏览器模块的两条通路、
     * 进程回收后的停泊排空（[restore]）、以及自定义流程的直接调用都汇到这里。
     *
     * - 幂等：同一 otc 只兑换一次，重复送入返回缓存结果、不打服务端，也**不重复投递**
     * - 结果（首次处理的）必发一份到 [oauthResults]——UI 只看那里；返回值给直接调用方
     *   （自定义流程、测试）用
     * - 对任何输入**不抛业务异常**：兑换失败映射为 [OAuthOutcome.Failed]，认不出的
     *   形状映射为 [OAuthOutcome.Unrecognized]（协程取消照常穿透）
     */
    suspend fun handleOAuthCallback(url: String): OAuthOutcome =
        when (val parsed = OAuthCallbackParams.parse(url)) {
            is OAuthCallbackParams.Login -> exchangeOtcIdempotent(parsed.otc)
            is OAuthCallbackParams.Linked ->
                publish(OAuthOutcome.Linked(OAuthProvider(parsed.provider)))
            is OAuthCallbackParams.Failed -> publish(OAuthOutcome.Failed(parsed.reason))
            OAuthCallbackParams.Unrecognized -> publish(OAuthOutcome.Unrecognized(url))
        }

    private suspend fun exchangeOtcIdempotent(otc: String): OAuthOutcome =
        oauthMutex.withLock {
            // 重复送入：返回缓存结果，不打服务端、不重复投递。失败结果同样缓存——
            // 重复送达的是同一次流程，它的答案不该因为问了两遍而变化
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
        return body.stringOrNull("authorizeUrl")
            ?: throw LoginbaseException.MalformedResponse("authorizeUrl")
    }

    // ---- 会话 ----

    /**
     * 取当前 access token。
     *
     * 忘了调 [restore] 也没关系：本方法反正要读一次存储，顺手把还停在
     * [AuthState.Unknown] 的状态补齐——否则会出现「[authState] 说 `Unknown`，
     * 而这里已经能返回有效令牌」这种两个对外信号互相矛盾、又没人提醒的局面。
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
        val tokens = tokenStore.load()
        syncStateIfUnknown(tokens)
        return tokens?.accessToken
    }

    // 状态还停在 Unknown（没调过 restore）时，用手头这次存储读取的结果补齐。
    // 只在 Unknown 时动手：别的状态都是别处根据更完整的信息写下的，不该被覆盖。
    private fun syncStateIfUnknown(tokens: TokenPair?) {
        if (_authState.value != AuthState.Unknown) return
        _authState.value = if (tokens != null) AuthState.SignedIn
        else AuthState.SignedOut(SignOutReason.NoSession)
    }

    /**
     * `POST /refresh`，**单飞**。
     *
     * 并发刷新会烧掉服务端的救活配额（1h/3 次护栏，超了整条会话按盗用撤销），所以
     * 同一时刻至多一条真实刷新在飞。完整论证见 `docs/design.md` 第 1 节。
     *
     * ## 什么是「一轮」
     *
     * 一轮 = 一次真正打到服务端的尝试（[runRound]），无论结果是成功、会话终结还是
     * 失败。每跑完一轮，[lastRound] 的编号 +1 并记下结果。调用方在**进锁前**记下当时
     * 的编号，进锁后编号变了就说明「我排队这段时间里有人替我把活干了」，直接复用。
     *
     * **失败也共享，这是关键**：刷新失败时存储纹丝不动，等待者看到的世界和「一次刷新
     * 都没发生过」一模一样，于是各自又发一次——那正是烧配额的路径。
     *
     * 共享的边界是「同一批等待者」，**不需要 TTL**：一轮结束之后才进来的调用方，
     * 读到的已经是新编号，进锁后发现编号没变，自然会发起真实刷新。
     *
     * 与 Logto 时代的一处差异：那时要给锁加 30 秒超时守卫，因为刷新藏在 SDK 的异步
     * 回调里、解析炸掉时 completion 永远不来。这里 HTTP 调用是我们自己的，`withLock`
     * 的 finally 保证锁必定释放——而**请求本身必有限时**：client 由本库自建，
     * `HttpTimeout` 必然装上（见 [LoginbaseConfig.httpEngine] 为什么只收 engine 不收
     * client）。「一个挂住的请求永久持锁、等锁的调用全部卡死」这件事因此在源头上
     * 就不可能发生，不必再叠一层守卫。
     */
    suspend fun refresh(): RefreshOutcome {
        val existing = tokenStore.load()
        syncStateIfUnknown(existing) // 与 accessToken 同一条不变式：读过存储就不再是 Unknown
        if (existing == null) {
            return RefreshOutcome.NoSession
                .also { signedOutUnlessAlready(SignOutReason.NoSession) }
        }

        // 【必须在锁外读】记的是「我什么时候来排队的」。挪进锁里它就永远等于当前值，
        // 判断恒不成立，整个单飞静默退化成「各刷各的」——不报错、不崩，只是变慢变贵。
        val entryRound = lastRound.value.id

        return refreshMutex.withLock {
            val completed = lastRound.value
            // 【必须排在读存储之前】等锁期间跑完了一整轮，复用它的结果。
            // 排到读存储之后的话，会话被 401 清掉时等待者会先掉进下面的 NoSession
            // 早返回，拿不到 SessionEnded 的归因，这条共享就白做了。
            if (completed.id != entryRound) {
                return@withLock completed.outcome
                    ?: RefreshOutcome.NoSession // 不可达：id 推进过就必然带着结果
            }

            val current = tokenStore.load() ?: run {
                // 等锁期间会话没了（并发 signOut，或别人刷到 401 清了会话）。
                // 那些路径本身都已置过 SignedOut，这里再置一次是幂等的防御——
                // 与锁外的早返回分支对称，不依赖「别人一定记得置」这个不变式。
                // 用 UnlessAlready：别人写下的原因比这里的 NoSession 精确得多。
                //
                // 这条**不记轮次**：一个字节都没发出去，不构成一轮。
                signedOutUnlessAlready(SignOutReason.NoSession)
                return@withLock RefreshOutcome.NoSession
            }

            val outcome = runRound(current)
            lastRound.value = RefreshRound(entryRound + 1, outcome)
            outcome
        }
    }

    /**
     * 一次真实的刷新尝试：发请求、判定结果、必要时落盘。
     *
     * **假定调用方已持有 [refreshMutex]**，本方法自己不加锁。
     *
     * 单独抽出来是为了把「一次尝试做什么」和「谁有资格尝试」分开：前者是一条直线，
     * 后者是并发编排。所有失败分支都是本方法的 `return`，于是调用处能在唯一一个
     * 地方处置本轮结果——新增分支在语法上绕不过去。
     */
    private suspend fun runRound(current: TokenPair): RefreshOutcome {
        val response = try {
            // 请求必定有限时——client 由本库自建、[installTimeout] 必然生效，所以「挂住的
            // 请求永久持有单飞锁」这件事在源头上就不可能发生，不需要再套一层 withTimeout。
            http.post("$base/refresh") {
                contentType(ContentType.Application.Json)
                setBody(jsonBody(mapOf("refreshToken" to current.refreshToken)))
            }
        } catch (e: CancellationException) {
            // 只有**当前协程确实被取消**才如实传播。ktor 会在 client 的 job 被 cancel 时
            // 连带取消在途请求——消费方退登/重建 DI 时 `close()` 掉自己的 HttpClient
            // 就是这个形态，那个 CancellationException 与调用方无关。原样抛出的话，
            // 调用方的 launch 会当成「自己被取消」而静默丢弃，UI 永远等不到 Failed。
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
            // 取消如实传播，不吞成 Failed。这里**不需要** [runRound] 里那道
            // `ensureActive()` 守卫：[TokenStore] 是我们直接调用的，没有第三方能从旁边
            // 取消它，所以这个取消必然来自调用方本身。
            throw e
        } catch (e: Exception) {
            return refreshFailed(LoginbaseException.Storage(e))
        }
        if (!persisted) {
            // 不碰 authState：清会话那一方已经写下了更准确的原因
            return RefreshOutcome.NoSession
        }
        return RefreshOutcome.Success(tokens)
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
    suspend fun signOut(): Unit = signOutInternal("$base/sessions")

    /** 登出该用户全部会话：`DELETE /sessions/all` + 清本地。同样尽力而为。 */
    suspend fun signOutAll(): Unit = signOutInternal("$base/sessions/all")

    /**
     * [signOut] / [signOutAll] 的共同实现——登出的取消策略只写这一遍。
     *
     * 服务端那一步是尽力而为，失败**和被取消**都不该拦住本地登出：用户点了登出，
     * 中途界面被销毁、协程被取消，结果却还登录着，是最难排查的一类状态不一致。
     * 故本地清除放在 `finally` 里、包在 [NonCancellable] 中，取消随后照常向上传播——
     * 调用方的协程正常结束，而不是被伪装成「登出成功」。
     *
     * 顺带修掉原来的 `runCatching`：它连 [CancellationException] 一起吞，
     * 已取消的协程会若无其事地继续往下走。
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
                    // 尽力而为：服务端那条会话最坏留到 refresh token 自然失效
                    // 或被重用检测清掉，不该因此把用户卡在登录态
                }
            }
        } finally {
            withContext(NonCancellable) { clearLocally() }
        }
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

    /**
     * 关闭本库的 [HttpClient]。
     *
     * **不会关掉你提供的 [LoginbaseConfig.httpEngine]**——ktor 的 `HttpClient(engine)`
     * 走 `manageEngine = false`，engine 的生命周期始终归你。没给 engine 时关的是
     * ktor 自行发现并由本库托管的那个。
     *
     * 没走过任何 HTTP 的实例不会因此白建一个 client（自建是惰性的）。
     *
     * 按文档把 [AuthClient] 当每进程一个的单例持有时，通常一辈子都不用调这个；
     * 它是给测试和 DI 图重建这类会反复创建实例的场景准备的。调用之后本实例不应再使用。
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
            val raw = body?.stringOrNull("error").orEmpty()
            // 响应体不是协议 JSON（网关的 HTML 错误页、空体等）时 raw 为空，
            // 回退成 http_<status> 而不是留个空串——否则异常里没有任何可诊断信息
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

    // 响应体不是协议 JSON（网关的 HTML 错误页、空体等）时返回 null，由调用处回退。
    // 不用 runCatching：它连 CancellationException 一起吞——bodyAsText() 是 suspend 的，
    // 调用方取消会被伪装成「响应体解析不出来」，已取消的协程继续往下跑，破坏结构化并发。
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
