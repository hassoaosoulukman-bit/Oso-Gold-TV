package com.osogold.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The separate page of the movies / series: sorted by country and type (Arabic, foreign,
 * Spanish, Turkish, Hindi, Korean, anime, action, comedy ...). Tap a poster -> its own page.
 */
class MoviesActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var kind: String
    private lateinit var status: TextView
    private lateinit var sourceChips: RecyclerView
    private lateinit var genreChips: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var search: EditText

    private val sourceAdapter = ChipAdapter { selectSource(it) }
    private val genreAdapter = ChipAdapter { selectGroup(it) }
    private val posterAdapter = PosterAdapter { openDetail(it) }

    private var sources: List<Pair<String, String>> = emptyList()
    private var sourceId = ""
    private var results: List<GenreClassifier.Result> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var loadJob: Job? = null
    private val groupCache = HashMap<String, List<Channel>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)
        prefs = Prefs(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "movies"

        findViewById<TextView>(R.id.tvMoviesTitle).setText(
            if (kind == "series") R.string.series_title else R.string.movies_title
        )
        findViewById<View>(R.id.btnMoviesBack).setOnClickListener { finish() }
        status = findViewById(R.id.tvMoviesStatus)
        sourceChips = findViewById(R.id.sourceChips)
        genreChips = findViewById(R.id.genreChips)
        grid = findViewById(R.id.posterGrid)
        search = findViewById(R.id.etMoviesSearch)

        sourceChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sourceChips.adapter = sourceAdapter
        genreChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        genreChips.adapter = genreAdapter
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = posterAdapter
        search.doAfterTextChanged { applyFilter() }

        // sources: the default list, the Xtream subscription and the lists of the user
        val list = ArrayList<Pair<String, String>>()
        list.add(kind to "الافتراضي")
        if (prefs.xtreamConfigured()) {
            list.add((if (kind == "series") "x_series" else "x_vod") to "Xtream")
        }
        Catalog.subscriptions(prefs).filter { it.id.startsWith("m3u:") }.forEach { list.add(it.id to it.title) }
        sources = list
        sourceChips.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        sourceAdapter.submit(list.map { it.first to it.second }, list.first().first)
        selectSource(list.first().first)
    }

    private fun selectSource(id: String) {
        sourceId = id
        sourceAdapter.selected = id
        groupCache.clear()
        loadJob?.cancel()
        status.text = getString(R.string.loading)
        posterAdapter.submit(emptyList())
        genreAdapter.submit(emptyList(), "")
        loadJob = lifecycleScope.launch {
            try {
                val packages = Catalog.packages(prefs, id)
                results = if (packages == null) {
                    listOf(GenreClassifier.Result(GenreClassifier.KEY_ALL, "الكل", "✨", emptyList()))
                } else {
                    GenreClassifier.classify(packages)
                }
                genreAdapter.submit(results.map { it.key to (it.badge + "  " + it.title) }, results.first().key)
                selectGroup(results.first().key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "تعذر التحميل: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun selectGroup(key: String) {
        genreAdapter.selected = key
        val group = results.firstOrNull { it.key == key } ?: return
        loadJob?.cancel()
        status.text = getString(R.string.loading)
        loadJob = lifecycleScope.launch {
            try {
                val channels = groupCache.getOrPut(key) {
                    if (group.categories.isEmpty() || key == GenreClassifier.KEY_ALL && results.size == 1) {
                        Catalog.channels(prefs, sourceId, null)
                    } else {
                        val out = LinkedHashMap<String, Channel>()
                        for (pkg in group.categories) {
                            for (c in Catalog.channels(prefs, sourceId, pkg.id)) out.putIfAbsent(c.url, c)
                        }
                        out.values.toList()
                    }
                }
                shown = channels
                applyFilter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "تعذر التحميل: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun applyFilter() {
        val query = search.text?.toString()?.trim().orEmpty()
        val list = if (query.isEmpty()) shown else shown.filter { it.name.contains(query, ignoreCase = true) }
        posterAdapter.submit(list)
        status.text = "${list.size} " + if (kind == "series") "مسلسل" else "فيلم"
    }

    private fun openDetail(channel: Channel) {
        startActivity(
            Intent(this, MovieDetailActivity::class.java)
                .putExtra(MovieDetailActivity.EXTRA_KIND, kind)
                .putExtra(MovieDetailActivity.EXTRA_NAME, channel.name)
                .putExtra(MovieDetailActivity.EXTRA_URL, channel.url)
                .putExtra(MovieDetailActivity.EXTRA_LOGO, channel.logo)
                .putExtra(MovieDetailActivity.EXTRA_SERIES_ID, channel.seriesId)
                .putExtra(MovieDetailActivity.EXTRA_INFO_ID, channel.infoId)
        )
    }

    // ------------------------------------------------------------------ adapters

    private class ChipAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<ChipAdapter.Holder>() {

        private var items: List<Pair<String, String>> = emptyList()
        var selected: String = ""
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        fun submit(list: List<Pair<String, String>>, select: String) {
            items = list
            selected = select
        }

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView)

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (key, label) = items[position]
            holder.text.text = label
            holder.text.setBackgroundResource(if (key == selected) R.drawable.bg_chip_active else R.drawable.bg_chip)
            holder.text.setOnClickListener { onClick(key) }
        }
    }

    private class PosterAdapter(private val onClick: (Channel) -> Unit) :
        RecyclerView.Adapter<PosterAdapter.Holder>() {

        private var items: List<Channel> = emptyList()

        fun submit(list: List<Channel>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.posterImage)
            val title: TextView = view.findViewById(R.id.posterTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = items[position]
            holder.title.text = channel.name
            if (channel.logo.isBlank()) {
                holder.image.setImageDrawable(null)
            } else {
                holder.image.load(channel.logo) { crossfade(true) }
            }
            holder.itemView.setOnClickListener { onClick(channel) }
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
