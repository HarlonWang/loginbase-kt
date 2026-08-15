package wang.harlon.loginbase.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import wang.harlon.loginbase.AuthClient
import wang.harlon.loginbase.Loginbase
import wang.harlon.loginbase.OAuthProvider

/**
 * 发起社交登录。**不挂起**——启动管理页即返回，结果只从 [AuthClient.oauthResults]
 * 送达（挂起返回值在屏幕旋转、进程回收下必然中断，见 design §11 差异 #5）。
 *
 * 浏览器按回退链选择：Custom Tab（任何实现 CustomTabsService 的浏览器）→ 系统浏览器；
 * Auth Tab 优先级在 2b 期加入。回跳的捕获、解析、取消判定、进程回收兜底全归库，
 * 消费方只需在 gradle 里给每个变体一行 `manifestPlaceholders["loginbaseRedirectScheme"]`。
 *
 * @param redirect 不传则由 manifest 的 meta-data 推导（[Loginbase.redirectUri]），
 *   与 intent-filter 同源、不可能漂移；自建 scheme / https app-link 才需要显式传
 */
public fun AuthClient.signIn(
    activity: Activity,
    provider: OAuthProvider,
    redirect: String = Loginbase.redirectUri(activity),
) {
    startFlow(activity, LoginbaseAuthActivity.MODE_SIGN_IN, provider, redirect)
}

/**
 * 已登录用户绑定第二身份。回跳参数与登录的差异（`linked=` / `error=` vs `otc=`）
 * 由库分辨，消费方在同一个 [AuthClient.oauthResults] 里拿 [wang.harlon.loginbase.OAuthOutcome.Linked]。
 * 其余同 [signIn]。
 */
public fun AuthClient.link(
    activity: Activity,
    provider: OAuthProvider,
    redirect: String = Loginbase.redirectUri(activity),
) {
    startFlow(activity, LoginbaseAuthActivity.MODE_LINK, provider, redirect)
}

/**
 * 本 App 的 OAuth 回跳 redirect（`<scheme>:/loginbase/callback`，无 host 单斜杠——
 * RFC 8252 §7.1 示例形态，design §11 差异 #4）。scheme 读自 manifest 合并后的
 * meta-data，与中转页 intent-filter 用同一个 placeholder。
 *
 * **服务端 redirect 白名单要配的就是这个值**（debug/release 变体各一条）。
 */
public fun Loginbase.redirectUri(context: Context): String =
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
    // 静态槽（design §5.5）：发起方就是自己，不需要消费方注册。进程死亡后槽随之
    // 消失，届时走停泊通路
    OAuthFlowRuntime.activeClient = this
    activity.startActivity(
        Intent(activity, LoginbaseAuthActivity::class.java)
            .putExtra(LoginbaseAuthActivity.EXTRA_MODE, mode)
            .putExtra(LoginbaseAuthActivity.EXTRA_PROVIDER, provider.id)
            .putExtra(LoginbaseAuthActivity.EXTRA_REDIRECT, redirect),
    )
}

/**
 * 发起前自检（design §5.3 感知机制第 2 层）：拉起浏览器**之前**把「三处一致」里
 * 客户端能查的两处查掉。错误在发起点就地爆出来，比「用户授权完卡在打不开的页面」
 * 早了一整个浏览器往返，且消息里带上服务端那一半的提醒。
 */
private fun preflight(context: Context, redirect: String) {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse(redirect))
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
        // 这不只是配置错误，还是安全信号：scheme 谁都能声明（RFC 8252 承认的固有弱点）
        error(
            "[loginbase] scheme 被其他应用抢注：$owner 认领了 $redirect，回跳会被它截走。" +
                "请改用自有域名反写的独占 scheme",
        )
    }
    logRedirectOnceInDebug(context, redirect)
}

private var redirectLogged = false

/** design §5.3 感知机制第 4 层：debug 构建首次发起时把「该填给服务端的值」打进日志 */
private fun logRedirectOnceInDebug(context: Context, redirect: String) {
    if (redirectLogged) return
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    redirectLogged = true
    Log.i("loginbase", "OAuth redirect = $redirect（服务端 redirect 白名单需包含此值）")
}

internal const val META_REDIRECT_SCHEME = "loginbase.redirectScheme"

/** 进程级运行时：静态槽 + 投递用的 scope。进程死亡即消失，冷启动走停泊通路。 */
internal object OAuthFlowRuntime {
    @Volatile
    var activeClient: AuthClient? = null

    /** 投递要在管理页 finish 之后继续跑完（exchangeOtc 是网络往返），故不用 Activity 的生命周期 scope */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
