package com.osogold.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One full screen page for a movie or a series: the trailer in full screen (Arabic subtitles
 * requested), the name, the story, "Play" and "Add to favourites". The story and trailer come
 * from the provider (Xtream); when the provider has none, the poster and a trailer search are used.
 */
class MovieDetailActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var poster: ImageView
    private lateinit var scrim: View
    private lateinit var panel: View
    private lateinit var btnFav: TextView
    private lateinit var btnEpisodes: TextView

    private lateinit var base: Channel
    private var detail = MovieDetail()
    private var infoShown = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_detail)
        prefs = Prefs(this)

        base = Channel(
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            logo = intent.getStringExtra(EXTRA_LOGO).orEmpty(),
            seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty(),
            infoId = intent.getStringExtra(EXTRA_INFO_ID).orEmpty()
        )

        web = findViewById(R.id.trailerWeb)
        poster = findViewById(R.id.detailPoster)
        scrim = findViewById(R.id.detailScrim)
        panel = findViewById(R.id.infoPanel)
        btnFav = findViewById(R.id.btnDetailFav)
        btnEpisodes = findViewById(R.id.btnDetailEpisodes)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()

        findViewById<View>(R.id.btnDetailBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnToggleInfo).setOnClickListener {
            infoShown = !infoShown
            panel.visibility = if (infoShown) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.btnDetailPlay).setOnClickListener { play() }
        btnFav.setOnClickListener { toggleFavorite() }
        btnEpisodes.setOnClickListener { showEpisodes() }

        findViewById<TextView>(R.id.detailTitle).text = base.name
        if (base.logo.isNotBlank()) poster.load(base.logo)
        updateFavoriteButton()
        loadDetail()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }

    private fun loadDetail() {
        lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    when {
                        base.seriesId.isNotEmpty() && prefs.xtreamConfigured() ->
                            XtreamClient.seriesDetail(prefs, base.seriesId)
                        base.infoId.isNotEmpty() && prefs.xtreamConfigured() ->
                            XtreamClient.vodDetail(prefs, base.infoId)
                        else -> MovieDetail()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MovieDetail()
            }
            detail = loaded
            show()
        }
    }

    private fun show() {
        findViewById<TextView>(R.id.detailTitle).text = detail.title.ifBlank { base.name }
        val meta = listOf(detail.year, detail.genre, if (detail.rating.isNotBlank()) "★ ${detail.rating}" else "")
            .filter { it.isNotBlank() }.joinToString("   •   ")
        findViewById<TextView>(R.id.detailMeta).text = meta
        findViewById<TextView>(R.id.detailPlot).text =
            detail.plot.ifBlank { "لا يتوفر شرح للقصة من المزوّد لهذا العمل." }
        if (detail.poster.isNotBlank()) poster.load(detail.poster)

        if (base.seriesId.isNotEmpty() && detail.episodes.isNotEmpty()) {
            btnEpisodes.visibility = View.VISIBLE
            findViewById<View>(R.id.spaceEpisodes).visibility = View.VISIBLE
        }

        if (detail.trailerId.isNotBlank()) {
            startTrailer(detail.trailerId)
        } else {
            // no trailer from the provider: offer a YouTube search for it
            val hint = findViewById<TextView>(R.id.detailMeta)
            hint.text = (meta + "   •   اضغط على العنوان للبحث عن الإعلان التشويقي").trim(' ', '•')
            findViewById<View>(R.id.detailTitle).setOnClickListener { searchTrailer() }
            scrim.visibility = View.VISIBLE
        }
    }

    /** Full screen YouTube trailer; Arabic subtitles are requested (shown when the video has them). */
    private fun startTrailer(id: String) {
        val html = """
            <html><body style="margin:0;background:#000">
            <iframe width="100%" height="100%" style="border:0;position:fixed;top:0;left:0;right:0;bottom:0"
              src="https://www.youtube.com/embed/$id?autoplay=1&controls=1&rel=0&modestbranding=1&playsinline=1&cc_load_policy=1&cc_lang_pref=ar&hl=ar&fs=0"
              allow="autoplay; encrypted-media; picture-in-picture" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        poster.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    private fun searchTrailer() {
        val query = Uri.encode((detail.title.ifBlank { base.name }) + " اعلان رسمي مترجم")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query")))
        } catch (_: Exception) {
            toast("لا يوجد تطبيق لفتح الرابط")
        }
    }

    // ------------------------------------------------------------------ buttons

    private fun play() {
        if (base.seriesId.isNotEmpty()) {
            val first = detail.episodes.firstOrNull()
            if (first == null) {
                toast("لا توجد حلقات متاحة")
                return
            }
            playChannel(first)
        } else {
            playChannel(base)
        }
    }

    private fun showEpisodes() {
        val episodes = detail.episodes
        val labels = episodes.map { it.group + " — " + it.name }.toTypedArray()
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("الحلقات (${episodes.size})")
            .setItems(labels) { _, which -> playChannel(episodes[which]) }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun playChannel(channel: Channel) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_PLAY_URL, channel.url)
                .putExtra(
                    MainActivity.EXTRA_PLAY_NAME,
                    if (channel === base) base.name else detail.title.ifBlank { base.name } + " — " + channel.name
                )
                .putExtra(MainActivity.EXTRA_PLAY_LOGO, base.logo)
        )
        finish()
    }

    private fun toggleFavorite() {
        prefs.toggleFavorite(base, if (base.seriesId.isNotEmpty()) "series" else "movie")
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        btnFav.setText(if (prefs.isFavorite(base.url)) R.string.fav_remove else R.string.fav_add)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_INFO_ID = "info_id"
    }
}
