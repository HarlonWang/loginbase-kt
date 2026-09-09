# loginbase-kt

> Kotlin Multiplatform client for [loginbase](https://github.com/HarlonWang/loginbase) — sign-in, sessions and token refresh, handled.

**English** | [简体中文](README.zh-CN.md)

[![Maven Central](https://img.shields.io/maven-central/v/wang.harlon/loginbase-kt)](https://central.sonatype.com/artifact/wang.harlon/loginbase-kt)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Once this is wired up, **your app code never contains a token** — no `Authorization` header, no refresh call, no 401 handler. The server half is [loginbase](https://github.com/HarlonWang/loginbase), a library that runs inside your own Cloudflare Worker.

| Platform | Status |
|---|---|
| Android | In production |
| iOS | Usable from a Kotlin host — email OTP and social sign-in both verified in a Compose Multiplatform app. [Remaining gaps](docs/design.md) |

## What you get

- **Tokens become somebody else's problem.** Storage, rotation, expiry and retry all live behind one `AuthClient`. Your API calls go back to looking like API calls.
- **Concurrent refresh is single-flighted.** Twenty requests hit 401 in the same moment and exactly one refresh goes out. Doing this per-HTTP-client — the obvious way — quietly burns the server's session-recovery budget, with no error and no symptom, until the day your users get force-signed-out.
- **Signed out and offline are different things.** A refresh that fails on a bad network is not a sign-out, but hand-rolled clients send the user to the login screen anyway. Four explicit states, handled exhaustively in one place.
- **Social sign-in, end to end.** The authorization page opens in the platform's compliant user agent (Auth Tab → Custom Tab → system browser on Android, `ASWebAuthenticationSession` on iOS), and callback capture, sign-in vs. link, code exchange, cancellation and process death are all handled for you. Optional module; projects that skip it never notice it.
- **Three dependencies, no UI, no engine.** `ktor-client-core`, `kotlinx-serialization-json`, `kotlinx-coroutines-core`. You bring the HTTP engine — the library never picks one on your behalf.

## Quick start

**1. Add the dependency.** The HTTP engine is yours to choose.

```kotlin
dependencies {
    implementation("wang.harlon:loginbase-kt:<version>")
    implementation("io.ktor:ktor-client-okhttp:<ktor-version>")   // engine, Android
    implementation("io.ktor:ktor-client-auth:<ktor-version>")     // for step 3
}
```

**2. Create one instance for the whole app.** Make it a DI singleton — the single-flight lock is a field on the instance, so two instances mean two locks and no single flight.

```kotlin
val auth = AuthClient(
    baseUrl = "https://api.example.com/auth",
    tokenStore = SharedPreferencesTokenStore(context),
) {
    httpEngine = okHttpEngine   // optional; sharing the engine shares the connection pool
    client = ClientInfo("MyApp", BuildConfig.VERSION_NAME, ClientPlatform.ANDROID)   // optional; versions your rows in the server's login analytics
}
```

**3. Teach your API client to refresh.**

```kotlin
val api = HttpClient(okHttpEngine) {
    install(Auth) {
        bearer {
            // The refresh token stays inside the library; the plugin never sees it.
            loadTokens { auth.accessToken()?.let { BearerTokens(it, null) } }
            refreshTokens {
                when (val r = auth.refresh()) {
                    is RefreshOutcome.Success -> BearerTokens(r.tokens.accessToken, null)
                    else -> null   // give up: the 401 reaches your code, step 4 navigates
                }
            }
        }
    }
}
```

Call `auth.refresh()` here — never `POST /refresh` yourself. Ktor's own single-flight is per-client, so going around the library is the one mistake that looks completely fine in testing. [Why, and what it costs](docs/integration.md#refresh)

**4. Observe the state in one place.** Scatter this across screens and "when do we show the login page" stops having a single answer.

```kotlin
auth.restore()   // on startup

auth.authState.collect { state ->
    when (state) {
        AuthState.Unknown          -> Unit                  // not restored yet; don't navigate
        AuthState.SignedIn         -> Unit
        is AuthState.RefreshFailed -> showOfflineBadge()    // not a sign-out, probably just a bad network
        is AuthState.SignedOut     -> {
            navigateToLogin()
            if (state.reason is SignOutReason.SessionEnded) toast("Session expired, please sign in again")
        }
    }
}
```

**5. Sign in.**

```kotlin
val cooldown = auth.sendCode(email).cooldownSeconds   // use the server's number, don't hardcode one
auth.verifyCode(email, code)                          // persisted on success; authState follows

auth.signIn(activity, OAuthProvider.GitHub)           // needs the browser module, below
auth.signOut()                                        // or signOutAll() for every session
```

From here, business code is just `api.get("$BASE/api/feed").body()`.

## What you don't have to write

| | |
|---|---|
| A mutex around refresh | One refresh per burst, however many calls hit 401 together |
| Telling a dead session from a bad network | Two distinct states, so you stop kicking offline users to the login screen |
| Replaying the original request after a refresh | The plugin does it; the retried call carries the new token |
| An `ON_RESUME` heuristic to guess whether the user backed out of the authorization page | Cancellation arrives as a definite `Cancelled` |
| Restarting an OAuth flow that Android killed mid-authorization | Results survive process death and arrive on one channel |
| `catch (IOException)` next to your API error handling | Transport failures are `LoginbaseException` too — ktor is an implementation detail |

## Social sign-in

Email codes need nothing but the core artifact. Add the optional module and the whole authorization round trip is handled:

```kotlin
dependencies { implementation("wang.harlon:loginbase-kt-browser:<version>") }

android.defaultConfig {
    // Reverse-DNS of a domain you own (RFC 8252 §7.1); missing it fails the build, not the login
    manifestPlaceholders["loginbaseRedirectScheme"] = "cn.example"
}
```

Both the manifest's intent filter and the redirect computed at runtime read that one placeholder, so they cannot drift apart. `Loginbase.redirectUri(context)` prints exactly what to put on the server's allow-list.

iOS has no manifest to derive it from, so pass the redirect directly — its scheme is the `callbackURLScheme`:

```kotlin
auth.signIn(OAuthProvider.GitHub, redirect = "cn.example:/loginbase/callback")
```

[Full wiring](docs/integration.md#social-sign-in) · [Design](docs/oauth-browser-design.md)

## Documentation

| | |
|---|---|
| [Integration guide](docs/integration.md) | Token storage, error handling, custom engines, the full OAuth wiring |
| [Troubleshooting](docs/troubleshooting.md) | Symptom → cause, and the known limits of social sign-in |
| [Design decisions](docs/design.md) | Single-flight, four states, the dependency line, where iOS stands |
| [Protocol contract](https://github.com/HarlonWang/loginbase/blob/main/docs/protocol.md) | The wire API, in the server repo — the single source of truth |

## Protocol compatibility

This library declares the protocol version it implements as `PROTOCOL_VERSION`, currently **1.9.0**. Server minor releases are backward compatible on the wire, so a newer server works with an older client — upgrade when you want a capability a later minor added, not because the numbers differ. The two repositories keep independent version lines.

## License

MIT. The published 0.1.0 and 0.1.1 POMs carry incorrect license metadata; [LICENSE](LICENSE) is authoritative.
