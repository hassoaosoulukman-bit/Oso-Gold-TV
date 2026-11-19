package com.osogold.tv

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings screen. Same golden circular icon style as the main screen:
 *  - Password            : write and save a new password
 *  - Welcome video       : pick any video from the gallery OR record a new one with the camera
 *  - Xtream subscription : server + user + password, optional pinned server
 *  - M3U lists           : default section links, add a list by link, upload a list file
 *  - Stalker (MAC)       : portal address + MAC
 *  - Player identity     : custom User-Agent (IPTVSmartersPro, VLC, ExoPlayer, any text)
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvInfo: TextView
    private var captureFile: File? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importVideo(uri)
    }

    private val pickM3uFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importM3uFile(uri)
    }

    private val captureVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val file = captureFile
        if (ok && file != null && file.exists() && file.length() > 0L) {
            replaceWelcomeVideo(file)
            toast("تم حفظ الفيديو المصوَّر كفيديو ترحيبي")
            warnIfShort(file)
            updateInfo()
        } else {
            file?.delete()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture() else toast("صلاحية الكاميرا مطلوبة لتصوير الفيديو")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        tvInfo = findViewById(R.id.tvSettingsInfo)

        findViewById<View>(R.id.btnPassword).setOnClickListener { showPasswordDialog() }
        findViewById<View>(R.id.btnWelcome).setOnClickListener { showWelcomeVideoDialog() }
        findViewById<View>(R.id.btnXtream).setOnClickListener { showXtreamDialog() }
        findViewById<View>(R.id.btnM3u).setOnClickListener { showM3uMenu() }
        findViewById<View>(R.id.btnStalker).setOnClickListener { showStalkerDialog() }
        findViewById<View>(R.id.btnAgent).setOnClickListener { showAgentDialog() }
        findViewById<View>(R.id.btnEngine).setOnClickListener { showEngineDialog() }
        findViewById<View>(R.id.btnRecordings).setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        findViewById<View>(R.id.btnSleep).setOnClickListener { showSleepDialog() }
        findViewById<View>(R.id.btnRepair).setOnClickListener { runRepair() }
        findViewById<View>(R.id.btnAssistant).setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { finish() }
        updateInfo()
    }

    private fun updateInfo() {
        val video = if (prefs.welcomeVideoFile() != null) "مخصّص" else "افتراضي"
        val xtream = if (prefs.xtreamConfigured()) "مفعّل" else "غير مفعّل"
        val stalker = if (prefs.stalkerConfigured()) "مفعّل" else "غير مفعّل"
        tvInfo.text = "الفيديو الترحيبي: $video   |   Xtream: $xtream   |   Stalker: $stalker\n" +
            "بطاقة المشغل: ${prefs.userAgent}"
    }

    // ------------------------------------------------------------------ password

    private fun showPasswordDialog() {
        val box = formBox()
        val etNew = styledEdit("كلمة السر الجديدة", password = true)
        val etConfirm = styledEdit("تأكيد كلمة السر", password = true)
        box.addView(etNew)
        box.addView(etConfirm)

        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("تغيير كلمة السر")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = etNew.text.toString()
                val second = etConfirm.text.toString()
                when {
                    first.length < 4 -> etNew.error = "4 أحرف على الأقل"
                    first != second -> etConfirm.error = "كلمتا السر غير متطابقتين"
                    else -> {
                        prefs.changePassword(first)
                        dialog.dismiss()
                        toast("تم حفظ كلمة السر. ستُطلب عند الفتح القادم")
                    }
                }
            }
        }
        showImmersive(dialog)
    }

    // ------------------------------------------------------------------ welcome video

    private fun showWelcomeVideoDialog() {
        val options = arrayOf(
            "اختيار فيديو من المعرض / الملفات",
            "تصوير فيديو جديد بكاميرا الجوال",
            "إعادة الفيديو الافتراضي"
        )
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("الفيديو الترحيبي")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickVideo.launch("video/*")
                    1 -> startCapture()
                    2 -> {
                        deleteWelcomeVideo()
                        prefs.welcomeVideoPath = null
                        updateInfo()
                        toast("تمت إعادة الفيديو الافتراضي")
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun startCapture() {
        if (Permissions.hasCamera(this)) launchCapture() else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun launchCapture() {
        val dir = File(filesDir, "captured")
        dir.mkdirs()
        val file = File(dir, "welcome_${System.currentTimeMillis()}.mp4")
        file.createNewFile()
        captureFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            captureVideo.launch(uri)
        } catch (e: Exception) {
            toast("لا يوجد تطبيق كاميرا لتصوير الفيديو")
        }
    }

    /** Copies the chosen video into the app storage so it keeps working even if the original is moved. */
    private fun importVideo(uri: Uri) {
        toast("جارٍ حفظ الفيديو…")
        lifecycleScope.launch {
            val target = File(filesDir, "welcome_${System.currentTimeMillis()}.video")
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok && target.length() > 0L) {
                replaceWelcomeVideo(target)
                toast("تم حفظ الفيديو الترحيبي")
                warnIfShort(target)
            } else {
                target.delete()
                toast("تعذر حفظ الفيديو")
            }
            updateInfo()
        }
    }

    private fun replaceWelcomeVideo(newFile: File) {
        deleteWelcomeVideo()
        prefs.welcomeVideoPath = newFile.absolutePath
    }

    private fun deleteWelcomeVideo() {
        val old = prefs.welcomeVideoPath ?: return
        try {
            File(old).delete()
        } catch (_: Exception) {
        }
    }

    private fun warnIfShort(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return
            if (duration < 30_000L) {
                toast("ملاحظة: مدة الفيديو أقل من 30 ثانية")
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ Xtream Codes

    private fun showXtreamDialog() {
        val pinned = prefs.isServerPinned()
        val box = formBox()

        val etServer = styledEdit("http://server.com:8080", password = false)
        etServer.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etServer.setText(prefs.xtreamServer)

        val etUser = styledEdit("اسم المستخدم", password = false)
        etUser.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        etUser.setText(prefs.xtreamUser)

        val etPass = styledEdit("كلمة المرور", password = false)
        etPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        etPass.setText(prefs.xtreamPass)

        val etName = styledEdit("اسم الاشتراك (يظهر في قوائمي)", password = false)
        etName.setText(prefs.xtreamName)

        // smart login: paste any long link (get.php / player_api / live/user/pass/...) and the fields fill themselves
        val etSmart = styledEdit("الصق الرابط الطويل هنا (اختياري)", password = false)
        etSmart.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etSmart.doAfterTextChanged { text ->
            val account = SmartLink.parse(text?.toString().orEmpty()) ?: return@doAfterTextChanged
            if (!pinned) etServer.setText(account.server)
            etUser.setText(account.user)
            etPass.setText(account.pass)
        }

        val cbPin = CheckBox(this).apply {
            text = "تثبيت هذا السيرفر كافتراضي (الدخول بالاسم وكلمة المرور فقط)"
            setTextColor(getColor(R.color.gold_light))
            isChecked = prefs.xtreamPinned
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        if (pinned) {
            box.addView(TextView(this).apply {
                text = "السيرفر المثبّت:\n${prefs.effectiveXtreamServer()}"
                setTextColor(getColor(R.color.gold))
                textSize = 12f
                gravity = Gravity.CENTER
            })
        } else {
            box.addView(etServer)
            box.addView(cbPin)
        }
        box.addView(etUser)
        box.addView(etPass)
        box.addView(etName)
        box.addView(etSmart)

        val builder = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("اشتراك Xtream Codes")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)

        if (prefs.xtreamConfigured()) {
            builder.setNeutralButton("حذف الاشتراك") { _, _ ->
                prefs.clearXtreamAccount()
                toast("تم حذف الاشتراك")
                updateInfo()
            }
        } else if (pinned && DefaultData.XTREAM_DEFAULT_SERVER.isBlank()) {
            builder.setNeutralButton("تغيير السيرفر") { _, _ ->
                prefs.unpinXtreamServer()
                showXtreamDialog()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = if (pinned) prefs.effectiveXtreamServer() else etServer.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                when {
                    server.isEmpty() -> etServer.error = "أدخل رابط السيرفر"
                    user.isEmpty() -> etUser.error = "أدخل اسم المستخدم"
                    pass.isEmpty() -> etPass.error = "أدخل كلمة المرور"
                    else -> {
                        if (!pinned) {
                            prefs.xtreamServer = server
                            prefs.xtreamPinned = cbPin.isChecked
                        }
                        prefs.xtreamUser = user
                        prefs.xtreamPass = pass
                        prefs.xtreamName = etName.text.toString().trim()
                        dialog.dismiss()
                        testXtream()
                    }
                }
            }
        }
        showImmersive(dialog)
    }

    private fun testXtream() {
        toast("جارٍ الاتصال بالسيرفر…")
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { XtreamClient.accountInfo(prefs) }
                updateInfo()
                showMessage("تم الاشتراك بنجاح", info + "\n\nستجد القنوات في الشاشة الرئيسية ← الاشتراكات.")
            } catch (e: Exception) {
                updateInfo()
                showMessage(
                    "تعذر الاتصال",
                    (e.message ?: e.javaClass.simpleName) +
                        "\n\nتم حفظ البيانات. تأكد من الرابط والاسم وكلمة المرور، أو جرّب بطاقة مشغل أخرى."
                )
            }
        }
    }

    // ------------------------------------------------------------------ M3U lists

    private fun showM3uMenu() {
        val options = arrayOf(
            "روابط الأقسام الافتراضية (بث، أفلام، مسلسلات، راديو، قرآن)",
            "إضافة قائمة برابط (M3U / M3U8)",
            "رفع ملف قائمة من الهاتف (M3U / M3U8)",
            "قوائمي المضافة (حذف)"
        )
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("قوائم M3U")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSourcesDialog()
                    1 -> showAddUrlDialog()
                    2 -> pickM3uFile.launch("*/*")
                    3 -> showCustomListsDialog()
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun showAddUrlDialog() {
        val box = formBox()
        val etName = styledEdit("اسم القائمة", password = false)
        val etUrl = styledEdit("https://example.com/list.m3u", password = false)
        etUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        box.addView(etName)
        box.addView(etUrl)

        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("إضافة قائمة برابط")
            .setView(box)
            .setPositiveButton("إضافة", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = etUrl.text.toString().trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    etUrl.error = "الرابط يجب أن يبدأ بـ http:// أو https://"
                    return@setOnClickListener
                }
                prefs.addCustomList(etName.text.toString(), url)
                dialog.dismiss()
                updateInfo()
                toast("تمت إضافة القائمة. ستجدها في الرئيسية ← الاشتراكات")
            }
        }
        showImmersive(dialog)
    }

    /** Copies a list file from the phone into the app and registers it. */
    private fun importM3uFile(uri: Uri) {
        lifecycleScope.launch {
            val dir = File(filesDir, "m3u")
            dir.mkdirs()
            val target = File(dir, "list_${System.currentTimeMillis()}.m3u")
            val name = displayName(uri)
            val count = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    M3uParser.parse(target.readText(Charsets.UTF_8)).size
                } catch (e: Exception) {
                    0
                }
            }
            if (count > 0) {
                prefs.addCustomList(name, "file:" + target.absolutePath)
                updateInfo()
                toast("تمت إضافة «$name» ($count عنصر). ستجدها في الرئيسية ← الاشتراكات")
            } else {
                target.delete()
                toast("الملف لا يحتوي على قائمة M3U صالحة")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val raw = cursor.getString(index)
                    if (!raw.isNullOrBlank()) return raw.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {
        }
        return "قائمة من الهاتف"
    }

    private fun showCustomListsDialog() {
        val lists = prefs.customLists()
        if (lists.isEmpty()) {
            toast("لا توجد قوائم مضافة بعد")
            return
        }
        val labels = lists.map {
            it.name + "\n" + (if (it.value.startsWith("file:")) "ملف من الهاتف" else it.value)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("قوائمي المضافة — اضغط للحذف")
            .setItems(labels) { _, which ->
                val chosen = lists[which]
                val confirm = AlertDialog.Builder(this, R.style.GoldDialog)
                    .setTitle("حذف القائمة")
                    .setMessage("هل تريد حذف «${chosen.name}»؟")
                    .setPositiveButton("حذف") { _, _ ->
                        prefs.removeCustomList(chosen.id)
                        PlaylistRepository.clearCache()
                        toast("تم الحذف")
                    }
                    .setNegativeButton("إلغاء", null)
                    .create()
                showImmersive(confirm)
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun showSourcesDialog() {
        val keys = DefaultData.sourceKeys
        val labels = keys.map { key ->
            val url = prefs.getSource(key)
            DefaultData.sourceTitle(key) + "\n" + (if (url.isBlank()) "— (لا يوجد رابط)" else url)
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("روابط الأقسام الافتراضية")
            .setItems(labels) { _, which -> editSource(keys[which]) }
            .setNeutralButton("استعادة الافتراضي") { _, _ ->
                prefs.resetSources()
                toast("تمت استعادة الروابط الافتراضية")
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun editSource(key: String) {
        val box = formBox()
        val et = styledEdit("https://example.com/list.m3u", password = false)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        et.setText(prefs.getSource(key))
        box.addView(et)

        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle(DefaultData.sourceTitle(key))
            .setMessage("رابط قائمة M3U (يمكن تحديثه في أي وقت)")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.setSource(key, et.text.toString())
                toast("تم الحفظ. ستُحمَّل القائمة عند العودة للشاشة الرئيسية")
            }
            .setNegativeButton("إلغاء", null)
            .create()
        showImmersive(dialog)
    }

    // ------------------------------------------------------------------ Stalker portal

    private fun showStalkerDialog() {
        val box = formBox()
        val etPortal = styledEdit("http://portal.com:8080/c/", password = false)
        etPortal.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etPortal.setText(prefs.stalkerPortal)
        val etMac = styledEdit("00:1A:79:XX:XX:XX", password = false)
        etMac.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        etMac.setText(prefs.stalkerMac)
        val etSName = styledEdit("اسم البوابة (يظهر في قوائمي)", password = false)
        etSName.setText(prefs.stalkerName)
        box.addView(etPortal)
        box.addView(etMac)
        box.addView(etSName)

        val builder = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("Stalker Portal (MAC)")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
        if (prefs.stalkerConfigured()) {
            builder.setNeutralButton("حذف") { _, _ ->
                prefs.clearStalker()
                toast("تم حذف بوابة Stalker")
                updateInfo()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val portal = etPortal.text.toString().trim()
                val mac = etMac.text.toString().trim().uppercase()
                val macPattern = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
                when {
                    portal.isEmpty() -> etPortal.error = "أدخل رابط البوابة"
                    !macPattern.matches(mac) -> etMac.error = "صيغة MAC غير صحيحة (مثال 00:1A:79:12:34:56)"
                    else -> {
                        prefs.stalkerPortal = portal
                        prefs.stalkerMac = mac
                        prefs.stalkerName = etSName.text.toString().trim()
                        dialog.dismiss()
                        testStalker()
                    }
                }
            }
        }
        showImmersive(dialog)
    }

    private fun testStalker() {
        toast("جارٍ الاتصال بالبوابة…")
        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { StalkerClient.test(prefs) }
                updateInfo()
                showMessage("Stalker Portal", message + "\n\nستجد القنوات في الشاشة الرئيسية ← الاشتراكات.")
            } catch (e: Exception) {
                updateInfo()
                showMessage(
                    "تعذر الاتصال بالبوابة",
                    (e.message ?: e.javaClass.simpleName) +
                        "\n\nتم حفظ البيانات. تأكد من الرابط ومن تفعيل عنوان MAC لدى المزوّد."
                )
            }
        }
    }

    // ------------------------------------------------------------------ User-Agent

    private fun showAgentDialog() {
        val presets = arrayOf(
            "IPTVSmartersPro",
            "VLC/3.0.20 LibVLC/3.0.20",
            "ExoPlayer",
            "OsoGoldTV/1.0"
        )
        val labels = arrayOf("IPTVSmartersPro", "VLC", "ExoPlayer", "OsoGoldTV", "مخصّص…")
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("بطاقة تعريف المشغل (User-Agent)\nالحالية: ${prefs.userAgent}")
            .setItems(labels) { _, which ->
                if (which < presets.size) {
                    prefs.userAgent = presets[which]
                    updateInfo()
                    toast("تم الحفظ: ${presets[which]}")
                } else {
                    showCustomAgentDialog()
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun showCustomAgentDialog() {
        val box = formBox()
        val et = styledEdit("مثال: IPTVSmartersPro", password = false)
        et.setText(prefs.userAgent)
        box.addView(et)

        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("User-Agent مخصّص")
            .setView(box)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = et.text.toString().trim()
                if (value.isEmpty()) {
                    et.error = "أدخل نصاً"
                } else {
                    prefs.userAgent = value
                    dialog.dismiss()
                    updateInfo()
                    toast("تم الحفظ: $value")
                }
            }
        }
        showImmersive(dialog)
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ engine / sleep timer / repair

    private fun showEngineDialog() {
        val options = arrayOf("Media3 (ExoPlayer) — الافتراضي", "LibVLC")
        val checked = if (prefs.useVlc) 1 else 0
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("المشغل")
            .setSingleChoiceItems(options, checked) { d, which ->
                prefs.playerEngine = if (which == 1) Prefs.ENGINE_VLC else Prefs.ENGINE_EXO
                toast("المشغل: " + options[which])
                d.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun showSleepDialog() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = ArrayList<String>()
        for (m in minutes) labels.add("$m دقيقة")
        if (SleepTimer.isActive) labels.add("إلغاء المؤقت (متبقي ${SleepTimer.remainingMinutes()} د)")
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("مؤقت النوم")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < minutes.size) {
                    SleepTimer.start(this, minutes[which])
                    toast("سيُغلق التطبيق بعد ${minutes[which]} دقيقة")
                } else {
                    SleepTimer.cancel()
                    toast("أُلغي مؤقت النوم")
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    private fun runRepair() {
        toast("جارٍ التصليح التلقائي…")
        lifecycleScope.launch {
            val report = try {
                Maintenance.repair(this@SettingsActivity, prefs)
            } catch (e: Exception) {
                "تعذر إكمال التصليح: " + (e.message ?: e.javaClass.simpleName)
            }
            showMessage("التصليح التلقائي", report)
            updateInfo()
        }
    }

    private fun showMessage(title: String, message: String) {
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("حسناً", null)
            .create()
        showImmersive(dialog)
    }

    private fun formBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun styledEdit(hintText: String, password: Boolean): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(getColor(R.color.gold_light))
            setHintTextColor(0x99D4AF37.toInt())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            maxLines = 3
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
