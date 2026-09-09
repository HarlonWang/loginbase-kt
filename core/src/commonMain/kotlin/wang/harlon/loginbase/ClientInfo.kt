package wang.harlon.loginbase

/**
 * 消费方 App 的自述标识（服务端 protocol.md「客户端标识」节，1.9.0 起）。
 * [version] 与 [platform] 以结构化头 / start 参数上报，是服务端统计的切片轴；
 * [appName] 与 [deviceInfo] 只进 `User-Agent`，供人工排障，服务端不解析。
 *
 * @param appName UA 产品令牌名，如 `TrendingAI`（不是包名 / bundle id）；须为 RFC 9110 的 token 字符
 * @param version App 版本，须匹配 `[0-9A-Za-z.+-]{1,32}`（服务端同一条规则，不合规静默丢弃，故这里提前拦）
 * @param platform 运行平台
 * @param deviceInfo 机型、系统版本、渠道等，进 UA 括号段，如 `Android 14; Pixel 7; channel=play`；不能含括号与控制字符
 */
data class ClientInfo(
    val appName: String,
    val version: String,
    val platform: ClientPlatform,
    val deviceInfo: String? = null,
) {
    init {
        require(TOKEN_PATTERN.matches(appName)) {
            "[loginbase] ClientInfo.appName 须为 RFC 9110 token（$TOKEN_PATTERN），收到 \"$appName\""
        }
        require(VERSION_PATTERN.matches(version)) {
            "[loginbase] ClientInfo.version 须匹配 $VERSION_PATTERN，收到 \"$version\""
        }
        require(deviceInfo == null || !deviceInfo.contains(COMMENT_FORBIDDEN)) {
            "[loginbase] ClientInfo.deviceInfo 不能含括号与控制字符，收到 \"$deviceInfo\""
        }
    }

    /** `App/1.5.0 (deviceInfo) loginbase-kt/0.4.0`：RFC 9110 产品令牌，库自己的令牌追加在末尾 */
    internal fun userAgent(): String = buildString {
        append(appName).append('/').append(version)
        deviceInfo?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
        append(" loginbase-kt/").append(LIBRARY_VERSION)
    }

    private companion object {
        val VERSION_PATTERN = Regex("[0-9A-Za-z.+-]{1,32}")

        // RFC 9110 §5.6.2 tchar：UA 产品令牌只能由这些字符组成，超出即不合法的头值
        val TOKEN_PATTERN = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")

        // 括号会终结 UA 的 comment 段；控制字符（含 CR / LF）不能进头值
        val COMMENT_FORBIDDEN = Regex("[()\\x00-\\x1f\\x7f]")
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
