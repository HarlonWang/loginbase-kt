package wang.harlon.loginbase

import platform.Foundation.NSUserDefaults

/**
 * iOS 令牌存储：NSUserDefaults。
 *
 * `synchronize()`：满足 [TokenStore] 的同步落盘硬要求（理由同 Android 侧的 `commit()`）。
 * 现代 iOS 上它已非必需（系统会适时写入），但显式调用把该语义摆在明面上，别删。
 * 未做 Keychain；确有需要的 App 自己实现 [TokenStore]。
 *
 * 注：尚未在真机链路上验证过（CI 也不编 iOS）。
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
