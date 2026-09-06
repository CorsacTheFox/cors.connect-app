package bypass.whitelist.tunnel

import androidx.annotation.StringRes
import bypass.whitelist.R

enum class TunnelMode(@StringRes val labelRes: Int, val relayArg: String, val isPion: Boolean) {
    DC(R.string.tunnel_mode_dc, "dc", false),
    VIDEO(R.string.tunnel_mode_video, "video", true);

    fun relayMode(platform: CallPlatform): String {
        if (!isPion) return "dc-joiner"
        return "${platform.id}-$relayArg-joiner"
    }

    fun forPlatform(platform: CallPlatform): TunnelMode {
        if (this == DC && (platform == CallPlatform.TELEMOST || platform == CallPlatform.DION)) {
            return VIDEO
        }
        return this
    }
}
