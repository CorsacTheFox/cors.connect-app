package bypass.whitelist.xray

import android.os.Handler
import android.os.Looper
import android.util.Log
import bypass.whitelist.util.Prefs
import kotlin.concurrent.thread

/**
 * Refreshes every imported Xray subscription: re-fetches the feed, swaps the
 * expanded servers in [Prefs] and stamps the subscription's metadata. The
 * running tunnel is intentionally left alone — its config was already built,
 * and the fresh server list is simply used on the next connect, so a refresh
 * never resets the connection (see [Prefs.replaceSubscriptionServers], which
 * also keeps the user's active selection whenever the server still exists).
 */
object XraySubscriptionRefresher {

    private const val TAG = "XraySubRefresher"
    private val main = Handler(Looper.getMainLooper())

    data class Result(
        val totalSubscriptions: Int,
        val okSubscriptions: Int,
        val totalServers: Int,
    )

    /**
     * Refreshes all subscriptions off the main thread. [onDone] (main thread,
     * optional) receives the aggregate result; subscriptions are skipped
     * silently when there are none.
     */
    fun refreshAll(onDone: ((Result) -> Unit)? = null) {
        thread(name = "xray-subscription-refresh") {
            var ok = 0
            var serversTotal = 0
            val subscriptions = Prefs.xraySubscriptions
            for (subscription in subscriptions) {
                try {
                    val fetched = XraySubscriptionManager.fetchWithUserInfo(subscription.url, subscription.id)
                    if (fetched.servers.isEmpty()) throw XraySubscriptionException("empty subscription")
                    Prefs.replaceSubscriptionServers(subscription.id, fetched.servers)
                    Prefs.addOrUpdateXraySubscription(
                        subscription.copy(
                            lastUpdatedMs = System.currentTimeMillis(),
                            lastServerCount = fetched.servers.size,
                            usedBytes = fetched.userInfo?.usedBytes ?: 0L,
                            totalBytes = fetched.userInfo?.totalBytes ?: 0L,
                            expireAtSec = fetched.userInfo?.expireAtSec ?: 0L,
                        ),
                    )
                    ok++
                    serversTotal += fetched.servers.size
                } catch (e: Exception) {
                    Log.w(TAG, "refresh failed for ${subscription.name}: ${e.message}")
                }
            }
            val result = Result(
                totalSubscriptions = subscriptions.size,
                okSubscriptions = ok,
                totalServers = serversTotal,
            )
            if (onDone != null) main.post { onDone(result) }
        }
    }
}
