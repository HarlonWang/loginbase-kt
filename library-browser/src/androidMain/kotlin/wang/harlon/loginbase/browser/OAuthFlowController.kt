package wang.harlon.loginbase.browser

/** 管理页在一次 onResume 里该做的事。谁执行、怎么执行是 Activity 的事，这里只做判定。 */
internal sealed interface FlowAction {
    /** 有回跳 URL：投递（App 活着）或停泊 + 起 launcher（进程被回收），然后 finish */
    data class Deliver(val url: String) : FlowAction

    /** 首次到位且带发起参数：按三级链开浏览器 */
    data object LaunchBrowser : FlowAction

    /** 不是库自己发起的启动（无参数、无回跳）：静默收场 */
    data object FinishUnexpected : FlowAction

    /** 开过浏览器、回来时两手空空：用户放弃了授权 */
    data object DeliverCancelled : FlowAction

    /** 流程在浏览器环节失败（Auth Tab 的异常结果码等）：投递 Failed 后收场 */
    data class DeliverFailed(val reason: String) : FlowAction
}

/**
 * 管理页的状态机（design：oauth-browser 方案 §6.4），从 Activity 里抽出来做纯 JVM 单测。
 *
 * 两条非显然的设计都在这里，也都做了反向验证（见 OAuthFlowControllerTest）：
 *
 * 1. **data-first，顺序即优先级**：回跳 data 的检查排在一切状态检查之前。Android 14
 *    可能重建而非复用管理页（AppAuth #977），重建实例的 savedInstanceState 与 extras
 *    全是空的——只有转发 intent 里的 data 还在。把「意外启动」的判定排在 data 前面，
 *    吞掉的就是唯一那份结果（Auth0 正是这么丢的）。
 * 2. **控制器自己持有最新 intent**：`onCreate` 与 `onNewIntent` 都喂进 [onIntent]，
 *    判定不依赖 Activity 的 `getIntent()`/`setIntent()` 组合——漏写 `setIntent` 会让
 *    每次 Custom Tab 成功回跳都被判成取消，且单实例直跑的测试还全绿（design §11
 *    差异 #6 记录的陷阱）。这里把陷阱从「容易漏写」变成「结构上不存在」。
 */
internal class OAuthFlowController(intentLaunched: Boolean) {

    /** 是否已经开过浏览器。要跨进程死亡（进 savedInstanceState），否则重建后会重开授权页 */
    var intentLaunched: Boolean = intentLaunched
        private set

    private var latestData: String? = null

    /** onCreate 与 onNewIntent 都喂到这里；null（非回跳 intent）不覆盖已有的 data */
    fun onIntent(data: String?) {
        if (data != null) latestData = data
    }

    fun onResume(hasLaunchRequest: Boolean): FlowAction {
        // data-first：见类文档第 1 条。挪到 intentLaunched 检查之后即复现 #977
        latestData?.let { return FlowAction.Deliver(it) }
        if (!intentLaunched && hasLaunchRequest) {
            intentLaunched = true
            return FlowAction.LaunchBrowser
        }
        if (!intentLaunched) return FlowAction.FinishUnexpected
        return FlowAction.DeliverCancelled
    }
}
