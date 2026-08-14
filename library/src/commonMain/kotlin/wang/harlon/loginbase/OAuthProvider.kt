package wang.harlon.loginbase

import kotlin.jvm.JvmInline

/**
 * OAuth 身份提供方。在协议里它就是一个路径段：`/oauth/{provider}/start`。
 *
 * **用 value class 而不是 enum**：服务端加一个 provider 不该逼客户端跟着发版。
 * 服务端的 provider 集合由服务端 App 自己配置（本库连它启用了哪几个都不知道），
 * 枚举意味着每次配置变化都是一次客户端 breaking change。已知的放在 [Companion]
 * 里，拿到补全与拼写保护；没列进来的直接 `OAuthProvider("google")` 即可。
 *
 * [id] 会被 URL 路径编码后拼进请求，故传什么都不会越出路径段。
 */
@JvmInline
public value class OAuthProvider(public val id: String) {

    init {
        require(id.isNotBlank()) { "OAuth provider id must not be blank" }
    }

    override fun toString(): String = id

    public companion object {
        public val GitHub: OAuthProvider = OAuthProvider("github")
    }
}
