package wang.harlon.loginbase

import kotlin.jvm.JvmInline

/**
 * OAuth 身份提供方，协议里就是一个路径段。value class 而非 enum：服务端加 provider
 * 不该逼客户端发版，未列进 [Companion] 的直接 `OAuthProvider("google")`。
 * [id] 会被路径编码，传什么都越不出路径段。
 */
@JvmInline
value class OAuthProvider(val id: String) {

    init {
        require(id.isNotBlank()) { "OAuth provider id must not be blank" }
    }

    override fun toString(): String = id

    companion object {
        val GitHub: OAuthProvider = OAuthProvider("github")
    }
}
