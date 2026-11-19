package com.osogold.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

/**
 * Main screen ("Cosmic Radar"):
 *   left 68%  : big rectangular live screen (Media3 or LibVLC) with the orbit of glowing icons
 *               (live, favourites, movies, series, Quran, radio, my lists, search, update, AI, settings)
 *   right 32% : two separate vertical lists side by side - channels (left) and sections (right)
 *
 * Touching the screen only shows / hides the player bar (it hides again after 5 seconds).
 * Play / pause exists ONLY as a dedicated button of the player bar.
 *
 * Also hosts: the full screen stage with the dual screen ([StageController]), the luxury audio
 * player of the radio and Quran sections ([AudioPanel]), subtitles, recording, the automatic
 * background update and the commands of the AI assistant.
 */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stage: StageController
    private lateinit var audio: AudioPanel
    private lateinit var subtitles: SubtitleController

    private lateinit var orbit: OrbitLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var vlcView: VLCVideoLayout
    private lateinit var controlsLayer: View
    private lateinit var btnPlay: ImageView
    private lateinit var btnRecord: ImageView
    private lateinit var btnQuality: ImageView
    private lateinit var btnEngine: ImageView
    private lateinit var btnSub: ImageView
    private lateinit var btnDual: ImageView
    private lateinit var btnFullscreen: ImageView
    private lateinit var tvRec: TextView
    private lateinit var tvIndicator: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var tvSectionTitle: TextView
    private lateinit var searchBar: View
    private lateinit var etSearch: EditText
    private lateinit var btnBack: View
    private lateinit var list: RecyclerView
    private lateinit var sectionList: RecyclerView
    private lateinit var adapter: RowAdapter
    private lateinit var sectionAdapter: RowAdapter

    private val vlc: VlcEngine by lazy { VlcEngine(this) }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingChannel: Channel? = null
    private var nowPlaying: Channel? = null
    private var nowUrl: String? = null
    private var nowAudio = false
    private var vlcActive = false
    private var playJob: Job? = null

    /** The catalog shown in the two lists: live, radio, quran, a subscription id ... */
    private var catalogId = "live"
    private var catalogTitle = "البث المباشر"
    private var currentPkgTag: String? = null
    private var currentRows: List<Row> = emptyList()
    private var sectionJob: Job? = null
    private var channelJob: Job? = null

    /** Restore actions for the "back" button (episodes, search results). */
    private val backStack = ArrayList<() -> Unit>()

    private var autoSkipRemaining = 0
    private var reconnectAttempts = 0
    private var firstAutoPlayDone = false
    private var listsSignature = ""

    private val handler = Handler(Looper.getMainLooper())
    private var recLocalStart = 0L
    private var recShown = false
    private val recTick = object : Runnable {
        override fun run() {
            updateRecordingTimer()
            if (recShown) handler.postDelayed(this, 1000L)
        }
    }
    private val hideControlsRunnable = Runnable { controlsLayer.visibility = View.GONE }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val subtitleLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importSubtitle(uri)
    }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        bindViews()
        setupStage()
        setupAudio()
        setupSubtitles()
        setupGestures()
        setupOrbit()
        setupLists()
        setupButtons()
        setupBackHandling()
        vlc.onError = { onPlayError() }
        vlc.onPlaying = {
            autoSkipRemaining = 0
            reconnectAttempts = 0
            updatePlayIcon()
        }

        listsSignature = prefs.listsSignature()

        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)

        openCatalog("live", "البث المباشر", autoPlay = true)
        showControls()
        startAutoUpdater()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        connectController()
        stage.onHostStart()
        if (vlcActive) {
            attachVlc()
            vlc.resume()
        }
        RecordingService.listener = { recording, path -> onRecordingState(recording, path) }
        if (RecordingService.active) showRecording(true)
    }

    override fun onResume() {
        super.onResume()
        // came back from Settings / My lists: reload when a list, an account or the User-Agent changed
        val signature = prefs.listsSignature()
        if (signature != listsSignature) {
            listsSignature = signature
            PlaylistRepository.clearCache()
            openCatalog(catalogId, catalogTitle, selectTag = currentPkgTag)
        }
        // the player engine was changed in Settings
        val url = nowUrl
        val channel = nowPlaying
        if (!nowAudio && url != null && channel != null && prefs.useVlc != vlcActive) {
            startOnEngine(channel, url)
        }
        subtitles.applyStyle()
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        stage.bigView.player = null
        stage.onHostStop()
        if (vlcActive) {
            vlc.pause()
            vlc.attach(null)
        }
        RecordingService.listener = null
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audio.release()
        stage.release()
        vlc.release()
        super.onDestroy()
    }

    /** A touch on the player keeps its bar open; every touch keeps the dual screen overlay open. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        stage.touch()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && controlsLayer.visibility == View.VISIBLE) {
            val rect = Rect()
            if (playerFrame.getGlobalVisibleRect(rect) && rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                showControls()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================== setup

    private fun bindViews() {
        orbit = findViewById(R.id.orbit)
        playerFrame = findViewById(R.id.playerFrame)
        playerView = findViewById(R.id.playerView)
        vlcView = findViewById(R.id.vlcView)
        controlsLayer = findViewById(R.id.controlsLayer)
        btnPlay = findViewById(R.id.btnPlay)
        btnRecord = findViewById(R.id.btnRecord)
        btnQuality = findViewById(R.id.btnQuality)
        btnEngine = findViewById(R.id.btnEngine)
        btnSub = findViewById(R.id.btnSub)
        btnDual = findViewById(R.id.btnDual)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        tvRec = findViewById(R.id.tvRec)
        tvIndicator = findViewById(R.id.tvIndicator)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvListTitle = findViewById(R.id.tvListTitle)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnBack = findViewById(R.id.btnBack)
        list = findViewById(R.id.list)
        sectionList = findViewById(R.id.sectionList)
    }

    private fun setupStage() {
        stage = StageController(
            activity = this,
            root = findViewById(R.id.stageRoot),
            prefs = prefs,
            indicator = tvIndicator,
            host = object : StageController.Host {
                override fun bigChannel(): Channel? = nowPlaying

                override fun playBig(channel: Channel) {
                    play(channel)
                }

                override fun moveBig(toStage: Boolean) {
                    if (vlcActive) {
                        attachVlc()
                    } else {
                        val c = controller ?: return
                        if (toStage) {
                            PlayerView.switchTargetView(c, playerView, stage.bigView)
                        } else {
                            PlayerView.switchTargetView(c, stage.bigView, playerView)
                        }
                    }
                }

                override fun qualityEntries(): List<Pair<String, () -> Unit>> = buildQualityEntries()

                override fun autoQuality() {
                    this@MainActivity.autoQuality()
                }

                override fun toggleEngine(): String = switchEngine()

                override fun onAspectChanged(mode: Int) {
                    vlc.applyAspect(mode)
                }

                override fun togglePlayPause() {
                    this@MainActivity.togglePlayPause()
                }

                override fun toggleRecording() {
                    this@MainActivity.toggleRecording()
                }

                override fun openSubtitles() {
                    subtitles.show()
                }

                override fun onStageClosed() {
                    enterImmersive()
                }
            }
        )
    }

    private fun setupAudio() {
        audio = AudioPanel(
            root = findViewById(R.id.audioRoot),
            host = object : AudioPanel.Host {
                override fun controller(): Player? = this@MainActivity.controller
                override fun next() = playRelative(+1)
                override fun previous() = playRelative(-1)
                override fun toggleRecord() = this@MainActivity.toggleRecording()
                override fun onMuteChanged(muted: Boolean) {
                    controller?.volume = if (muted) 0f else 1f
                }

                override fun onClosed() {
                    enterImmersive()
                }
            }
        )
    }

    private fun setupSubtitles() {
        subtitles = SubtitleController(
            activity = this,
            prefs = prefs,
            host = object : SubtitleController.Host {
                override fun exoPlayer(): Player? = controller
                override fun vlcEngine(): VlcEngine = vlc
                override fun isVlc(): Boolean = vlcActive
                override fun subtitleViews(): List<Pair<PlayerView, Float>> =
                    listOf(playerView to 0.8f, stage.bigView to 1f)

                override fun pickSubtitleFile() {
                    subtitleLauncher.launch("*/*")
                }
            }
        )
        vlc.subtitleOptions = subtitles.vlcOptions()
        subtitles.applyStyle()
    }

    /** Volume (right half) / brightness (left half) swipes. A tap only shows / hides the player bar. */
    private fun setupGestures() {
        playerView.setOnTouchListener(PlayerGestures(this, tvIndicator) { toggleControls() })
        vlcView.setOnTouchListener(PlayerGestures(this, tvIndicator) { toggleControls() })
    }

    private fun setupOrbit() {
        orbit.items = listOf(
            OrbitLayout.Item("live", "البث المباشر", R.drawable.ic_live),
            OrbitLayout.Item("fav", "المفضلة", R.drawable.ic_star),
            OrbitLayout.Item("movies", "الأفلام", R.drawable.ic_film),
            OrbitLayout.Item("series", "المسلسلات", R.drawable.ic_tv),
            OrbitLayout.Item("quran", "القرآن الكريم", R.drawable.ic_quran),
            OrbitLayout.Item("radio", "الراديو", R.drawable.ic_radio),
            OrbitLayout.Item("lists", "قوائمي", R.drawable.ic_server),
            OrbitLayout.Item("search", "بحث", R.drawable.ic_search),
            OrbitLayout.Item("refresh", "تحديث", R.drawable.ic_refresh),
            OrbitLayout.Item("ai", "المساعد الذكي", R.drawable.ic_ai),
            OrbitLayout.Item("settings", "الإعدادات", R.drawable.ic_settings)
        )
        orbit.selectedId = "live"
        orbit.onItemClick = { item -> onOrbitClick(item.id) }
    }

    private fun onOrbitClick(id: String) {
        when (id) {
            "live" -> {
                orbit.selectedId = id
                openCatalog("live", "البث المباشر")
            }
            "radio" -> {
                orbit.selectedId = id
                openCatalog("radio", "الراديو")
            }
            "quran" -> {
                orbit.selectedId = id
                openCatalog("quran", "القرآن الكريم")
            }
            "movies", "series" -> {
                // pause what is playing, then open the separate movies / series page
                controller?.pause()
                if (vlcActive) vlc.pause()
                startActivity(Intent(this, MoviesActivity::class.java).putExtra(MoviesActivity.EXTRA_KIND, id))
            }
            "fav" -> startActivity(Intent(this, FavoritesActivity::class.java))
            "lists" -> startActivity(Intent(this, MyListsActivity::class.java))
            "search" -> showSearch()
            "refresh" -> updateNow()
            "ai" -> startActivity(Intent(this, AssistantActivity::class.java))
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupLists() {
        adapter = RowAdapter(
            onClick = { row -> onChannelRowClick(row) },
            onStar = { row -> onStarClick(row) },
            isFavorite = { channel -> prefs.isFavorite(channel.url) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        sectionAdapter = RowAdapter(
            onClick = { row -> onSectionRowClick(row) },
            onStar = { },
            isFavorite = { false }
        )
        sectionList.layoutManager = LinearLayoutManager(this)
        sectionList.adapter = sectionAdapter
        sectionList.itemAnimator = null
    }

    private fun setupButtons() {
        btnPlay.setOnClickListener {
            togglePlayPause()
            showControls()
        }
        btnRecord.setOnClickListener {
            toggleRecording()
            showControls()
        }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnEngine.setOnClickListener { toast("المشغل الحالي: " + switchEngine()) }
        btnSub.setOnClickListener { subtitles.show() }
        btnDual.setOnClickListener { stage.open(dual = true) }
        btnFullscreen.setOnClickListener {
            val channel = nowPlaying
            if (nowAudio && channel != null) {
                audio.show(channel.name, channel.group, catalogId == "quran")
            } else {
                stage.open(dual = false)
            }
        }
        tvNowPlaying.setOnClickListener {
            val channel = nowPlaying
            if (nowAudio && channel != null) audio.show(channel.name, channel.group, catalogId == "quran")
        }
        btnBack.setOnClickListener { goBack() }

        findViewById<View>(R.id.btnSearchClose).setOnClickListener { closeSearch() }
        etSearch.doAfterTextChanged { applyFilter() }
        etSearch.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                enterImmersive()
                globalSearch(etSearch.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    audio.isVisible -> audio.hide()
                    stage.isOpen -> stage.onBack()
                    searchBar.visibility == View.VISIBLE -> closeSearch()
                    backStack.isNotEmpty() -> goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ================================================================== player bar (auto hide after 5 s)

    private fun showControls() {
        controlsLayer.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun toggleControls() {
        if (controlsLayer.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideControlsRunnable)
            controlsLayer.visibility = View.GONE
        } else {
            showControls()
        }
    }

    private fun updatePlayIcon() {
        val playing = if (vlcActive) vlc.isPlaying
        else controller?.let { it.isPlaying || it.playWhenReady } == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ================================================================== automatic / manual update

    /** Background update of every section: first after 20 s, then every 30 minutes. */
    private fun startAutoUpdater() {
        lifecycleScope.launch {
            delay(AutoUpdater.FIRST_DELAY_MS)
            while (true) {
                runUpdate(manual = false)
                delay(AutoUpdater.INTERVAL_MS)
            }
        }
    }

    /** The "Update" icon of the orbit: immediate update of all sections, then the lists are reloaded. */
    private fun updateNow() {
        toast("جارٍ تحديث جميع الأقسام…")
        lifecycleScope.launch { runUpdate(manual = true) }
    }

    private suspend fun runUpdate(manual: Boolean) {
        val updated = try {
            AutoUpdater.refreshAll(prefs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        PlaylistRepository.clearCache()
        if (manual) {
            openCatalog(catalogId, catalogTitle, selectTag = currentPkgTag)
            toast(if (updated > 0) "تم التحديث ($updated مصدر)" else "تعذر التحديث — تحقق من الإنترنت")
        }
    }

    // ================================================================== player connection

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                if (!vlcActive) (if (stage.isOpen) stage.bigView else playerView).player = c
                subtitles.applyStyle()

                val pending = pendingChannel
                if (pending != null) {
                    pendingChannel = null
                    val url = nowUrl
                    if (url != null) startOnEngine(pending, url)
                } else {
                    c.currentMediaItem?.mediaMetadata?.title?.let { if (nowPlaying == null) tvNowPlaying.text = it }
                }
                updatePlayIcon()
            } catch (e: Exception) {
                toast("تعذر تشغيل خدمة المشغل")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onPlayError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                autoSkipRemaining = 0
                reconnectAttempts = 0
            }
            updatePlayIcon()
        }
    }

    /** Anti-freeze: reconnect at once (up to 5 times), only then skip to the next channel. */
    private fun onPlayError() {
        if (nowPlaying != null && nowUrl != null && reconnectAttempts < MAX_RECONNECT) {
            reconnectAttempts++
            handler.postDelayed({ reconnect() }, 600L * reconnectAttempts)
            return
        }
        reconnectAttempts = 0
        if (autoSkipRemaining > 0) {
            autoSkipRemaining--
            playRelative(+1)
        } else {
            toast("تعذر تشغيل: ${nowPlaying?.name ?: ""}")
        }
    }

    private fun reconnect() {
        val channel = nowPlaying ?: return
        val url = nowUrl ?: return
        val c = controller
        if (!vlcActive && c != null && c.mediaItemCount > 0) {
            // keeps the position of movies, jumps to the live edge of live streams
            c.prepare()
            c.play()
        } else {
            startOnEngine(channel, url)
        }
    }

    private fun togglePlayPause() {
        if (vlcActive) {
            vlc.togglePlayPause()
        } else {
            controller?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
        }
        handler.postDelayed({ updatePlayIcon() }, 250L)
    }

    // ------------------------------------------------------------------ engines

    private fun engineName(): String = if (prefs.useVlc) "LibVLC" else "Media3 (ExoPlayer)"

    /** Media3 <-> LibVLC. The running video continues on the new engine. */
    private fun switchEngine(): String {
        prefs.playerEngine = if (prefs.useVlc) Prefs.ENGINE_EXO else Prefs.ENGINE_VLC
        val channel = nowPlaying
        val url = nowUrl
        if (!nowAudio && channel != null && url != null) startOnEngine(channel, url)
        return engineName()
    }

    private fun showEngineViews(useVlc: Boolean) {
        playerView.visibility = if (useVlc) View.GONE else View.VISIBLE
        vlcView.visibility = if (useVlc) View.VISIBLE else View.GONE
        stage.showEngineView(useVlc)
    }

    private fun attachVlc() {
        vlc.attach(if (stage.isOpen) stage.vlcView else vlcView)
    }

    /** Starts [url] on the engine chosen in the settings (audio always uses Media3 = background safe). */
    private fun startOnEngine(channel: Channel, url: String) {
        val useVlc = prefs.useVlc && !nowAudio
        if (useVlc) {
            controller?.let {
                it.stop()
                it.clearMediaItems()
            }
            vlcActive = true
            showEngineViews(true)
            attachVlc()
            vlc.applyAspect(prefs.aspectMode)
            vlc.play(url, NetConfig.userAgent())
        } else {
            if (vlcActive || vlc.isPlaying) {
                vlc.stop()
                vlc.attach(null)
            }
            vlcActive = false
            showEngineViews(false)
            val c = controller
            if (c == null) {
                pendingChannel = channel
                return
            }
            (if (stage.isOpen) stage.bigView else playerView).player = c
            c.volume = if (nowAudio && audio.muted) 0f else 1f
            c.setMediaItem(buildMediaItem(channel, url))
            c.prepare()
            c.play()
        }
        updatePlayIcon()
    }

    // ------------------------------------------------------------------ quality

    private fun autoQuality() {
        if (vlcActive) {
            vlc.resetQuality(NetConfig.userAgent())
        } else {
            controller?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .build()
            }
        }
    }

    /**
     * Every quality from the lowest to the highest: 360p, 480p, 720p, 1080p, 2K 1440p, 4K 2160p,
     * 8K 4320p. A level the stream really offers is selected directly; for the others the
     * automatic switching (ABR) is capped at that level.
     */
    private fun buildQualityEntries(): List<Pair<String, () -> Unit>> {
        if (vlcActive) {
            return vlc.videoTracks().map { (id, name) -> name to { vlc.selectVideoTrack(id) } }
        }
        val c = controller ?: return emptyList()
        val tracks = ArrayList<Pair<Int, Pair<androidx.media3.common.Tracks.Group, Int>>>()
        for (group in c.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val h = group.getTrackFormat(i).height
                if (h > 0) tracks.add(h to (group to i))
            }
        }
        val ladder = linkedMapOf(
            360 to "360p", 480 to "480p", 720 to "720p HD", 1080 to "1080p Full HD",
            1440 to "2K — 1440p", 2160 to "4K — 2160p", 4320 to "8K — 4320p"
        )
        val heights = (ladder.keys + tracks.map { it.first }).toSortedSet()
        val out = ArrayList<Pair<String, () -> Unit>>()
        for (h in heights) {
            val exact = tracks.firstOrNull { it.first == h }?.second
            val name = ladder[h] ?: "${h}p"
            val label = if (exact != null) "$name  ✓" else "$name  (حد أقصى)"
            out.add(label to {
                val builder = c.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (exact != null) {
                    builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    builder.setOverrideForType(TrackSelectionOverride(exact.first.mediaTrackGroup, exact.second))
                } else {
                    builder.setMaxVideoSize(h * 16 / 9 + 16, h)
                }
                c.trackSelectionParameters = builder.build()
                toast("الجودة: $name")
            })
        }
        return out
    }

    private fun showQualityDialog() {
        val entries = buildQualityEntries()
        val labels = (listOf("تلقائي (Auto)") + entries.map { it.first }).toTypedArray()
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("جودة البث")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    autoQuality()
                    toast("الجودة: تلقائي (Auto)")
                } else {
                    entries[which - 1].second.invoke()
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
        showControls()
    }

    // ------------------------------------------------------------------ playing

    private fun isAudioChannel(channel: Channel): Boolean {
        val ext = channel.url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("mp3", "aac", "m4a", "ogg", "opus", "pls")) return true
        return catalogId == "radio" || catalogId == "quran"
    }

    /** Plays a channel on the big screen (the sound always belongs to the big screen). */
    private fun play(channel: Channel, audioOverride: Boolean? = null) {
        val isAudio = audioOverride ?: isAudioChannel(channel)
        nowPlaying = channel
        nowUrl = null
        nowAudio = isAudio
        reconnectAttempts = 0
        tvNowPlaying.text = channel.name
        adapter.playingUrl = channel.url
        stage.onBigChanged()
        showControls()

        if (isAudio) {
            if (stage.isOpen) stage.close()
            if (!audio.isVisible) audio.resetMute()
            audio.show(channel.name, channel.group, catalogId == "quran")
        } else if (audio.isVisible) {
            audio.hide()
        }

        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val url = try {
                Catalog.resolve(prefs, channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("تعذر فتح القناة: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            nowUrl = url
            startOnEngine(channel, url)
        }
    }

    /** Next / previous entry of the channel list (also used by the audio player). */
    private fun playRelative(step: Int) {
        val current = nowPlaying ?: return
        val channels = currentRows.mapNotNull { it.channel }
        val index = channels.indexOfFirst { it.url == current.url }
        val target = index + step
        if (index >= 0 && target in channels.indices) {
            play(channels[target], audioOverride = nowAudio)
        }
    }

    // ================================================================== the two lists

    private fun favoriteKind(): String = when (catalogId) {
        "radio", "quran" -> "radio"
        "movies", "x_vod" -> "movie"
        "series", "x_series" -> "series"
        else -> ""
    }

    /**
     * Opens a catalog (live, radio, Quran, a subscription ...): the SECTIONS (groups, countries,
     * reciters, categories) go to the right list, the channels of the selected section to the
     * left list. Both lists scroll independently.
     */
    private fun openCatalog(id: String, title: String, autoPlay: Boolean = false, selectTag: String? = null) {
        catalogId = id
        catalogTitle = title
        currentPkgTag = null
        backStack.clear()
        updateBackButton()
        tvSectionTitle.text = title
        tvListTitle.text = getString(R.string.channels_title)
        sectionJob?.cancel()
        channelJob?.cancel()
        sectionAdapter.submit(emptyList())
        setRows(emptyList())

        sectionJob = lifecycleScope.launch {
            val packages: List<Pkg>? = try {
                Catalog.packages(prefs, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("تعذر التحميل: ${e.message ?: e.javaClass.simpleName}")
                null
            }
            val rows = ArrayList<Row>()
            val allTitle = when (id) {
                "radio" -> "المحطات الافتراضية"
                "quran" -> "إذاعات القرآن الكريم"
                else -> "الكل"
            }
            rows.add(Row(title = allTitle, leading = "⭐", tag = "p|$id|${Catalog.ALL}"))

            val arab = DefaultData.arabCountries.map { it.iso }.toSet()
            val list = packages?.filter { it.id != Catalog.ALL } ?: emptyList()
            if (id == "radio") {
                rows.add(Row(title = "🌍  الدول العربية", isHeader = true))
                list.filter { it.id in arab }.forEach { rows.add(pkgRow(id, it)) }
                rows.add(Row(title = "🌐  دول العالم", isHeader = true))
                list.filter { it.id !in arab }.forEach { rows.add(pkgRow(id, it)) }
            } else {
                list.forEach { rows.add(pkgRow(id, it)) }
            }
            if (id == "live") {
                // the countries (Arab first, then the world) live inside the live TV sections
                val countries = try {
                    Catalog.packages(prefs, "countries") ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                rows.add(Row(title = "🌍  الدول العربية", isHeader = true))
                countries.filter { it.id in arab }.forEach { rows.add(pkgRow("countries", it)) }
                rows.add(Row(title = "🌐  دول العالم", isHeader = true))
                countries.filter { it.id !in arab }.forEach { rows.add(pkgRow("countries", it)) }
            }
            sectionAdapter.submit(rows)

            val first = rows.firstOrNull { it.tag == selectTag } ?: rows.first { it.tag.startsWith("p|") }
            selectSectionRow(first, autoPlay)
        }
    }

    private fun pkgRow(catalog: String, pkg: Pkg) =
        Row(title = pkg.title, leading = pkg.leading.ifEmpty { "•" }, tag = "p|$catalog|${pkg.id}")

    private fun onSectionRowClick(row: Row) {
        if (row.tag.startsWith("p|")) selectSectionRow(row, autoPlay = false)
    }

    private fun selectSectionRow(row: Row, autoPlay: Boolean) {
        val parts = row.tag.split('|')
        if (parts.size < 3) return
        val cid = parts[1]
        val pid = parts.drop(2).joinToString("|")
        currentPkgTag = row.tag
        sectionAdapter.selectedTag = row.tag
        tvListTitle.text = row.title
        backStack.clear()
        updateBackButton()

        channelJob?.cancel()
        setRows(emptyList())
        channelJob = lifecycleScope.launch {
            val channels = try {
                Catalog.channels(prefs, cid, if (pid == Catalog.ALL) null else pid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("تعذر التحميل: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            setRows(channelRows(channels))
            if (autoPlay && !firstAutoPlayDone && channels.isNotEmpty()) {
                firstAutoPlayDone = true
                autoSkipRemaining = 6 // skip dead channels automatically at start
                play(channels.first())
            }
        }
    }

    /** Xtream series: shows the episodes; "back" returns to the previous list. */
    private fun openSeries(series: Channel) {
        val previousRows = currentRows
        val previousTitle = tvListTitle.text.toString()
        backStack.add {
            tvListTitle.text = previousTitle
            setRows(previousRows)
        }
        tvListTitle.text = series.name
        updateBackButton()
        setRows(emptyList())

        channelJob?.cancel()
        channelJob = lifecycleScope.launch {
            try {
                setRows(channelRows(Catalog.episodes(prefs, series.seriesId)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("تعذر تحميل الحلقات: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun onChannelRowClick(row: Row) {
        val channel = row.channel ?: return
        if (channel.seriesId.isNotEmpty()) openSeries(channel) else play(channel)
    }

    private fun onStarClick(row: Row) {
        val channel = row.channel ?: return
        val nowFavorite = prefs.toggleFavorite(channel, favoriteKind())
        adapter.refresh()
        toast(if (nowFavorite) "أُضيفت «${channel.name}» إلى المفضلة" else "أُزيلت «${channel.name}» من المفضلة")
    }

    private fun goBack() {
        if (backStack.isNotEmpty()) {
            val action = backStack.removeAt(backStack.size - 1)
            action()
        }
        updateBackButton()
    }

    private fun updateBackButton() {
        btnBack.visibility = if (backStack.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun channelRows(channels: List<Channel>): List<Row> = channels.map {
        Row(title = it.name, subtitle = it.group, leading = "", channel = it)
    }

    private fun setRows(rows: List<Row>) {
        currentRows = rows
        applyFilter()
    }

    private fun applyFilter() {
        val query = if (searchBar.visibility == View.VISIBLE) etSearch.text?.toString()?.trim().orEmpty() else ""
        val rows = if (query.isEmpty()) {
            currentRows
        } else {
            currentRows.filter {
                !it.isHeader && (it.title.contains(query, ignoreCase = true) ||
                    it.subtitle.contains(query, ignoreCase = true))
            }
        }
        adapter.submit(rows)
    }

    // ================================================================== search

    /** The search icon of the orbit: opens the search bar and the keyboard (landscape, never full screen). */
    private fun showSearch() {
        searchBar.visibility = View.VISIBLE
        etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        etSearch.post { imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun closeSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.setText("")
        searchBar.visibility = View.GONE
        applyFilter()
        enterImmersive()
    }

    /** Every source at once: favourites, live TV, radio, Quran and the user's lists. */
    private suspend fun searchSources(): List<Pair<String, List<Channel>>> {
        val sources = ArrayList<Pair<String, List<Channel>>>()
        sources.add("fav" to prefs.favorites())
        for (id in listOf("live", "radio", "quran") + Catalog.subscriptions(prefs).map { it.id }) {
            if (id == "x_vod" || id == "x_series" || id == "stalker") continue
            try {
                sources.add(id to Catalog.channels(prefs, id, null))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return sources
    }

    /** "Search" key of the keyboard: results of all sources in the channel list. */
    private fun globalSearch(query: String) {
        val wanted = ArabicText.normalize(query)
        if (wanted.isEmpty()) return
        val previousRows = currentRows
        val previousTitle = tvListTitle.text.toString()
        lifecycleScope.launch {
            val found = LinkedHashMap<String, Channel>()
            for ((_, channels) in searchSources()) {
                for (c in channels) {
                    if (ArabicText.normalize(c.name).contains(wanted)) found.putIfAbsent(c.url, c)
                    if (found.size >= 300) break
                }
            }
            backStack.add {
                tvListTitle.text = previousTitle
                setRows(previousRows)
            }
            updateBackButton()
            tvListTitle.text = "نتائج البحث"
            etSearch.setText("")
            setRows(channelRows(found.values.toList()))
            if (found.isEmpty()) toast("لا توجد نتائج لـ «$query»")
            closeSearch()
        }
    }

    // ================================================================== recording

    /** Record / stop for everything: live TV, movies, series and audio. */
    private fun toggleRecording() {
        if (RecordingService.active || recShown) {
            RecordingService.stop(this)
            toast("جارٍ إيقاف التسجيل وحفظه…")
            return
        }
        val channel = nowPlaying
        val url = nowUrl
        if (channel == null || url == null || !url.startsWith("http", ignoreCase = true)) {
            toast("شغّل بثاً أولاً ثم اضغط زر التسجيل")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            toast("امنح صلاحية التخزين ثم أعد المحاولة")
            return
        }
        RecordingService.start(this, url, channel.name)
        recLocalStart = System.currentTimeMillis()
        showRecording(true)
        toast("بدأ تسجيل: ${channel.name}")
    }

    private fun onRecordingState(recording: Boolean, path: String?) {
        if (recording) {
            showRecording(true)
        } else {
            showRecording(false)
            when {
                path != null -> toast("تم الحفظ في: $path")
                RecordingService.lastMessage != null -> toast(RecordingService.lastMessage!!)
                else -> toast("لم يُحفظ التسجيل (قصير جداً أو تعذر الحفظ)")
            }
        }
    }

    private fun showRecording(on: Boolean) {
        recShown = on
        btnRecord.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_record)
        handler.removeCallbacks(recTick)
        if (on) {
            tvRec.visibility = View.VISIBLE
            handler.post(recTick)
        } else {
            tvRec.visibility = View.GONE
            stage.setRecordingText(null)
            audio.setRecording(null)
        }
    }

    /** The small live counter above the record button (also on the stage overlay and in the audio player). */
    private fun updateRecordingTimer() {
        val start = if (RecordingService.active && RecordingService.startedAt > 0L) RecordingService.startedAt
        else recLocalStart
        val total = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
        val text = "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        tvRec.text = "● $text"
        stage.setRecordingText(text)
        audio.setRecording(text)
    }

    // ================================================================== subtitles

    /** Copies the chosen subtitle file into the app (the player service can read it) and loads it. */
    private fun importSubtitle(uri: Uri) {
        lifecycleScope.launch {
            var name = "subtitle.srt"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            } catch (_: Exception) {
            }
            val dir = File(filesDir, "subs")
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val ok = withContext(Dispatchers.IO) {
                try {
                    dir.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    target.length() > 0L
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) subtitles.loadFile(Uri.fromFile(target), name) else toast("تعذر قراءة ملف الترجمة")
        }
    }

    // ================================================================== intents (pages, assistant)

    private fun handleIntent(intent: Intent?) {
        intent ?: return

        // "Play" of the movie page / a favourite
        val playUrl = intent.getStringExtra(EXTRA_PLAY_URL)
        if (!playUrl.isNullOrBlank()) {
            val channel = Channel(
                name = intent.getStringExtra(EXTRA_PLAY_NAME).orEmpty().ifBlank { "—" },
                url = playUrl,
                logo = intent.getStringExtra(EXTRA_PLAY_LOGO).orEmpty()
            )
            val audioFlag = intent.getBooleanExtra(EXTRA_PLAY_AUDIO, false)
            val fullscreen = intent.getBooleanExtra(EXTRA_PLAY_FULLSCREEN, true)
            intent.removeExtra(EXTRA_PLAY_URL)
            play(channel, audioOverride = audioFlag)
            if (fullscreen && !audioFlag) stage.open(dual = false)
            return
        }

        // command of the AI assistant / "My lists"
        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (!command.isNullOrBlank()) {
            val argument = intent.getStringExtra(EXTRA_ARGUMENT).orEmpty()
            intent.removeExtra(EXTRA_COMMAND)
            executeCommand(command, argument)
        }
    }

    private fun openList(id: String) {
        val def = Catalog.sections(prefs).firstOrNull { it.id == id }
        if (def == null) {
            toast("القائمة غير موجودة")
            return
        }
        orbit.selectedId = "lists"
        openCatalog(def.id, def.title)
    }

    private fun executeCommand(command: String, argument: String) {
        when (command) {
            Assistant.CMD_PLAY -> findAndPlay(argument)
            Assistant.CMD_SURAH -> playSurah(argument)
            Assistant.CMD_OPEN_LIST -> openList(argument)
            Assistant.CMD_SECTION -> {
                when (argument) {
                    "countries" -> onOrbitClick("live")
                    "subs" -> onOrbitClick("lists")
                    else -> onOrbitClick(argument)
                }
            }
            Assistant.CMD_RECORD_START -> if (!(RecordingService.active || recShown)) toggleRecording()
            Assistant.CMD_RECORD_STOP -> if (RecordingService.active || recShown) toggleRecording()
            Assistant.CMD_PAUSE -> {
                controller?.pause()
                if (vlcActive) vlc.pause()
                updatePlayIcon()
            }
            Assistant.CMD_RESUME -> {
                controller?.play()
                if (vlcActive) vlc.resume()
                updatePlayIcon()
            }
            Assistant.CMD_MUTE -> {
                controller?.volume = 0f
                audio.setMuted(true)
            }
            Assistant.CMD_UNMUTE -> {
                controller?.volume = 1f
                audio.setMuted(false)
            }
            Assistant.CMD_NEXT -> playRelative(+1)
            Assistant.CMD_PREVIOUS -> playRelative(-1)
            Assistant.CMD_FULLSCREEN -> stage.open(dual = false)
            Assistant.CMD_DUAL -> stage.open(dual = true)
            Assistant.CMD_REFRESH -> updateNow()
            Assistant.CMD_ENGINE -> toast("المشغل الحالي: " + switchEngine())
        }
    }

    /** "Play channel X": looks in favourites, live TV, radio, Quran radio and the user's lists. */
    private fun findAndPlay(query: String) {
        val wanted = ArabicText.normalize(query)
        if (wanted.isEmpty()) {
            toast("لم أفهم اسم القناة")
            return
        }
        toast("أبحث عن «$query»…")
        lifecycleScope.launch {
            var best: Channel? = null
            var bestScore = 0
            var bestAudio = false
            for ((id, channels) in searchSources()) {
                for (c in channels) {
                    val name = ArabicText.normalize(c.name)
                    val score = when {
                        name == wanted -> 4
                        name.startsWith(wanted) -> 3
                        name.contains(wanted) -> 2
                        wanted.contains(name) && name.length >= 4 -> 1
                        else -> 0
                    }
                    if (score > bestScore) {
                        best = c
                        bestScore = score
                        bestAudio = id == "radio" || id == "quran"
                    }
                }
            }
            val found = best
            if (found == null) toast("لم أجد «$query» في القوائم") else play(found, audioOverride = bestAudio)
        }
    }

    /** "Open surah X [by reciter Y]". argument = "<number>|<reciter words>". */
    private fun playSurah(argument: String) {
        val number = argument.substringBefore('|').toIntOrNull()
        val reciterWords = ArabicText.normalize(argument.substringAfter('|', ""))
        if (number == null || number !in 1..114) {
            toast("لم أفهم رقم السورة")
            return
        }
        lifecycleScope.launch {
            val all = try {
                QuranClient.recitationsAsync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val available = all.filter { number in it.surahs }
            val wanted = if (reciterWords.isNotEmpty()) {
                available.firstOrNull { rec ->
                    val name = ArabicText.normalize(rec.reciter)
                    reciterWords.split(' ').filter { it.length > 2 }.any { name.contains(it) }
                }
            } else {
                null
            }
            val chosen = wanted
                ?: available.firstOrNull { ArabicText.normalize(it.reciter).contains("العفاسي") }
                ?: available.firstOrNull()
            if (chosen == null) {
                toast("لم أجد هذه السورة")
                return@launch
            }
            catalogId = "quran"
            orbit.selectedId = "quran"
            val channel = Channel(
                name = "%03d  سورة %s".format(number, Surahs.name(number)),
                url = chosen.server + "%03d.mp3".format(number),
                group = chosen.reciter
            )
            play(channel, audioOverride = true)
        }
    }

    companion object {
        const val EXTRA_PLAY_URL = "play_url"
        const val EXTRA_PLAY_NAME = "play_name"
        const val EXTRA_PLAY_LOGO = "play_logo"
        const val EXTRA_PLAY_AUDIO = "play_audio"
        const val EXTRA_PLAY_FULLSCREEN = "play_fullscreen"
        const val EXTRA_COMMAND = "assistant_command"
        const val EXTRA_ARGUMENT = "assistant_argument"

        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val MAX_RECONNECT = 5
    }
}
