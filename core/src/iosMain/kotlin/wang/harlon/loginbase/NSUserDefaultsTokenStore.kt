package wang.harlon.loginbase

import platform.Foundation.NSUserDefaults

/**
 * iOS 令牌存储：NSUserDefaults。
 *
 * `synchronize()` 强制立即落盘——理由同 Android 侧的 `commit()`：轮换到的新令牌
 * 必须在 [save] 返回前落盘，否则进程被杀会触发服务端的丢回执救活（有护栏）。
 * 该方法在现代 iOS 上已非必需（系统会适时写入），但显式调用把「同步落盘」这个
 * 与服务端配套的语义摆在明面上，代价可忽略。
 *
 * 未做 Keychain：与 Android 侧一致，保持 Logto 时代的存储强度；Keychain 需要
 * cinterop 与更多平台代码，确有需要的 App 自己实现 [TokenStore]。
 *
 * 注：本实现是为 target 完整性与将来准备，尚未在真机链路上验证过（CI 也不编 iOS）。
 */
class NSUserDefaultsTokenStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val keyPrefix: String = "loginbase.",
) : TokenStore {

    private val accessKey get() = "${keyPrefix}access_token"
    private val refreshKey get() = "${keyPrefix}refresh_token"

    override suspend fun load(): TokenPair? {
        val access = defaults.stringForKey(accessKey)
        val refresh = defaults.stringForKey(refreshKey)
        return if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) null
        else TokenPair(access, refresh)
    }

    override suspend fun save(tokens: TokenPair) {
        defaults.setObject(tokens.accessToken, accessKey)
        defaults.setObject(tokens.refreshToken, refreshKey)
        defaults.synchronize()
    }

    override suspend fun clear() {
        defaults.removeObjectForKey(accessKey)
        defaults.removeObjectForKey(refreshKey)
        defaults.synchronize()
    }
}
