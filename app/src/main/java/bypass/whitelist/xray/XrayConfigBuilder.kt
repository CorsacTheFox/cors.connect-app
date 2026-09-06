package bypass.whitelist.xray

import bypass.whitelist.util.DnsMode
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.Vpn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full Xray-core JSON config whose inbound is Xray-core's own
 * `protocol: "tun"` inbound (gVisor-backed, part of Xray-core itself — see
 * `proxy/tun` in XTLS/Xray-core) fed the VPN's TUN file descriptor directly
 * via [bypass.whitelist.xray.XrayEngine.start]'s `tunFd` parameter (which sets
 * the `xray.tun.fd` env var `CoreController.startLoop` reads before the loop
 * starts). Xray-core *is* the tun2socks bridge here — there is no separate
 * native tun2socks step, and no local SOCKS5 inbound to expose on loopback.
 *
 * Two outbound shapes are supported, covering both connection types this app
 * has:
 * - [buildForXrayServer]: outbound is the imported [XrayServer] (standard
 *   Xray connection mode, [bypass.whitelist.tunnel.XrayVpnService]).
 * - [buildForLocalUpstream]: outbound is a `socks` client pointed at
 *   `127.0.0.1:<port>` — the local SOCKS5 the instance/call relay subprocess
 *   already exposes (see `HeadlessRelayController`/`RelayController`). This
 *   is what [bypass.whitelist.tunnel.TunnelVpnService] uses: it reuses
 *   Xray-core purely as a TUN-to-local-SOCKS5 bridge for the instance/call
 *   flow, replacing the old `androidbind.Androidbind` native tun2socks call
 *   (which requires a JNI shared library that isn't part of this app's
 *   `mobile.aar` — only a subprocess-mode executable is).
 *
 * Device-wide split tunneling (per-app allow/deny) is handled entirely by
 * [bypass.whitelist.tunnel.VpnBuilder] via `VpnService.Builder`, before the fd
 * ever reaches Xray-core — this config has no opinion on it.
 */
object XrayConfigBuilder {

    /**
     * Standard Xray connection mode ([bypass.whitelist.tunnel.XrayVpnService]):
     * outbound is a remote VLESS/etc server from the user's subscription.
     * `routeDnsThroughProxy = true` here — see [buildDns]/[buildRouting] for
     * why this is required for the tunnel to actually pass traffic once
     * connected, not just report "connected".
     */
    fun buildForXrayServer(server: XrayServer, mtu: Int): String =
        build(
            mtu = mtu,
            outbound = server.toOutboundJson("proxy"),
            routeDnsThroughProxy = true,
            withLoopbackSocks = true,
        )

    /**
     * Loopback SOCKS5 inbound for Xray server mode, on the same port/creds
     * the instance mode's relay uses (the two modes never run at once — a
     * connect in the other mode always tears the tunnel down first). It
     * exists purely for the UI's per-service availability checks: they run
     * in the app process (excluded from the VPN) and need a way to dial
     * *through* the core. A plain SOCKS CONNECT to host:443 measures exactly
     * what the user cares about — can the tunnel reach the site — without
     * HTTP semantics, which the core's built-in `measureDelay` GET can't
     * guarantee (Reddit/Instagram et al. answer bot-filter 4xx/reset to a
     * bare header-less GET from a proxy IP even when fully reachable).
     */
    private fun buildLoopbackSocksInbound(): JSONObject = JSONObject().apply {
        put("tag", "socks-in")
        put("protocol", "socks")
        put("listen", "127.0.0.1")
        // Xray-server mode has its own loopback port (Ports.DEFAULT_XRAY_SOCKS,
        // 1081) — NOT Prefs.socksPort (1080), which the Whitelist Bypass relay
        // owns. Sharing it meant a still-releasing relay after a Whitelist
        // Bypass disconnect made the next Xray connect fail to bind 1080, and
        // the app got stuck on "previous session still running" until restart.
        put("port", Prefs.xraySocksPort)
        put(
            "settings",
            JSONObject().apply {
                put("auth", "password")
                put(
                    "accounts",
                    JSONArray().put(
                        JSONObject()
                            .put("user", SocksAuth.user)
                            .put("pass", SocksAuth.pass),
                    ),
                )
                put("udp", false)
            },
        )
    }

