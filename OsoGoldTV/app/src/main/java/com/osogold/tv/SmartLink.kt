package com.osogold.tv

import android.net.Uri

/**
 * "Smart login": extracts the Xtream Codes account out of any long link a provider gives:
 *   http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts
 *   http://host:port/player_api.php?username=U&password=P
 *   http://host:port/live/U/P/12345.ts   (also /movie/ and /series/)
 *   http://host:port/U/P/12345          (short live form)
 *   host:port user pass                 (three words)
 */
object SmartLink {

    data class Account(val server: String, val user: String, val pass: String)

    fun parse(raw: String): Account? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // "host:port user pass"
        val parts = text.split(Regex("\\s+"))
        if (parts.size == 3 && !parts[1].contains("://")) {
            val server = XtreamClient.normalizeServer(parts[0])
            if (server.isNotEmpty()) return Account(server, parts[1], parts[2])
        }

        val withScheme = if (text.contains("://")) text else "http://$text"
        val uri = try {
            Uri.parse(withScheme)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val origin = buildString {
            append(uri.scheme ?: "http").append("://").append(host)
            if (uri.port > 0) append(':').append(uri.port)
        }

        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) return Account(origin, user, pass)

        val seg = uri.pathSegments
        if (seg.size >= 3 && seg[0] in listOf("live", "movie", "series")) {
            return Account(origin, seg[1], seg[2])
        }
        if (seg.size == 3 && seg[2].firstOrNull()?.isDigit() == true) {
            return Account(origin, seg[0], seg[1])
        }
        return null
    }
}
