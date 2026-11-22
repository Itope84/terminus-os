package com.terminus.rec

/** Session goal strip text. Pure, unit-tested. Null means no goal: the
 *  strip stays hidden. The strip shows the objective only; state rides
 *  on the tint, never bracketed words. Short labels, no em-dashes. */
object GoalUi {
    fun plainText(g: OpencodeApi.GoalInfo?): String? {
        if (g == null || g.objective.isBlank()) return null
        return g.objective.trim().take(100)
    }
    /** Short manage-sheet title: state only, never the objective.
     *  The objective lives on the Edit row where it belongs. */
    fun sheetTitle(g: OpencodeApi.GoalInfo?): String {
        return when (g?.status) {
            "paused" -> "Goal paused"
            "complete" -> "Goal done"
            "blocked" -> "Goal blocked"
            else -> if (g?.loop == true) "Goal looping" else "Goal on"
        }
    }

    /** Manage-sheet sub for pause/resume: stuck and continuation counts. */
    fun manageSub(g: OpencodeApi.GoalInfo?): String {        if (g == null) return ""
        return when (g.status) {
            "blocked" -> "stuck ${g.stuck}, needs you"
            "complete" -> "settled"
            "paused" -> "paused"
            else -> if (g.loop && g.continuations > 0) "loop ${g.continuations}, stuck ${g.stuck}"
                else if (g.loop) "loop on"
                else "judged each turn"
        }
    }
}
