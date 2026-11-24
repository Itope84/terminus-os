package com.terminus.rec

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import java.io.File

/** Composer attachments: staging, thumbnails, the attach sheet. Extension functions of [MainActivity], moved out of it unchanged. */

/** Attachment strip: the visible proof files are staged. Typing never
 *  touches it — only send, ✕, or thread switch clears it. A single
 *  staged image adds a thumbnail next to the label; the filename always
 *  shows (first two names when several). */
internal fun MainActivity.showAttach() {
    if (!attachPreviewReady) return
    if (pendingAttaches.isEmpty()) return
    val singleImage = pendingAttaches.size == 1 &&
        MediaFiles.mimeFor(pendingAttaches.first().name).startsWith("image/")
    val label = AttachName.summary(pendingAttaches.map { it.name })
    attachPreviewLabel.text = "📎 $label"
    attachPreview.visibility = View.VISIBLE
    if (singleImage) loadAttachThumb(pendingAttaches.first()) else clearAttachThumb()
    updateComposerSlot()
}

/** Sampled thumbnail for the staged image, decoded off the UI thread
 *  (≤288px) with a path tag so a slow decode can't land on a newer
 *  stage. Failures keep the text label — never a broken image. */
internal fun MainActivity.loadAttachThumb(f: java.io.File) {
    if (!attachThumbReady) return
    attachThumb.tag = f.absolutePath
    attachThumb.visibility = View.VISIBLE
    attachThumb.setImageDrawable(null)
    Thread {
        val bmp = try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 288 || bounds.outHeight / sample > 288) sample *= 2
            android.graphics.BitmapFactory.decodeFile(f.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
        runOnUiThread {
            if (!attachThumbReady) return@runOnUiThread
            if (attachThumb.tag != f.absolutePath) return@runOnUiThread
            if (bmp != null) attachThumb.setImageBitmap(bmp)
            else attachThumb.visibility = View.GONE
        }
    }.start()
}

internal fun MainActivity.clearAttachThumb() {
    if (!attachThumbReady) return
    attachThumb.tag = null
    attachThumb.setImageDrawable(null)
    attachThumb.visibility = View.GONE
}

internal fun MainActivity.hideAttachStrip() {
    if (attachPreviewReady) attachPreview.visibility = View.GONE
    clearAttachThumb()
    updateComposerSlot()
}

internal fun MainActivity.dropAttach() {
    pendingAttaches.clear()
    hideAttachStrip()
    if (pendingVoice == null) composerInput.hint = "Message (/ for commands)"
}

/** Stage one content URI into pending attaches (camera capture path).
 *  Compression to model size happens later in prepareForUpload. */
internal fun MainActivity.stageAttachUri(uri: android.net.Uri, hint: String) {
    if (pendingAttaches.size >= 5) {
        android.widget.Toast.makeText(this,
            "Only 5 files per message", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    pendingAttaches.add(MediaFiles.saveAttach(this, uri, hint))
    afterAttachesStaged(false)
}

internal fun MainActivity.afterAttachesStaged(trimmed: Boolean) {
    if (pendingAttaches.isEmpty()) return
    if (trimmed) {
        android.widget.Toast.makeText(this,
            "Only 5 files per message", android.widget.Toast.LENGTH_SHORT).show()
    }
    // The preview chip above already shows what is attached; no raw file name in the input.
    composerInput.hint = if (pendingAttaches.size == 1)
        "Add a caption or send"
    else
        "${pendingAttaches.size} files attached, add a caption or send"
    showAttach()
}

/** Direct camera capture: hands the system camera app a FileProvider
 *  URI, stages the shot like any other attach. Model-size compression
 *  applies downstream in prepareForUpload. */
internal fun MainActivity.takePhoto() {
    try {
        val dir = java.io.File(cacheDir, "captures").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(dir, "shot-$stamp.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.files", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    } catch (_: Exception) {
        pendingCameraUri = null
    }
}

/** Plus sheet: attach rows plus Set goal, GPT-style. Same rows on
 *  every backend: all of them accept files, so capability-gating here
 *  only ever hid working features behind a stale catalog. */
internal fun MainActivity.attachSheetDialog() {
    val rows = mutableListOf<SelectRow>()
    if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
        rows.add(SelectRow("Camera", "take a photo", action = { takePhoto() }, icon = R.drawable.ic_attach_camera))
    }
    rows.add(SelectRow("Photos", "from gallery", action = { pickAttach("image/*", "photos") }, icon = R.drawable.ic_attach_photo))
    rows.add(SelectRow("Files", "any file", action = { pickAttach("*/*", "file") }, icon = R.drawable.ic_attach_file))
    val goalState = goalCache?.let { GoalUi.manageSub(it) } ?: ""
    rows.add(SelectRow("Set goal", goalState.ifBlank { "loop until done" }, action = { goalDialog() }, icon = R.drawable.ic_attach_goal))
    showSelectSheet("Add to chat", rows)
}

internal fun MainActivity.pickAttach(mime: String, kind: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = mime
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        attachLauncher.launch(android.content.Intent.createChooser(i, kind))
    } catch (_: Exception) {}
}
