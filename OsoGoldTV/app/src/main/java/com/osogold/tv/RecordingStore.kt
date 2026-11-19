package com.osogold.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Where the recordings live: the phone's public "Download/OsoGoldTV" folder.
 * Files are written straight into it while recording (no temporary copy), so even a recording of
 * many hours needs the disk space only once and survives if the app is closed by the system.
 */
object RecordingStore {

    const val FOLDER = "OsoGoldTV"

    data class Item(val name: String, val uri: Uri, val size: Long, val date: Long, val mime: String)

    class Handle(val name: String, val mime: String, val uri: Uri?, val file: File?)

    private fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /** Creates a new file and opens it for writing. Returns null when the phone refuses. */
    fun create(context: Context, fileName: String, mime: String): Pair<Handle, OutputStream>? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val out = context.contentResolver.openOutputStream(uri) ?: return null
                Handle(fileName, mime, uri, null) to out
            } else {
                val dir = publicDir()
                dir.mkdirs()
                val file = File(dir, fileName)
                Handle(fileName, mime, null, file) to file.outputStream()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Makes the finished file visible to the gallery / file manager. Returns the shown path. */
    fun finish(context: Context, handle: Handle): String {
        try {
            if (handle.uri != null) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(handle.uri, values, null, null)
            } else if (handle.file != null) {
                MediaScannerConnection.scanFile(
                    context, arrayOf(handle.file.absolutePath), arrayOf(handle.mime), null
                )
            }
        } catch (_: Exception) {
        }
        return "Download/$FOLDER/${handle.name}"
    }

    /** Removes a file that was created but never finished. */
    fun discard(context: Context, handle: Handle) {
        try {
            if (handle.uri != null) context.contentResolver.delete(handle.uri, null, null)
            handle.file?.delete()
        } catch (_: Exception) {
        }
    }

    fun sizeOf(context: Context, handle: Handle): Long = try {
        if (handle.file != null) handle.file.length()
        else context.contentResolver.openAssetFileDescriptor(handle.uri!!, "r")?.use { it.length } ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** The saved recordings, newest first. */
    fun list(context: Context): List<Item> {
        val out = ArrayList<Item>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.MIME_TYPE
                )
                val selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " +
                    MediaStore.MediaColumns.IS_PENDING + " = 0"
                val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%")
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out.add(
                            Item(
                                name = c.getString(1) ?: "—",
                                uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                                size = c.getLong(2),
                                date = c.getLong(3) * 1000L,
                                mime = c.getString(4) ?: ""
                            )
                        )
                    }
                }
            } else {
                publicDir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                    out.add(Item(it.name, Uri.fromFile(it), it.length(), it.lastModified(), ""))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Deletes one recording. Returns true when it is gone. */
    fun delete(context: Context, item: Item): Boolean = try {
        if (item.uri.scheme == "file") {
            File(item.uri.path ?: return false).delete()
        } else {
            context.contentResolver.delete(item.uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }
}
