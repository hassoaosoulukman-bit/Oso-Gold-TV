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
 * - login state ("password asked only once")
 * - password (stored as SHA-256 hash)
 * - welcome video file
 * - playlist (M3U) sources
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

    // ---------------------------------------------------------------- favourites

    fun favorites(): List<Channel> {
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
                        group = o.optString("group")
                    )
                )
            }
        } catch (_: Exception) {
            // corrupted data -> start with an empty list
        }
        return result
    }

    fun isFavorite(url: String): Boolean = favorites().any { it.url == url }

    /** Adds or removes the channel. Returns true when it is a favourite afterwards. */
    fun toggleFavorite(channel: Channel): Boolean {
        val list = favorites().toMutableList()
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
            )
        }
        sp.edit().putString(KEY_FAVORITES, array.toString()).apply()
        return nowFavorite
    }

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

        /** Password that is valid until the owner changes it in the settings. */
        const val DEFAULT_PASSWORD = "1234"

        /** true  = the welcome video is played at every launch (after the password was accepted).
         *  false = only directly after the password was entered. */
        const val WELCOME_EVERY_LAUNCH = true
    }
}
