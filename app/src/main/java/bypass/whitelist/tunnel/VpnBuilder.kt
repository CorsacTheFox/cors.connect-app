package bypass.whitelist.tunnel

import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import bypass.whitelist.util.DnsMode
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.Vpn

/**
 * Shared VpnService.Builder configuration (addresses/routes/DNS/split
 * tunneling) used by both [TunnelVpnService] (instance/call mode) and
 * [XrayVpnService] (standard Xray mode), so the two connection types capture
 * traffic identically and only differ in what feeds their local SOCKS5
 * endpoint. Extracted from what was originally inline in
 * `TunnelVpnService.start()`.
 */
object VpnBuilder {

    fun configure(
        service: VpnService,
        builder: VpnService.Builder,
        sessionName: String,
        ownPackageName: String,
        splitTunnelingMode: SplitTunnelingMode,
        splitTunnelingPackages: Set<String>,
        logTag: String,
    ) {
        builder.setSession(sessionName)
            .addAddress(Vpn.ADDRESS, Vpn.PREFIX_LENGTH)
            .addRoute(Vpn.ROUTE, 0)
            .setMtu(Vpn.MTU)
            // The VPN must not inherit the physical network's meteredness:
            // without this, apps on a metered (mobile) network see the tunnel
            // as metered too and throttle/background-restrict their traffic
            // through it, and JobScheduler defers work that could run.
            .setMetered(false)

        // IPv6 is deliberately NOT advertised on the TUN interface. Neither
        // connection mode can carry it end-to-end: the instance/call mode
        // forwards to a local SOCKS5 relay whose remote side often has no
        // IPv6 reachability, and the standard Xray mode dials literal IPv6
        // destinations (no sniffing) on a server that may be IPv4-only.
        // Advertising `::` anyway made dual-stack sites (Google, Yandex —
        // anything with AAAA records) connect to dead IPv6 paths: gVisor
        // completes the TCP handshake locally, the remote dial then fails,
        // and the user sees "Empty reply from server" / connection resets
        // while IPv4-only sites keep working. Without the route, IPv6
        // attempts fail fast and clients fall back to the IPv4 the tunnel
        // actually carries. Re-enable only when the upstreams are verified
        // IPv6-capable (see Vpn.ADDRESS6/PREFIX_LENGTH6/ROUTE6).

        when (Prefs.dnsMode) {
            DnsMode.SYSTEM -> {
                val systemDns = systemDnsServers(service)
                if (systemDns.isNotEmpty()) {
                    for (dns in systemDns) builder.addDnsServer(dns)
                } else {
                    builder.addDnsServer(Vpn.DNS_PRIMARY)
                    builder.addDnsServer(Vpn.DNS_SECONDARY)
                }
            }
            DnsMode.CUSTOM -> {
                val primary = Prefs.dnsPrimary.trim()
                val secondary = Prefs.dnsSecondary.trim()
                if (primary.isNotEmpty()) builder.addDnsServer(primary)
                if (secondary.isNotEmpty()) builder.addDnsServer(secondary)
                if (primary.isEmpty() && secondary.isEmpty()) {
                    builder.addDnsServer(Vpn.DNS_PRIMARY)
                    builder.addDnsServer(Vpn.DNS_SECONDARY)
                }
            }
        }

        try {
            when (splitTunnelingMode) {
                SplitTunnelingMode.NONE -> {
                    builder.addDisallowedApplication(ownPackageName)
                }
                SplitTunnelingMode.BYPASS -> {
                    builder.addDisallowedApplication(ownPackageName)
                    splitTunnelingPackages.forEach {
                        try {
                            builder.addDisallowedApplication(it)
                        } catch (ignored: Exception) {
                        }
                    }
                }
                SplitTunnelingMode.ONLY -> {
                    splitTunnelingPackages.forEach {
                        try {
                            builder.addAllowedApplication(it)
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Split tunneling failed: ${e.message}")
        }
    }

    private fun systemDnsServers(service: VpnService): List<String> {
        val connectivityManager = service.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()
        return linkProperties.dnsServers.mapNotNull { it.hostAddress }
    }
}
