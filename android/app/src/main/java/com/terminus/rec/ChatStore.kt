package com.terminus.rec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val role: String, // "user" | "assistant" | "tool" | "error" | "system" | "event"
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val attachment: String? = null,
    /** Second and later files of a multi-file send (`attachment` holds the first). */
    val moreAttachments: List<String> = emptyList(),
    val durationSec: Int = 0,
    val turnId: String = "",
    val stepsJson: String = "",
    /** Opencode assistant message id, stamped at settle. Durable merge key:
     *  post-restart replays match rows, not memory. Blank when unknown. */
    val messageID: String = "",
    /** Files this reply changed (settled footer "changed: ..."). Stamped at
     *  settle from the turn's tool step files; empty for old rows. */
    val changedFiles: List<String> = emptyList(),
    /** False while the turn is still streaming (live placeholder, even once
     *  delta text has landed); true once `done` settles it. Parsed messages
     *  holding real text default to settled so old files migrate cleanly. */
    val settled: Boolean = false,
    /** Transient send failure shown inside its original user bubble. */
    val sendFailed: Boolean = false,
    /** Provider's own words for that failure (redacted, capped); rendered
     *  under "send failed" in the same bubble. Empty = harness-side. */
    val failError: String = "",
    /** User ended voice dictation with the spoken send command. */
    val voiceSendCommand: Boolean = false
)

/** Every attachment on a row, first then the rest, in send order. */
internal val ChatMessage.allAttachments: List<String>
    get() = listOfNotNull(attachment) + moreAttachments

/** Pure: pointer row = tool role carrying a turnId (unit-tested). No existing
 *  receipt uses turnId, and pointer text is never matched — the predicate is
 *  structural, so wording changes can't break it. */
internal fun isPointer(m: ChatMessage): Boolean =
    m.role == "tool" && m.turnId.isNotBlank()

/** Pure: the "outcome unknown" status row for one turn. The row is written
 *  with role "system" today and "tool" was an older shape, so cleanup has to
 *  match both — a mismatch leaves the row behind after its turn settles and
 *  stale rows stack up. (Unit-tested.) */
internal fun isUnknownRow(m: ChatMessage, turnId: String): Boolean =
    (m.role == "tool" || m.role == "system") &&
        isUnknownText(m) &&
        m.text.contains("turn=$turnId")

/** Pure: the wording test alone, turn-agnostic — used to keep exactly one
 *  such row per chat. Covers every wording shipped ("still no reply",
 *  "no reply yet", "no reply — turn failed"); role is checked by callers. */
internal fun isUnknownText(m: ChatMessage): Boolean =
    m.text.startsWith("still no reply") || m.text.startsWith("no reply")

/** Pure: terminal-clear keeps a row only when its turn is still pending —
 *  live placeholders settle in place, unknown rows still reconcile
 *  (unit-tested). Everything settled drops. */
internal fun clearKeeps(m: ChatMessage, liveTurns: Set<String>): Boolean {
    if (m.role == "assistant" && m.turnId.isNotBlank() && !m.settled) return m.turnId in liveTurns
    if ((m.role == "tool" || m.role == "system") &&
        (m.text.startsWith("still no reply") || m.text.startsWith("no reply"))) {
        return liveTurns.any { m.text.contains("turn=$it") }
    }
    return false
}

/** Pure: rows that earn the unread dot. Delivered results and failures
 *  do; tool/system/event status rows do not. The one event that does is
 *  the session-gone note: the turn will never answer, so a user who
 *  walked away still needs the bell. (Unit-tested.) */
internal fun earnsUnread(m: ChatMessage): Boolean =
    m.turnId.isBlank() && (
        m.role == "assistant" || m.role == "error" ||
            (m.role == "event" && m.text.startsWith("session gone"))
        )

/** Pure: a row the unread counter counted. earnsUnread rows (added unsettled-free) plus delivered
 *  replies, which keep their turnId when settleTurn bumps the counter. Unit-tested. */
internal fun countsAsUnread(m: ChatMessage): Boolean =
    earnsUnread(m) || (m.role == "assistant" && m.settled && m.turnId.isNotBlank())

/** Pure: index of the oldest unread row: the `unread` newest rows that count. -1 when nothing is
 *  unread or the counter outruns the rows (a needs-you flag bumps it without a row): the thread then
 *  opens at the bottom as before. Unit-tested. */
internal fun firstUnreadIndex(messages: List<ChatMessage>, unread: Int): Int {
    if (unread <= 0) return -1
    var left = unread
    for (i in messages.indices.reversed()) {
        if (countsAsUnread(messages[i]) && --left == 0) return i
    }
    return -1
}

/** Pure: settle a merged turn + conditional shared-reply move (unit-tested).
 *  The live placeholder becomes a pointer row in its own slot (MID stamped
 *  when known; blank MID = inferred merge). When the claimed reply sits
 *  ABOVE the placeholder and no settled assistant row with a different MID
 *  lies between, payloads swap: the reply takes the merging slot, the
 *  pointer takes the old one — turnIds stay in their slots, so every
 *  turnId-keyed predicate keeps working. Anything else → pointer only.
 *  Null = no live placeholder (caller drops silently). */
internal fun mergeSettle(
    messages: List<ChatMessage>,
    turnId: String,
    mid: String,
    pointerText: String,
): List<ChatMessage>? {
    if (turnId.isBlank()) return null
    val idx = messages.indexOfFirst {
        it.role == "assistant" && it.turnId.isNotBlank() && !it.settled && it.turnId == turnId
    }
    if (idx < 0) return null
    val out = messages.toMutableList()
    out[idx] = out[idx].copy(role = "tool", text = pointerText, settled = true, messageID = mid)
    if (mid.isBlank()) return out
    val claimed = out.indexOfFirst { it.role == "assistant" && it.messageID == mid && !isPointer(it) }
    if (claimed < 0 || claimed >= idx) return out
    for (i in claimed + 1 until idx) {
        val m = out[i]
        if (m.role == "assistant" && m.settled && m.messageID != mid) return out
    }
    val reply = out[claimed]
    out[idx] = out[idx].copy(
        role = "assistant",
        text = reply.text,
        stepsJson = reply.stepsJson,
        settled = true,
        messageID = mid,
    )
    out[claimed] = reply.copy(role = "tool", text = pointerText, stepsJson = "", settled = true)
    return out
}

/** Pure: this turn already rendered any outcome (assistant reply or merge
 *  pointer), so a redelivery drops (unit-tested). User rows excluded — they
 *  open turns, never close them. Live placeholders excluded — unsettled. */
internal fun turnRendered(messages: List<ChatMessage>, turnId: String): Boolean {
    if (turnId.isBlank()) return false
    return messages.any { it.turnId == turnId && it.settled && (it.role == "assistant" || isPointer(it)) }
}

/** Pure: durable merge check over rows (unit-tested). Any NON-POINTER row
 *  already carrying this opencode messageID — the post-restart backing for
 *  the memory claim set. Pointers are deliberately invisible here: a
 *  pointer-held MID must never route a redelivery (direction/jump reads it
 *  through a separate lookup). Placeholders never carry MIDs. */
