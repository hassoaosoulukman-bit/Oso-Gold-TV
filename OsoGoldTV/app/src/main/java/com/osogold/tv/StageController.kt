package com.osogold.tv

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Full screen stage with the professional dual screen (picture in picture).
 *
 *  BIG screen   : always full screen. It is the ONLY screen with sound. It is played by the main
 *                 engine (Media3 or LibVLC, see [Host]).
 *  SMALL screen : floating in the bottom left corner (can be moved to the right), 3 sizes, its
 *                 own Media3 player which is muted all the time (the volume is forced back to 0
 *                 whenever anything changes it).
 *  SWAP         : exchanges the two channels; the sound always stays with the big screen.
 *  OVERLAY      : appears when the big screen is touched: two buttons on top (big screen / small
 *                 screen, each with its current channel), the tools (quality, auto quality,
 *                 video shape, player engine, dual on/off, back) and two completely separate
 *                 vertical lists: the CHANNELS on the left, the SECTIONS on the right.
 *  GESTURES     : swipe up / down on the right half = volume, left half = brightness. They are
 *                 attached to a touch layer that lies BEHIND the small screen and the overlay, so
 *                 they never collide with the small screen or with scrolling the lists.
 */
class StageController(
    private val activity: BaseActivity,
    private val root: View,
    private val prefs: Prefs,
    indicator: TextView,
    private val host: Host
) {

    interface Host {
        /** The channel that plays on the big screen. */
        fun bigChannel(): Channel?

        /** Plays a channel on the big screen. */
        fun playBig(channel: Channel)

        /** Moves the picture of the big screen between the main screen circle and the stage. */
        fun moveBig(toStage: Boolean)

        /** Quality choices of the running stream (label + action). Empty = nothing to choose. */
        fun qualityEntries(): List<Pair<String, () -> Unit>>

        /** Back to automatic quality. */
        fun autoQuality()

        /** Switches between Media3 and LibVLC; returns the name of the engine now in use. */
        fun toggleEngine(): String

        /** The video shape has changed (0 fill, 1 cinema, 2 original, 3 stretch). */
        fun onAspectChanged(mode: Int)

        /** Play / pause (the ONLY way to pause: touching the screen never pauses). */
        fun togglePlayPause()

        /** Starts / stops the recording of what the big screen plays. */
        fun toggleRecording()

        /** Opens the subtitle control (language, size, colour, position). */
        fun openSubtitles()

        /** The stage was closed ("smart back"): the app stays open on the main screen. */
        fun onStageClosed()
    }

    enum class Target { BIG, SMALL }

    // ------------------------------------------------------------------ views

    val bigView: PlayerView = root.findViewById(R.id.stageBig)
    val vlcView: VLCVideoLayout = root.findViewById(R.id.stageVlc)
    private val touchLayer: View = root.findViewById(R.id.stageTouch)

    private val pipContainer: LinearLayout = root.findViewById(R.id.pipContainer)
    private val pipStrip: LinearLayout = root.findViewById(R.id.pipStrip)
    private val pipFrame: FrameLayout = root.findViewById(R.id.pipFrame)
    private val pipView: PlayerView = root.findViewById(R.id.pipView)
    private val pipHint: TextView = root.findViewById(R.id.pipHint)
    private val btnPipM: ImageView = root.findViewById(R.id.btnPipM)
    private val btnPipS: ImageView = root.findViewById(R.id.btnPipS)
    private val btnPipXs: ImageView = root.findViewById(R.id.btnPipXs)
    private val btnPipSwap: ImageView = root.findViewById(R.id.btnPipSwap)
    private val btnPipSide: ImageView = root.findViewById(R.id.btnPipSide)
    private val btnPipOff: ImageView = root.findViewById(R.id.btnPipOff)

    private val overlayRoot: View = root.findViewById(R.id.overlayRoot)
    private val overlayPanel: View = root.findViewById(R.id.overlayPanel)
    private val ovTargetBig: TextView = root.findViewById(R.id.ovTargetBig)
    private val ovTargetSmall: TextView = root.findViewById(R.id.ovTargetSmall)
    private val ovBtnQuality: GoldIconButton = root.findViewById(R.id.ovBtnQuality)
    private val ovBtnAuto: GoldIconButton = root.findViewById(R.id.ovBtnAuto)
    private val ovBtnAspect: GoldIconButton = root.findViewById(R.id.ovBtnAspect)
    private val ovBtnEngine: GoldIconButton = root.findViewById(R.id.ovBtnEngine)
    private val ovBtnPlay: GoldIconButton = root.findViewById(R.id.ovBtnPlay)
    private val ovBtnSub: GoldIconButton = root.findViewById(R.id.ovBtnSub)
    private val ovBtnRec: GoldIconButton = root.findViewById(R.id.ovBtnRec)
    private val ovRecTimer: TextView = root.findViewById(R.id.ovRecTimer)
    private val ovBtnDual: GoldIconButton = root.findViewById(R.id.ovBtnDual)
    private val ovBtnBack: GoldIconButton = root.findViewById(R.id.ovBtnBack)
    private val tvChannelTitle: TextView = root.findViewById(R.id.tvChannelTitle)
    private val tvSectionTitle: TextView = root.findViewById(R.id.tvSectionTitle)
    private val channelList: RecyclerView = root.findViewById(R.id.channelList)
    private val sectionList: RecyclerView = root.findViewById(R.id.sectionList)

    // ------------------------------------------------------------------ state

    var isOpen = false
        private set
    var isDual = false
        private set

    private var target = Target.BIG
    private var pipPlayer: ExoPlayer? = null
    private var pipChannel: Channel? = null
    private var pipJob: Job? = null

    private var sections: List<SectionDef> = emptyList()
    private var currentSection: SectionDef? = null
    private var lastChannelRows: List<Row> = emptyList()
    private var lastChannelTitle = ""
    private var loadJob: Job? = null

    private var rootWidth = 0
    private var safeLeft = 0
    private var safeRight = 0
    private var safeBottom = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideStripRunnable = Runnable { pipStrip.visibility = View.GONE }

    private val density = activity.resources.displayMetrics.density

    private val sectionAdapter = RowAdapter(
        onClick = { row -> onSectionClick(row) },
        onStar = { },
        isFavorite = { false }
    )
    private val channelAdapter = RowAdapter(
        onClick = { row -> onChannelClick(row) },
        onStar = { row -> onStar(row) },
        isFavorite = { channel -> prefs.isFavorite(channel.url) }
    )

    init {
        sectionList.layoutManager = LinearLayoutManager(activity)
        sectionList.adapter = sectionAdapter
        sectionList.itemAnimator = null
        channelList.layoutManager = LinearLayoutManager(activity)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null

        // volume (right) / brightness (left) swipes + tap = show / hide the overlay
        touchLayer.setOnTouchListener(PlayerGestures(activity, indicator) { toggleOverlay() })

        // keep the layout correct for the screen size / rotation / camera cut-out
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val w = right - left
            if (w > 0 && w != rootWidth) {
                rootWidth = w
                layoutPanel()
                applyPipLayout()
                applyAspect()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            safeLeft = cut.left
            safeRight = cut.right
            safeBottom = cut.bottom
            applyPipLayout()
            insets
        }

        // small screen buttons
        pipFrame.setOnClickListener { showStrip() }
        btnPipM.setOnClickListener { setPipSize(0) }
        btnPipS.setOnClickListener { setPipSize(1) }
        btnPipXs.setOnClickListener { setPipSize(2) }
        btnPipSwap.setOnClickListener {
            swap()
            showStrip()
        }
        btnPipSide.setOnClickListener {
            prefs.pipRight = !prefs.pipRight
            applyPipLayout()
            showStrip()
        }
        btnPipOff.setOnClickListener { setDual(false) }

        // the two target buttons
        ovTargetBig.setOnClickListener {
            target = Target.BIG
            updateTargets()
        }
        ovTargetSmall.setOnClickListener {
            if (!isDual) setDual(true)
            target = Target.SMALL
            updateTargets()
        }

        // tools of the big screen
        ovBtnQuality.setOnClickListener { showQualityDialog() }
        ovBtnAuto.setOnClickListener {
            host.autoQuality()
            activity.toast("الجودة: تلقائي (Auto)")
        }
        ovBtnAspect.setOnClickListener { showAspectDialog() }
        ovBtnEngine.setOnClickListener {
            val name = host.toggleEngine()
            activity.toast("المشغل الحالي: $name")
        }
        ovBtnPlay.setOnClickListener { host.togglePlayPause() }
        ovBtnSub.setOnClickListener { host.openSubtitles() }
        ovBtnRec.setOnClickListener { host.toggleRecording() }
        ovBtnDual.setOnClickListener { setDual(!isDual) }
        // smart back: returns to the main screen, the app stays open
        ovBtnBack.setOnClickListener { close() }

        updateTargets()
        updatePipSizeButtons()
    }

    // ================================================================== open / close

    /** Opens the full screen stage. [dual] = also switch the small screen on. */
    fun open(dual: Boolean) {
        if (!isOpen) {
            isOpen = true
            root.visibility = View.VISIBLE
            host.moveBig(true)
            applyAspect()
            layoutPanel()
            showSections()
        }
        setDual(dual)
        if (!dual) {
            target = Target.BIG
            updateTargets()
            hideOverlay()
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideOverlay()
        handler.removeCallbacks(hideStripRunnable)
        releasePip()
        isDual = false
        pipContainer.visibility = View.GONE
        root.visibility = View.GONE
        host.moveBig(false)
        host.onStageClosed()
    }

    /** Back button: closes the overlay first, then the stage (never the app). */
    fun onBack() {
        if (overlayRoot.visibility == View.VISIBLE) hideOverlay() else close()
    }

    /** The main activity went to the background: free the second decoder. */
    fun onHostStop() {
        releasePip()
    }

    /** The main activity is visible again: bring the small screen back. */
    fun onHostStart() {
        if (isOpen && isDual) pipChannel?.let { playSmall(it) }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        loadJob?.cancel()
        pipJob?.cancel()
        releasePip()
    }

    /** Called when the channel of the big screen has changed. */
    fun onBigChanged() {
        refreshTargets()
    }

    /** Shows the running recording time on the overlay (null = not recording). */
    fun setRecordingText(text: String?) {
        ovBtnRec.active = text != null
        if (text == null) {
            ovRecTimer.visibility = View.GONE
        } else {
            ovRecTimer.visibility = View.VISIBLE
            ovRecTimer.text = "● $text"
        }
    }

    /** Any touch on the screen keeps the overlay open. */
    fun touch() {
        if (isOpen && overlayRoot.visibility == View.VISIBLE) scheduleHideOverlay()
    }

    /** The engine of the big screen changed: show the right video view. */
    fun showEngineView(vlc: Boolean) {
        bigView.visibility = if (vlc) View.GONE else View.VISIBLE
        vlcView.visibility = if (vlc) View.VISIBLE else View.GONE
    }

    // ================================================================== overlay

    private fun toggleOverlay() {
        if (overlayRoot.visibility == View.VISIBLE) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        overlayRoot.visibility = View.VISIBLE
        if (sectionAdapter.itemCount == 0) showSections()
        refreshTargets()
        scheduleHideOverlay()
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        overlayRoot.visibility = View.GONE
    }

    private fun scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun updateTargets() {
        ovBtnDual.active = isDual
        refreshTargets()
    }

    /** Updates the two buttons on top (selected one glows, each shows its current channel). */
    private fun refreshTargets() {
        val bigName = host.bigChannel()?.name ?: "—"
        val smallName = if (isDual) (pipChannel?.name ?: "—") else "غير مفعّلة"
        ovTargetBig.text = activity.getString(R.string.ov_target_big) + "\n" + bigName
        ovTargetSmall.text = activity.getString(R.string.ov_target_small) + "\n" + smallName
        ovTargetBig.setBackgroundResource(
            if (target == Target.BIG) R.drawable.bg_target_active else R.drawable.bg_target
        )
        ovTargetSmall.setBackgroundResource(
            if (target == Target.SMALL) R.drawable.bg_target_active else R.drawable.bg_target
        )
        channelAdapter.playingUrl =
            if (target == Target.BIG) host.bigChannel()?.url else pipChannel?.url
    }

    /** The lists never reach into the corner where the small screen lives. */
    private fun layoutPanel() {
        val w = if (rootWidth > 0) rootWidth else activity.resources.displayMetrics.widthPixels
        val reserve = pipWidth(w) + dp(26)
        val panelWidth = (w - 2 * reserve).coerceAtLeast(dp(340)).coerceAtMost(w - dp(16))
        val params = overlayPanel.layoutParams as FrameLayout.LayoutParams
        if (params.width != panelWidth) {
            params.width = panelWidth
            overlayPanel.layoutParams = params
        }
    }

    // ================================================================== sections list (right)

    private fun showSections() {
        loadJob?.cancel()
        currentSection = null
        sections = Catalog.sections(prefs)
        tvSectionTitle.text = activity.getString(R.string.sec_sections)
        sectionAdapter.submit(
            sections.map { Row(title = it.title, leading = it.badge, tag = "sec:${it.id}") }
        )
        if (lastChannelRows.isEmpty()) tvChannelTitle.text = activity.getString(R.string.channels_title)
    }

    private fun onSectionClick(row: Row) {
        when {
            row.tag == "back" -> showSections()
            row.tag.startsWith("sec:") -> {
                val id = row.tag.removePrefix("sec:")
                sections.firstOrNull { it.id == id }?.let { openSection(it) }
            }
            row.tag.startsWith("pkg:") -> {
                val def = currentSection ?: return
                val id = row.tag.removePrefix("pkg:")
                loadJob?.cancel()
                loadJob = activity.lifecycleScope.launch { fetchChannels(def, id, row.title) }
            }
        }
    }

    private fun openSection(def: SectionDef) {
        currentSection = def
        tvSectionTitle.text = def.title
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            tvChannelTitle.text = "جارٍ التحميل…"
            try {
                val packages = Catalog.packages(prefs, def.id)
                if (packages == null) {
                    fetchChannels(def, null, def.title)
                } else {
                    val rows = ArrayList<Row>()
                    rows.add(Row(title = "رجوع", leading = "↩", tag = "back"))
                    packages.forEach {
                        rows.add(
                            Row(
                                title = it.title,
                                leading = it.leading.ifEmpty { "•" },
                                tag = "pkg:${it.id}"
                            )
                        )
                    }
                    sectionAdapter.submit(rows)
                    channelAdapter.submit(emptyList())
                    lastChannelRows = emptyList()
                    tvChannelTitle.text = "اختر باقة"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvChannelTitle.text = "تعذر التحميل"
                activity.toast("تعذر التحميل: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ================================================================== channels list (left)

    private suspend fun fetchChannels(def: SectionDef, pkgId: String?, title: String) {
        tvChannelTitle.text = "جارٍ التحميل…"
        channelAdapter.submit(emptyList())
        val list = try {
            Catalog.channels(prefs, def.id, pkgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tvChannelTitle.text = "تعذر التحميل"
            activity.toast("تعذر التحميل: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val rows = channelRows(list)
        lastChannelRows = rows
        lastChannelTitle = "$title (${list.size})"
        tvChannelTitle.text = lastChannelTitle
        channelAdapter.submit(rows)
        refreshTargets()
    }

    private fun channelRows(list: List<Channel>): List<Row> = list.map {
        Row(
            title = it.name,
            subtitle = it.group,
            leading = it.name.trim().firstOrNull { ch -> ch.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "•",
            channel = it
        )
    }

    private fun onChannelClick(row: Row) {
        if (row.tag == "back") {
            channelAdapter.submit(lastChannelRows)
            tvChannelTitle.text = lastChannelTitle
            return
        }
        val channel = row.channel ?: return
        if (channel.seriesId.isNotEmpty()) {
            openEpisodes(channel)
            return
        }
        playOn(target, channel)
    }

    private fun openEpisodes(series: Channel) {
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            tvChannelTitle.text = "جارٍ التحميل…"
            try {
                val episodes = Catalog.episodes(prefs, series.seriesId)
                val rows = ArrayList<Row>()
                rows.add(Row(title = "رجوع", leading = "↩", tag = "back"))
                rows.addAll(channelRows(episodes))
                channelAdapter.submit(rows)
                tvChannelTitle.text = series.name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvChannelTitle.text = "تعذر التحميل"
                activity.toast("تعذر تحميل الحلقات: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun onStar(row: Row) {
        val channel = row.channel ?: return
        val id = currentSection?.id.orEmpty()
        val kind = when {
            id == "radio" || id == "quran" -> "radio"
            id == "movies" || id == "x_vod" -> "movie"
            id == "series" || id == "x_series" -> "series"
            else -> ""
        }
        prefs.toggleFavorite(channel, kind)
        channelAdapter.refresh()
    }

    /**
     * Changes the channel of ONE screen immediately. The other screen keeps playing without any
     * interruption.
     */
    private fun playOn(which: Target, channel: Channel) {
        if (which == Target.BIG) {
            host.playBig(channel)
        } else {
            if (!isDual) setDual(true)
            playSmall(channel)
        }
        refreshTargets()
        scheduleHideOverlay()
    }

    // ================================================================== small screen

    fun setDual(enabled: Boolean) {
        isDual = enabled
        pipContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            applyPipLayout()
            updatePipSizeButtons()
            val channel = pipChannel
            if (channel != null) {
                playSmall(channel)
            } else {
                pipHint.text = "اختر قناة للشاشة الصغيرة"
                pipHint.visibility = View.VISIBLE
                target = Target.SMALL
                showOverlay()
            }
            showStrip()
        } else {
            releasePip()
            handler.removeCallbacks(hideStripRunnable)
            pipStrip.visibility = View.GONE
            if (target == Target.SMALL) target = Target.BIG
        }
        updateTargets()
    }

    private fun ensurePip(): ExoPlayer {
        pipPlayer?.let { return it }
        val player = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(NetConfig.dataSourceFactory(activity)))
            // false = the small screen never takes the audio focus away from the big one
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
        player.volume = 0f
        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                // fixed rule: the small screen is ALWAYS muted
                if (volume != 0f) player.volume = 0f
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) pipHint.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                pipHint.text = "تعذر تشغيل القناة"
                pipHint.visibility = View.VISIBLE
            }
        })
        pipView.player = player
        pipPlayer = player
        return player
    }

    private fun releasePip() {
        pipJob?.cancel()
        pipView.player = null
        pipPlayer?.release()
        pipPlayer = null
    }

    private fun playSmall(channel: Channel) {
        pipChannel = channel
        pipHint.text = "جارٍ التشغيل…"
        pipHint.visibility = View.VISIBLE
        pipJob?.cancel()
        pipJob = activity.lifecycleScope.launch {
            val url = try {
                Catalog.resolve(prefs, channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pipHint.text = "تعذر فتح القناة"
                return@launch
            }
            val player = ensurePip()
            player.volume = 0f
            player.setMediaItem(buildMediaItem(channel, url))
            player.prepare()
            player.playWhenReady = true
        }
        refreshTargets()
    }

    /** Big <-> small. The picture AND the sound swap: the sound always stays with the big screen. */
    private fun swap() {
        val small = pipChannel
        if (small == null) {
            activity.toast("لا توجد قناة في الشاشة الصغيرة")
            return
        }
        val big = host.bigChannel()
        host.playBig(small)
        if (big != null) {
            playSmall(big)
        } else {
            pipChannel = null
            releasePip()
            pipHint.text = "اختر قناة للشاشة الصغيرة"
            pipHint.visibility = View.VISIBLE
        }
        pipPlayer?.volume = 0f
        refreshTargets()
    }

    private fun setPipSize(index: Int) {
        prefs.pipSize = index
        applyPipLayout()
        updatePipSizeButtons()
        layoutPanel()
        showStrip()
    }

    private fun updatePipSizeButtons() {
        val size = prefs.pipSize
        btnPipM.alpha = if (size == 0) 1f else 0.45f
        btnPipS.alpha = if (size == 1) 1f else 0.45f
        btnPipXs.alpha = if (size == 2) 1f else 0.45f
    }

    private fun showStrip() {
        pipStrip.visibility = View.VISIBLE
        handler.removeCallbacks(hideStripRunnable)
        handler.postDelayed(hideStripRunnable, STRIP_TIMEOUT_MS)
    }

    private fun pipWidth(screenWidth: Int): Int =
        (screenWidth * PIP_FRACTIONS[prefs.pipSize]).toInt()

    /** Size and corner of the small screen. The margins respect the camera cut-out. */
    private fun applyPipLayout() {
        val w = if (rootWidth > 0) rootWidth else activity.resources.displayMetrics.widthPixels
        val width = pipWidth(w)
        val height = width * 9 / 16
        val right = prefs.pipRight
        val side = if (right) Gravity.END else Gravity.START

        val frameParams = LinearLayout.LayoutParams(width, height)
        frameParams.gravity = side
        pipFrame.layoutParams = frameParams

        val stripParams = pipStrip.layoutParams as LinearLayout.LayoutParams
        stripParams.gravity = side
        pipStrip.layoutParams = stripParams

        val containerParams = pipContainer.layoutParams as FrameLayout.LayoutParams
        containerParams.gravity = Gravity.BOTTOM or side
        val margin = dp(10)
        containerParams.setMargins(
            if (right) 0 else margin + safeLeft,
            0,
            if (right) margin + safeRight else 0,
            margin + safeBottom
        )
        pipContainer.layoutParams = containerParams
    }

    // ================================================================== quality

    private fun showQualityDialog() {
        val entries = host.qualityEntries()
        if (entries.isEmpty()) {
            activity.toast("لا توجد جودات متعددة لهذا البث")
            return
        }
        val labels = (listOf("تلقائي (Auto)") + entries.map { it.first }).toTypedArray()
        val dialog = AlertDialog.Builder(activity, R.style.GoldDialog)
            .setTitle("جودة البث")
            .setItems(labels) { _, which ->
                if (which == 0) host.autoQuality() else entries[which - 1].second.invoke()
            }
            .setNegativeButton("إغلاق", null)
            .create()
        activity.showImmersive(dialog)
    }

    // ================================================================== video shape

    private fun showAspectDialog() {
        val dialog = AlertDialog.Builder(activity, R.style.GoldDialog)
            .setTitle("أبعاد عرض الفيديو")
            .setSingleChoiceItems(ASPECT_NAMES, prefs.aspectMode) { d, which ->
                prefs.aspectMode = which
                applyAspect()
                d.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .create()
        activity.showImmersive(dialog)
    }

    /** 0 fill screen, 1 cinema, 2 original, 3 stretch. Applies to the Media3 and the LibVLC view. */
    private fun applyAspect() {
        val mode = prefs.aspectMode
        val w = if (rootWidth > 0) rootWidth else activity.resources.displayMetrics.widthPixels
        for (view in listOf<View>(bigView, vlcView)) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            if (mode == 1) {
                params.height = (w / 2.39f).toInt()
                params.gravity = Gravity.CENTER
            } else {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.NO_GRAVITY
            }
            view.layoutParams = params
        }
        bigView.resizeMode = when (mode) {
            0, 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        host.onAspectChanged(mode)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val OVERLAY_TIMEOUT_MS = 9_000L
        private const val STRIP_TIMEOUT_MS = 4_500L

        /** Width of the small screen relative to the screen: medium, small, smallest. */
        private val PIP_FRACTIONS = floatArrayOf(0.25f, 0.18f, 0.12f)

        private val ASPECT_NAMES = arrayOf(
            "ملء الشاشة (Fill)",
            "عرض سينمائي (2.39:1)",
            "أصلي (Original)",
            "تمدد (Stretch)"
        )
    }
}