    /**
     * Instance/call mode ([bypass.whitelist.tunnel.TunnelVpnService]): outbound
     * is the local relay's own SOCKS5 (`127.0.0.1:<port>`), which already
     * forwards arbitrary UDP/TCP — including raw DNS packets — over the
     * WebRTC data channel without the limitation [buildForXrayServer] is
     * working around. Left on the old `routeDnsThroughProxy = false` behavior
     * unchanged so this already-working path can't regress.
     */
    fun buildForLocalUpstream(host: String, port: Int, user: String, pass: String, mtu: Int): String =
        build(mtu = mtu, outbound = buildSocksOutbound("proxy", host, port, user, pass), routeDnsThroughProxy = false)

    private fun build(
        mtu: Int,
        outbound: JSONObject,
        routeDnsThroughProxy: Boolean,
        withLoopbackSocks: Boolean = false,
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", if (Prefs.debug) "debug" else "warning"))
        root.put("dns", buildDns(routeDnsThroughProxy))
        root.put(
            "inbounds",
            JSONArray().apply {
                put(buildTunInbound(mtu))
                if (withLoopbackSocks) put(buildLoopbackSocksInbound())
            },
        )
        root.put(
            "outbounds",
            JSONArray().apply {
                put(outbound)
                put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                if (routeDnsThroughProxy) put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
            },
        )
        root.put("routing", buildRouting(routeDnsThroughProxy))
        return root.toString()
    }

    /**
     * Xray-core's native TUN inbound. `port` is required by the config schema
     * but ignored (this inbound isn't a listening proxy); the fd itself never
     * appears in JSON — it comes from the `xray.tun.fd` env var set by
     * [bypass.whitelist.xray.XrayEngine.start]. Android's own `VpnService`
     * already created/configured the interface (address, routes, DNS), so
     * `mtu` is the only setting Xray's `AndroidTun` implementation
     * (`proxy/tun/tun_android.go`) actually uses (sizes the gVisor netstack
     * link endpoint's read buffer) — it should match the same MTU
     * `VpnBuilder` configured on the `VpnService.Builder`.
     *
     * `settings.name` MUST be non-empty here even though `AndroidTun.NewTun`
     * itself ignores it (it derives the real interface name from the fd via
     * `TUNGETIFF`). If `name` is omitted, Xray-core's shared config builder
     * (`infra/conf/tun.go` `TunConfig.Build`) falls back to
     * `GetAvailableTunName()`, which calls Go's stdlib `net.Interfaces()` to
     * avoid picking a name that collides with an existing interface. On
     * Linux that walks a netlink route socket — a syscall Android's SELinux
     * policy denies to regular app processes — so it fails with
     * "fail to get system interface information: route ip+net: netlinkrib:
     * permission denied" before Xray ever reaches the Android-specific tun
     * code. Any placeholder value avoids that code path entirely.
     */
    private fun buildTunInbound(mtu: Int): JSONObject = JSONObject().apply {
        put("tag", "tun-in")
        put("port", 0)
        put("protocol", "tun")
        put("settings", JSONObject().put("mtu", mtu).put("name", "xray-tun"))
    }

