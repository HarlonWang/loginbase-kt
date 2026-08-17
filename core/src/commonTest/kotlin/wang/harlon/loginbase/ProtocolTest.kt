package wang.harlon.loginbase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 协议契约测试（客户端侧）：对着服务端仓 docs/protocol.md 的错误码总表断言。
 * 服务端仓有对称的一套——分仓后两端靠这两套测试 + PROTOCOL_VERSION 常量
 * 表达兼容关系，而不是靠版本号相等。
 */
class ProtocolTest {

    @Test
    fun `错误码 wire 值逐条对齐 protocol_md`() {
        val expected = mapOf(
            AuthError.INVALID_EMAIL to "invalid_email",
            AuthError.TOO_MANY_REQUESTS to "too_many_requests",
            AuthError.CODE_EXPIRED to "code_expired",
            AuthError.INVALID_CODE to "invalid_code",
            AuthError.TOO_MANY_ATTEMPTS to "too_many_attempts",
            AuthError.INVALID_REFRESH_TOKEN to "invalid_refresh_token",
            AuthError.INVALID_REDIRECT to "invalid_redirect",
            AuthError.INVALID_STATE to "invalid_state",
            AuthError.INVALID_OTC to "invalid_otc",
            AuthError.NOT_CONFIGURED to "not_configured",
            AuthError.INTERNAL to "internal",
        )
        expected.forEach { (code, wire) -> assertEquals(wire, code.wire) }
        // 漏网检查：除 UNKNOWN 外每个枚举都必须在上表里，新增错误码时测试会提醒补断言
        val covered = expected.keys + AuthError.UNKNOWN
        assertTrue(AuthError.entries.all { it in covered }, "有错误码未纳入契约断言")
    }

    @Test
    fun `refresh 归因 wire 值逐条对齐 protocol_md`() {
        val expected = mapOf(
            RefreshFailure.MISSING_TOKEN to "missing_token",
            RefreshFailure.SESSION_NOT_FOUND to "session_not_found",
            RefreshFailure.SESSION_REVOKED to "session_revoked",
            RefreshFailure.SESSION_EXPIRED to "session_expired",
            RefreshFailure.ROTATE_FAILED to "rotate_failed",
        )
        expected.forEach { (reason, wire) -> assertEquals(wire, reason.wire) }
        val covered = expected.keys + RefreshFailure.UNKNOWN
        assertTrue(RefreshFailure.entries.all { it in covered }, "有归因未纳入契约断言")
    }

    @Test
    fun `未知 wire 值不炸，落到 UNKNOWN`() {
        // 服务端将来新增错误码时，老客户端必须继续工作
        assertEquals(AuthError.UNKNOWN, AuthError.fromWire("brand_new_error"))
        assertEquals(AuthError.UNKNOWN, AuthError.fromWire(""))
        assertEquals(RefreshFailure.UNKNOWN, RefreshFailure.fromWire("brand_new_reason"))
    }

    @Test
    fun `fromWire 往返一致`() {
        AuthError.entries.filter { it != AuthError.UNKNOWN }.forEach {
            assertEquals(it, AuthError.fromWire(it.wire))
        }
        RefreshFailure.entries.filter { it != RefreshFailure.UNKNOWN }.forEach {
            assertEquals(it, RefreshFailure.fromWire(it.wire))
        }
    }

    @Test
    fun `声明的协议版本非空且为 semver`() {
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(PROTOCOL_VERSION), PROTOCOL_VERSION)
    }
}
