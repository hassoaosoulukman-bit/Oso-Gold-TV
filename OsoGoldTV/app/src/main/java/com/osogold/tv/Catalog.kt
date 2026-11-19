package com.osogold.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A top-level entry: built-in section, favourites or a subscription. */
data class SectionDef(val id: String, val title: String, val iconRes: Int, val badge: String)

/** A package inside a section (country, category, genre, M3U group ...). */
data class Pkg(val id: String, val title: String, val leading: String = "")

/**
 * One data model for everything that can be browsed:
 *
 *   section  ->  (optional) package  ->  channels
 *
 * Section ids:
 *   live | countries | movies | series | quran | radio | fav        built in
 *   x_live | x_vod | x_series                                      Xtream Codes subscription
 *   stalker                                                        Stalker Portal (MAC)
 *   m3u:<id>                                                       M3U / M3U8 list added by the user
 */
object Catalog {

    const val ALL = "__all__"

    private fun countries(): List<Country> = DefaultData.arabCountries + DefaultData.worldCountries

    fun defaults(): List<SectionDef> = listOf(
        SectionDef("live", "البث المباشر", R.drawable.ic_live, "📡"),
        SectionDef("countries", "الدول", R.drawable.ic_globe, "🌍"),
        SectionDef("movies", "الأفلام", R.drawable.ic_film, "🎬"),
        SectionDef("series", "المسلسلات", R.drawable.ic_tv, "📺"),
        SectionDef("quran", "القرآن الكريم", R.drawable.ic_quran, "📖"),
        SectionDef("radio", "الراديو", R.drawable.ic_radio, "📻"),
        SectionDef("fav", "المفضلة", R.drawable.ic_star, "★")
    )

    /** Subscriptions configured in Settings. */
    fun subscriptions(prefs: Prefs): List<SectionDef> {
        val out = ArrayList<SectionDef>()
        if (prefs.xtreamConfigured()) {
            val name = prefs.xtreamName.ifBlank { "Xtream" }
            out.add(SectionDef("x_live", "$name — بث مباشر", R.drawable.ic_server, "📡"))
            out.add(SectionDef("x_vod", "$name — أفلام", R.drawable.ic_film, "🎬"))
            out.add(SectionDef("x_series", "$name — مسلسلات", R.drawable.ic_tv, "📺"))
        }
        if (prefs.stalkerConfigured()) {
            out.add(SectionDef("stalker", prefs.stalkerName.ifBlank { "Stalker Portal" }, R.drawable.ic_mac, "🔌"))
        }
        for (list in prefs.customLists()) {
            out.add(SectionDef("m3u:${list.id}", list.name, R.drawable.ic_link, "📁"))
        }
        return out
    }

    /** Everything shown in the first bar of the dual screen. */
    fun sections(prefs: Prefs): List<SectionDef> = defaults() + subscriptions(prefs)

    // ------------------------------------------------------------------ packages

