package wang.harlon.loginbase.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * OAuth 回跳的接收中转页，**对外的哑门铃**：exported 意味着任何 App 都能发 intent，
 * 所以它无状态，收到什么都只是转交给管理页后消失。
 * `CLEAR_TOP or SINGLE_TOP`：命中既有管理页则清掉其上的 Custom Tab 走 onNewIntent，
 * 进程被回收时按 singleTask 新建——两种情况都由管理页的 data-first 状态机接住。
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
