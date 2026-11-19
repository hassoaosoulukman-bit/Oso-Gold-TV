package com.osogold.tv

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Main screen ("Cosmic Radar"):
 *   centre : glowing circular live player, surrounded by the rotating orbit of sections
 *   left   : quick search, favourites, sleep timer, refresh
 *   right  : list of channels / countries / favourites
 */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var orbit: OrbitLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerViewFull: PlayerView
    private lateinit var fullscreenRoot: FrameLayout
    private lateinit var btnRecord: ImageView
    private lateinit var btnFullscreen: ImageView
    private lateinit var btnExitFull: ImageView
    private lateinit var tvRec: TextView
    private lateinit var tvIndicator: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnFav: GoldIconButton
    private lateinit var btnSleep: GoldIconButton
    private lateinit var btnRefresh: GoldIconButton
    private lateinit var btnBack: GoldIconButton
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingChannel: Channel? = null
    private var nowPlaying: Channel? = null

    private var section: Section = Section.LIVE
    private var currentCountry: Country? = null
    private var showingFavorites = false
    private var currentRows: List<Row> = emptyList()
    private var loadJob: Job? = null

    private var autoSkipRemaining = 0
    private var firstAutoPlayDone = false
    private var isFullscreen = false
    private var sourcesSignature = ""

    private var recorder: StreamRecorder? = null
    private var recBlink: ObjectAnimator? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        bindViews()
        setupPlayerFrame()
        setupGestures()
        setupOrbit()
        setupList()
        setupButtons()
        setupBackHandling()

        sourcesSignature = currentSourcesSignature()

        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)

        selectSection(Section.LIVE, autoPlay = true)
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onResume() {
        super.onResume()
        btnSleep.active = SleepTimer.isActive
        // came back from Settings: reload when a playlist link was changed
        val signature = currentSourcesSignature()
        if (signature != sourcesSignature) {
            sourcesSignature = signature
            if (!showingFavorites) reloadCurrent(force = true)
        }
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        playerViewFull.player = null
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    override fun onDestroy() {
        recBlink?.cancel()
        recorder?.let { if (it.isRecording) it.stopAndSave { } }
        super.onDestroy()
    }

    // ================================================================== setup

    private fun bindViews() {
        orbit = findViewById(R.id.orbit)
        playerFrame = findViewById(R.id.playerFrame)
        playerView = findViewById(R.id.playerView)
        playerViewFull = findViewById(R.id.playerViewFull)
        fullscreenRoot = findViewById(R.id.fullscreenRoot)
        btnRecord = findViewById(R.id.btnRecord)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnExitFull = findViewById(R.id.btnExitFull)
        tvRec = findViewById(R.id.tvRec)
        tvIndicator = findViewById(R.id.tvIndicator)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvListTitle = findViewById(R.id.tvListTitle)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        btnFav = findViewById(R.id.btnFav)
        btnSleep = findViewById(R.id.btnSleep)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnBack = findViewById(R.id.btnBack)
        list = findViewById(R.id.list)
    }

    /** Makes the player a glowing circle. */
    private fun setupPlayerFrame() {
        playerFrame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        playerFrame.clipToOutline = true
    }

    /** Right half: volume, left half: brightness, tap: play / pause. */
    private fun setupGestures() {
        val gestures = PlayerGestures(this, tvIndicator) { togglePlayPause() }
        playerView.setOnTouchListener(gestures)
        playerViewFull.setOnTouchListener(gestures)
    }

    private fun setupOrbit() {
        orbit.items = Section.values().map { OrbitLayout.Item(it.id, getString(it.labelRes), it.iconRes) }
        orbit.selectedId = Section.LIVE.id
        orbit.onItemClick = { item ->
            Section.values().firstOrNull { it.id == item.id }?.let { selectSection(it) }
        }
    }

    private fun setupList() {
        adapter = RowAdapter(
            onClick = { row -> onRowClick(row) },
            onStar = { row -> onStarClick(row) },
            isFavorite = { channel -> prefs.isFavorite(channel.url) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    private fun setupButtons() {
        btnRecord.setOnClickListener { toggleRecording() }
        btnFullscreen.setOnClickListener { setFullscreen(true) }
        btnExitFull.setOnClickListener { setFullscreen(false) }
        btnFav.setOnClickListener { toggleFavorites() }
        btnSleep.setOnClickListener { showSleepDialog() }
        btnRefresh.setOnClickListener { reloadCurrent(force = true) }
        btnBack.setOnClickListener { goBack() }

        etSearch.doAfterTextChanged { applyFilter() }
        etSearch.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                enterImmersive()
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
                    isFullscreen -> setFullscreen(false)
                    showingFavorites || currentCountry != null -> goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
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
                (if (isFullscreen) playerViewFull else playerView).player = c

                val pending = pendingChannel
                if (pending != null) {
                    pendingChannel = null
                    play(pending)
                } else {
                    c.currentMediaItem?.mediaMetadata?.title?.let { tvNowPlaying.text = it }
                }
            } catch (e: Exception) {
                toast("تعذر تشغيل خدمة المشغل")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (autoSkipRemaining > 0) {
                autoSkipRemaining--
                playNextInList()
            } else {
                toast("تعذر تشغيل: ${nowPlaying?.name ?: ""}")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) autoSkipRemaining = 0
        }
    }

    private fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun play(channel: Channel) {
        if (recorder?.isRecording == true) stopRecording()

        nowPlaying = channel
        tvNowPlaying.text = channel.name
        adapter.playingUrl = channel.url

        val c = controller
        if (c == null) {
            pendingChannel = channel
            return
        }
        val lower = channel.url.lowercase()
        val mimeType = when {
            lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setArtist(channel.group)
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** Used by the "skip dead channels at start" logic. */
    private fun playNextInList() {
        val current = nowPlaying ?: return
        val channels = currentRows.mapNotNull { it.channel }
        val index = channels.indexOfFirst { it.url == current.url }
        if (index >= 0 && index + 1 < channels.size) {
            play(channels[index + 1])
        }
    }

    // ================================================================== full screen

    private fun setFullscreen(enabled: Boolean) {
        if (isFullscreen == enabled) return
        isFullscreen = enabled
        val c = controller
        if (enabled) {
            fullscreenRoot.visibility = View.VISIBLE
            if (c != null) PlayerView.switchTargetView(c, playerView, playerViewFull)
        } else {
            if (c != null) PlayerView.switchTargetView(c, playerViewFull, playerView)
            fullscreenRoot.visibility = View.GONE
        }
    }

    // ================================================================== sections / lists

    private fun selectSection(target: Section, autoPlay: Boolean = false) {
        if (target == Section.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        section = target
        currentCountry = null
        showingFavorites = false
        btnFav.active = false
        orbit.selectedId = target.id
        etSearch.setText("")
        updateBackButton()

        if (target == Section.COUNTRIES) {
            showCountries()
        } else {
            loadSectionChannels(target, force = false, autoPlay = autoPlay)
        }
    }

    private fun showCountries() {
        currentCountry = null
        loadJob?.cancel()
        tvListTitle.text = getString(R.string.sec_countries)

        val rows = ArrayList<Row>()
        rows.add(Row(title = "🌍  الدول العربية", isHeader = true))
        DefaultData.arabCountries.forEach {
            rows.add(Row(title = it.nameAr, subtitle = it.iso, leading = it.flag, country = it))
        }
        rows.add(Row(title = "🌐  دول العالم", isHeader = true))
        DefaultData.worldCountries.forEach {
            rows.add(Row(title = it.nameAr, subtitle = it.iso, leading = it.flag, country = it))
        }
        setRows(rows)
        setStatus("${DefaultData.arabCountries.size + DefaultData.worldCountries.size} دولة — اختر دولة لعرض قنواتها")
        updateBackButton()
    }

    private fun loadCountry(country: Country, force: Boolean = false) {
        currentCountry = country
        showingFavorites = false
        btnFav.active = false
        etSearch.setText("")
        tvListTitle.text = "${country.flag}  ${country.nameAr}"
        updateBackButton()
        fetchChannels(
            url = DefaultData.countryUrl(country),
            builtIn = emptyList(),
            builtInFirst = false,
            force = force,
            autoPlay = false,
            stillValid = { currentCountry == country && !showingFavorites }
        )
    }

    private fun loadSectionChannels(target: Section, force: Boolean, autoPlay: Boolean) {
        val key = target.sourceKey ?: return
        tvListTitle.text = getString(target.labelRes)
        fetchChannels(
            url = prefs.getSource(key),
            builtIn = DefaultData.builtIn(key),
            builtInFirst = DefaultData.builtInFirst(key),
            force = force,
            autoPlay = autoPlay,
            stillValid = { section == target && currentCountry == null && !showingFavorites }
        )
    }

    private fun fetchChannels(
        url: String,
        builtIn: List<Channel>,
        builtInFirst: Boolean,
        force: Boolean,
        autoPlay: Boolean,
        stillValid: () -> Boolean
    ) {
        loadJob?.cancel()
        setStatus(getString(R.string.loading))
        setRows(emptyList())

        loadJob = lifecycleScope.launch {
            var loaded: List<Channel> = emptyList()
            var error: String? = null
            if (url.isNotBlank()) {
                try {
                    loaded = PlaylistRepository.load(url, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            if (!stillValid()) return@launch

            val all = if (builtInFirst) builtIn + loaded else loaded + builtIn
            setRows(channelRows(all))
            setStatus(
                when {
                    error != null && all.isEmpty() -> "تعذر التحميل: $error"
                    error != null -> "تعذر تحديث القائمة ($error)"
                    else -> "${all.size} عنصر"
                }
            )

            if (autoPlay && !firstAutoPlayDone && all.isNotEmpty()) {
                firstAutoPlayDone = true
                autoSkipRemaining = 6 // skip dead channels automatically at start
                play(all.first())
            }
        }
    }

    /** Re-loads whatever is shown now. [force] = download the playlist again. */
    private fun reloadCurrent(force: Boolean) {
        val country = currentCountry
        when {
            showingFavorites -> {
                val favorites = prefs.favorites()
                setRows(channelRows(favorites))
                setStatus("${favorites.size} عنصر")
            }
            country != null -> loadCountry(country, force)
            section == Section.COUNTRIES -> showCountries()
            else -> loadSectionChannels(section, force, autoPlay = false)
        }
    }

    private fun toggleFavorites() {
        if (showingFavorites) {
            showingFavorites = false
            btnFav.active = false
            etSearch.setText("")
            val country = currentCountry
            when {
                country != null -> loadCountry(country)
                section == Section.COUNTRIES -> showCountries()
                else -> loadSectionChannels(section, force = false, autoPlay = false)
            }
        } else {
            showingFavorites = true
            btnFav.active = true
            loadJob?.cancel()
            etSearch.setText("")
            tvListTitle.text = getString(R.string.favorites)
            val favorites = prefs.favorites()
            setRows(channelRows(favorites))
            setStatus(
                if (favorites.isEmpty()) "لا توجد مفضلة بعد — اضغط على النجمة بجانب أي قناة"
                else "${favorites.size} عنصر"
            )
        }
        updateBackButton()
    }

    private fun goBack() {
        when {
            showingFavorites -> toggleFavorites()
            currentCountry != null -> {
                etSearch.setText("")
                showCountries()
            }
        }
    }

    private fun updateBackButton() {
        btnBack.visibility = if (showingFavorites || currentCountry != null) View.VISIBLE else View.INVISIBLE
    }

    private fun onRowClick(row: Row) {
        val country = row.country
        val channel = row.channel
        when {
            country != null -> loadCountry(country)
            channel != null -> play(channel)
        }
    }

    private fun onStarClick(row: Row) {
        val channel = row.channel ?: return
        val nowFavorite = prefs.toggleFavorite(channel)
        if (showingFavorites) {
            val favorites = prefs.favorites()
            setRows(channelRows(favorites))
            setStatus("${favorites.size} عنصر")
        } else {
            adapter.refresh()
            setStatus(if (nowFavorite) "أُضيفت «${channel.name}» إلى المفضلة" else "أُزيلت «${channel.name}» من المفضلة")
        }
    }

    private fun channelRows(channels: List<Channel>): List<Row> = channels.map {
        Row(
            title = it.name,
            subtitle = it.group,
            leading = it.name.trim().firstOrNull { ch -> ch.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "•",
            channel = it
        )
    }

    private fun setRows(rows: List<Row>) {
        currentRows = rows
        applyFilter()
    }

    private fun applyFilter() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
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

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun currentSourcesSignature(): String =
        DefaultData.sourceKeys.joinToString("|") { prefs.getSource(it) }

    // ================================================================== sleep timer

    private fun showSleepDialog() {
        val labels = arrayOf(
            "إيقاف المؤقت",
            "15 دقيقة", "30 دقيقة", "45 دقيقة", "60 دقيقة", "90 دقيقة", "120 دقيقة"
        )
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val title = if (SleepTimer.isActive) "مؤقت النوم (متبقي ${SleepTimer.remainingMinutes()} د)" else "مؤقت النوم"

        AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val chosen = minutes[which]
                if (chosen == 0) {
                    SleepTimer.cancel()
                    toast("تم إيقاف مؤقت النوم")
                } else {
                    SleepTimer.start(this, chosen)
                    toast("سيُغلق التطبيق تلقائياً بعد $chosen دقيقة")
                }
                btnSleep.active = SleepTimer.isActive
                enterImmersive()
            }
            .setNegativeButton("إغلاق") { _, _ -> enterImmersive() }
            .show()
    }

    // ================================================================== recording

    private fun toggleRecording() {
        val rec = recorder ?: StreamRecorder(applicationContext).also { r ->
            r.onWorkerEnded = { if (r.isRecording) stopRecording() }
            recorder = r
        }
        if (rec.isBusy) {
            toast("جارٍ حفظ التسجيل السابق…")
            return
        }
        if (rec.isRecording) {
            stopRecording()
            return
        }

        val channel = nowPlaying
        if (channel == null || !channel.url.startsWith("http", ignoreCase = true)) {
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
        if (rec.start(channel.url, channel.name)) {
            updateRecordingUi(true)
            toast("بدأ تسجيل: ${channel.name}")
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        updateRecordingUi(false)
        toast("جارٍ تحويل التسجيل إلى MP4 وحفظه…")
        rec.stopAndSave { path ->
            if (path != null) {
                toast("تم الحفظ في مجلد التنزيلات: $path")
            } else {
                toast("لم يُحفظ التسجيل (قصير جداً أو تعذر الحفظ)")
            }
        }
    }

    private fun updateRecordingUi(recording: Boolean) {
        btnRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        if (recording) {
            tvRec.visibility = View.VISIBLE
            recBlink?.cancel()
            recBlink = ObjectAnimator.ofFloat(tvRec, "alpha", 1f, 0.15f).apply {
                duration = 600L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            recBlink?.cancel()
            tvRec.alpha = 1f
            tvRec.visibility = View.GONE
        }
    }
}
