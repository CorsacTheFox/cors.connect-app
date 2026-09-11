package bypass.whitelist.tunnel

enum class CallPlatform(val id: String, val urlMarker: String) {
    VK("vk", ""),
    TELEMOST("telemost", "telemost"),
    WBSTREAM("wbstream", "wbstream://"),
    DION("dion", "dion://");

    companion object {
        fun fromUrl(url: String): CallPlatform = when {
            url.contains(DION.urlMarker) || url.contains("dion.vc/event/") -> DION
            url.contains(WBSTREAM.urlMarker) -> WBSTREAM
            url.contains(TELEMOST.urlMarker) -> TELEMOST
            else -> VK
        }

        /**
         * True when [url] looks like a video-call link this app can join and
         * tunnel over (VK call, Telemost, WB Stream, DION) rather than an Xray
         * share link or a subscription URL. Used by the "add" sheet to route a
         * pasted/scanned link to the right destination automatically.
         */
        fun isCallLink(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.startsWith(WBSTREAM.urlMarker, ignoreCase = true)) return true
            if (trimmed.startsWith(DION.urlMarker, ignoreCase = true)) return true
            if (trimmed.contains("dion.vc/event/", ignoreCase = true)) return true
            if (trimmed.contains(TELEMOST.urlMarker, ignoreCase = true)) return true
            if (trimmed.contains("vk.com", ignoreCase = true) &&
                (trimmed.contains("/call/join/", ignoreCase = true) ||
                    trimmed.contains("call=join", ignoreCase = true) ||
                    trimmed.contains("im/mvcall/join", ignoreCase = true))
            ) return true
            return false
        }

        fun extractRoomId(url: String): String {
            val trimmed = url.trim()
            if (trimmed.startsWith(WBSTREAM.urlMarker)) return trimmed.removePrefix(WBSTREAM.urlMarker).trim()
            if (trimmed.startsWith(DION.urlMarker)) return trimmed.removePrefix(DION.urlMarker).trim()
            val dionPrefix = "dion.vc/event/"
            val idx = trimmed.indexOf(dionPrefix)
            if (idx >= 0) {
                var slug = trimmed.substring(idx + dionPrefix.length)
                val q = slug.indexOf('?')
                if (q >= 0) slug = slug.substring(0, q)
                val s = slug.indexOf('/')
                if (s >= 0) slug = slug.substring(0, s)
                return slug.trim()
            }
            return trimmed
        }
    }
}
