package wang.harlon.loginbase

/**
 * 消费方 App 的自述标识（服务端 protocol.md「客户端标识」节，1.9.0 起）。
 * [version] 与 [platform] 以结构化头 / start 参数上报，是服务端统计的切片轴；
 * [app] 与 [comment] 只进 `User-Agent`，供人工排障，服务端不解析。
 *
 * @param app 产品令牌名，如 `TrendingAI`
 * @param version App 版本，须匹配 `[0-9A-Za-z.+-]{1,32}`（服务端同一条规则，不合规静默丢弃，故这里提前拦）
 * @param platform 运行平台
 * @param comment UA 括号段自由文本，如 `Android 14; Pixel 7; channel=play`；不能含括号与换行
 */
data class ClientInfo(
    val app: String,
    val version: String,
    val platform: ClientPlatform,
    val comment: String? = null,
) {
    init {
        require(app.isNotBlank() && !app.contains(Regex("[\\s/()]"))) {
            "[loginbase] ClientInfo.app 须为单个产品令牌（无空白、/、括号），收到 \"$app\""
        }
        require(VERSION_PATTERN.matches(version)) {
            "[loginbase] ClientInfo.version 须匹配 $VERSION_PATTERN，收到 \"$version\""
        }
        require(comment == null || !comment.contains(Regex("[()\\r\\n]"))) {
            "[loginbase] ClientInfo.comment 不能含括号与换行，收到 \"$comment\""
        }
    }

    /** `App/1.5.0 (comment) loginbase-kt/0.4.0`：RFC 9110 产品令牌，库自己的令牌追加在末尾 */
    internal fun userAgent(): String = buildString {
        append(app).append('/').append(version)
        comment?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
        append(" loginbase-kt/").append(LIBRARY_VERSION)
    }

    private companion object {
        val VERSION_PATTERN = Regex("[0-9A-Za-z.+-]{1,32}")
    }
}

/** 服务端白名单枚举（protocol.md「客户端标识」节）；wire 值即小写名 */
enum class ClientPlatform(internal val wire: String) {
    ANDROID("android"),
    IOS("ios"),
    WEB("web"),
    DESKTOP("desktop"),
}

internal const val CLIENT_VERSION_HEADER = "X-Client-Version"
internal const val CLIENT_PLATFORM_HEADER = "X-Client-Platform"
