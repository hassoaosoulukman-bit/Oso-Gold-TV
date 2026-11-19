package com.osogold.tv

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the live stream that is currently playing and saves it as an MP4 file in the phone's
 * Downloads folder (Downloads/OsoGoldTV).
 *
 * How it works
 *  1. The stream is downloaded (HLS playlists segment by segment, other streams byte by byte)
 *     into a temporary file.
 *  2. When the recording is stopped the temporary file is converted ("transmuxed") to MP4 with
 *     Media3 Transformer and copied to Downloads.
 *  3. If the conversion is impossible for a stream, the raw recording is saved instead
 *     (extension .ts) so nothing is lost.
 *
 * Not supported: DRM / AES-encrypted HLS streams.
 */
class StreamRecorder(private val context: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var busy = false

    @Volatile
    private var connection: HttpURLConnection? = null

    private var worker: Thread? = null
    private var tempFile: File? = null
    private var title = "stream"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread when the download ended by itself (network error / end of stream). */
    var onWorkerEnded: (() -> Unit)? = null

    val isRecording: Boolean
        get() = running

    val isBusy: Boolean
        get() = busy

    // ------------------------------------------------------------------ start

    fun start(url: String, name: String): Boolean {
        if (running || busy) return false
        val dir = File(context.cacheDir, "rec")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // remove leftovers of older recordings
        val file = File(dir, "rec_${System.currentTimeMillis()}.ts")
        tempFile = file
        title = name
        running = true

        worker = Thread({
            try {
                if (isHls(url)) recordHls(url, file) else recordProgressive(url, file)
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

        val source = tempFile
        val name = title
        Thread({
            // closing the socket must not happen on the main thread
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
            try {
                worker?.join(8_000L)
            } catch (_: InterruptedException) {
            }
            mainHandler.post {
                if (source == null || !source.exists() || source.length() < 4_096L) {
                    source?.delete()
                    busy = false
                    onDone(null)
                } else {
                    convertAndSave(source, name, onDone)
                }
            }
        }, "OsoGoldRecorderStop").start()
    }

    private fun convertAndSave(source: File, name: String, onDone: (String?) -> Unit) {
        val baseName = "OsoGoldTV_" + sanitize(name) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(source.parentFile, "$baseName.mp4")

        fun saveRawFallback() {
            val path = saveToDownloads(source, "$baseName.ts", "video/mp2t")
            source.delete()
            output.delete()
            busy = false
            onDone(path)
        }

        try {
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC) // transcode audio only when it is not AAC yet
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val path = saveToDownloads(output, "$baseName.mp4", "video/mp4")
                        source.delete()
                        output.delete()
                        busy = false
                        onDone(path)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        Log.w(TAG, "MP4 conversion failed, saving raw recording", exportException)
                        saveRawFallback()
                    }
                })
                .build()

            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).build()
            transformer.start(item, output.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start conversion", e)
            saveRawFallback()
        }
    }

    // ------------------------------------------------------------------ downloading

    private fun isHls(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    /** Plain streams (TS, MP4, MKV, MP3, AAC ...): copy bytes until stopped. */
    private fun recordProgressive(url: String, out: File) {
        val conn = open(url)
        connection = conn
        try {
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buffer = ByteArray(64 * 1024)
                    while (running) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        fos.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    /** HLS: poll the playlist and append every new segment. */
    private fun recordHls(url: String, out: File) {
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

        FileOutputStream(out).use { fos ->
            val seen = HashSet<String>()
            var initWritten = false
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
                        line.isNotEmpty() && !line.startsWith("#") ->
                            segments.add(resolve(playlistUrl, line))
                    }
                }

                if (!initWritten && mapUri != null) {
                    downloadTo(resolve(playlistUrl, mapUri), fos)
                    initWritten = true
                }
                for (segment in segments) {
                    if (!running) break
                    if (seen.add(segment)) downloadTo(segment, fos)
                }
                if (endList) break

                val waitMs = (targetSeconds * 500).toLong().coerceIn(1_000L, 5_000L)
                var waited = 0L
                while (running && waited < waitMs) {
                    Thread.sleep(250L)
                    waited += 250L
                }
                if (running) text = httpText(playlistUrl)
            }
        }
    }

    private fun downloadTo(address: String, out: OutputStream) {
        val conn = open(address)
        connection = conn
        try {
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        } finally {
            conn.disconnect()
            connection = null
        }
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
    private fun open(address: String): HttpURLConnection {
        var current = address
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OsoGoldTV/1.0")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location")
                current = URL(URL(current), location).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
        }
        throw IOException("too many redirects")
    }

    private fun resolve(base: String, relative: String): String =
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) relative
        else URL(URL(base), relative).toString()

    // ------------------------------------------------------------------ saving

    private fun saveToDownloads(source: File, fileName: String, mime: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OsoGoldTV")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                val stream = resolver.openOutputStream(uri) ?: return null
                stream.use { out -> source.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "OsoGoldTV"
                )
                dir.mkdirs()
                val target = File(dir, fileName)
                source.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(
                    context, arrayOf(target.absolutePath), arrayOf(mime), null
                )
            }
            "Download/OsoGoldTV/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "Saving to Downloads failed", e)
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "stream" }

    companion object {
        private const val TAG = "StreamRecorder"
    }
}
