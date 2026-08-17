package wang.harlon.loginbase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 令牌存储：App 私有目录下的 SharedPreferences。
 *
 * **用 `commit()` 而非 `apply()`**——[TokenStore] 的同步落盘硬要求：`apply()` 异步落盘，
 * 进程被杀会丢掉刚轮换的令牌。读写是两个小字符串，同步代价可忽略。
 * 未做加密（私有目录，非 root 不可读）；有需要的 App 自行实现 [TokenStore]。
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
