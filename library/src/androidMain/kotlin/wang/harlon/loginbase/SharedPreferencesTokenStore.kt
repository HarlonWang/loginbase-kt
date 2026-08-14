package wang.harlon.loginbase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 令牌存储：App 私有目录下的 SharedPreferences。
 *
 * **用 `commit()` 而非 `apply()`**——这是与服务端救活机制配套的关键，不是性能疏忽：
 * `apply()` 只把改动写进内存并异步落盘，进程在落盘前被杀就会丢掉刚轮换到的令牌，
 * 下次拿旧令牌去刷即触发服务端的丢回执救活（1h/3 次护栏，反复触发会撤销整条会话链）。
 * 令牌读写是两个小字符串、每小时至多几次，同步提交的代价可以忽略。
 *
 * 未做加密：与 Logto SDK 时代的存储强度一致（App 私有目录，非 root 不可读）。
 * `androidx.security` 的 EncryptedSharedPreferences 已被 Google 废弃，为此引依赖
 * 不划算；确有需要的 App 自己实现 [TokenStore] 即可。
 */
class SharedPreferencesTokenStore(
    context: Context,
    fileName: String = "loginbase_tokens",
) : TokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        fileName,
        Context.MODE_PRIVATE,
    )

    override suspend fun load(): TokenPair? = withContext(Dispatchers.IO) {
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) null
        else TokenPair(access, refresh)
    }

    override suspend fun save(tokens: TokenPair) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_ACCESS, tokens.accessToken)
                .putString(KEY_REFRESH, tokens.refreshToken)
                .commit() // 同步落盘，见类注释
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).commit()
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
    }
}