internal fun rowHasMessageId(messages: List<ChatMessage>, mid: String): Boolean {
    if (mid.isBlank()) return false
    return messages.any { it.messageID == mid && !isPointer(it) }
}

/** Pure: index of the settled row for turnId whose kept text the new text
 *  extends (partial → full upgrade), else -1. Equal text is -1 (the
 *  caller dedupes replays); unrelated text is -1 (never overwrite a
 *  stranger row). Unit-tested. */
internal fun upgradeTurnIndex(messages: List<ChatMessage>, turnId: String, text: String): Int {
    if (turnId.isBlank() || text.isBlank()) return -1
    val idx = messages.indexOfFirst { it.role == "assistant" && it.turnId == turnId && it.settled }
    if (idx < 0) return -1
    val cur = messages[idx].text
    // Normalized: a whitespace-only difference still upgrades in place.
    return if (cur != text && TurnActivity.normText(text).startsWith(TurnActivity.normText(cur))) idx else -1
}

/** Pure: index of a stale same-turn partial superseded by fullText (unit-tested).
 *  A settled row for this turn whose text is a strict prefix of the incoming
 *  full reply is the dead-settle leftover — safe to remove when the claimed
 *  (merged) reply renders instead. Anything else → -1, never touch. */
internal fun stalePartialIndex(messages: List<ChatMessage>, turnId: String, fullText: String): Int {
    if (turnId.isBlank() || fullText.isBlank()) return -1
    val normFull = TurnActivity.normText(fullText)
    val idx = messages.indexOfFirst {
        it.role == "assistant" && it.turnId == turnId && it.settled &&
            it.text != fullText && normFull.startsWith(TurnActivity.normText(it.text)) &&
            TurnActivity.normText(it.text).isNotBlank()
    }
    return idx
}

data class ChatSession(    val id: String,
    var title: String,
    var updatedAt: Long,
    var backend: String = "opencode",
    var model: String,
    /** Reasoning effort for codex turns (low/medium/high, blank = medium).
     *  Other backends ignore it. Persisted per chat. */
    var effort: String  = "",
    /** Working directory for codex turns (blank = Desktop root).
     *  Persisted per chat; the server resolves it every turn. */
    var cwd: String = "",
    var draft: String = "",
    var pinned: Boolean = false,
    /** Archive state: >0 archived by hand at that time (until new activity), <0 kept in Recent by hand at
     *  -value (auto-archive counts from then), 0 never touched. See SessionAdapter.isArchived. */
    var archivedAt: Long = 0L,
    var unread: Int = 0,
    /** Provider-native remote session/thread id (blank = never bound).
     *  Backend-qualified: only meaningful alongside `backend`. Used to
     *  tell mapped chats from importable server rows. */
    var remoteId: String = "",
    /** Bound session id per backend, so switching back resumes that backend's session.
     *  `remoteId` always mirrors the active backend's entry here. */
    val remoteIdsByBackend: MutableMap<String, String> = mutableMapOf(),
    /** True once a human (or fork/import) names the chat: the server title
     *  sync then leaves it alone. Auto names (first line, 📎) stay synced. */
    var titleLocked: Boolean = false,
    /** Why this chat is waiting on the person (browser hand-off, approval, failed run), blank = nothing.
     *  Set by code from a kind:"event" inbox entry or a local hand-off, never by the AI. Cleared when the chat
     *  opens. Counts only while needsYouUntil is in the future (see needsYouTtlMs). */
    var needsYou: String = "",
    var needsYouUntil: Long = 0L,
    val messages: MutableList<ChatMessage> = mutableListOf()
) {
    /** Needs-you that has not expired. */
    fun waitingOnYou(now: Long = System.currentTimeMillis()): Boolean = needsYouActive(needsYou, needsYouUntil, now)
}

/** [skill] = a Terminus skill offered by a backend with no native skill commands: sent as a prompt. */
data class SlashCommand(val name: String, val desc: String, val skill: Boolean = false)

