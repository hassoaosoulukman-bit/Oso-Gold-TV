package com.osogold.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/**
 * Favourites in four sections (live channels, movies, series, radio). Level 1 = four cards,
 * level 2 = the items of one section with a delete button each. "Back" goes level 2 -> level 1 -> out.
 */
class FavoritesActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var level1: View
    private lateinit var level2: FrameLayout
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private var kind: String? = null
    private var items: List<Channel> = emptyList()

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_fav, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = items[position]
            holder.name.text = channel.name
            if (channel.logo.isBlank()) {
                holder.logo.dispose()
                holder.logo.setImageResource(R.drawable.ic_live)
            } else {
                holder.logo.load(channel.logo) {
                    crossfade(true)
                    placeholder(R.drawable.ic_live)
                    error(R.drawable.ic_live)
                }
            }
            holder.itemView.setOnClickListener { open(channel) }
            holder.delete.setOnClickListener {
                prefs.removeFavorite(channel.url)
                loadLevel2()
                refreshCounts()
                toast("تم حذف «${channel.name}» من المفضلة")
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.favLogo)
        val name: TextView = view.findViewById(R.id.favName)
        val delete: TextView = view.findViewById(R.id.favDelete)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        prefs = Prefs(this)
        level1 = findViewById(R.id.favLevel1)
        level2 = findViewById(R.id.favLevel2)
        title = findViewById(R.id.favTitle)
        empty = findViewById(R.id.favEmpty)
        list = findViewById(R.id.favList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.favCardLive).setOnClickListener { openKind("live") }
        findViewById<View>(R.id.favCardMovie).setOnClickListener { openKind("movie") }
        findViewById<View>(R.id.favCardSeries).setOnClickListener { openKind("series") }
        findViewById<View>(R.id.favCardRadio).setOnClickListener { openKind("radio") }
        findViewById<View>(R.id.favBack).setOnClickListener { goBack() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
        refreshCounts()
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
        if (kind != null) loadLevel2()
    }

    private fun goBack() {
        if (kind != null) showLevel1() else finish()
    }

    private fun refreshCounts() {
        findViewById<TextView>(R.id.favCardLive).text = "📺\nالقنوات\n(${prefs.favoritesOf("live").size})"
        findViewById<TextView>(R.id.favCardMovie).text = "🎬\nالأفلام\n(${prefs.favoritesOf("movie").size})"
        findViewById<TextView>(R.id.favCardSeries).text = "🎞\nالمسلسلات\n(${prefs.favoritesOf("series").size})"
        findViewById<TextView>(R.id.favCardRadio).text = "📻\nالراديو\n(${prefs.favoritesOf("radio").size})"
    }

    private fun showLevel1() {
        kind = null
        level2.visibility = View.GONE
        level1.visibility = View.VISIBLE
        title.text = "المفضلة"
    }

    private fun openKind(value: String) {
        kind = value
        level1.visibility = View.GONE
        level2.visibility = View.VISIBLE
        title.text = "المفضلة — " + when (value) {
            "movie" -> "الأفلام"
            "series" -> "المسلسلات"
            "radio" -> "الراديو"
            else -> "القنوات"
        }
        loadLevel2()
    }

    private fun loadLevel2() {
        val k = kind ?: return
        items = prefs.favoritesOf(k)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(channel: Channel) {
        when (kind) {
            "movie", "series" -> startActivity(
                Intent(this, MovieDetailActivity::class.java)
                    .putExtra(MovieDetailActivity.EXTRA_KIND, if (kind == "series") "series" else "movies")
                    .putExtra(MovieDetailActivity.EXTRA_NAME, channel.name)
                    .putExtra(MovieDetailActivity.EXTRA_URL, channel.url)
                    .putExtra(MovieDetailActivity.EXTRA_LOGO, channel.logo)
                    .putExtra(MovieDetailActivity.EXTRA_SERIES_ID, channel.seriesId)
                    .putExtra(MovieDetailActivity.EXTRA_INFO_ID, channel.infoId)
            )
            else -> {
                val radio = kind == "radio"
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_PLAY_URL, channel.url)
                        .putExtra(MainActivity.EXTRA_PLAY_NAME, channel.name)
                        .putExtra(MainActivity.EXTRA_PLAY_LOGO, channel.logo)
                        .putExtra(MainActivity.EXTRA_PLAY_AUDIO, radio)
                        .putExtra(MainActivity.EXTRA_PLAY_FULLSCREEN, false)
                )
                finish()
            }
        }
    }
}
