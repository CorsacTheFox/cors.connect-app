package bypass.whitelist.xray

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Transport ("network") used to carry the proxy protocol, mirrors Xray-core's
 * `streamSettings.network`. Kept small on purpose; new values slot in without
 * touching [XrayServer]'s shape.
 */
enum class XrayNetwork(val wireValue: String) {
    TCP("tcp"),
    KCP("kcp"),
    WS("ws"),
    HTTP("http"),
    GRPC("grpc"),
    QUIC("quic"),
    HTTPUPGRADE("httpupgrade"),
    XHTTP("xhttp");

    companion object {
        /**
         * Unknown values must NOT silently fall back to TCP: a share link with
         * a transport this enum doesn't know would then be exported as raw
         * TCP, which connects to a server expecting a protocol handshake and
         * can never pass traffic. `splithttp` is xhttp's pre-rename value and
         * maps to [XHTTP] explicitly.
         */
        fun fromWire(value: String): XrayNetwork = when (value.lowercase()) {
            "splithttp" -> XHTTP
            else -> entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: TCP
        }
    }
}

/** Transport security layer, mirrors Xray-core's `streamSettings.security`. */
enum class XraySecurity(val wireValue: String) {
    NONE("none"),
    TLS("tls"),
    REALITY("reality");

    companion object {
        fun fromWire(value: String): XraySecurity =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: NONE
    }
}

/**
 * The proxy protocol used by an [XrayServer]. VLESS/VMess links are the bulk of
 * most subscriptions; Trojan is included because mixed subscriptions routinely
 * carry all three (entries this app can't parse are dropped on import, which is
 * how "my subscription has 30 servers but I only see one" happens).
 */
enum class XrayProtocol(val wireValue: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan");

    companion object {
        fun fromWire(value: String): XrayProtocol? =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) }
    }
}

/**
 * A single imported Xray server ("connection"), analogous to [bypass.whitelist.tunnel.CallConfig]
 * for the instance/call flow. Either added manually from a single share link, or
 * expanded from an [XraySubscription].
 *
 * Field reuse across protocols (kept to avoid a per-protocol model split):
 * - VLESS: [uuid] = user id, [encryption] = VLESS encryption ("none").
 * - VMess: [uuid] = user id, [encryption] = user security ("auto", "aes-128-gcm"…).
 * - Trojan: [uuid] = password ([encryption] unused).
 */
