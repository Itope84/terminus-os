package com.terminus.rec

import android.view.View
import android.widget.EditText
import android.widget.TextView

/** Chat goals. Extension functions of [MainActivity], moved out of it unchanged. */

/** Set goal as a bottom sheet (select-style, prefillable for edits).
 *  Arm re-arms: server resets stuck/continuations, keeps verdicts. */
internal fun MainActivity.goalDialog(prefill: String? = null, loopDefault: Boolean = false) {
    val id = activeSessionId ?: return
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
    val box = layoutInflater.inflate(R.layout.sheet_goal, null)
    val title: TextView = box.findViewById(R.id.goalTitle)
    title.text = if (prefill == null) "Set session goal" else "Edit session goal"
    val input: android.widget.EditText = box.findViewById(R.id.goalInput)
    input.setText(prefill ?: "")
    val loopBox: android.widget.CheckBox = box.findViewById(R.id.goalLoop)
    loopBox.isChecked = loopDefault
    val arm: com.google.android.material.button.MaterialButton = box.findViewById(R.id.goalArm)
    sheet.setContentView(box)
    arm.setOnClickListener {
        val obj = input.text.toString().trim()
        if (obj.isBlank()) { toast("goal needs an objective"); return@setOnClickListener }
        sheet.dismiss()
        Thread {
            try {
                val g = OpencodeApi.goalArm(prefs.serverUrl(), prefs.token(), id, obj, loopBox.isChecked)
                runOnUiThread {
                    goalCache = g
                    paintGoalStrip()
                    toast(if (g?.loop == true) "goal armed, loop on" else "goal armed, advisory only")
                }
            } catch (_: Exception) {
                runOnUiThread { toast("goal arm failed") }
            }
        }.start()
    }
    sheet.show()
    try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
}

internal fun MainActivity.goalManageDialog() {
    val g = goalCache ?: return
    // Blocked is resumable too (server resume clears stuck): offering
    // Pause on a blocked goal strands the user with no way forward.
    // Complete goals can't pause (server no-ops): offer restart instead.
    val resumable = g.status == "paused" || g.status == "blocked"
    val rows = mutableListOf<SelectRow>()
    if (g.status == "complete") {
        rows.add(SelectRow("Watch again", GoalUi.manageSub(g), action = {
            val id = activeSessionId ?: return@SelectRow
            Thread {
                try {
                    val ng = OpencodeApi.goalArm(
                        prefs.serverUrl(), prefs.token(), id, g.objective, g.loop)
                    runOnUiThread { goalCache = ng; paintGoalStrip() }
                } catch (_: Exception) {
                    runOnUiThread { toast("goal restart failed") }
                }
            }.start()
        }))
    } else {
        rows.add(SelectRow(
            if (resumable) "Resume goal" else "Pause goal",
            GoalUi.manageSub(g),
            action = { goalAction(if (resumable) "resume" else "pause") },
        ))
    }
    rows.add(SelectRow("Edit goal", g.objective.trim().take(60), action = {
        goalDialog(prefill = g.objective, loopDefault = g.loop)
    }))
    rows.add(SelectRow("Clear goal", action = { goalAction("remove") }))
    showSelectSheet(GoalUi.sheetTitle(g), rows)
}

internal fun MainActivity.goalAction(action: String) {
    val id = activeSessionId ?: return
    Thread {
        try {
            val g = OpencodeApi.goalControl(prefs.serverUrl(), prefs.token(), action, id)
            runOnUiThread {
                goalCache = if (action == "remove") null else g
                paintGoalStrip()
                // Not silent: clearing from the strip's ✕ must say so,
                // since it takes the whole advisory away.
                if (action == "remove") toast("Goal removed")
            }
        } catch (_: Exception) {
            runOnUiThread { toast("Couldn't $action the goal") }
        }
    }.start()
}

/** Refresh goal state for the open chat. Called on thread open and
 *  after each settle; quiet on failure (no goal or offline). */
internal fun MainActivity.refreshGoalStrip(id: String) {
    Thread {
        try {
            val g = OpencodeApi.goalGet(prefs.serverUrl(), prefs.token(), id)
            runOnUiThread {
                if (activeSessionId != id) return@runOnUiThread
                goalCache = g
                paintGoalStrip()
                syncGoalNudges(id, g)
                // The goal settled while the stream was held for it: let go.
                if (streamChat == id && !hasPlaceholder(id) && !streamWanted(id)) idleStreamHandoff(id)
            }
        } catch (_: Exception) { /* strip keeps its last paint */ }
    }.start()
}

internal fun MainActivity.paintGoalStrip() {
    if (!goalStripReady || !goalStripBarReady) return
    val g = goalCache
    val text = GoalUi.plainText(g)
    if (text == null) {
        goalStripBar.visibility = View.GONE
        return
    }
    goalStripBar.visibility = View.VISIBLE
    goalStrip.text = "◎ $text"
    goalStrip.contentDescription = "session goal, ${GoalUi.sheetTitle(g)}: $text"
    // State as pastel wash: stop clay when blocked, soft green when
    // done, link wash while looping, plain chip otherwise. Text stays
    // ink; only the ground carries state.
    try {
        val base = androidx.core.content.ContextCompat.getColor(this, R.color.t_chip)
        val tint = when (g?.status) {
            "complete" -> R.color.t_ok
            "blocked" -> R.color.t_clay
            "paused" -> R.color.t_muted
            else -> if (g?.loop == true) R.color.t_link else 0
        }
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(if (tint == 0) base else pastel(base, androidx.core.content.ContextCompat.getColor(this@paintGoalStrip, tint)))
        }
        goalStripBar.background = bg
    } catch (_: Exception) {}
}

/** The strip's ✕: stop watching this chat now. Same effect as the
 *  sheet's "Remove goal" (server + local), minus the detour. */
internal fun MainActivity.goalClearGoal() {
    if (activeSessionId == null) return
    goalAction("remove")
}

/** Pastel wash: mostly base, a breath of state. */
internal fun MainActivity.pastel(base: Int, tint: Int): Int {
    fun ch(b: Int, t: Int): Int = (b * 4 + t) / 5
    return android.graphics.Color.rgb(
        ch(android.graphics.Color.red(base), android.graphics.Color.red(tint)),
        ch(android.graphics.Color.green(base), android.graphics.Color.green(tint)),
        ch(android.graphics.Color.blue(base), android.graphics.Color.blue(tint)),
    )
}

/** Loop trail: server nudge events become divider rows, deduplicated
 *  by timestamp. Renders in place without moving reading position. */
internal fun MainActivity.syncGoalNudges(id: String, g: OpencodeApi.GoalInfo?) {
    val events = g?.events?.filter { (it.kind == "nudge" || it.kind == "blocked" || it.kind == "capped" || it.kind == "check-failed") && it.at > 0 } ?: return
    if (events.isEmpty()) return
    try {
        val s = chatStore.get(id) ?: return
        var added = false
        for (e in events) {
            if (s.messages.any { it.role == "goal" && it.ts == e.at }) continue
            chatStore.insertMessageByTs(id, ChatMessage("goal",
                e.text.ifBlank { "Continuing toward goal" }, ts = e.at, settled = true))
            added = true
        }
        if (added && activeSessionId == id) renderThread(id, scroll = false)
        // Wake on goal row (active goals only): a server-initiated
        // continuation has no phone placeholder, so nothing else opens
        // the stream to hear its done. Connect replays everything
        // after since; the untracked-done path below lands it.
        if (added && activeSessionId == id && g?.status == "active") {
            try { ensureStream(id) } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
}
