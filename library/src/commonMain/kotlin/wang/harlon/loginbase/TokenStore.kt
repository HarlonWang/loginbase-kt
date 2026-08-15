package wang.harlon.loginbase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一对令牌。`accessToken` 是 JWT，但客户端不校验也不解析——原样存、原样用。 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)

/**
 * 令牌持久化。唯一硬要求：**[save] 返回时数据已落盘**——异步落盘在进程被杀时
 * 丢掉刚轮换的令牌，反复触发会撞穿服务端救活护栏（docs/design.md 第 1 节）。
 */
interface TokenStore {
    suspend fun load(): TokenPair?

    /** 必须同步落盘后再返回 */
    suspend fun save(tokens: TokenPair)

    suspend fun clear()
}

/** 进程内实现，不落盘，**测试替身**。带互斥：会被并发测试直接使用。 */
class InMemoryTokenStore(initial: TokenPair? = null) : TokenStore {
    private val mutex = Mutex()
    private var tokens: TokenPair? = initial

    override suspend fun load(): TokenPair? = mutex.withLock { tokens }

    override suspend fun save(tokens: TokenPair) {
        mutex.withLock { this.tokens = tokens }
    }

    override suspend fun clear() {
        mutex.withLock { tokens = null }
    }
}
