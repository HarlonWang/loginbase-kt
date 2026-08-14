package wang.harlon.loginbase

import java.util.Locale

/**
 * `Locale.getDefault()` **已经跟随 per-app language**（用户在系统设置里给本 App 单独选的
 * 语言），所以这个最朴素的 API 恰好是最对的那个，且零依赖。
 *
 * 两个刻意不用的 API：
 * - `AppCompatDelegate.getApplicationLocales()`——用户没手动设过语言时返回**空列表**
 *   （即最常见的情况下它给不出任何值），且必须在 `Activity#onCreate` 之后调用，
 *   还会把 `androidx.appcompat` 拖进本库的依赖面（撞依赖最小集红线）；
 * - `Locale.toString()`——给的是 `zh_CN` 这种下划线形式，不是 BCP 47。
 */
actual fun platformLanguageTag(): String? = Locale.getDefault().toLanguageTag().usableTag()
