package com.osogold.tv

/**
 * Command understanding of the AI assistant (typed or spoken).
 *
 * It is a rule based language understanding engine (it runs on the phone, with no server and no
 * account). Every word is normalised first ([ArabicText.normalize]) and many dialect forms of
 * the same intent are known: Gulf, Egyptian, Levantine, Iraqi, Maghrebi, Sudanese, Yemeni ...
 * ("شغّل", "دير", "حط", "ابغى", "بدي", "عايز", "اشتي", "بغيت" ...).
 */
object Assistant {

    const val CMD_PLAY = "play"
    const val CMD_SURAH = "surah"
    const val CMD_SECTION = "section"
    const val CMD_RECORD_START = "record_start"
    const val CMD_RECORD_STOP = "record_stop"
    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_MUTE = "mute"
    const val CMD_UNMUTE = "unmute"
    const val CMD_NEXT = "next"
    const val CMD_PREVIOUS = "previous"
    const val CMD_FULLSCREEN = "fullscreen"
    const val CMD_DUAL = "dual"
    const val CMD_REFRESH = "refresh"
    const val CMD_ENGINE = "engine"
    const val CMD_OPEN_LIST = "open_list"      // from the "My lists" page
    const val CMD_REPAIR = "repair"          // handled by the assistant screen itself
    const val CMD_RECORDINGS = "recordings"  // handled by the assistant screen itself
    const val CMD_NONE = "none"

    data class Command(val name: String, val argument: String = "", val description: String = "")

    private val playVerbs = setOf(
        "شغل", "شغلي", "شغلو", "شغلها", "شغله", "دير", "ديري", "حط", "حطلي", "حطلنا", "حطها", "افتح", "افتحلي",
        "افتحها", "روح", "وريني", "ورجيني", "ورني", "عرض", "اعرض", "اعرضلي", "بدي", "ابغي", "ابغاه", "ابي", "ابا",
        "اريد", "عايز", "عاوز", "عايزه", "عاوزه", "نفسي", "خلي", "خليني", "طلع", "طلعلي", "جيب", "جبلي", "سمعني",
        "اسمع", "بغيت", "اشتي", "اشتيه", "شوف", "شاهد", "تفرج", "اظهر", "ركب", "فرجني", "دزلي", "هات", "هاتلي"
    )

    private val fillers = setOf(
        "قناه", "قنوات", "محطه", "اذاعه", "لي", "لنا", "من", "فضلك", "لو", "سمحت", "بليز", "يا", "اخي", "حبيبي",
        "استاذ", "الان", "هلا", "دلوقتي", "الحين", "هسا", "هسه", "توا", "شوي", "على", "علي", "ل", "ب", "في", "فى",
        "بث", "مباشر", "ممكن", "تقدر", "لوسمحت", "يعني", "هذي", "هذه", "هاي", "ده", "دي", "ديك", "تلك", "ال", "الله",
        "يخليك", "يحفظك", "وياك", "معاك", "لي", "الي", "اللي"
    )

    private val sectionWords = mapOf(
        "راديو" to "radio", "الراديو" to "radio", "اذاعه" to "radio", "الاذاعه" to "radio", "اذاعات" to "radio",
        "الاذاعات" to "radio", "محطات" to "radio", "المحطات" to "radio",
        "قران" to "quran", "القران" to "quran", "قرآن" to "quran",
        "افلام" to "movies", "الافلام" to "movies", "فيلم" to "movies", "سينما" to "movies",
        "مسلسلات" to "series", "المسلسلات" to "series", "مسلسل" to "series",
        "قنوات" to "live", "القنوات" to "live", "مباشر" to "live",
        "اعدادات" to "settings", "الاعدادات" to "settings", "ضبط" to "settings",
        "دول" to "countries", "الدول" to "countries", "بلدان" to "countries",
        "اشتراكات" to "subs", "الاشتراكات" to "subs", "اشتراك" to "subs"
    )

