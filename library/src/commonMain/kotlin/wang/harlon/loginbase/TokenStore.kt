package wang.harlon.loginbase

/**
 * 一对令牌。`refreshToken` 是服务端 32 字节 CSPRNG 的 base64url，服务端只存其 SHA-256；
 * `accessToken` 是 HS256 JWT，客户端**不校验签名也不解析**（见 [AuthClient] 关于时钟偏差的说明）。
 */
public data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)

/**
 * 令牌持久化。实现只需保证一件事，但必须严格保证：
 *
 * **[save] 返回时数据已经落盘。**
 *
 * 理由在服务端：refresh 每次必轮换，若客户端拿到新令牌却没存住就崩溃/被杀，
 * 下次只能拿旧令牌去刷——服务端的「丢回执救活」正是为此设计的，但救活有
 * 1h/3 次护栏，反复丢回执会把整条会话链判成盗用并撤销。异步落盘（如 Android
 * 的 `SharedPreferences.apply()`）在进程被杀时正好制造这种情况，所以平台实现
 * 一律走同步提交。
 *
 * 库不替消费方决定令牌存在哪：Android 需要 `Context`、iOS 可选 suite，都由
 * 消费方在平台代码里构造并注入（见 `SharedPreferencesTokenStore` /
 * `NSUserDefaultsTokenStore`）。这也让存储命名空间掌握在 App 手里，
 * 同设备多 App 不会撞在库的默认值上。
 */
public interface TokenStore {
    public suspend fun load(): TokenPair?

    /** 必须同步落盘后再返回 */
    public suspend fun save(tokens: TokenPair)

    public suspend fun clear()
}

/**
 * 进程内实现，不落盘。用于测试，以及「本次启动内有效即可」的场景。
 * 生产别用——进程重启即登出。
 */
public class InMemoryTokenStore(initial: TokenPair? = null) : TokenStore {
    private var tokens: TokenPair? = initial

    override suspend fun load(): TokenPair? = tokens

    override suspend fun save(tokens: TokenPair) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