data class XrayServer(
    val id: String,
    val remark: String,
    val protocol: XrayProtocol,
    val address: String,
    val port: Int,
    val uuid: String,
    val encryption: String = "none",
    val flow: String = "",
    val network: XrayNetwork = XrayNetwork.TCP,
    val security: XraySecurity = XraySecurity.NONE,
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val allowInsecure: Boolean = false,
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    /** The share link's `mode` param — grpc's "gun"/"multi" or xhttp's "auto"/"packet-up"/"stream-up"/"stream-one". */
    val grpcMode: String = "gun",
    val headerType: String = "",
    val seed: String = "",
    /**
     * Raw JSON object from the share link's `extra` param (an xhttp convention:
     * the panel packs xhttpSettings fields like `scStreamUpServerSecs` into
     * it). Passed through verbatim into `xhttpSettings.extra`, which Xray-core
     * merges itself — see SplitHTTPConfig.Build in `infra/conf`.
     */
    val extra: String = "",
    val subscriptionId: String? = null,
    val rawLink: String = "",
) {

    val summary: String get() = "$address:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("remark", remark)
        put("protocol", protocol.wireValue)
        put("address", address)
        put("port", port)
        put("uuid", uuid)
        put("encryption", encryption)
        put("flow", flow)
        put("network", network.wireValue)
        put("security", security.wireValue)
        put("sni", sni)
        put("fingerprint", fingerprint)
        put("alpn", alpn)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("spiderX", spiderX)
        put("allowInsecure", allowInsecure)
        put("path", path)
        put("host", host)
        put("serviceName", serviceName)
        put("grpcMode", grpcMode)
        put("headerType", headerType)
        put("seed", seed)
        if (extra.isNotBlank()) put("extra", extra)
        subscriptionId?.let { put("subscriptionId", it) }
        put("rawLink", rawLink)
    }

    /**
     * Builds the Xray-core outbound object for this server (protocol + stream
     * settings). Combined with a local socks inbound by [XrayConfigBuilder] to
     * form a full runnable config.
     */
    fun toOutboundJson(tag: String): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", tag)
        outbound.put("protocol", protocol.wireValue)

        when (protocol) {
            XrayProtocol.VLESS -> {
                val user = JSONObject().apply {
                    put("id", uuid)
                    put("encryption", encryption.ifBlank { "none" })
                    if (flow.isNotBlank()) put("flow", flow)
                }
                val vnext = JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
            XrayProtocol.VMESS -> {
                // alterId intentionally omitted: modern Xray-core is AEAD-only
                // (alterId 0), and older cores default the field to 0 as well.
                val user = JSONObject().apply {
                    put("id", uuid)
                    put("security", encryption.ifBlank { "auto" })
                }
                val vnext = JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("users", JSONArray().put(user))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            }
            XrayProtocol.TROJAN -> {
                val server = JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("password", uuid)
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(server)))
            }
        }

        outbound.put("streamSettings", buildStreamSettings())
        return outbound
    }

    private fun buildStreamSettings(): JSONObject {
        val stream = JSONObject()
        stream.put("network", network.wireValue)
        stream.put("security", security.wireValue)

        when (security) {
            XraySecurity.TLS -> {
                stream.put("tlsSettings", JSONObject().apply {
                    put("serverName", sni.ifBlank { address })
                    put("allowInsecure", allowInsecure)
                    if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                    if (alpn.isNotBlank()) put("alpn", JSONArray(alpn.split(",").map { it.trim() }))
                })
            }
            XraySecurity.REALITY -> {
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", sni.ifBlank { address })
                    put("fingerprint", fingerprint.ifBlank { "chrome" })
                    put("publicKey", publicKey)
                    if (shortId.isNotBlank()) put("shortId", shortId)
                    if (spiderX.isNotBlank()) put("spiderX", spiderX)
                })
            }
            XraySecurity.NONE -> {}
        }

        when (network) {
            XrayNetwork.WS -> {
                stream.put("wsSettings", JSONObject().apply {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("headers", JSONObject().put("Host", host))
                })
            }
            XrayNetwork.HTTP -> {
                stream.put("httpSettings", JSONObject().apply {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("host", JSONArray().put(host))
                })
            }
            XrayNetwork.HTTPUPGRADE -> {
                stream.put("httpupgradeSettings", JSONObject().apply {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("host", host)
                })
            }
            XrayNetwork.XHTTP -> {
                stream.put("xhttpSettings", JSONObject().apply {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("host", host)
                    // xhttp's `mode` param rides in [grpcMode] (the share-link
                    // param is `mode` for both transports). Without it the
                    // client defaults to "auto", which a stream-up-only server
                    // never answers — the tunnel then hangs instead of failing.
                    if (grpcMode.isNotBlank()) put("mode", grpcMode)
                    // Panels pack advanced xhttpSettings fields (e.g.
                    // scStreamUpServerSecs) into the link's `extra` JSON;
                    // Xray-core merges `xhttpSettings.extra` itself, so the
                    // value is passed through verbatim.
                    if (extra.isNotBlank()) put("extra", JSONObject(extra))
                })
            }
            XrayNetwork.GRPC -> {
                stream.put("grpcSettings", JSONObject().apply {
                    put("serviceName", serviceName)
                    put("multiMode", grpcMode.equals("multi", ignoreCase = true))
                })
            }
            XrayNetwork.KCP -> {
                stream.put("kcpSettings", JSONObject().apply {
                    if (headerType.isNotBlank()) put("header", JSONObject().put("type", headerType))
                    if (seed.isNotBlank()) put("seed", seed)
                })
            }
            XrayNetwork.TCP -> {
                if (headerType.equals("http", ignoreCase = true)) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            if (host.isNotBlank() || path.isNotBlank()) {
                                put("request", JSONObject().apply {
                                    put("path", JSONArray().put(path.ifBlank { "/" }))
                                    if (host.isNotBlank()) {
                                        put("headers", JSONObject().put("Host", JSONArray().put(host)))
                                    }
                                })
                            }
                        })
                    })
                }
            }
            XrayNetwork.QUIC -> {
                stream.put("quicSettings", JSONObject().apply {
                    if (headerType.isNotBlank()) put("header", JSONObject().put("type", headerType))
                    if (seed.isNotBlank()) put("key", seed)
                })
            }
        }
        return stream
    }

    companion object {

        fun fromJson(o: JSONObject): XrayServer = XrayServer(
            id = o.getString("id"),
            remark = o.optString("remark"),
            protocol = XrayProtocol.fromWire(o.optString("protocol", "vless")) ?: XrayProtocol.VLESS,
            address = o.optString("address"),
            port = o.optInt("port"),
            uuid = o.optString("uuid"),
            encryption = o.optString("encryption", "none"),
            flow = o.optString("flow"),
            network = XrayNetwork.fromWire(o.optString("network", "tcp")),
            security = XraySecurity.fromWire(o.optString("security", "none")),
            sni = o.optString("sni"),
            fingerprint = o.optString("fingerprint"),
            alpn = o.optString("alpn"),
            publicKey = o.optString("publicKey"),
            shortId = o.optString("shortId"),
            spiderX = o.optString("spiderX"),
            allowInsecure = o.optBoolean("allowInsecure", false),
            path = o.optString("path"),
            host = o.optString("host"),
            serviceName = o.optString("serviceName"),
            grpcMode = o.optString("grpcMode", "gun"),
            headerType = o.optString("headerType"),
            seed = o.optString("seed"),
            extra = o.optString("extra"),
            subscriptionId = if (o.has("subscriptionId")) o.optString("subscriptionId") else null,
            rawLink = o.optString("rawLink"),
        )

        fun listToJson(items: List<XrayServer>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String): List<XrayServer> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Parses a single share link into an [XrayServer]. Supported schemes:
         * `vless://`, `vmess://` (base64 JSON) and `trojan://` — the schemes
         * subscriptions actually carry. Everything else is dropped by the
         * caller (returns null), matching the pre-vmess/trojan behavior.
         */
        fun parseShareLink(raw: String, subscriptionId: String? = null): XrayServer? {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, subscriptionId)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, subscriptionId)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, subscriptionId)
                else -> null
            }
        }

        /**
         * Deterministic id for subscription-parsed servers: derived from the
         * subscription + raw link so a re-fetch produces the same id and the
         * active-selection survives subscription refreshes. Manually added
         * servers (no subscription) keep a random unique id.
         */
        private fun stableServerId(subscriptionId: String?, raw: String): String {
            if (subscriptionId == null) return UUID.randomUUID().toString()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$subscriptionId|${raw.trim()}".toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** The split pieces of a `scheme://userinfo@host:port?query#remark` share link. */
        private class ShareLinkParts(
            val userInfo: String,
            val address: String,
            val port: Int,
            val params: Map<String, String>,
            val remark: String,
        )

        /** Parses `scheme://userinfo@host:port?query#remark`, shared by vless/trojan. */
        private fun parseUriShareLink(raw: String, scheme: String): ShareLinkParts? {
            try {
                val body = raw.substring(scheme.length)
                val hashIndex = body.indexOf('#')
                val remarkRaw = if (hashIndex >= 0) body.substring(hashIndex + 1) else ""
                val beforeHash = if (hashIndex >= 0) body.substring(0, hashIndex) else body

                val atIndex = beforeHash.lastIndexOf('@')
                if (atIndex <= 0) return null
                val userInfo = beforeHash.substring(0, atIndex)
                val afterAt = beforeHash.substring(atIndex + 1)

                val queryIndex = afterAt.indexOf('?')
                val hostPort = if (queryIndex >= 0) afterAt.substring(0, queryIndex) else afterAt
                val queryRaw = if (queryIndex >= 0) afterAt.substring(queryIndex + 1) else ""

                val (address, port) = splitHostPort(hostPort) ?: return null
                return ShareLinkParts(userInfo, address, port, parseQuery(queryRaw), decode(remarkRaw))
            } catch (_: Exception) {
                return null
            }
        }

        private fun parseVless(raw: String, subscriptionId: String?): XrayServer? {
            val parts = parseUriShareLink(raw, "vless://") ?: return null
            val uuid = parts.userInfo
            if (uuid.isEmpty()) return null
            fun param(key: String) = parts.params[key].orEmpty()

            return XrayServer(
                id = stableServerId(subscriptionId, raw),
                remark = parts.remark.ifBlank { "${parts.address}:${parts.port}" },
                protocol = XrayProtocol.VLESS,
                address = parts.address,
                port = parts.port,
                uuid = uuid,
                encryption = param("encryption").ifBlank { "none" },
                flow = param("flow"),
                network = XrayNetwork.fromWire(param("type").ifBlank { "tcp" }),
                security = XraySecurity.fromWire(param("security").ifBlank { "none" }),
                sni = param("sni").ifBlank { param("peer") },
                fingerprint = param("fp"),
                alpn = param("alpn"),
                publicKey = param("pbk"),
                shortId = param("sid"),
                spiderX = param("spx"),
                allowInsecure = param("allowInsecure") == "1" || param("insecure") == "1",
                path = param("path"),
                host = param("host"),
                serviceName = param("serviceName"),
                grpcMode = param("mode").ifBlank { "gun" },
                headerType = param("headerType"),
                seed = param("seed"),
                extra = param("extra"),
                subscriptionId = subscriptionId,
                rawLink = raw,
            )
        }

        /**
         * `vmess://<base64 JSON>` — the de-facto v2rayNG/panel export format.
         * Field names follow the widely-implemented convention (`add`, `ps`,
         * `scy`, `net`, `type`, …); `port` is accepted as either a number or a
         * string since panels emit both. For `net: "grpc"` the serviceName
         * travels in `path`, so it's routed to [XrayServer.serviceName].
         * alterId is deliberately not read — modern Xray-core is AEAD-only
         * (see [toOutboundJson]).
         */
        private fun parseVmess(raw: String, subscriptionId: String?): XrayServer? {
            try {
                val encoded = raw.substring("vmess://".length).trim()
                val bytes = try {
                    Base64.decode(encoded, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                }
                val json = JSONObject(String(bytes, StandardCharsets.UTF_8))

                val address = json.optString("add")
                if (address.isEmpty()) return null
                val port = (json.opt("port") as? Number)?.toInt()
                    ?: json.optString("port").toIntOrNull()
                    ?: return null
                val uuid = json.optString("id")
                if (uuid.isEmpty()) return null
                val network = json.optString("net").ifBlank { "tcp" }

                return XrayServer(
                    id = stableServerId(subscriptionId, raw),
                    remark = json.optString("ps").ifBlank { "$address:$port" },
                    protocol = XrayProtocol.VMESS,
                    address = address,
                    port = port,
                    uuid = uuid,
                    encryption = json.optString("scy").ifBlank { json.optString("security") }
                        .ifBlank { "auto" },
                    network = XrayNetwork.fromWire(network),
                    security = XraySecurity.fromWire(json.optString("tls")),
                    sni = json.optString("sni"),
                    fingerprint = json.optString("fp"),
                    alpn = json.optString("alpn"),
                    allowInsecure = json.optBoolean("allowInsecure", false) ||
                        json.optString("allowInsecure") == "1",
                    path = json.optString("path"),
                    host = json.optString("host"),
                    serviceName = if (network.equals("grpc", ignoreCase = true)) {
                        json.optString("path")
                    } else {
                        json.optString("serviceName")
                    },
                    headerType = json.optString("type"),
                    seed = json.optString("seed"),
                    subscriptionId = subscriptionId,
                    rawLink = raw,
                )
            } catch (_: Exception) {
                return null
            }
        }

        /** `trojan://password@host:port?params#remark` — same URI shape as vless; TLS is the default. */
        private fun parseTrojan(raw: String, subscriptionId: String?): XrayServer? {
            val parts = parseUriShareLink(raw, "trojan://") ?: return null
            val password = decode(parts.userInfo)
            if (password.isEmpty()) return null
            fun param(key: String) = parts.params[key].orEmpty()

            return XrayServer(
                id = stableServerId(subscriptionId, raw),
                remark = parts.remark.ifBlank { "${parts.address}:${parts.port}" },
                protocol = XrayProtocol.TROJAN,
                address = parts.address,
                port = parts.port,
                uuid = password,
                network = XrayNetwork.fromWire(param("type").ifBlank { "tcp" }),
                security = XraySecurity.fromWire(param("security").ifBlank { "tls" }),
                sni = param("sni").ifBlank { param("peer") },
                fingerprint = param("fp"),
                alpn = param("alpn"),
                publicKey = param("pbk"),
                shortId = param("sid"),
                spiderX = param("spx"),
                allowInsecure = param("allowInsecure") == "1" || param("insecure") == "1",
                path = param("path"),
                host = param("host"),
                serviceName = param("serviceName"),
                grpcMode = param("mode").ifBlank { "gun" },
                headerType = param("headerType"),
                seed = param("seed"),
                extra = param("extra"),
                subscriptionId = subscriptionId,
                rawLink = raw,
            )
        }

        /** Splits `host:port` (or `[ipv6]:port`) into an (address, port) pair. */
        private fun splitHostPort(hostPort: String): Pair<String, Int>? {
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) return null
                val address = hostPort.substring(1, end)
                val rest = hostPort.substring(end + 1)
                val port = rest.removePrefix(":").toIntOrNull() ?: return null
                return address to port
            }
            val colonIndex = hostPort.lastIndexOf(':')
            if (colonIndex <= 0) return null
            val address = hostPort.substring(0, colonIndex)
            val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null
            return address to port
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&")
                .mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val eq = pair.indexOf('=')
                    if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
                }
                .toMap()
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

        fun suggestNameFor(server: XrayServer): String = server.remark.ifBlank { server.summary }
    }
}

/** Encodes [text] for use inside a share-link query component (kept for future export support). */
internal fun xrayUrlEncode(text: String): String = URLEncoder.encode(text, "UTF-8")

/**
 * Whether [link] is a single-server share link [XrayServer.parseShareLink]
 * understands — the gate for QR scans and the Add-connection sheet's input.
 * Subscription URLs (http/https) are accepted separately by the sheet.
 */
fun String.isSupportedXrayShareLink(): Boolean =
    startsWith("vless://", ignoreCase = true) ||
        startsWith("vmess://", ignoreCase = true) ||
        startsWith("trojan://", ignoreCase = true)
