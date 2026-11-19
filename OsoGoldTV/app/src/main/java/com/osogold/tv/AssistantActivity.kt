package com.osogold.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * AI assistant: type or speak a command (any Arabic dialect) and the app executes it:
 * play a channel, open a surah, start / stop recording, mute, clean the app ...
 * Voice uses the phone's own SpeechRecognizer (Google speech service); the understanding of the
 * command runs inside the app ([Assistant]).
 */
class AssistantActivity : BaseActivity() {

    private lateinit var etCommand: EditText
    private lateinit var tvResult: TextView
    private lateinit var btnMic: TextView
    private lateinit var btnDialect: TextView
    private lateinit var prefs: Prefs

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val dialects = listOf(
        "ar" to "عربي (تلقائي)",
        "ar-EG" to "مصري",
        "ar-SA" to "خليجي / سعودي",
        "ar-AE" to "إماراتي",
        "ar-KW" to "كويتي",
        "ar-QA" to "قطري",
        "ar-IQ" to "عراقي",
        "ar-JO" to "أردني",
        "ar-LB" to "لبناني",
        "ar-PS" to "فلسطيني",
        "ar-DZ" to "جزائري",
        "ar-MA" to "مغربي",
        "ar-TN" to "تونسي",
        "ar-OM" to "عُماني",
        "ar-YE" to "يمني",
        "ar-SD" to "سوداني"
    )
    private var dialectIndex = 0

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else toast("صلاحية الميكروفون مطلوبة للأوامر الصوتية")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        prefs = Prefs(this)
        etCommand = findViewById(R.id.etCommand)
        tvResult = findViewById(R.id.tvAssistantResult)
        btnMic = findViewById(R.id.btnMic)
        btnDialect = findViewById(R.id.btnDialect)

        findViewById<TextView>(R.id.tvExamples).text =
            "أمثلة على الأوامر (تفهم جميع اللهجات العربية):\n\n" + Assistant.examples.joinToString("\n") { "•  $it" }

        btnMic.setOnClickListener { if (listening) stopListening() else requestMic() }
        findViewById<View>(R.id.btnRun).setOnClickListener { runCommand(etCommand.text.toString()) }
        findViewById<View>(R.id.btnAssistantBack).setOnClickListener { finish() }
        btnDialect.setOnClickListener { showDialects() }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand(etCommand.text.toString())
                true
            } else {
                false
            }
        }
        updateDialect()
    }

    override fun onStop() {
        super.onStop()
        stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun updateDialect() {
        btnDialect.text = "لهجة الصوت: " + dialects[dialectIndex].second + "  (اضغط للتغيير)"
    }

    private fun showDialects() {
        val dialog = AlertDialog.Builder(this, R.style.GoldDialog)
            .setTitle("لهجة التعرف على الصوت")
            .setSingleChoiceItems(dialects.map { it.second }.toTypedArray(), dialectIndex) { d, which ->
                dialectIndex = which
                updateDialect()
                d.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .create()
        showImmersive(dialog)
    }

    // ------------------------------------------------------------------ voice

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("خدمة التعرف على الصوت غير متوفرة على هذا الهاتف — اكتب الأمر بدلاً من ذلك")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvResult.text = "🎤 أنا أستمع…"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                setListening(false)
            }

            override fun onError(error: Int) {
                setListening(false)
                tvResult.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم أسمع شيئاً — حاول مرة أخرى"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "التعرف على الصوت يحتاج إنترنت"
                    else -> "تعذر التعرف على الصوت (رمز $error)"
                }
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                val options = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                if (options.isEmpty()) {
                    tvResult.text = "لم أفهم — حاول مرة أخرى"
                    return
                }
                // first alternative that is a real command; otherwise the first one
                val chosen = options.firstOrNull { Assistant.parse(it).name !in setOf(Assistant.CMD_NONE, Assistant.CMD_PLAY) }
                    ?: options.first()
                etCommand.setText(chosen)
                runCommand(chosen)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    etCommand.setText(it)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, dialects[dialectIndex].first)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, dialects[dialectIndex].first)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 6)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        setListening(true)
        r.startListening(intent)
    }

    private fun stopListening() {
        if (listening) {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
            setListening(false)
        }
    }

    private fun setListening(value: Boolean) {
        listening = value
        btnMic.text = if (value) "⏹  إيقاف" else "🎤  تحدّث"
    }

    // ------------------------------------------------------------------ execution

    private fun runCommand(text: String) {
        val command = Assistant.parse(text)
        when (command.name) {
            Assistant.CMD_NONE -> tvResult.text = "لم أفهم الأمر — جرّب أحد الأمثلة"
            Assistant.CMD_REPAIR -> repair()
            Assistant.CMD_RECORDINGS -> startActivity(Intent(this, RecordingsActivity::class.java))
            else -> {
                tvResult.text = "✔ " + command.description
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_COMMAND, command.name)
                    .putExtra(MainActivity.EXTRA_ARGUMENT, command.argument)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun repair() {
        tvResult.text = "جارٍ التصليح التلقائي…"
        lifecycleScope.launch {
            val report = Maintenance.repair(this@AssistantActivity, prefs)
            tvResult.text = report
        }
    }
}
