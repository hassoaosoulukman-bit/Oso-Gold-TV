package com.osogold.tv

import java.util.Locale

/** Story, trailer and extra data of a movie or a series. */
data class MovieDetail(
    val title: String = "",
    val plot: String = "",
    /** YouTube id of the trailer, or "" when there is none. */
    val trailerId: String = "",
    val poster: String = "",
    val genre: String = "",
    val year: String = "",
    val rating: String = "",
    val episodes: List<Channel> = emptyList()
)

/** Extracts the 11 character YouTube id from an id or from any YouTube link. */
fun youtubeId(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    Regex("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{11})").find(text)?.let { return it.groupValues[1] }
    return if (Regex("^[A-Za-z0-9_-]{11}$").matches(text)) text else ""
}

/**
 * Sorts the categories of a movie / series source into the groups of the movies page:
 * by country or language (Arabic, foreign, Spanish, Turkish ...) and by type (action, comedy ...).
 * A category can belong to several groups. The decision is made from the category name.
 */
object GenreClassifier {

    class Group(val key: String, val title: String, val badge: String, val words: List<String>)

    /** Result: the group and the categories that belong to it. */
    class Result(val key: String, val title: String, val badge: String, val categories: List<Pkg>)

    const val KEY_ALL = "all"
    const val KEY_OTHER = "other"

    private val groups = listOf(
        Group(
            "arab", "عربي", "🌙",
            listOf(
                "arab", "arabic", "arabe", "عرب", "عربي", "عربية", "egypt", "egyptian", "مصر", "مصري",
                "khaleeji", "gulf", "خليج", "خليجي", "saudi", "سعود", "syria", "syrian", "سوري",
                "lebanon", "لبنان", "maghreb", "مغرب", "algeria", "جزائر", "tunisia", "تونس",
                "iraq", "عراق", "jordan", "أردن", "kuwait", "كويت", "ar", "egy"
            )
        ),
        Group(
            "foreign", "أجنبي", "🌎",
            listOf(
                "english", "foreign", "أجنب", "hollywood", "هوليوود", "american", "أمريك", "امريك",
                "usa", "us", "uk", "en", "french", "فرنس", "german", "ألمان", "الماني", "italian",
                "إيطال", "ايطال", "europe", "أورب", "اورب", "russian", "روس", "international", "عالمي"
            )
        ),
        Group(
            "spanish", "إسباني", "🌹",
            listOf(
                "spanish", "español", "espanol", "latino", "latin", "اسباني", "إسباني", "اسبان",
                "إسبان", "spain", "es", "esp", "mexican", "مكسيك"
            )
        ),
        Group(
            "turkish", "تركي", "🌙",
            listOf("turk", "turkish", "turkce", "تركي", "تركية", "تركيا", "tr", "istanbul")
        ),
        Group(
            "hindi", "هندي", "🎭",
            listOf("hindi", "indian", "india", "هند", "هندي", "هندية", "بوليوود", "bollywood", "tamil", "telugu")
        ),
        Group(
            "korean", "كوري", "🌸",
            listOf("korea", "korean", "كور", "كوري", "كورية", "kdrama", "kr")
        ),
        Group(
            "anime", "أنمي وكرتون", "🧸",
            listOf(
                "anime", "أنمي", "انمي", "cartoon", "كرتون", "kids", "أطفال", "اطفال",
                "animation", "ياباني", "japan", "japanese"
            )
        ),
        Group(
            "action", "أكشن", "💥",
            listOf(
                "action", "أكشن", "اكشن", "حركة", "adventure", "مغامر", "war", "حرب", "crime", "جريمة"
            )
        ),
        Group(
            "comedy", "كوميدي", "😄",
            listOf("comedy", "كوميدي", "كوميديا", "ضحك", "funny")
        ),
        Group(
            "drama", "دراما", "🎭",
            listOf("drama", "دراما", "family", "عائل", "social", "اجتماعي")
        ),
        Group(
            "horror", "رعب وإثارة", "👻",
            listOf("horror", "رعب", "thriller", "إثارة", "اثارة", "suspense", "تشويق", "mystery", "غموض")
        ),
        Group(
            "romance", "رومانسي", "❤",
            listOf("romance", "romantic", "رومانس", "رومانسي", "love", "حب")
        ),
        Group(
            "doc", "وثائقي", "🎥",
            listOf("documentary", "doc", "وثائقي", "وثائق", "history", "تاريخ")
        ),
        Group(
            "scifi", "خيال علمي", "🚀",
            listOf("scifi", "sci", "fantasy", "خيال", "فانتازيا")
        )
    )

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    private fun matches(name: String, group: Group): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val tokens = lower.split(splitter).filter { it.isNotEmpty() }.toSet()
        return group.words.any { word ->
            if (word.length <= 3) word in tokens else lower.contains(word)
        }
    }

    /** "All" first, then every group that has at least one category, then the rest. */
    fun classify(categories: List<Pkg>): List<Result> {
        val real = categories.filter { it.id != Catalog.ALL }
        val out = ArrayList<Result>()
        out.add(Result(KEY_ALL, "الكل", "✨", real))
        val used = HashSet<String>()
        for (group in groups) {
            val list = real.filter { matches(it.title, group) }
            if (list.isNotEmpty()) {
                out.add(Result(group.key, group.title, group.badge, list))
                list.forEach { used.add(it.id) }
            }
        }
        val others = real.filter { it.id !in used }
        if (others.isNotEmpty() && others.size != real.size) {
            out.add(Result(KEY_OTHER, "أخرى", "•", others))
        }
        return out
    }
}
