package com.osogold.tv

import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * Full control of the subtitles for movies and series (works with both engines):
 *  - choose a subtitle track of ANY language contained in the stream, or a preferred language
 *  - load an external subtitle file (SRT / VTT / ASS) from the phone
 *  - bigger / smaller text, text colour, top or bottom of the screen
 */
class SubtitleController(
    private val activity: BaseActivity,
    private val prefs: Prefs,
    private val host: Host
) {

    interface Host {
        fun exoPlayer(): Player?
        fun vlcEngine(): VlcEngine
        fun isVlc(): Boolean
        fun subtitleViews(): List<Pair<PlayerView, Float>>   // view + size factor
        fun pickSubtitleFile()
    }

    private val languages = linkedMapOf(
        "ar" to "العربية", "en" to "English", "fr" to "Français", "es" to "Español", "tr" to "Türkçe",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "ru" to "Русский", "fa" to "فارسی",
        "ur" to "اردو", "hi" to "हिन्दी", "ko" to "한국어", "ja" to "日本語", "zh" to "中文",
        "id" to "Indonesia", "nl" to "Nederlands", "sv" to "Svenska", "pl" to "Polski", "el" to "Ελληνικά",
        "he" to "עברית", "ku" to "Kurdî", "bn" to "বাংলা", "th" to "ไทย", "vi" to "Tiếng Việt"
    )

    private var dialog: AlertDialog? = null
    private var sizeLabel: TextView? = null

    // ------------------------------------------------------------------ look

    /** Applies size / colour / position to every video view. */
    fun applyStyle() {
        val color = SubtitleStyle.COLORS[prefs.subColor]
        for ((view, factor) in host.subtitleViews()) {
            val sv = view.subtitleView ?: continue
            sv.setApplyEmbeddedStyles(false)
            sv.setApplyEmbeddedFontSizes(false)
            sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.subSize * factor)
            sv.setStyle(
                CaptionStyleCompat(
                    color, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
                )
            )
            sv.setBottomPaddingFraction(if (prefs.subTop) 0.80f else 0.08f)
        }
        if (host.isVlc()) {
            val rel = (400 / prefs.subSize).coerceIn(8, 40)
            val rgb = SubtitleStyle.COLORS[prefs.subColor] and 0xFFFFFF
            val margin = if (prefs.subTop) 600 else 0
            host.vlcEngine().applySubtitleOptions(
                listOf("--freetype-rel-fontsize=$rel", "--freetype-color=$rgb", "--sub-margin=$margin")
            )
        }
    }

    /** Options for a new LibVLC player (called before the first start). */
    fun vlcOptions(): List<String> {
        val rel = (400 / prefs.subSize).coerceIn(8, 40)
        val rgb = SubtitleStyle.COLORS[prefs.subColor] and 0xFFFFFF
        val margin = if (prefs.subTop) 600 else 0
        return listOf("--freetype-rel-fontsize=$rel", "--freetype-color=$rgb", "--sub-margin=$margin")
    }

    // ------------------------------------------------------------------ dialog

    fun show() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        box.addView(button("📃  اختيار ترجمة (كل اللغات المتوفرة في البث)") { showTracks() })
        box.addView(button("🌐  لغة الترجمة المفضلة") { showLanguages() })
        box.addView(button("📂  تحميل ملف ترجمة من الهاتف (SRT / VTT / ASS)") {
            dialog?.dismiss()
            host.pickSubtitleFile()
        })
        box.addView(button("🚫  إيقاف الترجمة") { setOff() })

        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sizeRow.addView(smallButton("A−") { changeSize(-2) })
        sizeLabel = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.gold_light))
            gravity = Gravity.CENTER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sizeRow.addView(sizeLabel)
        sizeRow.addView(smallButton("A+") { changeSize(+2) })
        box.addView(sizeRow)
        updateSizeLabel()

        val colorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        SubtitleStyle.COLORS.forEachIndexed { i, c ->
            colorRow.addView(TextView(activity).apply {
                text = if (i == prefs.subColor) "●" else "○"
                setTextColor(c)
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    prefs.subColor = i
                    applyStyle()
                    show()
                }
            })
        }
        box.addView(colorRow)
        box.addView(button(if (prefs.subTop) "⬆  مكان الترجمة: أعلى الشاشة (اضغط للنقل للأسفل)" else "⬇  مكان الترجمة: أسفل الشاشة (اضغط للنقل للأعلى)") {
            prefs.subTop = !prefs.subTop
            applyStyle()
            show()
        })

        dialog?.dismiss()
        val d = AlertDialog.Builder(activity, R.style.GoldDialog)
            .setTitle("الترجمة")
            .setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton("إغلاق", null)
            .create()
        dialog = d
        activity.showImmersive(d)
    }

    private fun changeSize(delta: Int) {
        prefs.subSize = prefs.subSize + delta
        applyStyle()
        updateSizeLabel()
    }

    private fun updateSizeLabel() {
        sizeLabel?.text = "حجم الخط: ${prefs.subSize}"
    }

    // ------------------------------------------------------------------ tracks

    private fun showTracks() {
        if (host.isVlc()) {
            val tracks = host.vlcEngine().subtitleTracks()
            if (tracks.isEmpty()) {
                activity.toast("لا توجد ترجمة داخل هذا البث — يمكنك تحميل ملف ترجمة")
                return
            }
            choose("الترجمات المتوفرة", tracks.map { it.second }) { host.vlcEngine().selectSubtitleTrack(tracks[it].first) }
            return
        }
        val player = host.exoPlayer()
        if (player == null) {
            activity.toast("شغّل فيلماً أو مسلسلاً أولاً")
            return
        }
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val entries = ArrayList<Pair<String, () -> Unit>>()
        for (group in groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
                    ?.let { Locale(it).getDisplayLanguage(Locale("ar")).ifBlank { it } }
                val label = format.label ?: lang ?: "ترجمة ${entries.size + 1}"
                val g = group
                val index = i
                entries.add(label to {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, index))
                        .build()
                    activity.toast("الترجمة: $label")
                })
            }
        }
        if (entries.isEmpty()) {
            activity.toast("لا توجد ترجمة داخل هذا البث — يمكنك تحميل ملف ترجمة أو اختيار لغة مفضلة")
            return
        }
        choose("الترجمات المتوفرة", entries.map { it.first }) { entries[it].second.invoke() }
    }

    private fun showLanguages() {
        val codes = languages.keys.toList()
        choose("لغة الترجمة المفضلة", languages.values.toList()) { which ->
            val code = codes[which]
            if (host.isVlc()) {
                val tracks = host.vlcEngine().subtitleTracks()
                val name = languages[code] ?: code
                val match = tracks.firstOrNull { it.second.contains(name, true) || it.second.contains(code, true) }
                if (match != null) host.vlcEngine().selectSubtitleTrack(match.first)
                else activity.toast("لا توجد ترجمة بهذه اللغة في البث")
            } else {
                val player = host.exoPlayer() ?: return@choose
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setPreferredTextLanguage(code)
                    .build()
                activity.toast("لغة الترجمة المفضلة: ${languages[code]}")
            }
        }
    }

    private fun setOff() {
        if (host.isVlc()) {
            host.vlcEngine().selectSubtitleTrack(-1)
        } else {
            val player = host.exoPlayer() ?: return
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        activity.toast("تم إيقاف الترجمة")
    }

    /** Adds a subtitle file (already copied into the app) to the running video. */
    fun loadFile(uri: Uri, fileName: String) {
        if (host.isVlc()) {
            host.vlcEngine().addSubtitle(uri)
            activity.toast("تم تحميل الترجمة")
            return
        }
        val player = host.exoPlayer()
        val item = player?.currentMediaItem
        if (player == null || item == null) {
            activity.toast("شغّل فيلماً أو مسلسلاً أولاً")
            return
        }
        val mime = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "xml", "dfxp" -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val position = player.currentPosition
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        player.setMediaItem(item.buildUpon().setSubtitleConfigurations(listOf(config)).build(), position)
        player.prepare()
        player.play()
        activity.toast("تم تحميل ملف الترجمة")
    }

    // ------------------------------------------------------------------ helpers

    private fun choose(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val d = AlertDialog.Builder(activity, R.style.GoldDialog)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("إغلاق", null)
            .create()
        activity.showImmersive(d)
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.gold_light))
        textSize = 13f
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_chip)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.gold_light))
        textSize = 16f
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_chip_active)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
