package bypass.whitelist.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.cabinet.CabinetApi
import bypass.whitelist.util.Prefs
import kotlin.concurrent.thread

/**
 * First-run onboarding — mirrors the Nocturne redesign prototype (screen 1a,
 * `isOnboard`): four setup steps plus a "sign in with Telegram" button that
 * signs in to the bot cabinet (deep link + poll) and then hands over to the
 * Subscription tab, which imports the account's subscription.
 */
class OnboardingFragment : Fragment() {

    interface Host {
        fun onOnboardingFinished()
        /** Signed in to the bot cabinet from onboarding — show the Subscription tab. */
        fun onOnboardingSignedIn() {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val steps = listOf(
            view.findViewById<View>(R.id.obStep1) to
                (R.string.ob_step_1_title to R.string.ob_step_1_sub),
            view.findViewById<View>(R.id.obStep2) to
                (R.string.ob_step_2_title to R.string.ob_step_2_sub),
            view.findViewById<View>(R.id.obStep3) to
                (R.string.ob_step_3_title to R.string.ob_step_3_sub),
            view.findViewById<View>(R.id.obStep4) to
                (R.string.ob_step_4_title to R.string.ob_step_4_sub),
        )
        steps.forEachIndexed { index, (row, text) ->
            row.findViewById<TextView>(R.id.obStepNum).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.obStepTitle).setText(text.first)
            row.findViewById<TextView>(R.id.obStepSub).setText(text.second)
        }

        view.findViewById<View>(R.id.obAuthButton).setOnClickListener { startBotLogin() }
        view.findViewById<View>(R.id.obSkipButton).setOnClickListener { finish() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollToken: String? = null
    private var pollDeadline = 0L

    /**
     * Same sign-in as the Subscription tab: one-time deep link into the bot,
     * then poll until the user confirms. On success onboarding finishes and
     * the Subscription tab opens — it imports the account's subscription.
     */
    private fun startBotLogin() {
        if (pollToken != null) return
        thread(isDaemon = true) {
            val link = runCatching { CabinetApi.requestDeepLink() }.getOrNull()
            handler.post {
                if (!isAdded) return@post
                if (link == null) {
                    // Cabinet unreachable — fall back to just opening the bot.
                    openUrl("tg://resolve?domain=corsxray2bot") || openUrl("https://t.me/corsxray2bot")
                    return@post
                }
                pollToken = link.token
                pollDeadline = System.currentTimeMillis() + link.expiresInSec * 1000L
                view?.findViewById<TextView>(R.id.obAuthLabel)?.setText(R.string.sub_login_waiting)
                openUrl("tg://resolve?domain=${link.botUsername}&start=webauth_${link.token}") ||
                    openUrl("https://t.me/${link.botUsername}?start=webauth_${link.token}")
                schedulePoll()
            }
        }
    }

    private fun schedulePoll() {
        handler.postDelayed({
            val token = pollToken ?: return@postDelayed
            if (System.currentTimeMillis() > pollDeadline) {
                pollToken = null
                view?.findViewById<TextView>(R.id.obAuthLabel)?.setText(R.string.ob_auth)
                return@postDelayed
            }
            thread(isDaemon = true) {
                val r = runCatching { CabinetApi.pollDeepLink(token) }.getOrNull()
                handler.post {
                    if (!isAdded || pollToken != token) return@post
                    when (r) {
                        CabinetApi.PollState.DONE -> {
                            pollToken = null
                            finish()
                            (activity as? Host)?.onOnboardingSignedIn()
                        }
                        CabinetApi.PollState.EXPIRED -> {
                            pollToken = null
                            view?.findViewById<TextView>(R.id.obAuthLabel)?.setText(R.string.ob_auth)
                        }
                        else -> schedulePoll()
                    }
                }
            }
        }, 2_000)
    }

    private fun openUrl(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun finish() {
        Prefs.onboardingDone = true
        (activity as? Host)?.onOnboardingFinished()
    }
}
