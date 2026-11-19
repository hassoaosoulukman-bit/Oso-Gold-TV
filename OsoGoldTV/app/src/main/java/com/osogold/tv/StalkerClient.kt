package com.osogold.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Stalker / Ministra portal client (MAC authentication, MAG250 emulation).
 *
 * All functions are blocking: call them from a background thread.
 * Portal addresses such as  http://host/c/  or  http://host:8080/stalker_portal/c/  are accepted;
 * the matching API endpoint (portal.php / load.php) is detected automatically.
 *
 * Note: portals differ a lot between providers, so this is a best-effort implementation.
 */
object StalkerClient {

    private const val MAG_USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    private class Session(val key: String, val endpoint: String, val token: String)

    @Volatile
    private var session: Session? = null

    // ------------------------------------------------------------------ session

    private fun mac(prefs: Prefs): String = prefs.stalkerMac.trim().uppercase()

    private fun headers(mac: String, token: String?): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Cookie"] = "mac=" + URLEncoder.encode(mac, "UTF-8") + "; stb_lang=en; timezone=Europe/Berlin"
        map["X-User-Agent"] = "Model: MAG250; Link: WiFi"
        map["Accept"] = "*/*"
        if (token != null) map["Authorization"] = "Bearer $token"
        return map
    }

    private fun candidates(portal: String): List<String> {
        var base = portal.trim()
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "http://$base"
        base = base.trimEnd('/')
        val root = if (base.endsWith("/c")) base.removeSuffix("/c") else base
        val list = LinkedHashSet<String>()
        if (base.contains("load.php") || base.contains("portal.php")) list.add(base)
        list.add("$root/portal.php")
        list.add("$root/stalker_portal/server/load.php")
        list.add("$root/server/load.php")
        return list.toList()
    }

    private fun ensureSession(prefs: Prefs, force: Boolean = false): Session {
        val portal = prefs.stalkerPortal.trim()
        val mac = mac(prefs)
        if (portal.isEmpty() || mac.isEmpty()) throw IOException("بيانات Stalker غير مكتملة")
        val key = "$portal|$mac"
        val current = session
        if (!force && current != null && current.key == key) return current

        var lastError: Exception? = null
        for (endpoint in candidates(portal)) {
            try {
                val text = NetConfig.getText(
                    "$endpoint?type=stb&action=handshake&token=&JsHttpRequest=1-xml",
                    headers(mac, null),
                    MAG_USER_AGENT
                )
                val token = JSONObject(text).optJSONObject("js")?.optString("token").orEmpty()
                if (token.isEmpty()) continue
                // Some portals need a profile call before they answer (errors are ignored).
                try {
                    NetConfig.getText(
                        "$endpoint?type=stb&action=get_profile&hd=1&stb_type=MAG250&client_type=STB" +
                            "&image_version=218&video_out=hdmi&auth_second_step=1&not_valid_token=0" +
                            "&api_signature=262&JsHttpRequest=1-xml",
                        headers(mac, token),
                        MAG_USER_AGENT
                    )
                } catch (_: Exception) {
                }
                val fresh = Session(key, endpoint, token)
                session = fresh
                return fresh
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: "تعذر الاتصال بالبوابة")
    }

    private fun request(prefs: Prefs, query: String, s: Session): JSONObject {
        val text = NetConfig.getText(
            "${s.endpoint}?$query&JsHttpRequest=1-xml",
            headers(mac(prefs), s.token),
            MAG_USER_AGENT
        )
        return JSONObject(text)
    }

    /** Request with one automatic re-login when the token has expired. */
    private fun call(prefs: Prefs, query: String): JSONObject {
        return try {
            request(prefs, query, ensureSession(prefs))
        } catch (e: Exception) {
            request(prefs, query, ensureSession(prefs, force = true))
        }
    }

    // ------------------------------------------------------------------ public API

    /** Tests the portal login. Returns a short message or throws. */
    fun test(prefs: Prefs): String {
        ensureSession(prefs, force = true)
        val genres = genres(prefs)
        return "تم الاتصال بالبوابة بنجاح (${genres.size - 1} باقة)"
    }

    /** Genres (packages). The first entry is "all". */
    fun genres(prefs: Prefs): List<Pkg> {
        val obj = call(prefs, "type=itv&action=get_genres")
        val arr = obj.optJSONArray("js") ?: JSONArray()
        val out = ArrayList<Pkg>()
        out.add(Pkg(Catalog.ALL, "الكل"))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty() || id == "*") continue
            out.add(Pkg(id, o.optString("title").ifBlank { "—" }))
        }
        return out
    }

    /** Channels of one genre. The address is resolved when the channel is played. */
    fun channels(prefs: Prefs, genreId: String?): List<Channel> {
        val obj = call(prefs, "type=itv&action=get_all_channels")
        val arr = when (val js = obj.opt("js")) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (genreId != null && genreId != Catalog.ALL && o.optString("tv_genre_id") != genreId) continue
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            out.add(
                Channel(
                    name = o.optString("name").ifBlank { "—" },
                    url = "stalker:$cmd",
                    logo = o.optString("logo")
                )
            )
        }
        return out
    }

    /** Asks the portal for the real (temporary) stream address of a channel. */
    fun resolve(prefs: Prefs, cmd: String): String {
        val query = "type=itv&action=create_link&cmd=" + URLEncoder.encode(cmd, "UTF-8") +
            "&series=&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0"
        val resolved = try {
            call(prefs, query).optJSONObject("js")?.optString("cmd").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return clean(resolved.ifBlank { cmd })
    }

    /** "ffmpeg http://host/stream" -> "http://host/stream" */
    private fun clean(cmd: String): String {
        val t = cmd.trim()
        val index = t.indexOf("http", ignoreCase = true)
        return if (index >= 0) t.substring(index).trim() else t
    }
}
