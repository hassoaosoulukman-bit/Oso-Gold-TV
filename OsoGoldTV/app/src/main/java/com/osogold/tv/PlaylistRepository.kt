package com.osogold.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads (or reads from the phone) and caches playlists.
 *
 * Two caches are used:
 *  - memory : the parsed list, for instant switching between sections
 *  - disk   : the downloaded text, so the lists are available at once when the app starts and
 *             still work when the network is down (the last good copy is used)
 */
object PlaylistRepository {

    /** A disk copy younger than this is used without downloading again. */
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L

    private val cache = HashMap<String, List<Channel>>()

    /** Time of the last successful download (0 = nothing downloaded in this session). */
    @Volatile
    var lastUpdateMillis = 0L
        internal set

    private fun cacheFile(address: String): File {
        val dir = File(NetConfig.app.filesDir, "playlist_cache")
        dir.mkdirs()
        return File(dir, Integer.toHexString(address.hashCode()) + ".m3u")
    }

    /**
     * Loads a playlist.
     * @param address  http(s) link, or "file:<path>" for a list stored on the phone
     * @param force    true = download again (refresh button / automatic update)
     */
    suspend fun load(address: String, force: Boolean = false): List<Channel> {
        if (!force) {
            cache[address]?.let { return it }
        }
        val text = withContext(Dispatchers.IO) {
            if (address.startsWith("file:")) {
                File(address.removePrefix("file:")).readText(Charsets.UTF_8)
            } else {
                val file = cacheFile(address)
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
                if (!force && fresh) {
                    file.readText(Charsets.UTF_8)
                } else {
                    try {
                        val downloaded = NetConfig.getText(address)
                        file.writeText(downloaded, Charsets.UTF_8)
                        lastUpdateMillis = System.currentTimeMillis()
                        downloaded
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // network problem: the last good copy is better than nothing
                        if (file.exists()) file.readText(Charsets.UTF_8) else throw e
                    }
                }
            }
        }
        val channels = withContext(Dispatchers.Default) { M3uParser.parse(text) }
        cache[address] = channels
        return channels
    }

    /** Forgets the memory copies (the disk copies stay). */
    fun clearCache() {
        cache.clear()
    }
}

/**
 * Automatic background update of every list (live channels, movies, series, radio, Quran and the
 * lists added by the user). The main screen runs it regularly and the "Update now" button runs
 * it on demand.
 */
object AutoUpdater {

    /** Time between two automatic updates while the app is open. */
    const val INTERVAL_MS = 30L * 60L * 1000L

    /** Pause before the first automatic update after the app has started. */
    const val FIRST_DELAY_MS = 20_000L

    /** Downloads every list again. Returns how many lists could be updated. */
    suspend fun refreshAll(prefs: Prefs): Int {
        val addresses = LinkedHashSet<String>()
        for (key in DefaultData.sourceKeys) {
            val url = prefs.getSource(key)
            if (url.isNotBlank()) addresses.add(url)
        }
        for (list in prefs.customLists()) {
            if (!list.value.startsWith("file:")) addresses.add(list.value)
        }
        var updated = 0
        for (address in addresses) {
            try {
                PlaylistRepository.load(address, force = true)
                updated++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // one broken link must not stop the others
            }
        }
        // radio directory and Quran reciters
        try {
            withContext(Dispatchers.IO) {
                RadioClient.countries(force = true)
                QuranClient.recitations(force = true)
            }
            updated++
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        lastUpdateMillis = System.currentTimeMillis()
        return updated
    }
}
