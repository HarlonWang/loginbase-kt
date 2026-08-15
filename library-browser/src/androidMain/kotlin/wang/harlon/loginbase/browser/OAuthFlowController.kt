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
 * 管理页的状态机，抽出来做纯 JVM 单测。两条非显然设计（均有反向验证）：
 *
 * 1. **data-first**：回跳 data 的检查排在一切状态检查之前——系统重建的实例状态全空、
 *    只有转发 intent 里的 data，顺序颠倒就会把唯一的结果当「意外启动」吞掉；
 * 2. **控制器自持最新 intent**：判定不依赖 `getIntent()`/`setIntent()` 组合，
 *    「漏写 setIntent 导致成功被判成取消」的陷阱结构上不存在。
 * 论证与参考实现对照见 docs/oauth-browser-design.md。
 */
internal class OAuthFlowController(intentLaunched: Boolean) {

    /** 是否已开过浏览器。要进 savedInstanceState，否则重建后会重开授权页 */
    var intentLaunched: Boolean = intentLaunched
        private set

    private var latestData: String? = null

    /** onCreate 与 onNewIntent 都喂这里；null（非回跳 intent）不覆盖已有 data */
    fun onIntent(data: String?) {
        if (data != null) latestData = data
    }

    fun onResume(hasLaunchRequest: Boolean): FlowAction {
        // data-first：重建实例状态全空、只有 data，此检查必须最先（见类文档）
        latestData?.let { return FlowAction.Deliver(it) }
        if (!intentLaunched && hasLaunchRequest) {
            intentLaunched = true
            return FlowAction.LaunchBrowser
        }
        if (!intentLaunched) return FlowAction.FinishUnexpected
        return FlowAction.DeliverCancelled
    }
}
