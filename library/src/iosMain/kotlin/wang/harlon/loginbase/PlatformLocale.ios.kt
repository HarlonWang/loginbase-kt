package wang.harlon.loginbase

import platform.Foundation.NSBundle

/**
 * `preferredLocalizations.first` 是 **App 实际显示的语言**（用户偏好与 App 已做的本地化
 * 取交集后的首选）；`NSLocale.preferredLanguages.first` 则是用户的首选语言，App 没做那门
 * 语言时两者不等——系统首选日语、App 只有中英文时，前者给 `en`，后者给 `ja`。
 *
 * 取前者：邮件要对齐的是用户眼睛看到的 App 语言，取后者会造出新的割裂
 * （界面英文、邮件日文）。
 *
 * bundle 没有任何本地化时会是空列表，此时返回 null 交给服务端兜底。
 */
actual fun platformLanguageTag(): String? =
    (NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String).usableTag()
