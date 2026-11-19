package com.osogold.tv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xtream Codes client (player_api.php).
 *
 * All functions are blocking and must be called from a background thread
 * (Catalog / Settings do that with Dispatchers.IO).
 *
 * kind = "live" | "vod" | "series"
 */
object XtreamClient {

    /** Accepts "host:port", "http://host:port/" or a full get.php / player_api.php link. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        s = s.substringBefore("/player_api.php").substringBefore("/get.php")
        return s.trimEnd('/')
    }

    private fun base(prefs: Prefs): String {
        val server = normalizeServer(prefs.effectiveXtreamServer())
        if (server.isEmpty()) throw IOException("لم يتم إدخال رابط السيرفر")
        return server
    }

    private fun apiUrl(prefs: Prefs, action: String?, extra: String = ""): String {
        val user = URLEncoder.encode(prefs.xtreamUser, "UTF-8")
        val pass = URLEncoder.encode(prefs.xtreamPass, "UTF-8")
        val sb = StringBuilder(base(prefs))
            .append("/player_api.php?username=").append(user)
            .append("&password=").append(pass)
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    private fun parseArray(text: String): JSONArray {
        val t = text.trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONArray()
    }

    /** Checks the login and returns a readable summary of the subscription. */
    fun accountInfo(prefs: Prefs): String {
        val obj = JSONObject(NetConfig.getText(apiUrl(prefs, null)))
        val info = obj.optJSONObject("user_info") ?: throw IOException("رد غير صالح من السيرفر")
        if (info.optInt("auth", 0) != 1) throw IOException("اسم المستخدم أو كلمة المرور غير صحيحة")
        val status = info.optString("status", "-")
        val expRaw = info.optString("exp_date", "")
        val expText = expRaw.toLongOrNull()
            ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000L)) }
            ?: "غير محدود"
        val max = info.optString("max_connections", "-")
        return "الحالة: $status\nتاريخ الانتهاء: $expText\nعدد الاتصالات المسموحة: $max"
    }

    /** Packages (categories) of live TV, movies or series. */
    fun categories(prefs: Prefs, kind: String): List<Pkg> {
        val arr = parseArray(NetConfig.getText(apiUrl(prefs, "get_${kind}_categories")))
        val out = ArrayList<Pkg>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("category_id")
            if (id.isEmpty()) continue
            out.add(Pkg(id, o.optString("category_name").ifBlank { "—" }))
        }
        return out
    }

    /** Channels / movies / series of one package. [categoryId] = Catalog.ALL loads everything. */
    fun streams(prefs: Prefs, kind: String, categoryId: String): List<Channel> {
        val action = when (kind) {
            "live" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            else -> "get_series"
        }
        val extra = if (categoryId.isNotBlank() && categoryId != Catalog.ALL) {
            "&category_id=" + URLEncoder.encode(categoryId, "UTF-8")
        } else {
            ""
        }
        val arr = parseArray(NetConfig.getText(apiUrl(prefs, action, extra)))
        val server = base(prefs)
        val user = Uri.encode(prefs.xtreamUser)
        val pass = Uri.encode(prefs.xtreamPass)

        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { "—" }
            when (kind) {
                "live" -> {
                    val id = o.optString("stream_id")
                    if (id.isNotEmpty()) {
                        out.add(
                            Channel(
                                name = name,
                                url = "$server/live/$user/$pass/$id.ts",
                                logo = o.optString("stream_icon")
                            )
                        )
                    }
                }
                "vod" -> {
                    val id = o.optString("stream_id")
                    val ext = o.optString("container_extension").ifBlank { "mp4" }
                    if (id.isNotEmpty()) {
                        out.add(
                            Channel(
                                name = name,
                                url = "$server/movie/$user/$pass/$id.$ext",
                                logo = o.optString("stream_icon"),
                                infoId = id
                            )
                        )
                    }
                }
                else -> {
                    val id = o.optString("series_id")
                    if (id.isNotEmpty()) {
                        out.add(
                            Channel(
                                name = name,
                                url = "series:$id",
                                logo = o.optString("cover"),
                                seriesId = id
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    /** All episodes of a series (every season), in order. */
    fun episodes(prefs: Prefs, seriesId: String): List<Channel> =
        episodesFrom(prefs, seriesInfoJson(prefs, seriesId))

    private fun seriesInfoJson(prefs: Prefs, seriesId: String): JSONObject =
        JSONObject(NetConfig.getText(apiUrl(prefs, "get_series_info", "&series_id=" + Uri.encode(seriesId))))

    /** Story, trailer and cover of a movie (get_vod_info). */
    fun vodDetail(prefs: Prefs, vodId: String): MovieDetail {
        val obj = JSONObject(
            NetConfig.getText(apiUrl(prefs, "get_vod_info", "&vod_id=" + Uri.encode(vodId)))
        )
        val info = obj.optJSONObject("info") ?: JSONObject()
        return MovieDetail(
            title = info.optString("name"),
            plot = info.optString("plot").ifBlank { info.optString("description") },
            trailerId = youtubeId(info.optString("youtube_trailer")),
            poster = info.optString("movie_image").ifBlank { info.optString("cover_big") },
            genre = info.optString("genre"),
            year = info.optString("releasedate").ifBlank { info.optString("release_date") }.take(4),
            rating = info.optString("rating")
        )
    }

    /** Story, trailer, cover and all episodes of a series (get_series_info). */
    fun seriesDetail(prefs: Prefs, seriesId: String): MovieDetail {
        val obj = seriesInfoJson(prefs, seriesId)
        val info = obj.optJSONObject("info") ?: JSONObject()
        return MovieDetail(
            title = info.optString("name"),
            plot = info.optString("plot").ifBlank { info.optString("description") },
            trailerId = youtubeId(info.optString("youtube_trailer")),
            poster = info.optString("cover"),
            genre = info.optString("genre"),
            year = info.optString("releaseDate").ifBlank { info.optString("release_date") }.take(4),
            rating = info.optString("rating"),
            episodes = episodesFrom(prefs, obj)
        )
    }

    private fun episodesFrom(prefs: Prefs, obj: JSONObject): List<Channel> {
        val server = base(prefs)
        val user = Uri.encode(prefs.xtreamUser)
        val pass = Uri.encode(prefs.xtreamPass)

        val seasons = ArrayList<Pair<Int, JSONArray>>()
        when (val eps = obj.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = eps.optJSONArray(key) ?: continue
                    seasons.add((key.toIntOrNull() ?: 0) to arr)
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    val arr = eps.optJSONArray(i) ?: continue
                    seasons.add((i + 1) to arr)
                }
            }
        }
        seasons.sortBy { it.first }

        val out = ArrayList<Channel>()
        for ((season, arr) in seasons) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val ext = o.optString("container_extension").ifBlank { "mp4" }
                val number = o.optInt("episode_num", i + 1)
                val title = o.optString("title")
                val name = "S%02dE%02d".format(season, number) +
                    (if (title.isNotBlank()) " — $title" else "")
                out.add(
                    Channel(
                        name = name,
                        url = "$server/series/$user/$pass/$id.$ext",
                        group = "الموسم $season"
                    )
                )
            }
        }
        return out
    }
}