    fun parse(raw: String): Command {
        val text = ArabicText.normalize(raw)
        if (text.isEmpty()) return Command(CMD_NONE)
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val has = { word: String -> if (word.contains(' ')) text.contains(word) else tokens.contains(word) }
        val any = { words: List<String> -> words.any { has(it) } }

        // ---- recording
        val mentionsRecord = any(listOf("تسجيل", "سجل", "سجلي", "سجلها", "التسجيل", "صور البث"))
        val stopWord = any(listOf("وقف", "اوقف", "طفي", "اطفي", "خلص", "انهي", "ايقاف", "كفايه", "بس", "قف", "سكر", "اقفل"))
        if (any(listOf("تسجيلات", "التسجيلات", "المسجل", "المسجله"))) {
            return Command(CMD_RECORDINGS, description = "فتح قسم التسجيلات")
        }
        if (mentionsRecord) {
            return if (stopWord) Command(CMD_RECORD_STOP, description = "إيقاف التسجيل")
            else Command(CMD_RECORD_START, description = "بدء التسجيل")
        }

        // ---- clean / repair / update
        if (any(listOf("نظف", "نضف", "نظفلي", "نضفلي", "صلح", "صلحلي", "اصلح", "تصليح", "صيانه", "كلين", "امسح الكاش", "ريست"))) {
            return Command(CMD_REPAIR, description = "التصليح التلقائي وتنظيف التطبيق")
        }
        if (any(listOf("حدث", "حدثلي", "تحديث", "جدد", "تجديد", "حدّث", "حدثها"))) {
            return Command(CMD_REFRESH, description = "تحديث جميع الأقسام")
        }

        // ---- sound
        if (any(listOf("الغي الكتم", "فك الكتم", "رجع الصوت", "ارجع الصوت", "شغل الصوت", "افتح الصوت", "فتح الصوت"))) {
            return Command(CMD_UNMUTE, description = "إعادة الصوت")
        }
        if (any(listOf("كتم", "اكتم", "اكتمه", "اسكت", "صامت", "بدون صوت", "بلا صوت", "وطي الصوت كله"))) {
            return Command(CMD_MUTE, description = "كتم الصوت")
        }

        // ---- Quran: surah
        if (has("سوره") || has("سورة") || has("surah")) {
            val after = text.substringAfter("سوره", text).trim()
            val markers = listOf("بصوت", "بقراءه", "للشيخ", "لقاري", "القاري", "قراءه", "الشيخ", "صوت", "عن طريق")
            var surahPart = after
            var reciter = ""
            for (marker in markers) {
                val i = after.indexOf(marker)
                if (i >= 0) {
                    surahPart = after.substring(0, i).trim()
                    reciter = after.substring(i + marker.length).trim()
                    break
                }
            }
            val number = findSurah(surahPart) ?: findSurah(after)
            if (number != null) {
                return Command(
                    CMD_SURAH, "$number|$reciter",
                    "تشغيل سورة ${Surahs.name(number)}" + if (reciter.isNotEmpty()) " بصوت $reciter" else ""
                )
            }
        }

        // ---- player
        if (any(listOf("ملء الشاشه", "ملا الشاشه", "كامل الشاشه", "تكبير", "شاشه كامله", "فل سكرين"))) {
            return Command(CMD_FULLSCREEN, description = "ملء الشاشة")
        }
        if (any(listOf("شاشتين", "شاشه مزدوجه", "شاشه صغيره", "الشاشه الصغيره", "بيكتشر"))) {
            return Command(CMD_DUAL, description = "الشاشة المزدوجة")
        }
        if (any(listOf("بدل المشغل", "غير المشغل", "تبديل المشغل", "مشغل vlc", "فلك"))) {
            return Command(CMD_ENGINE, description = "تبديل المشغل")
        }
        if (any(listOf("التالي", "الي بعده", "اللي بعده", "القناه الجايه", "القناه التاليه", "بعدها", "نكست", "غيرها", "غير القناه", "بدلها"))) {
            return Command(CMD_NEXT, description = "التالي")
        }
        if (any(listOf("السابق", "الي قبله", "اللي قبله", "القناه السابقه", "ارجع للي قبل", "الي قبل"))) {
            return Command(CMD_PREVIOUS, description = "السابق")
        }
        if (stopWord && tokens.size <= 3 || any(listOf("بوز", "ايقاف مؤقت", "وقف مؤقت", "استنى", "ثواني"))) {
            return Command(CMD_PAUSE, description = "إيقاف مؤقت")
        }
        if (any(listOf("كمل", "كملي", "كمّل", "واصل", "استمر", "استئناف", "تابع", "رجع التشغيل"))) {
            return Command(CMD_RESUME, description = "متابعة التشغيل")
        }

        // ---- play something / open a section
        val hasPlayVerb = tokens.any { it in playVerbs }
        val rest = tokens.filter { it !in playVerbs && it !in fillers }

        if (rest.size == 1 && sectionWords.containsKey(rest[0])) {
            val id = sectionWords.getValue(rest[0])
            return Command(CMD_SECTION, id, "فتح القسم")
        }
        if (rest.isEmpty()) {
            return if (hasPlayVerb) Command(CMD_RESUME, description = "تشغيل") else Command(CMD_NONE)
        }
        val query = rest.joinToString(" ")
        return Command(CMD_PLAY, query, "تشغيل «$query»")
    }

    /** Surah number from the words after the word "surah" (names of 1..3 words or a number). */
    private fun findSurah(text: String): Int? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (n in minOf(3, tokens.size) downTo 1) {
            val candidate = tokens.take(n).joinToString(" ")
            Surahs.find(candidate)?.let { return it }
        }
        return null
    }

    val examples = listOf(
        "شغّل قناة الجزيرة",
        "ابغى أسمع إذاعة القرآن الكريم",
        "افتح سورة الكهف بصوت العفاسي",
        "ورجيني قناة mbc",
        "بدي سورة يس",
        "سجّل / وقّف التسجيل",
        "كتم الصوت / رجّع الصوت",
        "التالي / السابق",
        "نظّف التطبيق (تصليح تلقائي)",
        "افتح الراديو / الأفلام / المسلسلات"
    )
}
