package wang.harlon.loginbase

import java.util.Locale

/**
 * `Locale.getDefault()` 已跟随 per-app language，恰好是最对且零依赖的取法
 * （`AppCompatDelegate.getApplicationLocales()` 常见情形返回空列表、还拖进 appcompat 依赖）。
 */
internal actual fun platformLanguageTag(): String? = Locale.getDefault().toLanguageTag().usableTag()
