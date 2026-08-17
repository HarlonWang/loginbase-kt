package wang.harlon.loginbase.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import wang.harlon.loginbase.AuthClient
import wang.harlon.loginbase.Loginbase
import wang.harlon.loginbase.OAuthProvider

/**
 * 发起社交登录。**不挂起**——启动管理页即返回，结果只从 [AuthClient.oauthResults]
 * 送达（挂起返回值在屏幕旋转、进程回收下必然中断）。
 * 浏览器按 Auth Tab → Custom Tab → 系统浏览器的可用性回退；回跳捕获、解析、取消
 * 判定、进程回收兜底全归库，消费方只需每变体一行 placeholder。
 *
 * @param redirect 不传则由 manifest 的 meta-data 推导（[Loginbase.redirectUri]），
 *   与 intent-filter 同源；自建 scheme / https app-link 才需要显式传
 */
fun AuthClient.signIn(
    activity: Activity,
    provider: OAuthProvider,
    redirect: String = Loginbase.redirectUri(activity),
) {
    startFlow(activity, LoginbaseAuthActivity.MODE_SIGN_IN, provider, redirect)
}

/**
 * 已登录用户绑定第二身份：登录/绑定回跳的差异由库分辨，消费方在同一个
 * [AuthClient.oauthResults] 里拿 [wang.harlon.loginbase.OAuthOutcome.Linked]。其余同 [signIn]。
 */
fun AuthClient.link(
    activity: Activity,
    provider: OAuthProvider,
    redirect: String = Loginbase.redirectUri(activity),
) {
    startFlow(activity, LoginbaseAuthActivity.MODE_LINK, provider, redirect)
}

/**
 * 本 App 的 OAuth 回跳 redirect（`<scheme>:/loginbase/callback`，单斜杠无 host）。
 * scheme 读自 manifest meta-data，与中转页 intent-filter 同一个 placeholder。
 * **服务端白名单要配的就是这个值**（debug/release 变体各一条）。
 */
fun Loginbase.redirectUri(context: Context): String =
    "${redirectScheme(context)}:/loginbase/callback"

private fun redirectScheme(context: Context): String {
    val metaData = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).metaData
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        ).metaData
    }
    return metaData?.getString(META_REDIRECT_SCHEME)?.takeIf { it.isNotBlank() }
        ?: error(
            "[loginbase] manifest 里读不到 meta-data `$META_REDIRECT_SCHEME`。" +
                "请在 build.gradle 的每个变体里配 manifestPlaceholders[\"loginbaseRedirectScheme\"]" +
                "（值用自有域名反写，如 cn.example）。正常情况下漏配会直接构建失败，" +
                "能走到这里多半是 manifest 合并被 tools: 指令改过",
        )
}

private fun AuthClient.startFlow(
    activity: Activity,
    mode: String,
    provider: OAuthProvider,
    redirect: String,
) {
    preflight(activity, redirect)
    // 静态槽：发起方就是自己，不需要消费方注册；进程死亡后槽消失，届时走停泊通路
    OAuthFlowRuntime.activeClient = this
    activity.startActivity(
        Intent(activity, LoginbaseAuthActivity::class.java)
            .putExtra(LoginbaseAuthActivity.EXTRA_MODE, mode)
            .putExtra(LoginbaseAuthActivity.EXTRA_PROVIDER, provider.id)
            .putExtra(LoginbaseAuthActivity.EXTRA_REDIRECT, redirect),
    )
}

/**
 * 发起前自检：拉起浏览器之前把「三处一致」里客户端能查的两处查掉——错误在发起点
 * 就地爆出，比「用户授权完卡在打不开的页面」早一整个浏览器往返。
 */
private fun preflight(context: Context, redirect: String) {
    val probe = Intent(Intent.ACTION_VIEW, redirect.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val resolved = context.packageManager.resolveActivity(probe, 0)
        ?: error(
            "[loginbase] 回跳 redirect（$redirect）没有任何 Activity 认领——" +
                "scheme 写错，或当前构建变体没配 manifestPlaceholders[\"loginbaseRedirectScheme\"]。" +
                "服务端白名单需配同一个值：$redirect；两边不一致时浏览器会停在 " +
                "invalid_redirect，App 侧收不到任何信号",
        )
    val owner = resolved.activityInfo?.packageName
    if (owner != context.packageName) {
        // 不只是配置错误，还是安全信号：scheme 谁都能声明
        error(
            "[loginbase] scheme 被其他应用抢注：$owner 认领了 $redirect，回跳会被它截走。" +
                "请改用自有域名反写的独占 scheme",
        )
    }
    logRedirectOnceInDebug(context, redirect)
}

private var redirectLogged = false

/** debug 构建首次发起时把「该填给服务端的值」打进日志 */
private fun logRedirectOnceInDebug(context: Context, redirect: String) {
    if (redirectLogged) return
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    redirectLogged = true
    Log.i("loginbase", "OAuth redirect = $redirect（服务端 redirect 白名单需包含此值）")
}

internal const val META_REDIRECT_SCHEME = "loginbase.redirectScheme"

/** 进程级运行时：静态槽 + 投递 scope；进程死亡即消失，冷启动走停泊通路。 */
internal object OAuthFlowRuntime {
    @Volatile
    var activeClient: AuthClient? = null

    /** 投递要在管理页 finish 后继续跑完，故不用 Activity 的生命周期 scope */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
