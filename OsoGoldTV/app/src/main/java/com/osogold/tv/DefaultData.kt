package com.osogold.tv

/**
 * Default playlists and built-in entries.
 *
 * Every playlist URL can be changed from  Settings -> "Playlist sources" (M3U links),
 * so none of the addresses below are hard-wired.
 */
object DefaultData {

    const val SRC_LIVE = "live"
    const val SRC_MOVIES = "movies"
    const val SRC_SERIES = "series"
    const val SRC_RADIO = "radio"
    const val SRC_QURAN = "quran"

    val sourceKeys = listOf(SRC_LIVE, SRC_MOVIES, SRC_SERIES, SRC_RADIO, SRC_QURAN)

    /**
     * Pinned default Xtream Codes server.
     * Put your server address here (for example "http://my-server.com:8080") and the app will
     * ask only for user name and password. Leave it empty to let the user type the server
     * (the settings screen can also pin the typed server at run time).
     */
    const val XTREAM_DEFAULT_SERVER = ""

    fun sourceTitle(key: String): String = when (key) {
        SRC_LIVE -> "البث المباشر"
        SRC_MOVIES -> "الأفلام"
        SRC_SERIES -> "المسلسلات"
        SRC_RADIO -> "الراديو"
        SRC_QURAN -> "القرآن الكريم"
        else -> key
    }

    /** Default M3U address of every section (public iptv-org playlists). */
    fun defaultSource(key: String): String = when (key) {
        SRC_LIVE -> "https://iptv-org.github.io/iptv/languages/ara.m3u"
        SRC_MOVIES -> "https://iptv-org.github.io/iptv/categories/movies.m3u"
        SRC_SERIES -> "https://iptv-org.github.io/iptv/categories/series.m3u"
        else -> ""
    }

    /** Playlist of one country from iptv-org. */
    fun countryUrl(country: Country): String =
        "https://iptv-org.github.io/iptv/countries/${country.fileCode}.m3u"

    /** Test stream that is used when nothing else could be loaded. */
    const val FALLBACK_STREAM = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    /** Built-in entries of a section. Shown in addition to the downloaded playlist. */
    fun builtIn(key: String): List<Channel> = when (key) {
        SRC_LIVE -> listOf(
            Channel("Oso Gold - بث تجريبي", FALLBACK_STREAM, group = "Demo")
        )
        SRC_QURAN -> listOf(
            Channel("إذاعة القرآن الكريم - القاهرة", "https://stream.radiojar.com/8s5u5tpdtwzuv", group = "إذاعة"),
            Channel("إذاعة التراويح والتلاوات", "https://qurango.net/radio/tarateel", group = "إذاعة"),
            Channel("إذاعة القرآن الكريم - منوعات", "https://qurango.net/radio/mix", group = "إذاعة")
        )
        SRC_RADIO -> listOf(
            Channel("BBC Arabic Radio", "https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio", group = "أخبار"),
            Channel("مونت كارلو الدولية", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya-128.mp3", group = "أخبار")
        )
        else -> emptyList()
    }

    /** True when the built-in entries should be placed BEFORE the downloaded ones. */
    fun builtInFirst(key: String): Boolean = key == SRC_QURAN || key == SRC_RADIO

    /** Arab countries (ISO 3166-1 alpha-2). */
    val arabCountries: List<Country> = listOf(
        "SA", "AE", "EG", "IQ", "SY", "LB", "JO", "PS", "KW", "QA", "BH",
        "OM", "YE", "SD", "LY", "TN", "DZ", "MA", "MR", "SO", "DJ", "KM"
    ).map { Country(it, true) }

    /** Other countries of the world. */
    val worldCountries: List<Country> = listOf(
        "US", "GB", "FR", "DE", "TR", "ES", "IT", "PT", "NL", "BE", "CH", "AT",
        "SE", "NO", "GR", "PL", "UA", "RU", "IR", "AF", "PK", "IN", "BD", "ID",
        "MY", "CN", "JP", "KR", "CA", "MX", "BR", "AR", "AU", "ZA", "NG", "KE", "ET"
    ).map { Country(it, false) }
}
