# Security cleanup — what was removed before publishing

This is a scrubbed copy of the private `android-app` tree. All hardcoded
backend addresses, secrets, signing material and machine-local files were
replaced with placeholders or deleted. The app still compiles and runs; it
simply won't talk to a real backend until you configure your own values
(see [`local.properties.sample`](local.properties.sample)).

## Secrets / credentials removed

| Item | Where it was | Now |
|---|---|---|
| **`CORS_APP_TOKEN`** — real shared secret (`X-App-Token` / server `WB_APP_TOKEN`), 64-hex-char value | `app/build.gradle.kts` default | Placeholder `REPLACE_WITH_WB_APP_TOKEN`. `CorsClient.isConfigured` already detects this placeholder and treats the build as unconfigured, so the instance-creation API is never called. |
| Committed **`debug.keystore`** (binary, `android`/`android`) | repo root | Deleted. Debug builds now use the Android Gradle Plugin's auto-generated `~/.android/debug.keystore`. `signingConfigs { debug { ... } }` block removed from `app/build.gradle.kts`. |

## Addresses / hosts removed

| Item | Where it was | Now |
|---|---|---|
| **`CORS_BASE_URL`** — real instance-creation endpoint (a specific Yandex Cloud Function URL) | `app/build.gradle.kts` default | Placeholder `https://example.invalid/replace-with-your-endpoint` |
| **`CORS_TG_BOT`** — real claim-flow bot username | `app/build.gradle.kts` default | Placeholder `REPLACE_WITH_TELEGRAM_BOT_USERNAME` |
| **Telegram App Link host** — real verified HTTPS domain used for the `/tginit` initData callback | `TelegramAuth.CALLBACK_HOST` **and** the matching `<data android:host="…">` in `AndroidManifest.xml` | Both set to `applink.example.invalid`. These two must stay in sync and must match whatever domain your bot's WebApp actually redirects to (they are intentionally **not** derived from `CORS_BASE_URL`). |
| **Subscription bot username** (`@…bot`) — real Telegram bot for buying/checking a subscription | `res/values/strings.xml`, `res/values-ru/strings.xml`, `ui/OnboardingFragment.kt`, `ui/MainFragment.kt`, `docs/quickstart.md` | Replaced with `your_subscription_bot` |
| Example host in a doc comment (`beta.cors-fox.cc`) | `api/CorsClient.kt` KDoc | Replaced with `your-backend.example.com` |
| Design-time preview IP `185.42.7.19` in a `tools:text` attribute (never shipped, but real-looking) | `res/layout/fragment_main_screen.xml` | `0.0.0.0` |

### Not changed (not sensitive)

- `functions.yandexcloud.net` — generic Yandex Cloud Functions hostname used only as a branch condition in `CorsClient` (direct-invocation vs path routing). No account-specific value.
- Public infrastructure hosts: `1.1.1.1` / `1.0.0.1` (Cloudflare DNS), `www.gstatic.com/generate_204`, `www.msftconnecttest.com` (captive-portal probes), RFC1918 CIDRs in `XrayConfigBuilder`.
- `AppUpdater` GitHub repo (`CorsacTheFox/cors.connect-app`) — public releases URL; adjust to your own fork if you ship updates.
- `res/assets/names.txt` — list of generic Russian full names used to generate a fake display name for the autofill feature. No real personal data.

## Machine-local / build files removed

- `local.properties` (contained `sdk.dir`) — gitignored, regenerated on first Gradle sync. A template is provided as `local.properties.sample`.
- `.idea/`, `.gradle/`, `.kotlin/`, `build/`, `app/build/` — IDE / build caches.
- `.claude/`, `.zcode/`, `.artifacts/` — local tooling state.
- `build_output.txt`, `build_output_info.txt` — captured build logs.
- `temp_lib/`, `temp_lib_v2ray/`, `temp_so/` — scratch extraction dirs.
- `app/libs/libv2ray.aar` (~56 MB vendored native Xray engine) — **not** redistributed. Build it yourself following [`BUILD_XRAY_ENGINE.md`](BUILD_XRAY_ENGINE.md); the project will not compile until it is present in `app/libs/`. `app/libs/mobile.aar` (small call/relay bindings) is kept.

## Prebuilt native binaries kept as-is

`app/src/main/jniLibs/*/librelay.so` and `app/libs/mobile.aar` are prebuilt
from a separate Go relay project that is **not** part of this repo. They are
shipped unmodified. They contain no API keys or account secrets, but — being
compiled — they do embed the target-service endpoints they were built against
(e.g. VK Telemost / `dion.vc` conferencing hosts). Those strings cannot be
scrubbed without rebuilding from the (not included) Go source. If that matters
for your fork, rebuild those artifacts yourself.

## How to configure

Set the four values below as environment variables (CI) or in a gitignored
`local.properties` at the repo root (env vars win). They are read in
`app/build.gradle.kts` and exposed as `BuildConfig.CORS_BASE_URL`,
`BuildConfig.CORS_APP_TOKEN`, `BuildConfig.CORS_TG_BOT`.

```properties
sdk.dir=/path/to/Android/sdk
CORS_BASE_URL=https://your-backend.example.com
CORS_APP_TOKEN=your-shared-secret-matching-server-WB_APP_TOKEN
CORS_TG_BOT=your_telegram_bot_username
```

Then update, to your own verified HTTPS domain:

- `TelegramAuth.CALLBACK_HOST` in `app/src/main/java/cc/cors/connect/cors/TelegramAuth.kt`
- the `<data android:host="…" android:pathPrefix="/tginit">` entry in `app/src/main/AndroidManifest.xml`

### Release signing

The `release` build type still points at the `debug` signing config as a
placeholder. Before shipping, generate your own keystore
(`keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000`),
keep it out of git, and add a `signingConfigs { create("release") { ... } }`
block that reads its passwords from env vars / `local.properties`.
