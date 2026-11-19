package com.osogold.tv

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** "Recordings": the saved files, play them here, delete any of them with the clear red button. */
class RecordingsActivity : BaseActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var playing: TextView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var items: List<RecordingStore.Item> = emptyList()

    private val adapter = object : RecyclerView.Adapter<RecordingsActivity.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.info.text = Formatter.formatShortFileSize(this@RecordingsActivity, item.size) + "  •  " +
                DateFormat.format("yyyy-MM-dd  HH:mm", Date(item.date))
            holder.play.setOnClickListener { playItem(item) }
            holder.delete.setOnClickListener { confirmDelete(item) }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.recName)
        val info: TextView = view.findViewById(R.id.recInfo)
        val play: TextView = view.findViewById(R.id.recPlay)
        val delete: TextView = view.findViewById(R.id.recDelete)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        list = findViewById(R.id.recList)
        empty = findViewById(R.id.recEmpty)
        playing = findViewById(R.id.recPlaying)
        playerView = findViewById(R.id.recPlayer)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.btnRecBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun reload() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { RecordingStore.list(this@RecordingsActivity) }
            adapter.notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun playItem(item: RecordingStore.Item) {
        releasePlayer()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(NetConfig.dataSourceFactory(this)))
            .build()
        player = exo
        playerView.player = exo
        playing.text = item.name
        exo.setMediaItem(MediaItem.fromUri(item.uri))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun confirmDelete(item: RecordingStore.Item) {
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("حذف التسجيل")
            .setMessage("هل تريد حذف «${item.name}» نهائياً؟")
            .setPositiveButton("حذف") { _, _ ->
                releasePlayer()
                playing.text = "اختر تسجيلاً للتشغيل"
                val ok = RecordingStore.delete(this, item)
                toast(if (ok) "تم حذف التسجيل" else "تعذر الحذف (ربما أُنشئ قبل إعادة تثبيت التطبيق — احذفه من مدير الملفات)")
                reload()
            }
            .setNegativeButton("إلغاء", null)
            .create()
        showImmersive(dialog)
    }
}
