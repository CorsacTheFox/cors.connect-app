# Building the Xray engine (`app/libs/libv2ray.aar`)

The standard Xray connection mode (`bypass.whitelist.xray.XrayEngine`,
`bypass.whitelist.tunnel.XrayVpnService`) is written against the Android
bindings produced by **[2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)**
— the same Go wrapper around [Xray-core](https://github.com/XTLS/Xray-core)
that [v2rayNG](https://github.com/2dust/v2rayNG) uses. It is not vendored
into this repo (it's a separate Go module, built with `gomobile`, that
produces a binary `.aar`) — you build it once and drop it into `app/libs/`,
exactly like the existing `mobile.aar` already there for the instance/call
relay.

**Until `app/libs/libv2ray.aar` exists, the project will not compile** —
`XrayEngine.kt` imports `libv2ray.Libv2ray`, `libv2ray.CoreController`, and
`libv2ray.CoreCallbackHandler` directly, the same way `RelayController.kt`
and `TunnelVpnService.kt` already import `androidbind.Androidbind` from
`mobile.aar`.

## 1. Prerequisites

- Go (1.21+)
- Android SDK + NDK (the NDK version Xray-core's current release expects —
  check [AndroidLibXrayLite's CI config](https://github.com/2dust/AndroidLibXrayLite/blob/main/.github/workflows)
  for the exact version if `gomobile bind` fails with an NDK error)
- `gomobile`:
  ```shell
  go install golang.org/x/mobile/cmd/gomobile@latest
  gomobile init
  ```

## 2. Build

```shell
git clone https://github.com/2dust/AndroidLibXrayLite.git
cd AndroidLibXrayLite
go mod tidy -v
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o libv2ray.aar ./
```

This produces `libv2ray.aar` exposing (per the module's own generated Go
doc — see `libv2ray_main.go`):

```go
type CoreCallbackHandler interface {
    Startup() int
    Shutdown() int
    OnEmitStatus(int, string) int
}

type CoreController struct {
    CallbackHandler CoreCallbackHandler
    IsRunning       bool
}

func NewCoreController(s CoreCallbackHandler) *CoreController
func InitCoreEnv(envPath string, key string)
func CheckVersionX() string
func (x *CoreController) StartLoop(configContent string, tunFd int32) (err error)
func (x *CoreController) StopLoop() error
func (x *CoreController) QueryStats(tag string, direct string) int64
func (x *CoreController) QueryAllOutboundTrafficStats() string
func (x *CoreController) MeasureDelay(url string) (int64, error)
```

`gomobile bind` maps this to a Java/Kotlin package named after the Go
package (`libv2ray` — no `-javapkg` flag was passed above, so it stays
unprefixed), lower-cases the first letter of exported method names, and
maps Go's `int` to Java `long`. That's exactly what `XrayEngine.kt` is
written against:

```kotlin
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

Libv2ray.initCoreEnv(assetsDir, "")
val core = Libv2ray.newCoreController(callbackHandler)
core.startLoop(configJson, 0)
core.stopLoop()
```

If the `.aar` you build exposes different casing/types (this happens across
`gomobile` versions), the fix is entirely local to `XrayEngine.kt` — nothing
else in the app depends on the exact generated type shapes.

## 3. Install

```shell
cp libv2ray.aar /path/to/android-app/app/libs/libv2ray.aar
```

`app/build.gradle.kts` already picks up every `.aar` under `app/libs/`
(`fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar")))`) — no
Gradle changes are needed.

## 4. Why no TUN handed to Xray-core directly

`XrayVpnService` does **not** pass the VPN's TUN file descriptor to
`CoreController.startLoop` (it passes `0`). Instead it reuses the tun2socks
bridge already vendored in `mobile.aar` (`androidbind.Androidbind.startTun2Socks`,
already used by `TunnelVpnService` for the instance/call flow) and points it
at a local SOCKS5 inbound that Xray-core exposes (see
`bypass.whitelist.xray.XrayConfigBuilder`). This means:

- DNS, IPv6, MTU, and split-tunneling handling are byte-for-byte identical
  between the instance/call VPN and the Xray VPN (both go through
  `bypass.whitelist.tunnel.VpnBuilder`).
- No `VpnService.protect()` wiring is needed for Xray's outbound sockets:
  they're opened in-process, and this app's own package is always excluded
  from (or simply not included in) the VPN's captured UID set — exactly how
  the existing relay sockets already escape the tunnel today.

If Xray-core's own TUN handling is later preferred instead (dropping the
tun2socks dependency), that's a change scoped entirely to `XrayVpnService`
and `XrayEngine` — the config/model/UI layers don't assume either approach.

## 5. Assets (`geoip.dat` / `geosite.dat`)

`XrayConfigBuilder`'s routing rules deliberately avoid `geoip:`/`geosite:`
categories (private-network bypass uses literal CIDRs instead), so this app
does **not** currently require bundling `geoip.dat`/`geosite.dat` as
assets. If you extend routing with domain/geosite rules later, you'll need
to fetch those files (e.g. from
[Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat))
into `app/src/main/assets/` and point `XrayEngine.initEnv`'s `assetsDir` at
wherever you extract them at runtime.
