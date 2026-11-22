package com.terminus.rec

/** What to call an attachment when showing it to a person. Machine-made names (screenshots, camera
 *  files, the HHmmss stamp added when a file is staged) read as noise, so they become "Screenshot" or
 *  "Photo"; real names (report.pdf, notes.txt) are kept, minus the stamp. Pure, no Android types. */
object AttachName {
    private val stamp = Regex("^\\d{6}(_\\d+)?_")
    private val screenshot = Regex("^(screenshot|screen[ _]?shot|screen[ _]?capture)", RegexOption.IGNORE_CASE)
    private val camera = Regex("^(img|pxl|dsc|mvimg|photo|image|pic)[-_ ]?\\d|^\\d{8}[-_ ]?\\d|^\\d[\\d_ -]{5,}$", RegexOption.IGNORE_CASE)
    private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp")

    fun friendly(fileName: String): String {
        val core = fileName.substringAfterLast('/').replace(stamp, "")
        val ext = core.substringAfterLast('.', "").lowercase()
        if (ext !in imageExt) return core.ifBlank { fileName }
        val base = core.substringBeforeLast('.')
        return when {
            screenshot.containsMatchIn(base) -> "Screenshot"
            camera.containsMatchIn(base) -> "Photo"
            base.isBlank() || base.equals("attach", true) -> "Image"
            else -> core
        }
    }

    /** Summary for several files, e.g. "3 files: Screenshot, report.pdf +1 more". */
    fun summary(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> friendly(names[0])
        else -> {
            val head = names.take(2).joinToString(", ") { friendly(it) }
            "${names.size} files: $head" + if (names.size > 2) " +${names.size - 2} more" else ""
        }
    }
}
