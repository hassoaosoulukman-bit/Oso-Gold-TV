package com.osogold.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Parses #EXTM3U playlists (TV channels, radio stations, movies ...). */
object M3uParser {

    private val attributeCache = HashMap<String, Regex>()

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var logo = ""
        var group = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                logo = attribute(line, "tvg-logo")
                group = attribute(line, "group-title")
                val afterAttributes = line.lastIndexOf('"').coerceAtLeast(0)
                val comma = line.indexOf(',', afterAttributes)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                if (name.isEmpty()) name = attribute(line, "tvg-name")
            } else if (line.startsWith("#")) {
                // other directive (#EXTM3U, #EXTVLCOPT, #EXTGRP ...) -> ignore
                continue
            } else {
                // a stream address
                result.add(
                    Channel(
                        name = name.ifEmpty { line },
                        url = line,
                        logo = logo,
                        group = group
                    )
                )
                name = ""
                logo = ""
                group = ""
            }
        }
        return result
    }

    private fun attribute(line: String, key: String): String {
        val regex = attributeCache.getOrPut(key) {
            Regex(Regex.escape(key) + "=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        }
        return regex.find(line)?.groupValues?.get(1)?.trim() ?: ""
    }
}

/** Downloads and caches playlists. */
object PlaylistRepository {

    private val cache = HashMap<String, List<Channel>>()

    /**
     * Loads a playlist.
     * @param force true = ignore the in-memory cache (used by the refresh button).
     */
    suspend fun load(url: String, force: Boolean = false): List<Channel> {
        if (!force) {
            cache[url]?.let { return it }
        }
        val text = withContext(Dispatchers.IO) { download(url) }
        val channels = withContext(Dispatchers.Default) { M3uParser.parse(text) }
        cache[url] = channels
        return channels
    }

    private fun download(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "OsoGoldTV/1.0")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
