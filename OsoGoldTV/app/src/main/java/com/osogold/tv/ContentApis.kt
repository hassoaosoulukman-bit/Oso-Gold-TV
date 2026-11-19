package com.osogold.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Tiny disk cache for the text answers of the radio / Quran web services. */
object ApiCache {

    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L

    private fun file(key: String): File {
        val dir = File(NetConfig.app.filesDir, "api_cache")
        dir.mkdirs()
        return File(dir, Integer.toHexString(key.hashCode()) + ".json")
    }

    /** Downloads [address] (or uses the disk copy when it is fresh and [force] is false). */
    fun get(address: String, force: Boolean = false): String {
        val f = file(address)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
        if (!force && fresh) return f.readText(Charsets.UTF_8)
        return try {
            val text = NetConfig.getText(address)
            f.writeText(text, Charsets.UTF_8)
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (f.exists()) f.readText(Charsets.UTF_8) else throw e
        }
    }

    fun clear() {
        File(NetConfig.app.filesDir, "api_cache").listFiles()?.forEach { it.delete() }
    }
}

/**
 * Radio stations of the whole world from the free community directory radio-browser.info
 * (grouped by country, then by station name).
 */
object RadioClient {

    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info"
    )

    private fun fetch(path: String, force: Boolean): String {
        var last: Exception? = null
        for (server in servers) {
            try {
                return ApiCache.get(server + path, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("تعذر الوصول إلى دليل الراديو")
    }

    /** Countries that have stations: the Arab countries first, then the world (by name). */
    fun countries(force: Boolean = false): List<Pkg> {
        val arr = JSONArray(fetch("/json/countrycodes?hidebroken=true", force))
        val counts = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("name").uppercase(Locale.ROOT)
            if (code.length == 2) counts[code] = o.optInt("stationcount", 0)
        }
        val arab = DefaultData.arabCountries.map { it.iso }.toSet()
        fun pkg(code: String): Pkg {
            val country = Country(code, code in arab)
            return Pkg(code, "${country.nameAr} (${counts[code]})", country.flag)
        }
        val arabList = DefaultData.arabCountries.map { it.iso }.filter { (counts[it] ?: 0) > 0 }.map { pkg(it) }
        val world = counts.keys.filter { it !in arab && (counts[it] ?: 0) > 0 }
            .map { Country(it, false) }
            .sortedBy { it.nameAr }
            .map { pkg(it.iso) }
        return arabList + world
    }

    /** Stations of one country, sorted by name. */
    fun stations(code: String, force: Boolean = false): List<Channel> {
        val path = "/json/stations/bycountrycodeexact/" + URLEncoder.encode(code, "UTF-8") +
            "?hidebroken=true&order=name&limit=1500"
        val arr = JSONArray(fetch(path, force))
        val out = ArrayList<Channel>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (url.isEmpty() || name.isEmpty() || !seen.add(url)) continue
            out.add(
                Channel(
                    name = name,
                    url = url,
                    logo = o.optString("favicon"),
                    group = o.optString("tags").split(',').firstOrNull()?.trim().orEmpty()
                )
            )
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}

/** One Quran recitation: reciter + a "riwaya" with its audio server and available surahs. */
data class Recitation(
    val id: String,
    val reciter: String,
    val riwaya: String,
    val server: String,
    val surahs: Set<Int>
)

/**
 * All reciters and the 114 surahs, from the mp3quran.net web service. The list is cached on the
 * phone; a short built-in list is used when the service cannot be reached the first time.
 */
object QuranClient {

    private const val API = "https://mp3quran.net/api/v3/reciters?language=ar"

    private val fallback = listOf(
        Recitation("fb_afs", "مشاري راشد العفاسي", "حفص عن عاصم", "https://server8.mp3quran.net/afs/", (1..114).toSet()),
        Recitation("fb_basit", "عبد الباسط عبد الصمد", "مرتل", "https://server7.mp3quran.net/basit/", (1..114).toSet()),
        Recitation("fb_sds", "عبد الرحمن السديس", "حفص عن عاصم", "https://server11.mp3quran.net/sds/", (1..114).toSet()),
        Recitation("fb_maher", "ماهر المعيقلي", "حفص عن عاصم", "https://server12.mp3quran.net/maher/", (1..114).toSet()),
        Recitation("fb_husr", "محمود خليل الحصري", "مرتل", "https://server13.mp3quran.net/husr/", (1..114).toSet())
    )

    fun recitations(force: Boolean = false): List<Recitation> {
        val out = ArrayList<Recitation>()
        try {
            val root = JSONObject(ApiCache.get(API, force))
            val reciters = root.optJSONArray("reciters") ?: JSONArray()
            for (i in 0 until reciters.length()) {
                val r = reciters.optJSONObject(i) ?: continue
                val moshafs = r.optJSONArray("moshaf") ?: continue
                for (j in 0 until moshafs.length()) {
                    val m = moshafs.optJSONObject(j) ?: continue
                    val server = m.optString("server")
                    if (server.isBlank()) continue
                    val list = m.optString("surah_list").split(',')
                        .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..114 }.toSet()
                    if (list.isEmpty()) continue
                    out.add(
                        Recitation(
                            id = "${r.optInt("id")}_${m.optInt("id")}",
                            reciter = r.optString("name").ifBlank { "—" },
                            riwaya = m.optString("name"),
                            server = if (server.endsWith("/")) server else "$server/",
                            surahs = list
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return if (out.isEmpty()) fallback else out.sortedBy { it.reciter }
    }

    /** Reciters as packages. A reciter with several riwayat appears once per riwaya. */
    fun packages(force: Boolean = false): List<Pkg> {
        val all = recitations(force)
        val perReciter = all.groupingBy { it.reciter }.eachCount()
        return all.map {
            val title = if ((perReciter[it.reciter] ?: 1) > 1 && it.riwaya.isNotBlank())
                "${it.reciter} — ${it.riwaya}" else it.reciter
            Pkg(it.id, title, "🎙")
        }
    }

    /** The surahs (as playable items) of one recitation. */
    fun surahs(recitationId: String): List<Channel> {
        val rec = recitations().firstOrNull { it.id == recitationId }
            ?: throw IOException("القارئ غير موجود")
        return rec.surahs.sorted().map { n ->
            Channel(
                name = "%03d  سورة %s".format(n, Surahs.name(n)),
                url = rec.server + "%03d.mp3".format(n),
                group = rec.reciter
            )
        }
    }

    suspend fun recitationsAsync(force: Boolean = false): List<Recitation> =
        withContext(Dispatchers.IO) { recitations(force) }
}

/** The 114 surahs of the Holy Quran. */
object Surahs {

    val names = listOf(
        "الفاتحة", "البقرة", "آل عمران", "النساء", "المائدة", "الأنعام", "الأعراف", "الأنفال",
        "التوبة", "يونس", "هود", "يوسف", "الرعد", "إبراهيم", "الحجر", "النحل", "الإسراء",
        "الكهف", "مريم", "طه", "الأنبياء", "الحج", "المؤمنون", "النور", "الفرقان", "الشعراء",
        "النمل", "القصص", "العنكبوت", "الروم", "لقمان", "السجدة", "الأحزاب", "سبأ", "فاطر",
        "يس", "الصافات", "ص", "الزمر", "غافر", "فصلت", "الشورى", "الزخرف", "الدخان", "الجاثية",
        "الأحقاف", "محمد", "الفتح", "الحجرات", "ق", "الذاريات", "الطور", "النجم", "القمر",
        "الرحمن", "الواقعة", "الحديد", "المجادلة", "الحشر", "الممتحنة", "الصف", "الجمعة",
        "المنافقون", "التغابن", "الطلاق", "التحريم", "الملك", "القلم", "الحاقة", "المعارج",
        "نوح", "الجن", "المزمل", "المدثر", "القيامة", "الإنسان", "المرسلات", "النبأ",
        "النازعات", "عبس", "التكوير", "الانفطار", "المطففين", "الانشقاق", "البروج", "الطارق",
        "الأعلى", "الغاشية", "الفجر", "البلد", "الشمس", "الليل", "الضحى", "الشرح", "التين",
        "العلق", "القدر", "البينة", "الزلزلة", "العاديات", "القارعة", "التكاثر", "العصر",
        "الهمزة", "الفيل", "قريش", "الماعون", "الكوثر", "الكافرون", "النصر", "المسد",
        "الإخلاص", "الفلق", "الناس"
    )

    fun name(number: Int): String = names.getOrElse(number - 1) { "" }

    /** Surah number (1..114) found in a free text (name or number), or null. */
    fun find(text: String): Int? {
        val query = ArabicText.normalize(text)
        if (query.isEmpty()) return null
        query.toIntOrNull()?.let { if (it in 1..114) return it }
        // exact first, then "contains"
        names.forEachIndexed { i, n -> if (ArabicText.normalize(n) == query) return i + 1 }
        var best: Int? = null
        var bestLen = 0
        names.forEachIndexed { i, n ->
            val norm = ArabicText.normalize(n).removePrefix("ال")
            if (norm.length >= 2 && (query.contains(norm) || norm.contains(query)) && norm.length > bestLen) {
                best = i + 1
                bestLen = norm.length
            }
        }
        return best
    }
}

/** Arabic text normalisation shared by the search and the voice assistant (all dialects). */
object ArabicText {

    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun normalize(text: String): String {
        var s = text.lowercase(Locale.ROOT)
        s = diacritics.replace(s, "")
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        s = s.replace('ى', 'ي').replace('ئ', 'ي').replace('ؤ', 'و').replace('ة', 'ه')
        s = s.replace('گ', 'ك').replace('ک', 'ك').replace('چ', 'ج').replace('پ', 'ب').replace('ڤ', 'ف')
        return s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
