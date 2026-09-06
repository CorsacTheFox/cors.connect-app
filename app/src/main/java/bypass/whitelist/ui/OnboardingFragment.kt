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
import bypass.whitelist.util.Prefs

/**
 * First-run onboarding — mirrors the Nocturne redesign prototype (screen 1a,
 * `isOnboard`): four setup steps plus a "sign in with Telegram" button that
 * opens @your_subscription_bot. Both buttons finish onboarding; the real sign-in
 * happens later via the subscription link / Telegram deep-link callback.
 */
class OnboardingFragment : Fragment() {

    interface Host {
        fun onOnboardingFinished()
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

        view.findViewById<View>(R.id.obAuthButton).setOnClickListener {
            openTelegramBot()
            finish()
        }
        view.findViewById<View>(R.id.obSkipButton).setOnClickListener { finish() }
    }

    private fun finish() {
        Prefs.onboardingDone = true
        (activity as? Host)?.onOnboardingFinished()
    }

    /** @your_subscription_bot: tg:// first (opens the app), web profile as fallback. */
    private fun openTelegramBot() {
        val ctx = context ?: return
        val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=your_subscription_bot"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(tg)
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/your_subscription_bot"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
