package com.osogold.tv

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One place for every network setting.
 *
 * The User-Agent ("player identity card") is read from the settings every time a connection is
 * opened, so a change in Settings takes effect immediately for ExoPlayer, playlist downloads,
 * Xtream Codes requests and stream recording.
 */
object NetConfig {

    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The User-Agent chosen in Settings (IPTVSmartersPro, VLC, ExoPlayer or a custom text). */
    fun userAgent(): String = Prefs(app).userAgent

    /** Creates a fresh HTTP data source for every request, always with the current User-Agent. */
    fun httpFactory(): DataSource.Factory = DataSource.Factory {
        DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent())
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .createDataSource()
    }

    /** HTTP + file + asset + content sources, used by every ExoPlayer instance of the app. */
    fun dataSourceFactory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory())

    /** Opens a connection and follows redirects (also http -> https). Throws on HTTP errors. */
    fun open(
        address: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = userAgent(),
        connectMs: Int = 15_000,
        readMs: Int = 30_000
    ): HttpURLConnection {
        var current = address
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", userAgent)
            for ((key, value) in headers) conn.setRequestProperty(key, value)
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location")
                current = URL(URL(current), location).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
        }
        throw IOException("too many redirects")
    }

    /** Downloads a text resource. Call from a background thread. */
    fun getText(
        address: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = userAgent()
    ): String {
        val conn = open(address, headers, userAgent)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
