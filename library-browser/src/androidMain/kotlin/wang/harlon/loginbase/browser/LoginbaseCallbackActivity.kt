package wang.harlon.loginbase.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * OAuth 回跳的接收中转页（design：oauth-browser 方案 §3）。**对外的哑门铃**：
 * exported + BROWSABLE 意味着任何 App 都能向它发 intent，所以它无状态、收到什么都
 * 只做一件事——把 data 转交给压在浏览器页下面的管理页，然后消失。
 *
 * `CLEAR_TOP or SINGLE_TOP`：命中栈内既有的管理页实例（清掉压在它上面的 Custom Tab
 * 与本页）并走 `onNewIntent`；进程被回收时无既有实例，则按 singleTask 新建——两种
 * 情况都由管理页的 data-first 状态机统一接住。flags 组合与 AppAuth / Auth0 的转发
 * 逐字一致（design §11 差异 #3）。
 */
internal class LoginbaseCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, LoginbaseAuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(intent?.data),
        )
        finish()
    }
}
