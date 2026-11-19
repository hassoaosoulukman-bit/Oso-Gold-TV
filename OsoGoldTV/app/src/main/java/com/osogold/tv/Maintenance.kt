package com.osogold.tv

import android.content.Context
import android.content.Intent
import coil.Coil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Auto repair": cleans the temporary memory, downloads every list again and re-initialises the
 * player. It NEVER touches the subscriptions (Xtream / Stalker / M3U lists / the pinned server),
 * the favourites, the password or the recordings.
 */
object Maintenance {

    suspend fun repair(context: Context, prefs: Prefs): String {
        val lines = ArrayList<String>()

        // 1) temporary memory
        val freed = withContext(Dispatchers.IO) {
            var bytes = 0L
            fun wipe(dir: File?, keep: (File) -> Boolean = { false }) {
                dir?.listFiles()?.forEach { f ->
                    if (keep(f)) return@forEach
                    bytes += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    f.deleteRecursively()
                }
            }
            wipe(File(context.filesDir, "playlist_cache"))
            wipe(File(context.filesDir, "api_cache"))
            // a conversion that is running right now must not be removed
            val now = System.currentTimeMillis()
            wipe(context.cacheDir) { now - it.lastModified() < 10L * 60L * 1000L }
            bytes
        }
        PlaylistRepository.clearCache()
        try {
            Coil.imageLoader(context).memoryCache?.clear()
            Coil.imageLoader(context).diskCache?.clear()
        } catch (_: Exception) {
        }
        lines.add("✔ تم تنظيف الذاكرة المؤقتة (%.1f ميغابايت)".format(freed / 1048576.0))

        // 2) update the links
        val updated = try {
            AutoUpdater.refreshAll(prefs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        lines.add(
            if (updated > 0) "✔ تم تحديث الروابط والقوائم ($updated مصدر)"
            else "⚠ تعذر تحديث الروابط الآن (تحقق من الإنترنت) — ستُحدَّث تلقائياً لاحقاً"
        )

        // 3) re-initialise the player
        try {
            PlaybackService.instance?.stopPlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            lines.add("✔ تمت إعادة تهيئة المشغل")
        } catch (_: Exception) {
            lines.add("⚠ تعذرت إعادة تهيئة المشغل")
        }

        lines.add("✔ الاشتراكات والتسجيلات والمفضلة لم تُمسّ")
        return lines.joinToString("\n")
    }
}
