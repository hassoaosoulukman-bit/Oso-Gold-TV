package com.osogold.tv

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records ANY stream that is playing: live TV, radio, movies and series.
 *
 *  - HLS (m3u8)      : playlist polled, every new segment appended (survives short network cuts)
 *  - live TS / radio : bytes copied; the connection is re-opened automatically after a drop, so a
 *                      recording of many hours (3 hours and more) keeps going
 *  - movies / series : the file is copied as it is (mp4, mkv ...)
 *
 * The file is written directly into Download/OsoGoldTV while recording. A live .ts recording up
 * to [CONVERT_LIMIT_BYTES] is converted to MP4 when it is stopped; longer ones stay .ts (plays
 * everywhere, and no long conversion is needed).
 * Recording stops by itself when the free space of the phone falls under 150 MB.
 * Not supported: DRM / AES-encrypted HLS streams.
 */
class StreamRecorder(private val context: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var busy = false

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    var lowSpace = false
        private set

    private var worker: Thread? = null
    private var handle: RecordingStore.Handle? = null
    private var stream: OutputStream? = null
    private var title = "stream"
    private var extension = "ts"
    private var live = true
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread when the download ended by itself (network error / disk full). */
    var onWorkerEnded: (() -> Unit)? = null

    val isRecording: Boolean
        get() = running

    val isBusy: Boolean
        get() = busy

    // ------------------------------------------------------------------ start

    fun start(url: String, name: String): Boolean {
        if (running || busy) return false
        title = name
        val kind = kindOf(url)
        extension = kind.first
        live = kind.second
        val fileName = "OsoGoldTV_" + sanitize(name) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + extension
        val created = RecordingStore.create(context, fileName, mimeOf(extension)) ?: return false
        handle = created.first
        val out = BufferedOutputStream(created.second, 256 * 1024)
        stream = out
        lowSpace = false
        running = true

        worker = Thread({
            try {
                if (isHls(url)) recordHls(url, out) else recordProgressive(url, out)
            } catch (e: Exception) {
                Log.w(TAG, "Recording download ended: ${e.message}")
            } finally {
                if (running) {
                    mainHandler.post { onWorkerEnded?.invoke() }
                }
            }
        }, "OsoGoldRecorder").also { it.start() }
        return true
    }

    // ------------------------------------------------------------------ stop + save

    /** Stops the recording and saves it. [onDone] gets the saved location (or null) on the main thread. */
    fun stopAndSave(onDone: (String?) -> Unit) {
        if (!running) {
            onDone(null)
            return
        }
        running = false
        busy = true

        val h = handle
        val out = stream
        Thread({
            try {
                connection?.disconnect() // must not happen on the main thread
            } catch (_: Exception) {
            }
            try {
                worker?.join(10_000L)
            } catch (_: InterruptedException) {
            }
            try {
                out?.flush()
                out?.close()
            } catch (_: Exception) {
            }
            mainHandler.post {
                if (h == null) {
                    busy = false
                    onDone(null)
                } else {
                    val size = RecordingStore.sizeOf(context, h)
                    if (size < 4_096L) {
                        RecordingStore.discard(context, h)
                        busy = false
                        onDone(null)
                    } else {
                        val path = RecordingStore.finish(context, h)
                        if (extension == "ts" && size <= CONVERT_LIMIT_BYTES) {
                            convertToMp4(h, path, onDone)
                        } else {
                            busy = false
                            onDone(path)
                        }
                    }
                }
            }
        }, "OsoGoldRecorderStop").start()
    }

    /** .ts -> .mp4 (short recordings). If anything fails the .ts file simply stays. */
    private fun convertToMp4(source: RecordingStore.Handle, tsPath: String, onDone: (String?) -> Unit) {
        val temp = File(context.cacheDir, "convert_${System.currentTimeMillis()}.mp4")
        val input: Uri = source.uri ?: Uri.fromFile(source.file!!)

        fun keepTs() {
            temp.delete()
            busy = false
            onDone(tsPath)
        }

        try {
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        Thread({
                            val mp4Name = source.name.removeSuffix(".ts") + ".mp4"
                            val created = RecordingStore.create(context, mp4Name, "video/mp4")
                            var ok = false
                            if (created != null) {
                                try {
                                    created.second.use { o -> temp.inputStream().use { it.copyTo(o) } }
                                    ok = true
                                } catch (_: Exception) {
                                }
                            }
                            mainHandler.post {
                                if (ok && created != null) {
                                    val mp4Path = RecordingStore.finish(context, created.first)
                                    RecordingStore.discard(context, source) // the .ts is no longer needed
                                    temp.delete()
                                    busy = false
                                    onDone(mp4Path)
                                } else {
                                    created?.let { RecordingStore.discard(context, it.first) }
                                    keepTs()
                                }
                            }
                        }, "OsoGoldMp4Save").start()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        Log.w(TAG, "MP4 conversion failed, keeping .ts", exportException)
                        keepTs()
                    }
                })
                .build()
            transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(input)).build(), temp.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start conversion", e)
            keepTs()
        }
    }

    // ------------------------------------------------------------------ downloading

    private fun isHls(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    /** Checks the free space every 32 MB. Returns false (and stops) when the phone is nearly full. */
    private fun spaceOk(written: Long): Boolean {
        return try {
            val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            if (free < MIN_FREE_BYTES) {
                lowSpace = true
                running = false
                false
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    /** Plain streams: copy bytes until stopped; live streams reconnect after a drop. */
    private fun recordProgressive(url: String, out: OutputStream) {
        var written = 0L
        var nextCheck = CHECK_EVERY_BYTES
        var failures = 0
        while (running) {
            var gotBytes = false
            try {
                val conn = open(url)
                connection = conn
                try {
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (running) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            gotBytes = true
                            written += n
                            if (written >= nextCheck) {
                                nextCheck += CHECK_EVERY_BYTES
                                if (!spaceOk(written)) return
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                    connection = null
                }
            } catch (e: IOException) {
                if (!live) throw e
            }
            if (!live) return // a movie / episode ends when the file ends
            if (!running) return
            failures = if (gotBytes) 0 else failures + 1
            if (failures > 90) throw IOException("انقطع البث لفترة طويلة")
            sleepWhileRunning(2_000L)
        }
    }

    /** HLS: poll the playlist and append every new segment. */
    private fun recordHls(url: String, out: OutputStream) {
        var playlistUrl = url
        var text = httpText(playlistUrl)

        // master playlist -> take the first variant
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val variant = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("empty master playlist")
            playlistUrl = resolve(playlistUrl, variant.trim())
            text = httpText(playlistUrl)
        }

        val seen = LinkedHashSet<String>()
        var initWritten = false
        var written = 0L
        var nextCheck = CHECK_EVERY_BYTES
        var failures = 0
        while (running) {
            var targetSeconds = 6.0
            var endList = false
            val segments = ArrayList<String>()
            var mapUri: String? = null

            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        targetSeconds = line.substringAfter(':').toDoubleOrNull() ?: 6.0
                    line.startsWith("#EXT-X-ENDLIST") -> endList = true
                    line.startsWith("#EXT-X-MAP:") ->
                        mapUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    line.isNotEmpty() && !line.startsWith("#") -> segments.add(resolve(playlistUrl, line))
                }
            }

            try {
                if (!initWritten && mapUri != null) {
                    written += downloadTo(resolve(playlistUrl, mapUri), out)
                    initWritten = true
                }
                for (segment in segments) {
                    if (!running) break
                    if (segment !in seen) {
                        written += downloadTo(segment, out)
                        seen.add(segment)
                        if (seen.size > 600) seen.remove(seen.first()) // keeps memory small on long recordings
                    }
                }
                failures = 0
            } catch (e: IOException) {
                failures++
                if (failures > 60) throw e
            }
            if (written >= nextCheck) {
                nextCheck = written + CHECK_EVERY_BYTES
                if (!spaceOk(written)) return
            }
            if (endList) break

            sleepWhileRunning((targetSeconds * 500).toLong().coerceIn(1_000L, 5_000L))
            if (running) {
                try {
                    text = httpText(playlistUrl)
                } catch (e: IOException) {
                    failures++
                    if (failures > 60) throw e
                }
            }
        }
    }

    private fun sleepWhileRunning(ms: Long) {
        var waited = 0L
        while (running && waited < ms) {
            Thread.sleep(250L)
            waited += 250L
        }
    }

    private fun downloadTo(address: String, out: OutputStream): Long {
        val conn = open(address)
        connection = conn
        var total = 0L
        try {
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
            }
        } finally {
            conn.disconnect()
            connection = null
        }
        return total
    }

    private fun httpText(address: String): String {
        val conn = open(address)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Opens a connection and follows redirects (also http -> https). */
    private fun open(address: String): HttpURLConnection =
        NetConfig.open(address, connectMs = 15_000, readMs = 20_000)

    private fun resolve(base: String, relative: String): String =
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) relative
        else URL(URL(base), relative).toString()

    // ------------------------------------------------------------------ helpers

    /** (file extension, isLive) of an address. */
    private fun kindOf(url: String): Pair<String, Boolean> {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        val ext = path.substringAfterLast('.', "")
        return when {
            path.contains(".m3u8") -> "ts" to true
            ext in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv") -> ext to false
            path.contains("/movie/") || path.contains("/series/") -> (ext.ifEmpty { "mp4" }) to false
            ext == "mp3" -> "mp3" to true
            ext in setOf("aac", "m4a") -> "aac" to true
            ext == "ts" -> "ts" to true
            else -> "ts" to true
        }
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> "video/mp2t"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "stream" }

    companion object {
        private const val TAG = "StreamRecorder"
        private const val CHECK_EVERY_BYTES = 32L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 150L * 1024L * 1024L
        private const val CONVERT_LIMIT_BYTES = 500L * 1024L * 1024L
    }
}
