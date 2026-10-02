package bypass.whitelist.xray

import android.util.Base64
import android.util.Log
import bypass.whitelist.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * "Route table (auto)": Happ-compatible routing profiles. Remnawave (and
 * other panels) send the profile in the subscription's `routing` response
 * header as `happ://routing/add/<base64 json>` (or `/onadd/`); the JSON names
 * direct/proxy/block domain+IP lists (`geosite:`/`geoip:`/`domain:`/`regexp:`
 * entries, plain Xray syntax) plus the geoip.dat/geosite.dat URLs they refer to.
 *
 * Applied only in standard Xray mode ([XrayConfigBuilder.buildForXrayServer]).
 * Whitelist Bypass mode deliberately ignores it: its "direct" would leave via
 * the whitelisted carrier network, where everything not on the operator's
 * allow-list is dead anyway, so a geo split there only breaks sites.
 */
object XrayRouting {

    private const val TAG = "XrayRouting"
    private const val GEO_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 30_000

    data class Profile(
        val name: String,
        val globalProxy: Boolean,
        val routeOrder: List<String>,
        val domainStrategy: String,
        val domesticDnsIp: String,
        val geoipUrl: String,
        val geositeUrl: String,
        val directSites: List<String>,
        val directIp: List<String>,
        val proxySites: List<String>,
        val proxyIp: List<String>,
        val blockSites: List<String>,
        val blockIp: List<String>,
    ) {
        companion object {
            fun parse(json: String): Profile? = try {
                val o = JSONObject(json)
                fun list(key: String): List<String> {
                    val arr = o.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
                }
                Profile(
                    name = o.optString("Name").ifBlank { "Routing" },
                    globalProxy = !o.optString("GlobalProxy", "true").equals("false", ignoreCase = true),
                    routeOrder = o.optString("RouteOrder", "block-proxy-direct").split('-').map { it.trim().lowercase() },
                    domainStrategy = o.optString("DomainStrategy").ifBlank { "IPIfNonMatch" },
                    domesticDnsIp = o.optString("DomesticDNSIP").trim(),
                    geoipUrl = o.optString("Geoipurl").trim(),
                    geositeUrl = o.optString("Geositeurl").trim(),
                    directSites = list("DirectSites"),
                    directIp = list("DirectIp"),
                    proxySites = list("ProxySites"),
                    proxyIp = list("ProxyIp"),
                    blockSites = list("BlockSites"),
                    blockIp = list("BlockIp"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Decodes a `routing` header value (`happ://routing/add/<b64>`) to its JSON, or null. */
    fun decodeHeader(header: String?): String? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        val b64 = value.substringAfterLast('/').trim()
        val json = runCatching {
            String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(b64, Base64.URL_SAFE), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return json.takeIf { Profile.parse(it) != null }
    }

    /** Xray asset dir (geoip.dat/geosite.dat), `filesDir/xray`; set once from [bypass.whitelist.App]. */
    @Volatile var assetsDir: File? = null

    /** Stores a fresh profile from a subscription response and refreshes geo files if enabled. */
    fun onSubscriptionHeader(header: String?) {
        val json = decodeHeader(header) ?: return
        if (json != Prefs.xrayRoutingProfile) Prefs.xrayRoutingProfile = json
        val dir = assetsDir
        if (Prefs.xrayRoutingAuto && dir != null) ensureGeoFilesAsync(dir)
    }

    val currentProfile: Profile?
        get() = Prefs.xrayRoutingProfile.takeIf { it.isNotBlank() }?.let { Profile.parse(it) }

    fun geoFilesPresent(assetsDir: File): Boolean =
        File(assetsDir, "geoip.dat").length() > 0 && File(assetsDir, "geosite.dat").length() > 0

    @Volatile private var downloading = false

    fun ensureGeoFilesAsync(assetsDir: File, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        thread(name = "xray-geo-download") {
            val ok = runCatching { ensureGeoFiles(assetsDir, force) }.getOrElse {
                Log.w(TAG, "geo download failed: ${it.message}")
                false
            }
            onDone?.invoke(ok)
        }
    }

    /**
     * Blocking: downloads the profile's geoip/geosite when missing, stale or
     * from a different source. Files are written to a temp name and renamed,
     * so a running core never sees a half-written .dat. The app process is
     * outside the VPN, so this works whether or not the tunnel is up.
     */
    @Synchronized
    fun ensureGeoFiles(assetsDir: File, force: Boolean = false): Boolean {
        val profile = currentProfile ?: return false
        if (profile.geoipUrl.isEmpty() || profile.geositeUrl.isEmpty()) return geoFilesPresent(assetsDir)
        val source = "${profile.geoipUrl}|${profile.geositeUrl}"
        val fresh = System.currentTimeMillis() - Prefs.xrayGeoUpdatedMs < GEO_MAX_AGE_MS
        if (!force && fresh && Prefs.xrayGeoSource == source && geoFilesPresent(assetsDir)) return true
        if (downloading) return geoFilesPresent(assetsDir)
        downloading = true
        try {
            assetsDir.mkdirs()
            download(profile.geoipUrl, File(assetsDir, "geoip.dat"))
            download(profile.geositeUrl, File(assetsDir, "geosite.dat"))
            Prefs.xrayGeoSource = source
            Prefs.xrayGeoUpdatedMs = System.currentTimeMillis()
            return true
        } finally {
            downloading = false
        }
    }

    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", XraySubscriptionManager.USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code for $url")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (tmp.length() == 0L) throw IllegalStateException("empty file from $url")
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("cannot move ${target.name}")
            }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /**
     * Routing rules for the profile, in its RouteOrder. With geo files missing,
     * `geosite:`/`geoip:` entries are dropped (Xray refuses to start on a rule
     * that names a missing .dat) — the plain `domain:`/`regexp:` ones still apply.
     */
    fun buildRules(profile: Profile, geoAvailable: Boolean): List<JSONObject> {
        fun keep(list: List<String>) =
            if (geoAvailable) list else list.filterNot { it.startsWith("geosite:") || it.startsWith("geoip:") }

        val byOutbound = mapOf(
            "proxy" to (keep(profile.proxySites) to keep(profile.proxyIp)),
            "direct" to (keep(profile.directSites) to keep(profile.directIp)),
            "block" to (keep(profile.blockSites) to keep(profile.blockIp)),
        )
        val order = (profile.routeOrder.filter { it in byOutbound } + listOf("block", "proxy", "direct")).distinct()
        val rules = mutableListOf<JSONObject>()
        for (tag in order) {
            val (sites, ips) = byOutbound.getValue(tag)
            if (sites.isNotEmpty()) {
                rules += JSONObject().put("type", "field").put("outboundTag", tag).put("domain", JSONArray(sites))
            }
            if (ips.isNotEmpty()) {
                rules += JSONObject().put("type", "field").put("outboundTag", tag).put("ip", JSONArray(ips))
            }
        }
        if (!profile.globalProxy) {
            rules += JSONObject().put("type", "field").put("outboundTag", "direct").put("network", "tcp,udp")
        }
        return rules
    }

    /** Domain lists (DNS-server `domains` syntax) that should resolve via the domestic DNS. */
    fun domesticDnsDomains(profile: Profile, geoAvailable: Boolean): List<String> =
        profile.directSites.filter { geoAvailable || !it.startsWith("geosite:") }

    private const val GEO_WORK_NAME = "xray-geo-refresh"

    /**
     * Daily background check (survives the process dying, unlike the 5-minute
     * in-process subscription refresh); [ensureGeoFiles] only actually
     * downloads once the files are older than [GEO_MAX_AGE_MS] (3 days).
     */
    fun scheduleGeoRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<GeoRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(GEO_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class GeoRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!Prefs.xrayRoutingAuto) return Result.success()
            val dir = assetsDir ?: applicationContext.filesDir.resolve("xray")
            return if (runCatching { ensureGeoFiles(dir) }.getOrDefault(false)) Result.success() else Result.retry()
        }
    }
}