/** File-backed session store. One JSON file, no new deps. */
class ChatStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "chat").apply { mkdirs() }
    private val file = File(dir, "sessions.json")
    private val lock: Any get() = Shared.lock

    /** Process-wide single writer. MainActivity AND InboxWorker (every
     *  15 min, app dead or alive) each build their own ChatStore — with
     *  per-instance locks/caches/executors their tmp+rename writes raced,
     *  corrupting sessions.json; the next load then parsed [], and a later
     *  write baked the wipe in. That chain is the recurring blank-app data
     *  loss. All instances share one lock, one cache, one write queue, so
     *  only complete snapshots ever reach disk, in order. */
    private object Shared {
        val lock = Any()
        var cache: MutableList<ChatSession>? = null
        var dirty = false
        var pendingWrite: Runnable? = null
        val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var graveCache: Set<String>? = null
        /** True once any load in this process saw real data. An empty
         *  snapshot may only hit disk when the store is KNOWN empty
         *  (fresh install / user deleted everything) — never on top of a
         *  file we merely failed to load. */
        var seenNonEmpty = false
    }

    // In-memory model, loaded once per process: every read/mutation below
    // touches this, never the file. Persistence is a separate debounced
    // background step, so per-keystroke and per-delta updates cost a list
    // mutation, not a full JSON parse + serialize + atomic write on the
    // caller's thread. Callers must never mutate returned sessions in
    // place — all writes go through the methods below (each schedules
    // persistence).

    /** The live model, loading from disk exactly once per process. */
    private fun cache(): MutableList<ChatSession> = synchronized(lock) {
        val c = Shared.cache ?: readAllFromDisk().toMutableList().also { Shared.cache = it }
        if (c.isNotEmpty()) Shared.seenNonEmpty = true
        c
    }

    /** Main-looper coalescing; null when no looper (background-only edge). */
    private fun mainHandler(): android.os.Handler? {
        return try {
            val looper = android.os.Looper.getMainLooper() ?: return null
            android.os.Handler(looper)
        } catch (_: Exception) { null }
    }

    /** Mark dirty + coalesce a background persist (150ms bursts, e.g. a run
     *  of delta fragments, collapse into one write). */
    private fun scheduleWrite(): Unit {
        synchronized(lock) {
            Shared.dirty = true
            if (Shared.pendingWrite != null) return
            val task = Runnable {
                val snapshot = synchronized(lock) {
                    if (!Shared.dirty) return@Runnable
                    Shared.dirty = false
                    Shared.pendingWrite = null
                    Shared.cache?.toList() ?: emptyList()
                }
                persistSnapshot(snapshot)
            }
            Shared.pendingWrite = task
            val h = mainHandler()
            if (h != null) {
                try { h.postDelayed(task, 150); return } catch (_: Exception) {}
            }
            // No looper: persist straight onto the shared writer queue
            // instead of dropping the write.
            Shared.pendingWrite = null
            persistSnapshot(Shared.cache?.toList() ?: emptyList())
        }
    }

    /** Never bake a wipe: an empty snapshot is refused while a non-trivial
     *  file stands that this process never successfully loaded. */
    private fun mayPersistEmpty(): Boolean {
        if (Shared.seenNonEmpty) return true
        return try { !(file.exists() && file.length() > 2) } catch (_: Exception) { true }
    }

    /** Off-caller-thread persist (shared single-thread executor = every
     *  instance's writes stay ordered, never interleaved). */
    private fun persistSnapshot(all: List<ChatSession>) {
        if (all.isEmpty() && !mayPersistEmpty()) return
        try {
            Shared.ioExecutor.execute {
                try { atomicWrite(file, renderAll(all)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Synchronous persist for paths that must survive a kill immediately
     *  (delete/clear, activity backgrounding, /stop). Rare by design;
     *  a second call with nothing dirty is a no-op. Single writer path:
     *  the snapshot goes through the shared executor behind any earlier
     *  snapshot and this waits for it — a queued older write can never
     *  land after this newer one, and return means completed (true) or
     *  failed with dirty preserved for the next write (false). Never call
     *  from the shared writer thread itself (no caller does). */
    fun flushNow(): Boolean {
        val snapshot = takeSnapshot() ?: return true
        return try {
            val f = Shared.ioExecutor.submit(java.util.concurrent.Callable {
                atomicWrite(file, renderAll(snapshot))
            })
            f.get(15, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            synchronized(lock) { Shared.dirty = true }
            false
        }
    }

    /** Fire-and-forget write for UI-thread call sites (send/close/draft):
     *  the old blocking flush froze the UI for a full serialize + fsync on
     *  every send and every chat switch, cost growing with history size.
     *  Failure re-dirties, so the next debounced write still picks it up —
     *  same durability posture as scheduleWrite, just prompt. Destructive
     *  paths (clear/delete) and onPause keep blocking flushNow. */
    fun flushAsync() {
        val snapshot = takeSnapshot() ?: return
        try {
            Shared.ioExecutor.submit {
                try {
                    atomicWrite(file, renderAll(snapshot))
                } catch (_: Exception) {
                    synchronized(lock) { Shared.dirty = true }
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { Shared.dirty = true }
        }
    }

    private fun takeSnapshot(): List<ChatSession>? = synchronized(lock) {
        Shared.pendingWrite?.let { pending ->
            try { mainHandler()?.removeCallbacks(pending) } catch (_: Exception) {}
        }
        Shared.pendingWrite = null
        if (!Shared.dirty) return null
        Shared.dirty = false
        val snapshot = Shared.cache?.toList() ?: emptyList()
        if (snapshot.isEmpty() && !mayPersistEmpty()) return null
        snapshot
    }

    private fun renderAll(all: List<ChatSession>): String {
        val arr = JSONArray()
        all.forEach { arr.put(render(it)) }
        return arr.toString()
    }

    fun list(): List<ChatSession> = synchronized(lock) {
        cache().sortedWith(compareByDescending<ChatSession> { it.pinned }
            .thenByDescending { it.updatedAt })
    }

    fun get(id: String): ChatSession? = synchronized(lock) { cache().firstOrNull { it.id == id } }

    /** Stable threads (job `schedule:<name>` chats + the `alerts` thread):
     *  return the existing session or create it with the given id. */
    fun ensure(id: String, title: String, model: String = defaultModel()): ChatSession =
        ensure(id, title, "opencode", model)

    fun ensure(id: String, title: String, backend: String, model: String): ChatSession = synchronized(lock) {
        val all = cache()
        all.firstOrNull { it.id == id }?.let { return it }
        val s = ChatSession(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            backend = Prefs.cannonBackend(backend),
            model = model
        )
        all += s
        scheduleWrite()
        s
    }

    fun create(model: String = defaultModel()): ChatSession = create("opencode", model)

    fun create(backend: String, model: String): ChatSession = synchronized(lock) {
        val all = cache()
        val s = ChatSession(
            id = "s-${System.currentTimeMillis()}",
            title = "new chat",
            updatedAt = System.currentTimeMillis(),
            backend = Prefs.cannonBackend(backend),
            model = model
        )
        all += s
        scheduleWrite()
        s
    }

    /** App start: any browser card still in its live form belongs to a session that no longer exists.
     *  Turn it into a finished card (no steps are known) so it stops looking alive. Returns the count. */
    fun freezeStaleBrowserCards(): Int {
        var n = 0
        synchronized(lock) {
            val hex = """{"n":0,"steps":[]}""".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            for (s in cache()) for (i in s.messages.indices) {
                val m = s.messages[i]
                if (m.text == "[Browser](terminus://browser)") {
                    s.messages[i] = m.copy(text = "[Browser session](terminus://browser#f=$hex)")
                    n++
                }
            }
            if (n > 0) scheduleWrite()
        }
        return n
    }

    /** Swap the text of the row stamped [ts] whose text starts with [startsWith]. True when it changed. */
    fun replaceTextByTs(id: String, ts: Long, startsWith: String, text: String): Boolean {
        synchronized(lock) {
            val s = cache().firstOrNull { it.id == id } ?: return false
            val i = s.messages.indexOfFirst { it.ts == ts && it.text.startsWith(startsWith) }
            if (i < 0) return false
            s.messages[i] = s.messages[i].copy(text = text)
            scheduleWrite()
            return true
        }
    }

    fun addMessage(id: String, msg: ChatMessage) = synchronized(lock) {
        appendMessage(id, msg)
    }

    /** Timestamp-ordered insert: loop nudge dividers carry the server event
     *  time, which predates the continuation reply already on screen.
     *  Plain append parks them after it (reply reply continuing). */
    fun insertMessageByTs(id: String, msg: ChatMessage) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val at = if (msg.ts > 0) msg.ts else System.currentTimeMillis()
        val pos = s.messages.indexOfFirst { it.ts > 0 && it.ts > at }
        val fixed = if (msg.ts > 0) msg else msg.copy(ts = at)
        if (pos < 0) appendMessage(id, fixed)
        else {
            s.messages.add(pos, fixed)
            s.updatedAt = System.currentTimeMillis()
            scheduleWrite()
        }
    }

    /** Called when a reply or delivery lands, with its text. The app uses it to arm alarm links the moment they arrive,
     *  whatever screen is showing (nothing has to render). Must stay quick: it runs under the store lock. */
    @Volatile var onLanded: ((chatId: String, text: String) -> Unit)? = null

    private fun landed(id: String, text: String) {
        if (!text.contains("terminus://alarm")) return
        try { onLanded?.invoke(id, text) } catch (_: Exception) {}
    }

    private fun appendMessage(id: String, msg: ChatMessage) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.messages += msg
        s.updatedAt = System.currentTimeMillis()
        // Unread = genuinely delivered results only: a settled assistant row
        // with no turn (inbox/worker deliveries land here), plus error rows
        // (failures deserve the bell dot even though they never ping), plus
        // the session-gone event (a turn that will never answer). Live
        // placeholders carry a turnId and settle via settleTurn (exactly
        // one unread there); other tool/system/event rows never count.
        if (earnsUnread(msg)) s.unread += 1
        // Only a fresh arrival counts as landing: an old message re-added (import, restore) must not arm anything.
        if (msg.role == "assistant" && System.currentTimeMillis() - (if (msg.ts > 0) msg.ts else System.currentTimeMillis()) < 15 * 60_000L) landed(id, msg.text)
        if (s.title == "new chat" && msg.role == "user" && msg.text.isNotBlank()) {
            // Non-opencode chats never get server titles: tidy locally.
            // Opencode keeps the raw first line (its server title wins).
            s.title = if (s.backend == "opencode") msg.text.trim().take(42).lineSequence().first()
                else autoTitle(msg.text)
        }
        scheduleWrite()
    }

    fun dropLastAssistant(id: String, placeholder: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val last = s.messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.text == placeholder) {
            s.messages.removeAt(s.messages.size - 1)
            scheduleWrite()
        }
    }

    /** Collapse repeats: drop the trailing row when it already carries this
     *  exact tool note (redelivered error dones refresh one row instead of
     *  stacking identical triplets). */
    /** True when the chat's newest row is a tool/event note with exactly [text]. */
    fun lastNoteIs(id: String, text: String): Boolean = synchronized(lock) {
        val last = cache().firstOrNull { it.id == id }?.messages?.lastOrNull() ?: return false
        (last.role == "tool" || last.role == "event") && last.text == text
    }

    fun dropLastMatching(id: String, text: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val last = s.messages.lastOrNull()
        if (last != null && (last.role == "tool" || last.role == "event") && last.text == text) {
            s.messages.removeAt(s.messages.size - 1)
            scheduleWrite()
        }
    }

    /** Drop the failure note for one turn wherever it stands (a successful
     *  resend clears its own footer even when later rows buried it).
     *  Matches by the retry link's turn, never by exact text. */
    fun dropFailureNoteForTurn(id: String, turnId: String) = synchronized(lock) {
        if (turnId.isBlank()) return
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val re = Regex("turn=${Regex.escape(turnId)}(?![A-Za-z0-9-])")
        val before = s.messages.size
        s.messages.removeAll { m ->
            (m.role == "error" || m.role == "system") && m.turnId.isBlank() && re.containsMatchIn(m.text)
        }
        if (s.messages.size != before) scheduleWrite()
    }

    /** Discard one queued message: its user row (and a live placeholder for the same turn) goes.
     *  False when the chat or the turn is not there, so callers can tell nothing changed. */
    fun discardQueuedTurn(id: String, turnId: String): Boolean = synchronized(lock) {
        if (turnId.isBlank()) return false
        val s = cache().firstOrNull { it.id == id } ?: return false
        val before = s.messages.size
        s.messages.removeAll { m ->
            (m.role == "user" && m.turnId == turnId) || (isLive(m) && m.turnId == turnId)
        }
        if (s.messages.size == before) return false
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        true
    }

    /** Collapse repeats: a new failure note replaces the trailing one
     *  instead of stacking, so back-to-back failures leave a single quiet
     *  line. Matches by shape (error role, retry link, send-failed tool
     *  text), never by exact text — redeliveries reword the reason. */
    fun dropLastFailureNote(id: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val last = s.messages.lastOrNull() ?: return
        val failure = last.role == "error" ||
            ((last.role == "system" || last.role == "tool") &&
                ("terminus://retry" in last.text || "send failed" in last.text))
        if (!failure) return
        s.messages.removeAt(s.messages.size - 1)
        scheduleWrite()
    }

    /** Drop "outcome unknown" rows for a turn that just settled (one way
     *  or another): the row's retry link carries the turnId, so only that
     *  turn's own rows go — other turns' rows stay. Keeps dead-turn scars
     *  from piling up behind late failures. */
    fun dropUnknownRows(id: String, turnId: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val before = s.messages.size
        s.messages.removeAll { isUnknownRow(it, turnId) }
        if (s.messages.size != before) scheduleWrite()
    }

    /** Keep at most ONE "no reply" failure row per chat: the newest turn
     *  replaces the previous one instead of stacking under it, so a run of
     *  dead turns leaves a single line with a retry that targets the turn
     *  that actually failed last. Legacy wordings count too. */
    fun dropAllUnknownRows(id: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val before = s.messages.size
        s.messages.removeAll { isUnknownText(it) }
        if (s.messages.size != before) scheduleWrite()
    }

    /** A message is a live placeholder while its turn streams, matched by
     *  turnId — never by the "…" text, which deltas overwrite as they land. */
    private fun isLive(m: ChatMessage): Boolean =
        m.role == "assistant" && m.turnId.isNotBlank() && !m.settled

    /** Overwrite the standing live placeholder (delta buffering owns the
     *  accumulation; the store is persisted throttled, not per fragment).
     *  Returns false when no placeholder for this turn stands. */
    fun setLiveText(id: String, turnId: String, text: String): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfFirst { isLive(it) && it.turnId == turnId }
        if (idx < 0) return false
        val cur = s.messages[idx]
        s.messages[idx] = cur.copy(text = text.take(32768), ts = System.currentTimeMillis())
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return true
    }

    /** Delete the live browser placeholder from one chat. Frozen records are kept. */
    fun removeLiveBrowserCard(id: String): Boolean = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return false
        val before = s.messages.size
        s.messages.removeAll { it.role == "assistant" && it.text == "[Browser](terminus://browser)" }
        if (s.messages.size == before) return false
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return true
    }

    /** Delete stray live browser placeholders from every chat except [exceptId].
     *  Frozen records ([Name](terminus://browser#f=…)) are never touched.
     *  Returns how many were removed. */
    fun removeLiveBrowserCards(exceptId: String? = null): Int = synchronized(lock) {
        var n = 0
        for (s in cache()) {
            if (s.id == exceptId) continue
            val before = s.messages.size
            s.messages.removeAll { it.role == "assistant" && it.text == "[Browser](terminus://browser)" }
            if (s.messages.size != before) {
                n += before - s.messages.size
                s.updatedAt = System.currentTimeMillis()
            }
        }
        if (n > 0) scheduleWrite()
        return n
    }

    /** Replace the browser card placeholder with its frozen snapshot link (the chat's latest unfrozen card). */
    fun freezeBrowserCard(id: String, text: String): Boolean = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfLast { it.role == "assistant" && it.text == "[Browser](terminus://browser)" }
        if (idx < 0) return false
        s.messages[idx] = s.messages[idx].copy(text = text)
        scheduleWrite()
        return true
    }

    /** Append a live text fragment to the standing placeholder. Returns the
     *  full text, or null when no placeholder for this turn stands. */
    fun appendPlaceholderText(id: String, turnId: String, frag: String): String? = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return null
        val idx = s.messages.indexOfFirst { isLive(it) && it.turnId == turnId }
        if (idx < 0) return null
        val cur = s.messages[idx]
        val grown = (cur.text.takeIf { it != "…" } ?: "") + frag
        s.messages[idx] = cur.copy(text = grown.take(32768), ts = System.currentTimeMillis())
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return s.messages[idx].text
    }

    /** Standing live-placeholder text for a turn (partial deltas), or null
     *  when no live placeholder stands. Lets error paths settle useful
     *  partial output instead of silently deleting it. */
    fun placeholderText(id: String, turnId: String): String? = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return null
        val m = s.messages.firstOrNull { isLive(it) && (turnId.isBlank() || it.turnId == turnId) } ?: return null
        val t = m.text
        return if (t.isBlank() || t == "…") null else t
    }

    /** Settle one send-through turn in place (ordering fix): the live
     *  placeholder carrying this turnId becomes the reply, so user messages
     *  never slide behind responses. Returns false when the placeholder is
     *  gone; replay handling must not append a second copy. A nonblank
     *  messageID stamps the row: the durable merge key for post-restart
     *  replays (memory claims don't survive death). */
    fun settleTurn(id: String, turnId: String, text: String, stepsJson: String = "", messageID: String = ""): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfFirst { isLive(it) && (turnId.isBlank() || it.turnId == turnId) }
        if (idx >= 0) {
            val cur = s.messages[idx]
            s.messages[idx] = cur.copy(
                text = text,
                stepsJson = stepsJson,
                settled = true,
                messageID = if (messageID.isNotBlank()) messageID else cur.messageID,
            )
            s.updatedAt = System.currentTimeMillis()
            s.unread += 1
            scheduleWrite()
            // A reply that landed clears its own failure state: the status
            // must never sit beside a delivered answer (the stale-timeout
            // race flags the bubble a second before the reply arrives).
            // Reentrant lock: same monitor, safe inline.
            dropFailureNoteForTurn(id, turnId)
            landed(id, text)
            if (turnId.isNotBlank()) {
                val uidx = s.messages.indexOfFirst { it.role == "user" && it.turnId == turnId }
                if (uidx >= 0 && s.messages[uidx].sendFailed) {
                    s.messages[uidx] = s.messages[uidx].copy(sendFailed = false, failError = "")
                    scheduleWrite()
                }
            }
            return true
        }
        // Placeholder already gone: duplicate delivery or cleared thread.
        if (turnId.isNotBlank() && s.messages.any { it.role == "assistant" && it.turnId == turnId && it.text == text }) return false
        return false
    }

    /** Settle a merged turn's placeholder into a pointer row and, when the
     *  guard passes, move the shared reply payload down onto the merging
     *  slot (turnIds stay in their slots) — one synchronized mutation, one
     *  rule. Role="tool" (muted, no toolhead), NO unread bump. Returns false
     *  when no live placeholder stands (caller drops silently instead). */
    fun settleAsPointer(id: String, turnId: String, text: String, messageID: String = ""): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val next = mergeSettle(s.messages, turnId, messageID, text) ?: return false
        s.messages.clear()
        s.messages.addAll(next)
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        landed(id, text)
        return true
    }

    /** Durable merge check: any row already carrying this opencode messageID.
     *  Backs the memory claim set across process death. */
    fun hasMessageId(id: String, mid: String): Boolean = synchronized(lock) {
        rowHasMessageId(cache().firstOrNull { it.id == id }?.messages ?: emptyList(), mid)
    }

    /** Stamp the settled reply's changed-files footer. Matches the row by
     *  turnId, or by messageID for merged turns whose reply settled under a
     *  different turn. Pointer rows never match (bare labels, no footer).
     *  No-op on empty lists or no match. Returns whether a row was stamped. */
    fun stampChangedFiles(id: String, turnId: String, messageID: String, files: List<String>): Boolean = synchronized(lock) {
        if (files.isEmpty()) return false
        val clean = files.mapNotNull { it.trim().takeUnless { s -> s.isEmpty() } }.distinct().take(12)
        if (clean.isEmpty()) return false
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        var stamped = false
        s.messages.forEachIndexed { i, m ->
            if (m.role != "assistant" || isPointer(m)) return@forEachIndexed
            val mine = (turnId.isNotBlank() && m.turnId == turnId) ||
                (messageID.isNotBlank() && m.messageID == messageID)
            if (!mine) return@forEachIndexed
            val merged = (m.changedFiles + clean).distinct().take(12)
            if (merged != m.changedFiles) {
                s.messages[i] = m.copy(changedFiles = merged)
                stamped = true
            }
        }
        if (stamped) {
            s.updatedAt = System.currentTimeMillis()
            scheduleWrite()
        }
        return stamped
    }

    /** Server-confirmed running: re-stamp the placeholder clock so the
     *  stop button and freshness checks treat a quiet-but-alive turn as
     *  live. No text change, no new row. False when no placeholder stands. */
    fun touchLive(id: String, turnId: String): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfFirst { isLive(it) && it.turnId == turnId }
        if (idx < 0) return false
        val cur = s.messages[idx]
        s.messages[idx] = cur.copy(ts = System.currentTimeMillis())
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return true
    }

    fun dropPlaceholder(id: String, turnId: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val idx = s.messages.indexOfFirst { isLive(it) && (turnId.isBlank() || it.turnId == turnId) }
        if (idx >= 0) {
            s.messages.removeAt(idx)
            scheduleWrite()
        }
    }

    /** Late-`done` upgrade: the settled row already kept for this turn
     *  (partial from an earlier dead-settle) takes the full text in place
     *  — no second row. False when no upgrade applies (caller appends or
     *  drops). Unread untouched: the kept row already counted it. */
    fun replaceTurnText(id: String, turnId: String, text: String, stepsJson: String = "", messageID: String = ""): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = upgradeTurnIndex(s.messages, turnId, text)
        if (idx < 0) return false
        val cur = s.messages[idx]
        s.messages[idx] = cur.copy(
            text = text,
            stepsJson = if (stepsJson.isNotBlank()) stepsJson else cur.stepsJson,
            messageID = if (messageID.isNotBlank()) messageID else cur.messageID,
        )
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        // Same stale-timeout race as settleTurn: a delivered upgrade clears
        // its own failure note and flag.
        dropFailureNoteForTurn(id, turnId)
        if (turnId.isNotBlank()) {
            val uidx = s.messages.indexOfFirst { it.role == "user" && it.turnId == turnId }
            if (uidx >= 0 && s.messages[uidx].sendFailed) {
                s.messages[uidx] = s.messages[uidx].copy(sendFailed = false, failError = "")
                scheduleWrite()
            }
        }
        return true
    }

    /** Remove a stale same-turn partial superseded by a merged reply (see
     *  stalePartialIndex). The dead-settle leftover must not stand beside
     *  the full reply that claimed its messageID. */
    fun dropStalePartial(id: String, turnId: String, fullText: String): Boolean = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = stalePartialIndex(s.messages, turnId, fullText)
        if (idx < 0) return false
        s.messages.removeAt(idx)
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return true
    }

    /** Any turn still streaming in this chat. */
    fun hasLive(id: String): Boolean = synchronized(lock) {
        cache().firstOrNull { it.id == id }?.messages?.any { isLive(it) } ?: false
    }

    /** Chats with live (unsettled) placeholders — turns still in flight.
     *  The stream layer stays awake for these and may sleep otherwise. */
    fun chatsWithLive(): List<String> = synchronized(lock) {
        cache().filter { s -> s.messages.any { isLive(it) } }.map { it.id }
    }

    /** Live placeholders with their start ts — lets the UI tell an
     *  in-flight turn from an orphaned one (done lost to a restart). */
    fun liveTurns(id: String): List<Pair<String, Long>> = synchronized(lock) {
        cache().firstOrNull { it.id == id }?.messages
            ?.filter { isLive(it) }
            ?.map { it.turnId to it.ts } ?: emptyList()
    }

    /** Drop every live placeholder (e.g. /stop cancelling overlapping turns). */
    fun dropAllPlaceholders(id: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        if (s.messages.removeAll { isLive(it) }) {
            scheduleWrite()
        }
    }

    /** Watchdog for hung turns: live placeholders older than maxAgeMs are
     *  NOT removed — a timeout is not proof of failure, and dropping the
     *  placeholder would orphan a late `done` (settleTurn needs it). Instead
     *  each newly-stale turnId is reported once and its clock re-armed, so
     *  the caller can reconcile (server turn registry → still running →
     *  honest unknown) while the placeholder stays settleable. Only
     *  definitive cancellation (/stop), failure paths, or user deletion
     *  remove placeholders. */
    fun sweepStalePlaceholders(id: String, maxAgeMs: Long = 10 * 60 * 1000): List<String> = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return emptyList()
        val now = System.currentTimeMillis()
        val stale = s.messages.filter { m -> isLive(m) && now - m.ts > maxAgeMs }.map { it.turnId }.filter { it.isNotBlank() }.distinct()
        if (stale.isEmpty()) return emptyList()
        for (i in s.messages.indices) {
            val m = s.messages[i]
            if (isLive(m) && m.turnId in stale) s.messages[i] = m.copy(ts = now)
        }
        s.updatedAt = now
        scheduleWrite()
        return stale
    }

    /** Single-pass user send: append the user row, keep live placeholders
     *  after it, apply auto titles, clear the draft — one read-modify-write
     *  so send-tap cost stays flat as history grows. */
    fun appendUserTurn(id: String, msg: ChatMessage, quoteTitle: String? = null, attachTitle: String? = null) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.messages += msg
        // Placeholders stay in their original slot: User A → Reply A →
        // User B → Reply B, even while A still streams. settleTurn matches
        // by turnId in place, so no reordering is ever needed.
        s.updatedAt = System.currentTimeMillis()
        if (s.title == "new chat" && msg.role == "user" && msg.text.isNotBlank()) {
            s.title = if (s.backend == "opencode") msg.text.trim().take(42).lineSequence().first()
                else autoTitle(msg.text)
            if (quoteTitle != null && !s.titleLocked) s.title = quoteTitle
        }
        // attachTitle arrives pre-gated by the caller on the pre-send title
        // (mirrors the old two-pass order exactly, including overwriting a
        // just-derived first line on quote+attachment sends).
        if (attachTitle != null) s.title = attachTitle
        s.draft = ""
        scheduleWrite()
    }

    /** The persisted user row that opened this turn (wire text incl. quote
     *  prefix + attachment path). Survives process death, unlike the memory
     *  stash — retry recovers inputs from here. */
    fun userTurnFor(id: String, turnId: String): ChatMessage? = synchronized(lock) {
        if (turnId.isBlank()) return null
        cache().firstOrNull { it.id == id }?.messages
            ?.firstOrNull { it.role == "user" && it.turnId == turnId }
    }

    /** Update the original user bubble when an attempt fails or is retried. */
    fun setUserSendFailed(id: String, turnId: String, failed: Boolean, error: String = ""): Boolean = synchronized(lock) {
        if (turnId.isBlank()) return false
        val s = cache().firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfLast { it.role == "user" && it.turnId == turnId }
        if (idx < 0) return false
        s.messages[idx] = s.messages[idx].copy(sendFailed = failed, failError = if (failed) error.take(500) else "")
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        true
    }

    /** Retries reuse the same user bubble but get a fresh server turn id. */
    fun rebindUserTurn(id: String, oldTurnId: String, newTurnId: String): Boolean = synchronized(lock) {
        if (oldTurnId.isBlank() || newTurnId.isBlank()) return false
        val s = cache().firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfLast { it.role == "user" && it.turnId == oldTurnId }
        if (idx < 0) return false
        s.messages[idx] = s.messages[idx].copy(turnId = newTurnId, sendFailed = false, failError = "")
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        true
    }

    /** Legacy server failures lack a turn id; attach them to the latest send. */
    fun failLatestUserSend(id: String): Boolean = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfLast { it.role == "user" && it.turnId.isNotBlank() }
        if (idx < 0) return false
        s.messages[idx] = s.messages[idx].copy(sendFailed = true)
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        true
    }

    /** Rewrite a failed turn's user text in place (tap-to-edit): the bubble
     *  shows the edited text and the resend carries it. The "> quote" wire
     *  prefix is the caller's to preserve. False when the row is gone
     *  (restart race, cleared thread). */
    fun updateUserText(id: String, turnId: String, text: String): Boolean = synchronized(lock) {
        if (turnId.isBlank() || text.isBlank()) return false
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return false
        val idx = s.messages.indexOfFirst { it.role == "user" && it.turnId == turnId }
        if (idx < 0) return false
        s.messages[idx] = s.messages[idx].copy(text = text, ts = System.currentTimeMillis())
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
        return true
    }

    /** Mark the queued bubble as sending and stamp the drained turn's id, so
     *  failure status, retry and edit attach to it like any direct send.
     *  No-op when the row is gone (edited away while queued). */
    fun markWaitSending(id: String, text: String, turnId: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val marker = "\n\n⏳ queued behind running reply (/wait)"
        val idx = s.messages.indexOfFirst { it.role == "user" && it.text == text + marker }
        if (idx >= 0) {
            s.messages[idx] = s.messages[idx].copy(text = text, turnId = turnId)
            scheduleWrite()
        }
    }

    fun setDraft(id: String, draft: String) = synchronized(lock) {
        val all = cache()
        all.firstOrNull { it.id == id }?.draft = draft
        scheduleWrite()
    }

    fun setModel(id: String, model: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.model = model
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
    }

    /** Working directory for codex turns (blank = Desktop). Client-side
     *  shape check only; the server re-validates containment every turn. */
    fun setCwd(id: String, cwd: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.cwd = cwd.take(300)
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
    }

    /** Reasoning effort for codex turns (low/medium/high, blank = medium). */
    fun setEffort(id: String, effort: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.effort = effort.take(10)
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
    }

    /** Atomic backend+model swap: a session never persists a new backend
     *  with an old provider-specific model (or vice versa). */
    fun setBackendAndModel(id: String, backend: String, model: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val next = Prefs.cannonBackend(backend)
        if (next != s.backend) {
            // Park the old backend's session id; the new backend resumes its own, if it has one.
            if (s.remoteId.isNotBlank()) s.remoteIdsByBackend[s.backend] = s.remoteId
            s.remoteId = s.remoteIdsByBackend[next].orEmpty()
        }
        s.backend = next
        s.model = model
        s.updatedAt = System.currentTimeMillis()
        scheduleWrite()
    }

    fun setPinned(id: String, pinned: Boolean) = synchronized(lock) {
        val all = cache()
        all.firstOrNull { it.id == id }?.pinned = pinned
        scheduleWrite()
    }

    /** Archive or bring back by hand. Never touches updatedAt, so the list order stays honest. */
    fun setArchived(id: String, archived: Boolean, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        cache().firstOrNull { it.id == id }?.archivedAt = if (archived) now else -now
        scheduleWrite()
    }

    /** Opening a chat reads it: the unread count and any needs-you flag both clear. */
    fun markRead(id: String) = synchronized(lock) {
        val all = cache()
        all.firstOrNull { it.id == id }?.let { it.unread = 0; it.needsYou = ""; it.needsYouUntil = 0L }
        scheduleWrite()
    }

    /** Flag a chat as waiting on the person until `until` (epoch ms). Unknown chat ids are ignored. */
    fun setNeedsYou(id: String, reason: String, until: Long) = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return
        s.needsYou = reason
        s.needsYouUntil = until
        // A waiting chat lights the bell and the Unread tab until it is opened.
        if (reason.isNotBlank() && s.unread == 0) s.unread = 1
        scheduleWrite()
    }

    /** Drop the flag (the hand-off was answered or timed out). */
    fun clearNeedsYou(id: String) = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return
        if (s.needsYou.isEmpty() && s.needsYouUntil == 0L) return
        s.needsYou = ""
        s.needsYouUntil = 0L
        scheduleWrite()
    }

    /** Clear every unread dot at once (bell clear action). One pass. */
    fun markAllRead() = synchronized(lock) {
        val all = cache()
        var changed = false
        all.forEach { if (it.unread != 0) { it.unread = 0; changed = true } }
        if (changed) scheduleWrite()
    }

    /** Record the bound provider session/thread id (import path only). */
    fun setRemoteId(id: String, remote: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        val r = remote.take(200)
        s.remoteId = r
        if (r.isBlank()) s.remoteIdsByBackend.remove(s.backend) else s.remoteIdsByBackend[s.backend] = r
        scheduleWrite()
    }

    /** All bound provider session ids, every backend — the refresh merge keys on these. */
    fun remoteIds(): Set<String> = synchronized(lock) {
        cache().flatMap { it.remoteIdsByBackend.values + it.remoteId }.filter { it.isNotBlank() }.toSet()
    }

    private val graveFile: File get() = File(file.parentFile, "tombstones.json")
    // Tombstones are read on every list render — memory-cached in Shared
    // like the sessions themselves; bury/unbury update both together.

    /** Session ids the user deleted here: never re-show as import rows. */
    fun tombstones(): Set<String> = synchronized(lock) {
        Shared.graveCache?.let { return it }
        try {
            if (!graveFile.exists()) return emptySet<String>().also { Shared.graveCache = it }
            val arr = JSONArray(graveFile.readText())
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
                .also { Shared.graveCache = it }
        } catch (_: Exception) { emptySet() }
    }

    private fun writeGrave(cur: Set<String>) {
        try {
            val arr = JSONArray()
            (cur.toList().takeLast(500)).forEach { arr.put(it) }
            atomicWrite(graveFile, arr.toString())
        } catch (_: Exception) {}
    }

    fun buryRemote(id: String) = synchronized(lock) {
        if (id.isBlank()) return
        val cur = tombstones().toMutableSet()
        if (cur.add(id)) {
            Shared.graveCache = cur.toSet()
            writeGrave(cur)
        }
    }

    fun unburyRemote(id: String) = synchronized(lock) {
        val cur = tombstones().toMutableSet()
        if (cur.remove(id)) {
            Shared.graveCache = cur.toSet()
            writeGrave(cur)
        }
    }

    fun rename(id: String, title: String, locked: Boolean = false) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        s.title = title.ifBlank { "untitled" }.take(60)
        if (locked) s.titleLocked = true
        scheduleWrite()
    }

    fun isTitleLocked(id: String): Boolean = synchronized(lock) {
        cache().firstOrNull { it.id == id }?.titleLocked ?: false
    }

    /** Freeze the current name: pre-lock manual renames (named before the
     *  lock shipped) heal to locked instead of being clobbered later. */
    fun lockTitle(id: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        if (!s.titleLocked) {
            s.titleLocked = true
            scheduleWrite()
        }
    }

    fun unlockTitle(id: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        if (s.titleLocked) {
            s.titleLocked = false
            scheduleWrite()
        }
    }

    /** Apply the current opencode title without turning it into a manual
     *  override. Future opens may refresh it again from the server. */
    fun setServerTitle(id: String, title: String) = synchronized(lock) {
        val all = cache()
        val s = all.firstOrNull { it.id == id } ?: return
        if (s.titleLocked) return
        val clean = title.trim()
        if (clean.isBlank()) return
        s.title = clean.take(60)
        scheduleWrite()
    }

    /** First-line derivations the app assigns itself (first user message,
     *  raw and quote-stripped). A title matching one of these is auto-tier
     *  and still follows the server; anything else custom is user-tier. */
    fun autoTitleLines(id: String): List<String> = synchronized(lock) {
        val s = cache().firstOrNull { it.id == id } ?: return emptyList()
        val first = s.messages.firstOrNull { it.role == "user" }?.text ?: return emptyList()
        val raw = first.trim().take(42).lineSequence().firstOrNull()?.toString() ?: ""
        val unquoted = first.trim().removePrefix(">").trim().take(42).lineSequence().firstOrNull()?.toString() ?: ""
        return listOf(raw, unquoted).filter { it.isNotBlank() }.distinct()
    }

    /** Destructive + synchronous: a cleared chat must stay cleared even if
     *  the process dies mid-debounce. */
    fun clear(id: String) {
        synchronized(lock) {
            val s = cache().firstOrNull { it.id == id } ?: return
            s.messages.clear()
            s.updatedAt = System.currentTimeMillis()
            scheduleWrite()
        }
        flushNow()
    }

    /** Terminal-clear: drop settled history, keep the session and every
     *  live turn standing (placeholders + their unknown rows for turns
     *  still pending, so in-flight replies land normally). Client-side
     *  only; the server is never touched. Dedupe memory is deliberately
     *  kept, so replayed dones still drop as duplicates instead of
     *  resurrecting cleared rows. Synchronous, like clear. */
    fun clearHistory(id: String, liveTurns: Set<String>) {
        synchronized(lock) {
            val s = cache().firstOrNull { it.id == id } ?: return
            s.messages.removeAll { !clearKeeps(it, liveTurns) }
            s.updatedAt = System.currentTimeMillis()
            scheduleWrite()
        }
        flushNow()
    }

    /** Destructive + synchronous: a deleted chat must stay deleted even if
     *  the process dies mid-debounce. */
    fun delete(id: String) {
        synchronized(lock) {
            cache().removeAll { it.id == id }
            scheduleWrite()
        }
        flushNow()
    }

    /** Heal-before-load: rename is atomic, so a torn write can only strand
     *  tmp — the main file is either whole or untouched. If main is
     *  missing/unparseable but tmp parses with real data, adopt tmp and
     *  repair main. A main file that parses (even `[]`) is authoritative:
     *  stale tmp must never resurrect deleted chats. An unloadable
     *  non-trivial main is preserved as evidence before anyone overwrites
     *  it (plus the no-wipe guard refuses to persist over it unseen). */
    private fun readAllFromDisk(): List<ChatSession> {
        parseSessionsFile(file)?.let { return it }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        parseSessionsFile(tmp)?.let { good ->
            if (good.isNotEmpty()) {
                try { atomicWrite(file, tmp.readText()) } catch (_: Exception) {}
                return good
            }
        }
        try {
            if (file.exists() && file.length() > 2) {
                file.copyTo(
                    File(file.parentFile, "sessions.json.corrupt-${System.currentTimeMillis()}"),
                    overwrite = true)
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    /** Null when missing or unparseable; a parsed (possibly empty) list
     *  otherwise. Zero-length files can't be valid JSON. */
    private fun parseSessionsFile(f: File): List<ChatSession>? {
        return try {
            if (!f.exists() || f.length() == 0L) return null
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        } catch (_: Exception) { null }
    }

    /** Crash-safe write (temp + rename): an interrupted save can never
     *  leave a truncated file behind, which the load would otherwise parse
     *  as an empty store — and the next write would persist that emptiness.
     *  Direct write is the last-resort fallback, never the first attempt. */
    private fun atomicWrite(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                try { target.delete() } catch (_: Exception) {}
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: ${target.name}")
            }
        } catch (e: Exception) {
            try { android.util.Log.w("ChatStore", "atomic write failed for ${target.name}: ${e.message}") } catch (_: Throwable) {}
            // Last resort. A failure here now propagates, so flushNow/flushAsync keep `dirty` set
            // instead of reporting a write that never happened as done.
            target.writeText(text)
        }
    }

    private fun parse(o: JSONObject): ChatSession {
        val msgs = mutableListOf<ChatMessage>()
        val arr = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            msgs += parseMessage(m)
        }
        val bk = Prefs.cannonBackend(o.optString("backend", ""))
        val remote = o.optString("remote", "")
        val byBackend = mutableMapOf<String, String>()
        o.optJSONObject("remoteByBackend")?.let { m ->
            m.keys().forEach { k -> m.optString(k, "").takeIf { it.isNotBlank() && Prefs.validBackend(k) }?.let { byBackend[k] = it } }
        }
        if (remote.isNotBlank() && !byBackend.containsKey(bk)) byBackend[bk] = remote
        return ChatSession(
            id = o.optString("id"),
            title = o.optString("title", "untitled"),
            updatedAt = o.optLong("updatedAt", 0L),
            backend = bk,
            model = o.optString("model", "").takeUnless { it == "default" } ?: "",
            effort = o.optString("effort", ""),
            cwd = o.optString("cwd", ""),
            draft = o.optString("draft", ""),
            pinned = o.optBoolean("pinned", false),
            archivedAt = o.optLong("archivedAt", 0L),
            unread = o.optInt("unread", 0),
            remoteId = remote,
            remoteIdsByBackend = byBackend,
            titleLocked = o.optBoolean("titleLocked", false),
            needsYou = o.optString("needsYou", ""),
            needsYouUntil = o.optLong("needsYouUntil", 0L),
            messages = msgs
        )
    }

    private fun parseMessage(m: JSONObject): ChatMessage {
        val text = m.optString("text", "")
        val role = m.optString("role", "user")
        val turn = m.optString("turn", "")
        return ChatMessage(
            role = role,
            text = text,
            ts = m.optLong("ts", 0L),
            attachment = m.optString("attachment", "").takeUnless { it.isBlank() },
            moreAttachments = m.optJSONArray("more")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeUnless { s -> s.isBlank() } }
            } ?: emptyList(),
            durationSec = m.optInt("dur", 0),
            turnId = turn,
            stepsJson = m.optString("steps", ""),
            messageID = m.optString("mid", ""),
            changedFiles = m.optJSONArray("files")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeUnless { s -> s.isBlank() } }
            } ?: emptyList(),
            // No flag on disk = old file: anything holding real text settled
            // long ago; only a bare "…" with a turnId is still live.
            // Heal: delivered inbox rows (assistant, no turn, real text)
            // were once written unsettled, which hid their copy/speak/menu.
            settled = (if (m.has("settled")) m.optBoolean("settled", true) else text != "…") ||
                (role == "assistant" && turn.isBlank() && text.isNotBlank() && text != "…"),
            sendFailed = m.optBoolean("sendFailed", false),
            voiceSendCommand = m.optBoolean("voiceSendCommand", false)
        )
    }

    private fun render(s: ChatSession): JSONObject {
        val arr = JSONArray()
        s.messages.forEach {
            arr.put(JSONObject()
                .put("role", it.role)
                .put("text", it.text)
                .put("ts", it.ts)
                .put("attachment", it.attachment ?: "")
                .put("more", org.json.JSONArray(it.moreAttachments))
                .put("dur", it.durationSec)
                .put("turn", it.turnId)
                .put("steps", it.stepsJson)
                .put("mid", it.messageID)
                .put("files", org.json.JSONArray(it.changedFiles))
                .put("settled", it.settled)
                .put("sendFailed", it.sendFailed)
                .put("voiceSendCommand", it.voiceSendCommand))
        }
        return JSONObject()
            .put("id", s.id)
            .put("title", s.title)
            .put("updatedAt", s.updatedAt)
            .put("backend", s.backend)
            .put("model", s.model)
            .put("effort", s.effort)
            .put("cwd", s.cwd)
            .put("draft", s.draft)
            .put("pinned", s.pinned)
            .put("archivedAt", s.archivedAt)
            .put("unread", s.unread)
            .put("remote", s.remoteId)
            .put("remoteByBackend", JSONObject(s.remoteIdsByBackend as Map<*, *>))
            .put("titleLocked", s.titleLocked)
            .put("needsYou", s.needsYou)
            .put("needsYouUntil", s.needsYouUntil)
            .put("messages", arr)
    }

    companion object {
        /** Sentinel for “use backend default”; never displayed as a model. */
        fun defaultModel() = "default"

        /** Static session commands. Live opencode commands (GET /api/commands)
         *  merge in front of these at render time; unknown text falls back here. */
        val COMMANDS = listOf(
            SlashCommand("/new", "start a new chat"),
            SlashCommand("/search", "filter sessions"),
            SlashCommand("/fetch", "fetch a page into this chat"),
            SlashCommand("/stop", "stop the running reply"),
            SlashCommand("/compact", "summarize session into compacted context"),
            SlashCommand("/usage", "show tracked token usage for this chat"),
            SlashCommand("/cost", "show tracked cost estimate for this chat"),
            SlashCommand("/context", "show context window and token details"),
            SlashCommand("/status", "show backend login and model status"),
            SlashCommand("/clear", "clear this chat's messages"),
            SlashCommand("/delete", "delete this chat"),
            SlashCommand("/sessions", "back to session list"),
            SlashCommand("/models", "pick model"),
            SlashCommand("/model", "set model for this chat"),
            SlashCommand("/current", "show current model"),
            SlashCommand("/cwd", "show or set this chat's working folder"),
            SlashCommand("/fork", "duplicate this chat")
        )
    }
}
