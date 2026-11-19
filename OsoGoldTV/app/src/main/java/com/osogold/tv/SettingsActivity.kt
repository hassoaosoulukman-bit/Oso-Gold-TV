package com.osogold.tv

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings screen. Same golden circular icon style as the main screen:
 *  - Password            : write and save a new password
 *  - Welcome video       : pick any video from the gallery OR record a new one with the camera
 *  - Playlist sources    : M3U links that can be updated at any time
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvInfo: TextView
    private var captureFile: File? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importVideo(uri)
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
        findViewById<View>(R.id.btnSources).setOnClickListener { showSourcesDialog() }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { finish() }
        updateInfo()
    }

    private fun updateInfo() {
        val video = if (prefs.welcomeVideoFile() != null) "مخصّص" else "افتراضي"
        tvInfo.text = "الفيديو الترحيبي: $video"
    }

    // ------------------------------------------------------------------ password

    private fun showPasswordDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
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
        dialog.show()
    }

    // ------------------------------------------------------------------ welcome video

    private fun showWelcomeVideoDialog() {
        val options = arrayOf(
            "اختيار فيديو من المعرض / الملفات",
            "تصوير فيديو جديد بكاميرا الجوال",
            "إعادة الفيديو الافتراضي"
        )
        AlertDialog.Builder(this, R.style.GoldDialog)
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
            .show()
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

    // ------------------------------------------------------------------ playlist sources

    private fun showSourcesDialog() {
        val keys = DefaultData.sourceKeys
        val labels = keys.map { key ->
            val url = prefs.getSource(key)
            DefaultData.sourceTitle(key) + "\n" + (if (url.isBlank()) "— (لا يوجد رابط)" else url)
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("مصادر القوائم (M3U)")
            .setItems(labels) { _, which -> editSource(keys[which]) }
            .setNeutralButton("استعادة الافتراضي") { _, _ ->
                prefs.resetSources()
                toast("تمت استعادة الروابط الافتراضية")
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun editSource(key: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val et = styledEdit("https://example.com/list.m3u", password = false)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        et.setText(prefs.getSource(key))
        box.addView(et)

        AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle(DefaultData.sourceTitle(key))
            .setMessage("رابط قائمة M3U (يمكن تحديثه في أي وقت)")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.setSource(key, et.text.toString())
                toast("تم الحفظ. ستُحمَّل القائمة عند العودة للشاشة الرئيسية")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

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
