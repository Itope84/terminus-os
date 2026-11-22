package com.terminus.rec

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Upload-once path: chat attachments land directly in media day folders
 *  (filesDir/media/YYYY-MM-DD/), the same file that chat preview + gallery use. */
object MediaFiles {
    fun dayDir(ctx: Context, date: Date = Date()): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        return File(ctx.filesDir, "media/$day").apply { mkdirs() }
    }

    /** Copy a content URI into today's media day folder. Returns the saved File. */
    fun saveAttach(ctx: Context, uri: Uri, hint: String? = null): File {
        val cr = ctx.contentResolver
        var name = hint ?: "attach"
        // Try display name from provider.
        try {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (_: Exception) {}
        name = name.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "attach"
        if (!name.contains(".")) {
            val mime = try { cr.getType(uri) } catch (_: Exception) { null }
            val ext = when {
                mime?.contains("png") == true -> ".png"
                mime?.contains("jpeg") == true -> ".jpg"
                mime?.contains("jpg") == true -> ".jpg"
                mime?.contains("webp") == true -> ".webp"
                mime?.contains("gif") == true -> ".gif"
                mime?.contains("pdf") == true -> ".pdf"
                mime?.contains("xapk") == true -> ".xapk"
                mime?.contains("itunes-ipa") == true -> ".ipa"
                mime?.contains("package-archive") == true -> ".apk"
                mime?.contains("mp4") == true -> ".mp4"
                mime?.contains("m4a") == true || mime?.contains("audio") == true -> ".m4a"
                else -> ""
            }
            name += ext
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        // Two files picked in the same second with the same name used to overwrite each other.
        var dest = File(dayDir(ctx), "${stamp}_$name")
        var n = 1
        while (dest.exists()) dest = File(dayDir(ctx), "${stamp}_${n++}_$name")
        cr.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { outs -> ins.copyTo(outs) }
        } ?: throw IllegalStateException("cannot read attachment")
        return dest
    }

    const val MAX_RAW_BYTES = 5 * 1024 * 1024

    fun mimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".xapk") -> "application/xapk-package-archive"
            lower.endsWith(".apks") -> "application/octet-stream"
            lower.endsWith(".ipa") -> "application/x-itunes-ipa"
            lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    /** Prepared upload payload: images are downscaled to ≤1600px and
     *  re-encoded JPEG80; other files pass through raw (≤5MB).
     *  Returns (name, mime, base64) or null when the file is unusable. */
    fun prepareForUpload(file: File): Triple<String, String, String>? {
        return try {
            val mime = mimeFor(file.name)
            val bytes: ByteArray = if (mime.startsWith("image/")) {
                compressImage(file) ?: return null
            } else {
                if (!file.exists() || file.length() <= 0 || file.length() > MAX_RAW_BYTES) return null
                file.readBytes()
            }
            if (bytes.isEmpty() || bytes.size > MAX_RAW_BYTES) return null
            val outName = if (mime.startsWith("image/") && !file.name.lowercase().endsWith(".jpg")
                && !file.name.lowercase().endsWith(".jpeg")) {
                file.nameWithoutExtension.take(80) + ".jpg"
            } else file.name
            val outMime = if (mime.startsWith("image/")) "image/jpeg" else mime
            Triple(outName, outMime, Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (_: Exception) { null }
    }

    /** Downscale to ≤1600px on the long edge, JPEG80. Null on decode failure. */
    fun compressImage(file: File): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val scale = 1600f / maxOf(bmp.width, bmp.height).toFloat()
            if (scale < 1f) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}
