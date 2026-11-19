package com.osogold.tv

/** Parses #EXTM3U / M3U8 playlists (TV channels, radio stations, movies ...). */
object M3uParser {

    private val attributeCache = HashMap<String, Regex>()

    /**
     * Understands every common list format: #EXTM3U / M3U / M3U8 (with or without attributes),
     * PLS (File1= / Title1=), XSPF (<location>/<title>) and plain text with one address per line.
     */
    fun parse(text: String): List<Channel> {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(400)
        return when {
            head.startsWith("[playlist]", ignoreCase = true) -> parsePls(text)
            head.startsWith("<?xml", ignoreCase = true) || head.startsWith("<playlist", ignoreCase = true) ->
                parseXspf(text)
            else -> parseM3u(text)
        }
    }

    private fun parsePls(text: String): List<Channel> {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).lowercase()
            val value = line.substring(eq + 1).trim()
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { files[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        return files.keys.sorted().map { Channel(name = titles[it].orEmpty().ifEmpty { files[it]!! }, url = files[it]!!) }
    }

    private fun parseXspf(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val track = Regex("<track>(.*?)</track>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val loc = Regex("<location>(.*?)</location>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val title = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in track.findAll(text)) {
            val body = m.groupValues[1]
            val url = loc.find(body)?.groupValues?.get(1)?.trim() ?: continue
            out.add(Channel(name = title.find(body)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { url }, url = url))
        }
        return out
    }

    private fun parseM3u(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var logo = ""
        var group = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                logo = attribute(line, "tvg-logo")
                group = attribute(line, "group-title")
                val afterAttributes = line.lastIndexOf('"').coerceAtLeast(0)
                val comma = line.indexOf(',', afterAttributes)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                if (name.isEmpty()) name = attribute(line, "tvg-name")
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                if (group.isEmpty()) group = line.substringAfter(':').trim()
            } else if (line.startsWith("#")) {
                // other directive (#EXTM3U, #EXTVLCOPT ...) -> ignore
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
