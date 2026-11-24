package com.terminus.rec

/** When a chat's live stream stays open, moves to another chat, or goes to sleep.
 *
 *  The app keeps one live stream, owned by whichever chat has work. Which chat that is used to be decided inline
 *  in three places in the stream code, and a wrong turn hid replies: a finishing background job released the
 *  stream in the middle of a reply that was riding on it. The decisions are pure and live here so their cases
 *  are pinned by tests; the stream code only carries out the answer. */
internal object StreamPolicy {

    /** A chat with no live turn still has a reason to keep its stream open: an outstanding background job (its
     *  finish row is still coming), or a looping goal on the open thread (it pushes its own turns, which have no
     *  placeholder on the phone). */
    fun streamWanted(hasBackgroundJob: Boolean, goal: OpencodeApi.GoalInfo?, isOpenThread: Boolean): Boolean =
        hasBackgroundJob || (goal != null && goal.loop && goal.status == "active" && isOpenThread)

    /** What the one live stream does when a chat goes idle. */
    sealed interface Handoff {
        /** The stream already belongs to this chat, which still has a reason to listen: leave it. */
        object Keep : Handoff

        /** This chat has a reason to listen and does not own the stream yet: open it for this chat. */
        data class Open(val chat: String) : Handoff

        /** Another chat still has turns in flight: the stream moves to it. */
        data class Adopt(val chat: String) : Handoff

        /** Nothing is running anywhere: sleep. */
        object Sleep : Handoff
    }

    /** [liveChats] are the chats with a turn in flight, oldest first; it is only read when [chat] has no reason
     *  to keep listening, so callers can leave it empty in that case. The newest other chat wins. */
    fun handoff(chat: String, wanted: Boolean, owner: String?, liveChats: List<String>): Handoff = when {
        wanted -> if (owner != chat) Handoff.Open(chat) else Handoff.Keep
        else -> liveChats.lastOrNull { it != chat }?.let { Handoff.Adopt(it) } ?: Handoff.Sleep
    }

    /** A background job finished or stopped. The stream this chat held for it is released only when that was the
     *  last job, this chat still owns the stream, and no reply is running in the chat: a running reply's `done`
     *  arrives on that same stream, so dropping it would hide the reply until the next send. */
    fun releaseAfterJobFinish(hasJobsLeft: Boolean, ownsStream: Boolean, replyRunning: Boolean): Boolean =
        !hasJobsLeft && ownsStream && !replyRunning
}