    /** Packages of a section, or null when the section has none (channels are shown directly). */
    suspend fun packages(prefs: Prefs, id: String, force: Boolean = false): List<Pkg>? = when {
        id == "countries" -> countries().map { Pkg(it.iso, it.nameAr, it.flag) }
        id == "radio" -> withContext(Dispatchers.IO) {
            try {
                RadioClient.countries(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // directory unreachable: the built-in / M3U stations are shown directly
            }
        }
        id == "quran" -> withContext(Dispatchers.IO) { QuranClient.packages(force) }
        id == "live" || id == "movies" || id == "series" -> groupsOf(sectionM3u(prefs, id, force))
        id.startsWith("m3u:") -> groupsOf(customM3u(prefs, id, force))
        id == "x_live" -> withContext(Dispatchers.IO) {
            listOf(Pkg(ALL, "الكل")) + XtreamClient.categories(prefs, "live")
        }
        id == "x_vod" -> withContext(Dispatchers.IO) { XtreamClient.categories(prefs, "vod") }
        id == "x_series" -> withContext(Dispatchers.IO) { XtreamClient.categories(prefs, "series") }
        id == "stalker" -> withContext(Dispatchers.IO) { StalkerClient.genres(prefs) }
        else -> null
    }

    private fun groupsOf(list: List<Channel>): List<Pkg>? {
        val groups = list.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (groups.size < 2) return null
        return listOf(Pkg(ALL, "الكل (${list.size})")) + groups.map { Pkg(it, it) }
    }

    // ------------------------------------------------------------------ channels

    suspend fun channels(
        prefs: Prefs,
        id: String,
        pkgId: String?,
        force: Boolean = false
    ): List<Channel> = when {
        id == "fav" -> prefs.favorites()
        id == "countries" -> {
            val country = countries().firstOrNull { it.iso == pkgId }
                ?: throw IOException("لم يتم اختيار دولة")
            PlaylistRepository.load(DefaultData.countryUrl(country), force)
        }
        id == "radio" -> {
            if (pkgId == null || pkgId == ALL) {
                sectionM3u(prefs, id, force)
            } else {
                withContext(Dispatchers.IO) { RadioClient.stations(pkgId, force) }
            }
        }
        id == "quran" -> {
            if (pkgId == null || pkgId == ALL) {
                sectionM3u(prefs, id, force)
            } else {
                withContext(Dispatchers.IO) { QuranClient.surahs(pkgId) }
            }
        }
        id == "live" || id == "movies" || id == "series" ->
            filterGroup(sectionM3u(prefs, id, force), pkgId)
        id.startsWith("m3u:") -> filterGroup(customM3u(prefs, id, force), pkgId)
        id == "x_live" -> withContext(Dispatchers.IO) { XtreamClient.streams(prefs, "live", pkgId ?: ALL) }
        id == "x_vod" -> withContext(Dispatchers.IO) { XtreamClient.streams(prefs, "vod", pkgId ?: ALL) }
        id == "x_series" -> withContext(Dispatchers.IO) { XtreamClient.streams(prefs, "series", pkgId ?: ALL) }
        id == "stalker" -> withContext(Dispatchers.IO) { StalkerClient.channels(prefs, pkgId) }
        else -> emptyList()
    }

    /** Episodes of a series (Xtream Codes). */
    suspend fun episodes(prefs: Prefs, seriesId: String): List<Channel> =
        withContext(Dispatchers.IO) { XtreamClient.episodes(prefs, seriesId) }

    /** Turns the stored address of a channel into a playable one (Stalker links are temporary). */
    suspend fun resolve(prefs: Prefs, channel: Channel): String =
        if (channel.url.startsWith("stalker:")) {
            withContext(Dispatchers.IO) { StalkerClient.resolve(prefs, channel.url.removePrefix("stalker:")) }
        } else {
            channel.url
        }

    // ------------------------------------------------------------------ helpers

    private fun filterGroup(list: List<Channel>, pkgId: String?): List<Channel> =
        if (pkgId == null || pkgId == ALL) list else list.filter { it.group.trim() == pkgId }

    /** Built-in section: downloaded playlist plus the built-in entries. */
    private suspend fun sectionM3u(prefs: Prefs, key: String, force: Boolean): List<Channel> {
        val built = DefaultData.builtIn(key)
        val url = prefs.getSource(key)
        val loaded = if (url.isBlank()) {
            emptyList()
        } else {
            try {
                PlaylistRepository.load(url, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (built.isEmpty()) throw e
                emptyList()
            }
        }
        return if (DefaultData.builtInFirst(key)) built + loaded else loaded + built
    }

    private suspend fun customM3u(prefs: Prefs, id: String, force: Boolean): List<Channel> {
        val entry = prefs.customLists().firstOrNull { "m3u:${it.id}" == id }
            ?: throw IOException("القائمة غير موجودة")
        return PlaylistRepository.load(entry.value, force)
    }
}
