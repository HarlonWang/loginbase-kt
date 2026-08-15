package wang.harlon.loginbase

/**
 * loginbase 内部 API：仅供本库的配套模块（如 `loginbase-kt-browser`）跨模块调用。
 *
 * Kotlin 的 `internal` 出不了编译单元，而配套模块是独立 artifact——这些成员只能做成
 * public。用 opt-in 把「public 但不是契约」标出来：形状可能在任意版本变化，
 * 不受语义化版本保护，消费方代码不该碰。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "loginbase 内部 API，仅供配套模块使用；形状可能在任意版本变化，不受语义化版本保护",
)
@Retention(AnnotationRetention.BINARY)
annotation class LoginbaseInternalApi
