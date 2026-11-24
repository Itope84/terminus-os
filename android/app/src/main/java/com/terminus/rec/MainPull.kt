package com.terminus.rec

import android.view.View
import android.widget.TextView

/** The pull-up footer under a thread. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.pullEnsure(): android.widget.LinearLayout? {
    pullBox?.let { return it }
    val frame = threadFrame as? android.widget.FrameLayout ?: return null
    val dp = resources.displayMetrics.density
    val icon = android.widget.TextView(this).apply {
        text = "\u21BB"; textSize = 16f; gravity = android.view.Gravity.CENTER
    }
    val label = android.widget.TextView(this).apply {
        textSize = 12.5f; setPadding((8 * dp).toInt(), 0, 0, 0)
    }
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        addView(icon); addView(label)
        visibility = View.GONE; alpha = 0f; elevation = 6 * dp
        isClickable = false; isFocusable = false
    }
    val lp = android.widget.FrameLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (14 * dp).toInt() }
    frame.addView(box, lp)
    pullBox = box; pullIcon = icon; pullLabel = label
    return box
}

internal fun MainActivity.pullStyle() {
    val box = pullBox ?: return
    val dp = resources.displayMetrics.density
    val dark = isDark()
    box.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 40 * dp
        setColor(if (dark) 0xF2303030.toInt() else 0xF2EFEFEF.toInt())
    }
    val ink = if (dark) 0xFFE6E6E6.toInt() else 0xFF222222.toInt()
    pullIcon?.setTextColor(ink); pullLabel?.setTextColor(ink)
}

/** While dragging: p (0..1) is how far the pill has emerged; `armed` = letting go will check. */
internal fun MainActivity.pullShow(p: Float, armed: Boolean) {
    val box = pullEnsure() ?: return
    pullStyle()
    progressHandler.removeCallbacks(pullHideRun)
    val dp = resources.displayMetrics.density
    box.visibility = View.VISIBLE
    box.animate().cancel()
    box.alpha = p
    box.translationY = (1f - p) * 20 * dp
    pullIcon?.rotation = p * 180f
    pullLabel?.text = if (armed) "Release to check for replies" else "Pull up to check for replies"
}

internal fun MainActivity.pullHide(animate: Boolean = true) {
    val box = pullBox ?: return
    if (box.visibility != View.VISIBLE) return
    pullSpin?.cancel(); pullSpin = null
    if (!animate) { box.visibility = View.GONE; box.alpha = 0f; return }
    val dp = resources.displayMetrics.density
    box.animate().alpha(0f).translationY(16 * dp).setDuration(180L)
        .withEndAction { box.visibility = View.GONE }.start()
}

internal fun MainActivity.pullReset() {
    try {
        progressHandler.removeCallbacks(pullHideRun)
        pullChecking = false
        pullHide(animate = false)
    } catch (_: Exception) {}
}

internal fun MainActivity.pullStartChecking() {
    val box = pullEnsure() ?: return
    pullStyle()
    progressHandler.removeCallbacks(pullHideRun)
    pullChecking = true
    box.visibility = View.VISIBLE
    box.animate().cancel()
    box.alpha = 1f; box.translationY = 0f
    pullLabel?.text = "Checking for replies\u2026"
    pullSpin?.cancel()
    pullSpin = android.animation.ObjectAnimator.ofFloat(pullIcon, "rotation", 0f, 360f).apply {
        duration = 800L; repeatCount = android.animation.ObjectAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator(); start()
    }
}

internal fun MainActivity.pullFinish(msg: String) {
    val box = pullEnsure() ?: return
    pullSpin?.cancel(); pullSpin = null
    pullIcon?.rotation = 0f
    pullLabel?.text = msg
    box.visibility = View.VISIBLE; box.alpha = 1f; box.translationY = 0f
    pullChecking = true
    progressHandler.removeCallbacks(pullHideRun)
    progressHandler.postDelayed(pullHideRun, 1600L)
}

/** Tap-check loading box (retry/reconcile links): show on tap, clear on
 *  every outcome — the thread's shimmer stands in as the acknowledgement
 *  and vanishes if nothing comes. 15s watchdog covers a dead worker.
 *  Thread box only: never pendingTurns, so no indicator/Stop effects. */
internal fun MainActivity.showChecking(id: String) {
    checking.add(id)
    progressHandler.postDelayed({ hideChecking(id) }, 15_000)
    if (activeSessionId == id) renderThread(id)
}

internal fun MainActivity.hideChecking(id: String) {
    if (checking.remove(id) && activeSessionId == id) renderThread(id)
}