    /** A `socks` outbound pointed at a local SOCKS5 proxy already listening on loopback. */
    private fun buildSocksOutbound(tag: String, host: String, port: Int, user: String, pass: String): JSONObject {
        val server = JSONObject().put("address", host).put("port", port)
        if (user.isNotBlank()) {
            server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
        }
        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "socks")
            put("settings", JSONObject().put("servers", JSONArray().put(server)))
        }
    }

    /**
     * `routeDnsThroughProxy = false` (instance/local-upstream mode) keeps the
     * original behavior: `"localhost"` tells Xray-core to resolve using
     * whatever system resolver is available in-process — irrelevant to actual
     * packet forwarding either way, since that path never depends on Xray's
     * own DNS client (see [buildForLocalUpstream]'s doc comment).
     *
     * `routeDnsThroughProxy = true` (standard Xray server mode) instead:
     *  - tags this block `dns_in` so [buildRouting] can route Xray-core's own
     *    DNS lookups through the `proxy` outbound (keeps DNS resolution
     *    itself encrypted/tunneled — important on the censored networks this
     *    app targets, where local resolvers are often blocked or tampered
     *    with).
     *  - always uses literal DNS server IPs, never `"localhost"`: `"localhost"`
     *    is special-cased by Xray-core to resolve directly in-process,
     *    bypassing outbound routing entirely, which would defeat the
     *    `dns_in` → `proxy` rule above (and is separately unreliable on
     *    Android, where Go's resolver has no `/etc/resolv.conf` to read).
     */
    private fun buildDns(routeDnsThroughProxy: Boolean): JSONObject {
        val servers = JSONArray()
        when (Prefs.dnsMode) {
            DnsMode.SYSTEM -> {
                if (routeDnsThroughProxy) {
                    servers.put(Vpn.DNS_PRIMARY).put(Vpn.DNS_SECONDARY)
                } else {
                    servers.put("localhost")
                }
            }
            DnsMode.CUSTOM -> {
                val primary = Prefs.dnsPrimary.trim()
                val secondary = Prefs.dnsSecondary.trim()
                if (primary.isNotEmpty()) servers.put(primary)
                if (secondary.isNotEmpty()) servers.put(secondary)
                if (servers.length() == 0) {
                    servers.put(Vpn.DNS_PRIMARY).put(Vpn.DNS_SECONDARY)
                }
            }
        }
        val dns = JSONObject().put("servers", servers)
        if (routeDnsThroughProxy) {
            dns.put("tag", "dns_in")
            // The TUN interface carries IPv4 only (see VpnBuilder), so never
            // answer with AAAA records: clients that get them build IPv6
            // connections the tunnel can't carry ("empty reply"/reset on
            // dual-stack sites like Google). UseIPv4 makes both Xray's own
            // lookups and the port-53 `dns-out` interception resolve A only.
            dns.put("queryStrategy", "UseIPv4")
        }
        return dns
    }

    /**
     * Minimal routing: send loopback/private/link-local destinations direct so
     * the local socks inbound and any LAN traffic never loop back through the
     * proxy outbound. Deliberately uses literal CIDRs instead of `geoip:private`
     * so it never depends on geoip.dat/geosite.dat assets being present.
     *
     * When [routeDnsThroughProxy] is set (standard Xray server mode only, see
     * [buildForXrayServer]), two rules are prepended ahead of the private-CIDR
     * one, both addressing the same underlying issue: many VLESS configs use
     * `flow: xtls-rprx-vision`, which only works over raw TCP — Xray-core
     * rejects/drops UDP proxied through such an outbound. Since DNS lookups
     * from apps on the device arrive as UDP packets on the TUN interface, that
     * silently breaks *all* hostname resolution — the VPN reports connected
     * (the tun-in inbound came up fine) and raw-IP TCP may even work, but
     * nothing that needs a DNS lookup ever gets anywhere, which is
     * indistinguishable from "no internet" to the user:
     *  - `{"inboundTag": ["dns_in"], "outboundTag": "proxy"}` sends Xray-core's
     *    *own* DNS client traffic (see [buildDns]) through the proxy outbound
     *    instead of leaking it to the local network.
     *  - `{"port": 53, "network": "tcp,udp", "outboundTag": "dns-out"}`
     *    intercepts raw DNS packets arriving via the TUN interface and answers
     *    them using Xray-core's own DNS subsystem (the `dns-out` "dns"
     *    protocol outbound added in [build]) instead of forwarding the raw
     *    UDP/TCP bytes through the (possibly UDP-broken) proxy outbound.
     *
     * Domain/geosite-based routing rules are out of scope for this pass (the
     * product decision was per-app split tunneling only for now, handled at
     * the VpnService layer, not here) — this object is the natural place to
     * extend later with a `rules` list built from user-editable domain rules.
     */
    private fun buildRouting(routeDnsThroughProxy: Boolean): JSONObject {
        val privateCidrs = JSONArray()
            .put("127.0.0.0/8")
            .put("10.0.0.0/8")
            .put("172.16.0.0/12")
            .put("192.168.0.0/16")
            .put("169.254.0.0/16")
            .put("::1/128")
            .put("fc00::/7")
            .put("fe80::/10")
        val rules = JSONArray()
        if (routeDnsThroughProxy) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("dns_in"))
                    .put("outboundTag", "proxy"),
            )
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("port", "53")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "dns-out"),
            )
        }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("outboundTag", "direct")
                .put("ip", privateCidrs),
        )
        return JSONObject().put("domainStrategy", "AsIs").put("rules", rules)
    }
}
