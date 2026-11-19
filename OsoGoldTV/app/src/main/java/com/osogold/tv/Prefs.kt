package com.osogold.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Small wrapper around SharedPreferences.
 *
 * - login state ("password asked only once") and password (SHA-256 hash)
 * - welcome video file
 * - playlist (M3U) sources and user playlists (link / file)
 * - Xtream Codes account (with optional pinned server), Stalker portal (MAC)
 * - custom User-Agent
 * - dual screen settings
 * - favourites
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp: SharedPreferences =
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- login / password

    /** True once the correct password has been entered. Reset when the password is changed. */
    var loggedIn: Boolean
        get() = sp.getBoolean(KEY_LOGGED_IN, false)
        set(value) {
            sp.edit().putBoolean(KEY_LOGGED_IN, value).apply()
        }

    fun checkPassword(input: String): Boolean {
        val stored = sp.getString(KEY_PASSWORD_HASH, null) ?: sha256(DEFAULT_PASSWORD)
        return sha256(input) == stored
    }

    /** Saves a new password. The password screen will be shown again on the next launch. */
    fun changePassword(newPassword: String) {
        sp.edit()
            .putString(KEY_PASSWORD_HASH, sha256(newPassword))
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
    }

    // ---------------------------------------------------------------- welcome video

    var welcomeVideoPath: String?
        get() = sp.getString(KEY_WELCOME_VIDEO, null)
        set(value) {
            sp.edit().putString(KEY_WELCOME_VIDEO, value).apply()
        }

    /** The saved welcome video, or null if none was chosen (or the file vanished). */
    fun welcomeVideoFile(): File? {
        val path = welcomeVideoPath ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0L) file else null
    }

    // ---------------------------------------------------------------- playlist sources

    fun getSource(key: String): String =
        sp.getString(KEY_SOURCE_PREFIX + key, null) ?: DefaultData.defaultSource(key)

    fun setSource(key: String, url: String) {
        sp.edit().putString(KEY_SOURCE_PREFIX + key, url.trim()).apply()
    }

    fun resetSources() {
        val editor = sp.edit()
        DefaultData.sourceKeys.forEach { editor.remove(KEY_SOURCE_PREFIX + it) }
        editor.apply()
    }

    // ---------------------------------------------------------------- user playlists (link / file)

    fun customLists(): List<CustomList> {
        val json = sp.getString(KEY_CUSTOM_LISTS, null) ?: return emptyList()
        val result = ArrayList<CustomList>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(CustomList(o.optString("id"), o.optString("name"), o.optString("value")))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun saveCustomLists(list: List<CustomList>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("value", it.value))
        }
        sp.edit().putString(KEY_CUSTOM_LISTS, array.toString()).apply()
    }

    fun addCustomList(name: String, value: String): CustomList {
        val entry = CustomList(System.currentTimeMillis().toString(), name.trim().ifEmpty { "قائمة M3U" }, value.trim())
        saveCustomLists(customLists() + entry)
        return entry
    }

    /** Removes a list. A list that was copied from the phone is deleted from the app storage too. */
    fun removeCustomList(id: String) {
        val all = customLists()
        all.firstOrNull { it.id == id }?.let {
            if (it.value.startsWith("file:")) {
                try {
                    File(it.value.removePrefix("file:")).delete()
                } catch (_: Exception) {
                }
            }
        }
        saveCustomLists(all.filter { it.id != id })
    }

    // ---------------------------------------------------------------- Xtream Codes

    var xtreamServer: String
        get() = sp.getString(KEY_X_SERVER, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_X_SERVER, value.trim()).apply()
        }

    var xtreamUser: String
        get() = sp.getString(KEY_X_USER, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_X_USER, value.trim()).apply()
        }

    var xtreamPass: String
        get() = sp.getString(KEY_X_PASS, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_X_PASS, value.trim()).apply()
        }

    /** The typed server was pinned as default: from now on only user name and password are asked. */
    var xtreamPinned: Boolean
        get() = sp.getBoolean(KEY_X_PINNED, false)
        set(value) {
            sp.edit().putBoolean(KEY_X_PINNED, value).apply()
        }

    /** The server really used: the one compiled into the app, otherwise the one typed by the user. */
    fun effectiveXtreamServer(): String =
        DefaultData.XTREAM_DEFAULT_SERVER.ifBlank { xtreamServer }

    fun isServerPinned(): Boolean =
        DefaultData.XTREAM_DEFAULT_SERVER.isNotBlank() || (xtreamPinned && xtreamServer.isNotBlank())

    fun xtreamConfigured(): Boolean =
        effectiveXtreamServer().isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()

    /** Deletes the account. A pinned server stays. */
    fun clearXtreamAccount() {
        val editor = sp.edit().remove(KEY_X_USER).remove(KEY_X_PASS)
        if (!xtreamPinned) editor.remove(KEY_X_SERVER)
        editor.apply()
    }

    fun unpinXtreamServer() {
        sp.edit().putBoolean(KEY_X_PINNED, false).apply()
    }

    // ---------------------------------------------------------------- Stalker portal (MAC)

    var stalkerPortal: String
        get() = sp.getString(KEY_S_PORTAL, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_S_PORTAL, value.trim()).apply()
        }

    var stalkerMac: String
        get() = sp.getString(KEY_S_MAC, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_S_MAC, value.trim()).apply()
        }

    fun stalkerConfigured(): Boolean = stalkerPortal.isNotBlank() && stalkerMac.isNotBlank()

    fun clearStalker() {
        sp.edit().remove(KEY_S_PORTAL).remove(KEY_S_MAC).apply()
    }

    // ---------------------------------------------------------------- User-Agent

    /** Player identity card sent to every server (IPTVSmartersPro, VLC, ExoPlayer, custom ...). */
    var userAgent: String
        get() = sp.getString(KEY_USER_AGENT, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_USER_AGENT
        set(value) {
            sp.edit().putString(KEY_USER_AGENT, value.trim()).apply()
        }

    /** Names typed by the user for the subscriptions (shown on the "My lists" page). */
    var xtreamName: String
        get() = sp.getString(KEY_X_NAME, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_X_NAME, value.trim()).apply()
        }

    var stalkerName: String
        get() = sp.getString(KEY_S_NAME, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_S_NAME, value.trim()).apply()
        }

    // ---------------------------------------------------------------- dual screen

    /** 0 = medium, 1 = small, 2 = smallest. */
    var pipSize: Int
        get() = sp.getInt(KEY_PIP_SIZE, 1).coerceIn(0, 2)
        set(value) {
            sp.edit().putInt(KEY_PIP_SIZE, value.coerceIn(0, 2)).apply()
        }

    /** true = small screen in the bottom right corner, false = bottom left (default). */
    var pipRight: Boolean
        get() = sp.getBoolean(KEY_PIP_RIGHT, false)
        set(value) {
            sp.edit().putBoolean(KEY_PIP_RIGHT, value).apply()
        }

    /** Video shape of the big screen: 0 fill screen, 1 cinema, 2 original, 3 stretch. */
    var aspectMode: Int
        get() = sp.getInt(KEY_ASPECT_V2, 2).coerceIn(0, 3)
        set(value) {
            sp.edit().putInt(KEY_ASPECT_V2, value.coerceIn(0, 3)).apply()
        }

    // ---------------------------------------------------------------- player engine

    /** "exo" = Media3 / ExoPlayer (default, strongest) or "vlc" = LibVLC (secondary). */
    var playerEngine: String
        get() = sp.getString(KEY_ENGINE, ENGINE_EXO).let { if (it == ENGINE_VLC) ENGINE_VLC else ENGINE_EXO }
        set(value) {
            sp.edit().putString(KEY_ENGINE, if (value == ENGINE_VLC) ENGINE_VLC else ENGINE_EXO).apply()
        }

    val useVlc: Boolean
        get() = playerEngine == ENGINE_VLC

    // ---------------------------------------------------------------- subtitles

    /** Subtitle size in sp (12..48). */
    var subSize: Int
        get() = sp.getInt(KEY_SUB_SIZE, 20).coerceIn(12, 48)
        set(value) {
            sp.edit().putInt(KEY_SUB_SIZE, value.coerceIn(12, 48)).apply()
        }

    /** Index in [SubtitleStyle.COLORS]. */
    var subColor: Int
        get() = sp.getInt(KEY_SUB_COLOR, 0).coerceIn(0, SubtitleStyle.COLORS.size - 1)
        set(value) {
            sp.edit().putInt(KEY_SUB_COLOR, value.coerceIn(0, SubtitleStyle.COLORS.size - 1)).apply()
        }

    /** true = subtitles at the top of the screen, false = bottom (default). */
    var subTop: Boolean
        get() = sp.getBoolean(KEY_SUB_TOP, false)
        set(value) {
            sp.edit().putBoolean(KEY_SUB_TOP, value).apply()
        }

    // ---------------------------------------------------------------- favourites

    /** Kind of every favourite: "live", "movie", "series" or "radio" (url -> kind). */
    private var kinds = HashMap<String, String>()

    private fun inferKind(c: Channel): String {
        val ext = c.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            c.seriesId.isNotEmpty() -> "series"
            c.infoId.isNotEmpty() || c.url.contains("/movie/") -> "movie"
            c.url.contains("/series/") -> "series"
            ext in setOf("mp3", "aac", "m4a", "ogg", "pls") -> "radio"
            ext in setOf("mp4", "mkv", "avi", "mov") -> "movie"
            else -> "live"
        }
    }

    /** The favourites of one kind: "live", "movie", "series" or "radio". */
    fun favoritesOf(kind: String): List<Channel> {
        val all = favorites()
        return all.filter { kinds[it.url] == kind }
    }

    fun favoriteKind(url: String): String = kinds[url] ?: "live"

    fun removeFavorite(url: String) {
        val c = favorites().firstOrNull { it.url == url } ?: return
        toggleFavorite(c)
    }

    fun favorites(): List<Channel> {
        kinds = HashMap()
        val json = sp.getString(KEY_FAVORITES, null) ?: return emptyList()
        val result = ArrayList<Channel>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(
                    Channel(
                        name = o.optString("name"),
                        url = o.optString("url"),
                        logo = o.optString("logo"),
                        group = o.optString("group"),
                        seriesId = o.optString("seriesId"),
                        infoId = o.optString("infoId")
                    )
                )
                kinds[o.optString("url")] = o.optString("kind").ifBlank { inferKind(result.last()) }
            }
        } catch (_: Exception) {
            // corrupted data -> start with an empty list
        }
        return result
    }

    fun isFavorite(url: String): Boolean = favorites().any { it.url == url }

    /** Adds or removes the channel. Returns true when it is a favourite afterwards. */
    fun toggleFavorite(channel: Channel, kind: String = ""): Boolean {
        val list = favorites().toMutableList()
        val savedKinds = HashMap(kinds)
        if (kind.isNotEmpty()) savedKinds[channel.url] = kind
        val existing = list.indexOfFirst { it.url == channel.url }
        val nowFavorite: Boolean
        if (existing >= 0) {
            list.removeAt(existing)
            nowFavorite = false
        } else {
            list.add(channel)
            nowFavorite = true
        }
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("logo", it.logo)
                    .put("group", it.group)
                    .put("seriesId", it.seriesId)
                    .put("infoId", it.infoId)
                    .put("kind", savedKinds[it.url] ?: inferKind(it))
            )
        }
        sp.edit().putString(KEY_FAVORITES, array.toString()).apply()
        return nowFavorite
    }

    /** Changes whenever something that influences the lists changes (used to reload on return). */
    fun listsSignature(): String =
        DefaultData.sourceKeys.joinToString("|") { getSource(it) } + "#" +
            effectiveXtreamServer() + xtreamUser + xtreamPass + xtreamName + "#" +
            stalkerPortal + stalkerMac + stalkerName + "#" +
            customLists().joinToString("|") { it.id + it.value }

    // ---------------------------------------------------------------- helpers

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val FILE_NAME = "oso_gold_prefs"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_WELCOME_VIDEO = "welcome_video_path"
        private const val KEY_SOURCE_PREFIX = "source_"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_CUSTOM_LISTS = "custom_lists"
        private const val KEY_X_SERVER = "x_server"
        private const val KEY_X_USER = "x_user"
        private const val KEY_X_PASS = "x_pass"
        private const val KEY_X_PINNED = "x_pinned"
        private const val KEY_S_PORTAL = "s_portal"
        private const val KEY_S_MAC = "s_mac"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_PIP_SIZE = "pip_size"
        private const val KEY_PIP_RIGHT = "pip_right"
        private const val KEY_ASPECT_V2 = "aspect_mode_v2"
        private const val KEY_ENGINE = "player_engine"
        private const val KEY_X_NAME = "x_name"
        private const val KEY_S_NAME = "s_name"
        private const val KEY_SUB_SIZE = "sub_size"
        private const val KEY_SUB_COLOR = "sub_color"
        private const val KEY_SUB_TOP = "sub_top"

        const val ENGINE_EXO = "exo"
        const val ENGINE_VLC = "vlc"

        /** Password that is valid until the owner changes it in the settings. */
        const val DEFAULT_PASSWORD = "1234"

        /** Default player identity card. Can be changed in Settings. */
        const val DEFAULT_USER_AGENT = "IPTVSmartersPro"

        /** true  = the welcome video is played at every launch (after the password was accepted).
         *  false = only directly after the password was entered. */
        const val WELCOME_EVERY_LAUNCH = true
    }
}
