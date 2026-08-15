package wang.harlon.loginbase.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import wang.harlon.loginbase.AuthClient
import wang.harlon.loginbase.LoginbaseException
import wang.harlon.loginbase.LoginbaseInternalApi
import wang.harlon.loginbase.LoginbaseModuleBridge
import wang.harlon.loginbase.OAuthOutcome
import wang.harlon.loginbase.OAuthProvider
import wang.harlon.loginbase.oauthFailureReason

/**
 * 管理页：`signIn()/link()` 启动它，压在浏览器页下面当锚点——开浏览器、收结果、
 * 判取消、决定去向、投递。判定全在 [OAuthFlowController]，本类只是执行者。
 * 透明 + 不 exported + singleTask（只收库内转发，不接浏览器外部 intent）。
 */
@OptIn(LoginbaseInternalApi::class)
internal class LoginbaseAuthActivity : ComponentActivity() {

    private lateinit var controller: OAuthFlowController

    /**
     * Auth Tab 的结果通道：回跳在 Tab 内被捕获、经 ActivityResult 直达这里，不经
     * Intent 与中转页。属性初始化器 = 构造期注册，满足「STARTED 之前」的要求。
     */
    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        when (val action = mapAuthTabResult(result.resultCode, result.resultUri?.toString())) {
            is FlowAction.Deliver -> deliver(action.url)
            FlowAction.DeliverCancelled -> {
                publishAsync(OAuthOutcome.Cancelled)
                finish()
            }
            is FlowAction.DeliverFailed -> {
                publishAsync(OAuthOutcome.Failed(action.reason))
                finish()
            }
            // mapAuthTabResult 只产出上面三种；穷尽 when 防新增动作被静默漏接
            FlowAction.LaunchBrowser, FlowAction.FinishUnexpected -> finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = OAuthFlowController(
            intentLaunched = savedInstanceState?.getBoolean(KEY_INTENT_LAUNCHED) == true,
        )
        controller.onIntent(intent?.dataString)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.onIntent(intent.dataString)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INTENT_LAUNCHED, controller.intentLaunched)
    }

    override fun onResume() {
        super.onResume()
        // Auth Tab 的结果回调先于 onResume 且已 finish；不挡会把这次 resume 误判成取消
        if (isFinishing) return
        when (val action = controller.onResume(
            hasLaunchRequest = intent?.hasExtra(EXTRA_MODE) == true,
        )) {
            is FlowAction.Deliver -> deliver(action.url)
            FlowAction.LaunchBrowser -> launchBrowser()
            FlowAction.FinishUnexpected -> finish()
            FlowAction.DeliverCancelled -> {
                publishAsync(OAuthOutcome.Cancelled)
                finish()
            }
            is FlowAction.DeliverFailed -> {
                publishAsync(OAuthOutcome.Failed(action.reason))
                finish()
            }
        }
    }

    /**
     * 回跳送达：App 活着交给静态槽里的 [AuthClient]、finish 原地露出发起页；
     * 进程被回收（槽空）则停泊 URL → 起 launcher，冷启动时 `restore()` 排空。
     */
    private fun deliver(url: String) {
        val client = OAuthFlowRuntime.activeClient
        if (client != null) {
            // 进程级 scope：投递要在本页 finish 后继续跑完（exchangeOtc 是网络往返）
            OAuthFlowRuntime.scope.launch { client.handleOAuthCallback(url) }
        } else {
            LoginbaseModuleBridge.parkOAuthCallback(url)
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        }
        finish()
    }

    private fun launchBrowser() {
        // 只有 signIn()/link() 刚启动本页才走到这里，槽必然有值；空值属防御，静默收场
        val client = OAuthFlowRuntime.activeClient ?: run { finish(); return }
        val provider = OAuthProvider(intent.getStringExtra(EXTRA_PROVIDER) ?: run { finish(); return })
        val redirect = intent.getStringExtra(EXTRA_REDIRECT) ?: run { finish(); return }

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SIGN_IN -> openBrowser(client, client.signInUrl(provider, redirect), redirect)
            MODE_LINK -> OAuthFlowRuntime.scope.launch {
                // link 的授权 URL 要先带 Bearer POST 换取，这次往返里用户停在透明页上
                val url = try {
                    client.linkUrl(provider, redirect)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LoginbaseException) {
                    client.publishOAuthOutcome(OAuthOutcome.Failed(e.oauthFailureReason()))
                    finish()
                    return@launch
                }
                if (isFinishing || isDestroyed) {
                    // 等待期间用户按返回退出了：流程已被放弃
                    client.publishOAuthOutcome(OAuthOutcome.Cancelled)
                } else {
                    openBrowser(client, url, redirect)
                }
            }
            else -> finish()
        }
    }

    /** 三级回退链：Auth Tab → Custom Tab → 系统浏览器，按可用性回退 */
    private fun openBrowser(client: AuthClient, url: String, redirect: String) {
        val uri = Uri.parse(url)
        try {
            val cctPackage = CustomTabsClient.getPackageName(this, null)
            val tier = selectBrowserTier(
                authTabSupported = cctPackage != null &&
                    CustomTabsClient.isAuthTabSupported(this, cctPackage),
                cctPackage = cctPackage,
            )
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.util.Log.i("loginbase", "browser tier = $tier (provider = $cctPackage)")
            }
            when (tier) {
                BrowserTier.AUTH_TAB -> AuthTabIntent.Builder().build()
                    .apply { intent.setPackage(cctPackage) }
                    // 第三参是回跳 scheme，浏览器据此在 Tab 内截住回跳；缺 scheme 属防御不可达
                    .launch(authTabLauncher, uri, requireNotNull(Uri.parse(redirect).scheme))
                BrowserTier.CUSTOM_TAB -> CustomTabsIntent.Builder().build()
                    .apply { intent.setPackage(cctPackage) } // 锁定探测到的 provider，防被 App Links 截走
                    .launchUrl(this, uri)
                BrowserTier.SYSTEM_BROWSER -> startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: ActivityNotFoundException) {
            // 设备上一个能开 http(s) 的应用都没有——必须给出结果而不是挂死
            OAuthFlowRuntime.scope.launch {
                client.publishOAuthOutcome(OAuthOutcome.Failed("no_browser"))
            }
            finish()
        }
    }

    private fun publishAsync(outcome: OAuthOutcome) {
        val client = OAuthFlowRuntime.activeClient ?: return
        OAuthFlowRuntime.scope.launch { client.publishOAuthOutcome(outcome) }
    }

    internal companion object {
        const val EXTRA_MODE = "wang.harlon.loginbase.MODE"
        const val EXTRA_PROVIDER = "wang.harlon.loginbase.PROVIDER"
        const val EXTRA_REDIRECT = "wang.harlon.loginbase.REDIRECT"
        const val MODE_SIGN_IN = "sign_in"
        const val MODE_LINK = "link"
        private const val KEY_INTENT_LAUNCHED = "wang.harlon.loginbase.INTENT_LAUNCHED"
    }
}
