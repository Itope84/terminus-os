package com.terminus.rec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File

/** Pure Activity-sheet model (unit-tested): unread chats head the sheet
 *  under one header, server inbox entries follow under another. Triples
 *  keep it decoupled from store/API types. */
internal sealed interface ActivityEntry {
    data class Header(val title: String) : ActivityEntry
    data class Chat(val id: String, val title: String, val unread: Int) : ActivityEntry
    data class Event(val index: Int, val job: String, val preview: String, val at: Long) : ActivityEntry
}

internal fun activityEntries(
    unread: List<Triple<String, String, Int>>,
    events: List<Triple<String, String, Long>>,
): List<ActivityEntry> {
    val out = mutableListOf<ActivityEntry>()
    if (unread.isNotEmpty()) {
        out += ActivityEntry.Header("Unread")
        unread.forEach { (id, title, n) -> out += ActivityEntry.Chat(id, title, n) }
    }
    if (events.isNotEmpty()) {
        out += ActivityEntry.Header("Activity")
        events.forEachIndexed { i, (job, preview, at) -> out += ActivityEntry.Event(i, job, preview, at) }
    }
    return out
}

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var phoneBrowser: PhoneBrowser? = null
    private var approvalPrompt: ApprovalPrompt? = null
    private lateinit var chunks: ChunkStore
    private lateinit var adapter: ChunkAdapter

    private lateinit var echoPane: View
    private lateinit var galleryWeb: WebView
    private lateinit var opsWeb: WebView
    private lateinit var notesWeb: WebView
    private lateinit var galleryPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var opsPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var notesPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var galleryPane: View
    private lateinit var notesPane: View
    private lateinit var opsPane: View
    private lateinit var echoCard: android.view.View
    private lateinit var echoOrb: android.view.View
    private lateinit var recChip: TextView
    private lateinit var waveView: WaveView
    private lateinit var echoTitle: TextView
    private lateinit var echoSub: TextView
        private lateinit var timerText: TextView
    private lateinit var serverDot: View
    private var serverDots: MutableList<View> = mutableListOf()
    private lateinit var statQueue: TextView
    private lateinit var statStore: TextView
    private lateinit var nav: BottomNavigationView

    // ---- Chat ----
    private lateinit var chatStore: ChatStore
    private lateinit var chatPane: View
    private lateinit var sessionList: RecyclerView
    private lateinit var sessionPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var sessionAdapter: SessionAdapter
    /** Last server session list (GET /api/sessions, pull-to-refresh only).
     *  Memory-only: mapped rows stay local chats, the rest render as
     *  importable rows. Never written to the ChatStore. */
    private var serverImports: List<OpencodeApi.RemoteSession> = emptyList()
    private var sessionsLoading = false
    private lateinit var chatEmpty: TextView
    private lateinit var sessionSearch: EditText
    private lateinit var searchToggle: View
    private lateinit var searchIcon: android.widget.ImageView
    private lateinit var bellBtn: View
    private var searchOpen = false
    private lateinit var chatHero: View
    private lateinit var heroModelBtn: Button
    private lateinit var threadHeader: View
    private lateinit var threadTitle: TextView
    private var threadTitleExpanded = false
    private var expandedHeaderControlVisibility: List<Pair<View, Int>>? = null
    private lateinit var threadWeb: QuoteWebView
    /** Wrapper carrying the thread WebView + jump button. It holds layout
     *  weight, so it must hide with the thread — a visible-but-empty frame
     *  steals half the screen from the session list (v151 halving bug). */
    private lateinit var threadFrame: View
    /** WhatsApp-style jump-to-latest: appears when the thread is scrolled
     *  up, one tap drops to the bottom. Pure position affordance — it never
     *  scrolls on its own and never fights the touch truce. */
    private lateinit var jumpLatest: View
    private lateinit var threadDivider: View
    private lateinit var goalStrip: TextView
    private lateinit var goalStripBar: android.view.View
    private lateinit var composerRow: View
    /** Debounced list render for search keystrokes (see sessionSearch watcher):
     *  the query scan walks message bodies, so it runs per pause. hideSearch
     *  renders directly; a stale post after it is a harmless no-op. */
    private val searchRenderDebounce = Runnable { try { renderSessions() } catch (_: Exception) {} }
    private lateinit var composerInput: EditText
    private lateinit var chatMic: ImageView
    /** The layout's own tint for the idle mic icon; the recording state paints it white. */
    private var chatMicIdleTint: android.content.res.ColorStateList? = null
    private lateinit var chatRecStatus: TextView
    private lateinit var stopBtn: View
    private lateinit var slashList: android.widget.ListView
    private lateinit var slashAdapter: android.widget.ArrayAdapter<String>
    private var slashShown: List<SlashCommand> = emptyList()
    private var slashRowCommands: List<SlashCommand?> = emptyList()
    private var liveCommands: List<SlashCommand> = emptyList()
    private var liveCommandsBackend: String = ""
    private var commandsLoading = false
    private var liveCommandsAt = 0L
    /** True while completeSlash does its programmatic setText — the
     *  TextWatcher must not re-show the dropdown for that edit. */
    private var completingSlash = false
    private var micPulse: android.animation.ValueAnimator? = null
    // Continuous dictation: the in-app recognizer is restarted whenever the engine ends a phrase, so a pause
    // never ends dictation; only a tap, the send word, or DICT_IDLE_MS of silence does.
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var macDict: MacDictation? = null // saywhat on the Mac; null = the phone recogniser (or idle)
    private var dictating = false
    private var dictBase = ""       // composer text from before dictation began
    private var dictDone = ""       // phrases already finalized in this dictation
    private var dictRendered = ""   // what dictation last wrote to the composer (edit detection)
    private var dictPartial = ""    // phrase still being heard; kept if the engine moves on without a final result
    private var dictSendArmedAt = 0L // when the rendered text last started ending with the send word (0 = not armed)
    private val SEND_ARM_MS = 1500L // silence after a trailing send word before it counts as the command
    private var dictLastHeard = 0L
    private var dictErrors = 0
    private val dictHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val DICT_IDLE_MS = 6000L
    private val easeOut = android.view.animation.DecelerateInterpolator()
    private lateinit var modelChip: com.google.android.material.button.MaterialButton
    private lateinit var attachListBtn: View
    private lateinit var contextRing: ContextRingView
    private var contextInfo: OpencodeApi.ContextInfo? = null
    private var contextChat: String? = null
    private var contextFetchAt = 0L
    private var activeSessionId: String? = null
    /** Read-aloud for assistant replies (engine, chunking, button state): see ReplyVoice. */
    private val replyVoice by lazy {
        ReplyVoice(
            act = this,
            runJs = { js -> if (::threadWeb.isInitialized) threadWeb.evaluateJavascript(js, null) },
            toast = { toast(it) },
            onPlaying = { speakPlaying = it; updateScreenOn() },
        )
    }
    /** Mirrors ReplyVoice's speaking state (avoids instantiating it just to ask). */
    private var speakPlaying = false

    /** Screen stays on while the mic is hot or audio is playing: Echo capture, a chat voice
     *  note, live dictation (a screen timeout would end it mid-sentence), read-aloud, or chunk playback. Cleared the moment everything is idle. */
    private fun updateScreenOn() {
        val keep = RecorderService.running || chatRecActive || dictating || speakPlaying || playingName != null
        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    /** Lazy draft: thread UI open with NO stored session. activeSessionId
     *  stays null; persist (chatStore.create) only on first send or first
     *  model pick. Backing out via < discards everything. */
    private var draftOpen = false
    private var draftText = ""
    private val pendingAttaches = mutableListOf<java.io.File>()
    /** Per-tab change dots (watcher): in-memory only. Notebook edits dot
     *  the notebook tab, gallery uploads dot artifacts. Visiting the tab
     *  clears its dot. Restart wipes them; dots are hints, not guarantees. */
    private val tabDots = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun watchAreaOf(body: String): String =
        if (body.trimStart().startsWith("notebook/")) "notes" else "gallery"

    private fun paintTabDots() {
        if (!::nav.isInitialized) return
        try {
            if (tabDots.contains("notes")) nav.getOrCreateBadge(R.id.tabNotes)
            else nav.removeBadge(R.id.tabNotes)
            if (tabDots.contains("gallery")) nav.getOrCreateBadge(R.id.tabGallery)
            else nav.removeBadge(R.id.tabGallery)
        } catch (_: Exception) {}
    }
    /** Pending camera capture target: set before TakePicture launches,
     *  staged into pendingAttaches on success. */
    private var pendingCameraUri: android.net.Uri? = null
    private var pendingVoice: java.io.File? = null
    // Quote-reply: long-press a thread message fills these; the strip above
    // the composer shows excerpt + author, ✕ clears. Send prepends
    // `> excerpt` to the wire text. Hidden on send / thread switch.
    /** Quoted passages staged for the next send, each with an optional note (QuoteDraft.wire formats them). */
    private val quotes = mutableListOf<QuoteItem>()
    private lateinit var quoteBar: View
    private lateinit var quoteChips: android.widget.LinearLayout
    private lateinit var quoteEditor: View
    private lateinit var quoteNote: EditText
    /** The quote whose note is open inline; isNew = ✕ discards the quote itself, not just the edit. */
    private var editingQuote: QuoteItem? = null
    private var editingQuoteIsNew = false
    /** Send-through: plain sends POST immediately even while a turn
     *  runs; each turn carries its own turnId and settles its own "…" in
     *  place, so ordering never slides. Queue-by-default: a send behind a
     *  running reply waits its turn on the server. "Send now" (steer) folds
     *  a message into the running reply instead. Commands bypass queues. */
    private data class PendingSend(val text: String, val files: List<java.io.File>, val quote: String, val turnId: String = "")
    private val waitQueue = mutableMapOf<String, MutableList<PendingSend>>()
    private val backendWaitingTurns = mutableMapOf<String, Set<String>>()
    private val stopInProgress = mutableSetOf<String>()
    /** Turns folded into the running reply ("Send now"): they are no longer waiting in line. */
    private val steeredTurnIds = mutableSetOf<String>()
    /** Chat + waiting turns the page was last told about: skips the script when nothing changed. */
    private var sendNowKey = ""
    /** Stale turns already surfaced as unknown (chat → turnIds). Placeholders
     *  stay by design, so without this the sweep would re-report (and
     *  re-reconcile) on every render. Pruned to live turns on each render;
     *  a recheck tap bypasses it via reconcileTurn directly. */
    private val unknownSurfaced = mutableMapOf<String, MutableSet<String>>()
    /** In-flight automatic stale-turn checks (chat:turn). Prevents the
     *  sweep from stacking a reconcile per render while one runs. */
    private val checkingTurns = mutableSetOf<String>()
    /** Opencode's authoritative session-wide idle signal (distinct from a
     *  tool `step ended`); null until observed in this process. */
    private val serverIdle = mutableMapOf<String, Boolean>()
    /** In-flight send-through turns per chat (turnIds POSTed, done not yet seen). */
    private val pendingTurns = mutableMapOf<String, MutableSet<String>>()
    /** When each chat last sent a turn: tells the browser card which chat asked for it. */
    private val lastAskedAt = mutableMapOf<String, Long>()
    /** Per-turn restore text for failure paths (turnId → user text). */
    private val turnRestore = mutableMapOf<String, String>()
    /** Failed-turn retry stash (turnId → user text): tap-to-retry resends
     *  this as a FRESH turn without touching the composer's current text. */
    private val failedRestore = mutableMapOf<String, String>()
    /** Composer edit mode: the box holds (chatId, turnId) of a failed turn
     *  being edited in place — send resends that turn, back cancels.
     *  Stash (turnId → displaced draft) is restored on cancel and after a
     *  successful resend, so entering edit mode never eats typed text. */
    private var editingTurn: Pair<String, String>? = null
    private val editingStashByTurn = HashMap<String, String>()
    /** Delivered-turn dedupe: retention beats the server replay window
     *  (turnResults TTL 10min) — entries live 15min, pruned on touch. A
     *  bare count cap can't cover a 50-turn burst inside the window. */
    private val deliveredTurns = mutableMapOf<String, MutableMap<String, Long>>()
    private val deliveredTtlMs = 15 * 60 * 1000L
    /** Abandoned turns: a retry resent this prompt as a fresh turn, so the
     *  original's late `done` drops even with new text (same TTL discipline
     *  as deliveredTurns). Answered by abandon-vs-merge: abandon — the fresh
     *  turn already carries the prompt, merging risks twins. */
    private val supersededTurns = mutableMapOf<String, MutableMap<String, Long>>()
    /** Tap-checks in flight (retry/reconcile links): the open thread shows
     *  its loading box until the check lands, then it clears. UI-thread only;
     *  never a turn — verify/ticker/reconcile ignore it, Stop never shows. */
    private val checking = mutableSetOf<String>()
    /** Last automatic stale-turn reconcile per chat:turn (see renderThread).
     *  Tap-checks bypass this map entirely. */
    private val lastAutoReconcileAt = mutableMapOf<String, Long>()
    /** Merged-turn pointer text: generic by design (no quoted reply, no MID),
     *  so regeneration can never stale it. Rendered muted without toolhead. */
    private val POINTER_TEXT = "answered together with the shared reply"
    /** Opencode replies already rendered (messageID → settled). A later turn
     *  resolving to the same messageID merged server-side: its placeholder
     *  drops silently instead of painting a twin row. Distinct messageIDs
     *  still settle independently — fair game. */
    private val settledMessages = mutableMapOf<String, MutableSet<String>>()
    private lateinit var voicePreview: View
    private lateinit var voicePreviewLabel: TextView
    private lateinit var attachPreview: View
    private lateinit var attachPreviewLabel: TextView
    private lateinit var attachThumb: android.widget.ImageView
    private val attachmentPreviewExec = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val attachmentPreviewCache = object : android.util.LruCache<String, android.graphics.Bitmap>(4096) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Attachment strip: the visible proof files are staged. Typing never
     *  touches it — only send, ✕, or thread switch clears it. A single
     *  staged image adds a thumbnail next to the label; the filename always
     *  shows (first two names when several). */
    private fun showAttach() {
        if (!::attachPreview.isInitialized) return
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
    private fun loadAttachThumb(f: java.io.File) {
        if (!::attachThumb.isInitialized) return
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
                if (!::attachThumb.isInitialized) return@runOnUiThread
                if (attachThumb.tag != f.absolutePath) return@runOnUiThread
                if (bmp != null) attachThumb.setImageBitmap(bmp)
                else attachThumb.visibility = View.GONE
            }
        }.start()
    }

    private fun clearAttachThumb() {
        if (!::attachThumb.isInitialized) return
        attachThumb.tag = null
        attachThumb.setImageDrawable(null)
        attachThumb.visibility = View.GONE
    }

    private fun hideAttachStrip() {
        if (::attachPreview.isInitialized) attachPreview.visibility = View.GONE
        clearAttachThumb()
        updateComposerSlot()
    }

    private fun dropAttach() {
        pendingAttaches.clear()
        hideAttachStrip()
        if (pendingVoice == null) composerInput.hint = "Message (/ for commands)"
    }
    private var sessionQuery = ""
    /** Session list filter tab: 0 = Recent, 1 = Unread, 2 = Schedules. */
    private var sessionFilter = 0
    /** Session list sort: newest | activity | name. */
    private var sessionSort = "newest"
    private lateinit var sessControls: View
    private lateinit var tabAll: TextView
    private lateinit var tabUnread: TextView
    private lateinit var tabSchedules: TextView
    private lateinit var sortBtn: TextView
    /** Fullscreen video surface for thread WebViews (expand actually works). */
    private var fullscreenView: View? = null
    private var fullscreenDialog: android.app.Dialog? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var iconBackendScope: ImageView
    /** Full HTML last loaded into threadWeb — identical renders skip
     *  loadData entirely (scroll-touch truce). Compared by value: a hash
     *  collision here would silently skip a genuine update. */
    private var lastThreadHtml = ""
    /** History paging: messages rendered per thread (default tail).
     *  Reset on thread open; grows by page size per "show earlier" tap. */
    private val threadVisible = mutableMapOf<String, Int>()
    /** Chats with a server delete in flight (row shows Deleting until the
     *  server confirms or fails). Mirrored into the adapter for render. */
    private val deletingIds = mutableSetOf<String>()
    private val threadPageSize = 60
    /** No-blink streaming: the thread page stays loaded during live turns;
     *  step-only updates patch #prog via JS (throttled, coalesced). A false
     *  here (or a changed message signature) falls back to full render. */
    private var threadPageLoaded = false
    private var lastStepPatchAt = 0L
    private var pendingStepPatch: Runnable? = null
    private val lastStepPatched = mutableMapOf<String, Int>()
    private var lastFullMsgSig = ""
    private val STEP_PATCH_MIN_MS = 800L
    /** Live progress steps per chat (current turn) + settled history kept
     *  for the post-turn expander. Memory-only; never persisted. */
    private val progressLive = mutableMapOf<String, List<OpencodeApi.ProgressStep>>()
    /** Newest applied done settle time per chat (server `at`). Sent as the
     *  stream replay floor (?since=) so reconnects replay only turns
     *  settled after it — never the full retained history. Memory-only;
     *  a fresh process omits the floor and takes the server window. */
    private val doneHighWater = mutableMapOf<String, Long>()
    /** Settled text (normalized) per delivered turn: a reconnect replay of
     *  the exact same outcome drops with zero message scans and zero
     *  renders. Key "$chat:$turn". TurnIds are unique per turn, so an exact
     *  match can never be a new outcome; anything different falls through
     *  to the normal merge rule. Capped; memory-only (cross-restart replays
     *  are already bounded by the persisted high-water floor). */
    private val deliveredTextNorm = mutableMapOf<String, String>()
    private fun noteDeliveredText(id: String, turnId: String, text: String) {
        if (turnId.isBlank() || text.isBlank()) return
        try {
            synchronized(deliveredTextNorm) {
                deliveredTextNorm["$id:$turnId"] = TurnActivity.normText(text)
                while (deliveredTextNorm.size > 200) { deliveredTextNorm.remove(deliveredTextNorm.keys.first()) }
            }
        } catch (_: Exception) {}
    }
    private fun isExactRedelivery(id: String, turnId: String, text: String): Boolean {
        if (turnId.isBlank() || text.isBlank()) return false
        return try {
            synchronized(deliveredTextNorm) { deliveredTextNorm["$id:$turnId"] == TurnActivity.normText(text) }
        } catch (_: Exception) { false }
    }
    /** Replay floor for (re)connects: memory first, persisted across
     *  restarts second. The server defaults to its recent window when 0. */
    private fun streamSince(id: String): Long = try {
        maxOf(doneHighWater[id] ?: 0L, prefs.doneHighWater(id))
    } catch (_: Exception) { 0L }
    /** Per-chat changed-files accumulation from step `files` (see step
     *  handler). The settling turn takes and clears it; capped. */
    private val changedLive = mutableMapOf<String, MutableSet<String>>()
    private fun takeChanged(id: String): List<String> = try {
        (changedLive.remove(id) ?: emptySet()).toList()
    } catch (_: Exception) { emptyList() }
    /** Stamp the settling turn's reply, then clear the accumulator whether
     *  or not a row matched (a new turn starts clean either way). */
    private fun stampSettledChanged(id: String, turnId: String, messageID: String) {
        val files = takeChanged(id)
        if (files.isEmpty()) return
        try { chatStore.stampChangedFiles(id, turnId, messageID, files) } catch (_: Exception) {}
    }
    private val progressDone = mutableMapOf<String, List<OpencodeApi.ProgressStep>>()
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Draft debounce: keystrokes update the composer immediately; the store
     *  (now cheap, but still IO-bound) persists after a short idle gap, or
     *  synchronously on thread close / send / backgrounding — never lost. */
    private val draftHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDraftSave: Runnable? = null
    private var pendingDraftId: String? = null
    /** Cancel any debounced draft write (a fresher write is taking over). */
    private fun dropPendingDraft() {
        pendingDraftSave?.let { try { draftHandler.removeCallbacks(it) } catch (_: Exception) {} }
        pendingDraftSave = null
        pendingDraftId = null
    }
    /** Flush a debounced draft write now (must-survive points). */
    private fun flushPendingDraft() {
        val r = pendingDraftSave ?: return
        val id = pendingDraftId
        dropPendingDraft()
        try { r.run() } catch (_: Exception) {}
        if (id != null) { try { chatStore.flushAsync() } catch (_: Exception) {} }
    }
    private val stopRefresh = object : Runnable {
        override fun run() {
            updateStopBtn()
            // The list has no other ticker: re-render it off-thread so a
            // stale "replying…" row ages out even with no new events.
            if (!isThreadOpen()) renderSessions()
            // Self-healing, every chat with a pending reply — not just the
            // open one. Verify once against live session state; a
            // confirmed-idle session settles stale orphans on its own — no
            // tap needed. Fresh turns and unverifiable sessions are never
            // touched here. Capped per tick; usually zero or one qualifies.
            try {
                val liveByChat = chatStore.chatsWithLive().associateWith { cid ->
                    try { chatStore.liveTurns(cid) } catch (_: Exception) { emptyList() }
                }
                val now = System.currentTimeMillis()
                val verifying = synchronized(autoVerifying) { autoVerifying.toSet() }
                for (cid in TurnActivity.verifyCandidates(liveByChat, now, verifying).take(3)) {
                    val started = synchronized(autoVerifying) {
                        if (cid in autoVerifying) false else { autoVerifying.add(cid); true }
                    }
                    if (started) Thread { verifyStopIdle(cid) }.start()
                }
            } catch (_: Exception) {}
        }
    }
    /** Chats with an in-flight dead-turn verification (one at a time). */
    private val autoVerifying = mutableSetOf<String>()
    private var stopWasActive = false
    private var stopPulse: android.animation.ObjectAnimator? = null
    /** SSE stream turn: startStream() owns this; stopStream()/settle paths
     *  clear it. Steps ride the stream; the server also polls opencode
     *  every few seconds as a fallback, so steps can arrive twice. */
    private var streamThread: Thread? = null
    private var streamChat: String? = null
    /** Background jobs still running, per chat. While any is live the chat's
     *  stream must stay open: the finish row arrives after the turn settled,
     *  and a closed stream would drop it. */
    private val backgroundJobs = BackgroundJobs()
    /** The single pending scroll-to-bottom post; any touch on threadWeb
     *  cancels it. Exactly one scroll per new render. */
    /** What the last full render asked of the scroll position (see scrollPlan). */
    private var lastRenderScroll = true
    private var lastRenderKeepCssY = 0
    /** One-shot "open at the first unread message": (chat, row index, message count at open). Dropped by
     *  a touch, a new message, or closing the thread. */
    private var openAnchor: Triple<String, Int, Int>? = null
    /** Where each chat was left (css px; -1 = at the bottom). In memory only: reopening a chat with
     *  nothing unread returns there instead of jumping. `openRestore` is the one-shot for this open. */
    private val leftScrollY = HashMap<String, Int>()
    private var openRestore: Pair<String, Int>? = null
    private val threadScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingThreadScroll: Runnable? = null
    // Chat voice notes use RecorderService's local-only capture mode. The
    // recordings enter the normal chat attachment flow only after approval.
    private var chatRecActive = false
    private var chatRecStartingAt = 0L
    private var chatChunkBaseline = setOf<String>()

    private var player: android.media.MediaPlayer? = null
    private var playingName: String? = null
    private var serverOk = false
    private var healthBackend = ""
    /** Last accepted send tap (bounce debounce). */
    private var lastSendAt = 0L
    /** Clip lengths by file name (ms); filled lazily, survives refreshes. */
    private val clipLens = mutableMapOf<String, Long>()
    /** Last render time per turn for live delta painting (throttled). */
    private val deltaRenderAt = mutableMapOf<String, Long>()
    /** Delta buffering: fragments accumulate here; the store persists at most
     *  every DELTA_PERSIST_MS so streaming cost stays flat as history grows.
     *  DOM paints straight from the buffer; `done` settles canonically. */
    private val deltaBuf = mutableMapOf<String, StringBuilder>()
    private val deltaPersistAt = mutableMapOf<String, Long>()
    private val DELTA_PERSIST_MS = 1500L
    /** Trailing paint per turn: the throttle drops the tail of a burst, so a reply that pauses on a tool call
     *  would sit at its first words ("Let me cap") until the next delta. One scheduled flush closes that gap. */
    private val deltaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val deltaTrail = mutableMapOf<String, Runnable>()

    /** Drop per-turn transient state (render/persist clocks + delta buffer). */
    private fun dropTurnBuf(turnId: String) {
        deltaTrail.remove(turnId)?.let { deltaHandler.removeCallbacks(it) }
        deltaBuf.remove(turnId)
        deltaPersistAt.remove(turnId)
        deltaRenderAt.remove(turnId)
    }
    private var pulse: android.animation.ValueAnimator? = null
    /** Last LAN-retried WebView URL — guards the onReceivedError fallback loop. */
    private var lastLanRetry: String? = null

    /** True while the activity is resumed. Long opencode turns that finish
     *  with this false fire a Chat-channel notification instead of UI only. */
    @Volatile private var appForeground = false
    /** Label inside the open cog sheet showing the current chat tone. */
    private var toneLabel: android.widget.TextView? = null
    /** System tone picker result → stored pref + applied to Chat channel. */
    private val soundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri != null) {
                    prefs.setChatSound(uri.toString())
                    ChatNotify.ensureChannel(this)
                    toneLabel?.text = toneName()
                }
            }
        }

    /** Current night mode (follows System/Light/Dark setting). */
    private fun isDark(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applyAppearance(mode: String) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
    }

    /** 150ms ease-out cross-fade for tab pane switches. */
    private fun crossFade(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(150L).setInterpolator(easeOut).start()
    }

    private fun setPulsing(on: Boolean) {
        if (on && pulse == null) {
            pulse = android.animation.ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = 900
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    echoOrb.scaleX = v; echoOrb.scaleY = v
                }
                start()
            }
        } else if (!on) {
            pulse?.cancel(); pulse = null
            echoOrb.scaleX = 1f; echoOrb.scaleY = 1f
        }
    }

    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            val s = ((android.os.SystemClock.elapsedRealtime() - RecorderService.startedAt) / 1000).coerceAtLeast(0)
            timerText.text = String.format("%02d:%02d", s / 60, s % 60)
            if (RecorderService.running && !chatRecActive && !RecorderService.chatCaptureActive) {
                echoSub.text = chunkLine(RecorderService.chunkNo, uploadedInSession(chunks.all().filter { it.state == ChunkStore.STATE_UPLOADED }.map { it.name }, RecorderService.sessionPrefix))
            }
            if (RecorderService.running) timerHandler.postDelayed(this, 500)
        }
    }

    /** Feeds the waveform ~8×/s while a recording runs; stops itself otherwise. */
    private val waveTick = object : Runnable {
        override fun run() {
            if (!::waveView.isInitialized) return
            if (RecorderService.running && !chatRecActive && !RecorderService.chatCaptureActive) {
                waveView.push(waveLevel(RecorderService.amplitude()))
                timerHandler.postDelayed(this, 120)
            }
        }
    }

    private var filePickerCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = if (res.resultCode == RESULT_OK) {
                val d = res.data
                when {
                    d?.clipData != null -> Array(d.clipData!!.itemCount) { d.clipData!!.getItemAt(it).uri }
                    d?.data != null -> arrayOf(d.data!!)
                    else -> null
                }
            } else null
            filePickerCallback?.onReceiveValue(uris)
            filePickerCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply BEFORE super.onCreate so the correct DayNight theme inflates.
        applyAppearance(Prefs(this).appearance())
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        try { ChatMarkdown.pbScript = assets.open("pb-card.js").bufferedReader().use { it.readText() } } catch (_: Exception) {}
        try { ChatMarkdown.pbCss = assets.open("pb-card.css").bufferedReader().use { it.readText() } } catch (_: Exception) {}
        try { ChatMarkdown.threadCardsCss = assets.open("thread-cards.css").bufferedReader().use { it.readText() } } catch (_: Exception) {}
        try { ChatMarkdown.threadUiJs = assets.open("thread-ui.js").bufferedReader().use { it.readText() } } catch (_: Exception) {}
        pruneShareExports()
        ensureServerCookie()
        LinkToken.refresh(this@MainActivity, prefs)
        chunks = ChunkStore(this)
        recoverOrphans()
        setContentView(R.layout.activity_main)
        phoneBrowser = PhoneBrowser(this, prefs).also {
            it.onState = { json -> browserCards.onState(json) }
            it.onCookiesCleared = { ensureServerCookie() }
            it.onChannelEvent = { e, d -> approvalPrompt?.onEvent(e, d) }
            it.onNeedsYou = { chat, reason, on -> runOnUiThread { flagNeedsYou(chat, reason, on) } }
            it.attach()
        }
        approvalPrompt = ApprovalPrompt(this, prefs).also {
            it.onNeedsYou = { chat, reason, on -> runOnUiThread { flagNeedsYou(chat, reason, on) } }
            it.onOpenChat = { chat -> runOnUiThread { openThread(chat) } }
        }

        echoPane = findViewById(R.id.echoPane)
        echoCard = findViewById(R.id.echoCard)
        echoOrb = findViewById(R.id.echoOrb)
        recChip = findViewById(R.id.recChip)
        waveView = findViewById(R.id.waveView)
        echoTitle = findViewById(R.id.echoTitle)
        echoSub = findViewById(R.id.echoSub)
        timerText = findViewById(R.id.timerText)
        serverDot = findViewById(R.id.serverDot)
        serverDots = mutableListOf(serverDot)
        for (id in listOf(R.id.serverDotEcho, R.id.serverDotNotes, R.id.serverDotGallery, R.id.serverDotOps)) {
            try {
                findViewById<View>(id)?.let { serverDots.add(it) }
            } catch (_: Exception) {}
        }
        statQueue = findViewById(R.id.statQueue)
        statStore = findViewById(R.id.statStore)

        val list: RecyclerView = findViewById(R.id.chunkList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ChunkAdapter(
            ChunkAdapter.grouped(chunks.all()),
            onRetry = { Uploader.enqueue(this, retryRejected = true) },
            onPlay = { playChunk(it) },
            onDelete = { item ->
                val drop = {
                    stopPlayback(); playingName = null
                    try { File(recordingsDir(this), item.name).delete() } catch (_: Exception) {}
                    chunks.forget(item.name); refresh()
                }
                if (item.state == ChunkStore.STATE_UPLOADED) drop()
                else AlertDialog.Builder(this, R.style.CreamDialog)
                    .setMessage("This clip has not synced. Delete it anyway?")
                    .setPositiveButton("Delete") { _, _ -> drop() }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onToggle = { adapter.toggle(it) }
        )
        list.adapter = adapter

        galleryWeb = findViewById(R.id.galleryWeb)
        opsWeb = findViewById(R.id.opsWeb)
        notesWeb = findViewById(R.id.notesWeb)
        tuneAuthWebView(galleryWeb)
        tuneAuthWebView(opsWeb)
        tuneAuthWebView(notesWeb)
        galleryPull = findViewById(R.id.galleryPull)
        opsPull = findViewById(R.id.opsPull)
        notesPull = findViewById(R.id.notesPull)
        galleryPane = findViewById(R.id.galleryPane)
        opsPane = findViewById(R.id.opsPane)
        notesPane = findViewById(R.id.notesPane)
        setupWeb(galleryWeb)
        setupWeb(opsWeb)
        setupWeb(notesWeb)
        galleryPull.setOnRefreshListener { galleryWeb.reload(); galleryPull.isRefreshing = false }
        opsPull.setOnRefreshListener { opsWeb.reload(); opsPull.isRefreshing = false }
        notesPull.setOnRefreshListener { notesWeb.reload(); notesPull.isRefreshing = false }

        nav = findViewById(R.id.bottomNav)
        // No pill behind the selected tab: the icon tint alone marks it.
        nav.isItemActiveIndicatorEnabled = false
        nav.setOnItemSelectedListener {
            if (it.itemId != R.id.tabEcho && playingName != null) { stopPlayback(); playingName = null }
            when (it.itemId) {
                R.id.tabChat -> { showChat(); true }
                R.id.tabEcho -> { showEcho(); true }
                R.id.tabNotes -> { tabDots.remove("notes"); paintTabDots(); showWeb(notesWeb, "/notes"); true }
                R.id.tabGallery -> { tabDots.remove("gallery"); paintTabDots(); showWeb(galleryWeb, "/terminus/"); true }
                R.id.tabOps -> { showWeb(opsWeb, "/ops"); true }
                else -> false
            }
        }
        // Triple-tap the active chat tab = backend picker (fast switch
        // with no cog round-trip). Single/double reselections behave exactly
        // as before; only the third tap within the window diverts.
        var chatTabTaps = 0
        var chatTabLastTap = 0L
        nav.setOnItemReselectedListener {
            // Tap active tab: thread → list, list → scroll top. No double-tap.
            // Web tabs (gallery/notes/ops) reset to their root — a file opened
            // inside the tab never traps it.
            when (it.itemId) {
                R.id.tabChat -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    chatTabTaps = if (now - chatTabLastTap < 600) chatTabTaps + 1 else 1
                    chatTabLastTap = now
                    if (chatTabTaps >= 3) {
                        chatTabTaps = 0
                        quickToggleBackend()
                    } else if (isThreadOpen()) closeThread()
                    else sessionList.smoothScrollToPosition(0)
                }
                R.id.tabGallery -> resetWebTab(galleryWeb, "/terminus/")
                R.id.tabNotes -> resetWebTab(notesWeb, "/notes")
                R.id.tabOps -> resetWebTab(opsWeb, "/ops")
                else -> {}
            }
        }
        initChat()
        nav.selectedItemId = R.id.tabChat
        ChatNotify.ensureChannel(this)
        scheduleInboxWorker()
        LiveLinkService.start(this)
        LiveLinkService.onLinkUp = { runOnUiThread { scheduleOutboxRetry(500) } }
        ReplyFollow.isWatching = { chat ->
            activeSessionId == chat && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        NotificationReplyReceiver.onExternalTurn = { chat, turn ->
            runOnUiThread {
                pendingTurns.getOrPut(chat) { mutableSetOf() }.add(turn)
                if (activeSessionId == chat) renderThread(chat)
                renderSessions()
                ensureStream(chat)
            }
        }
        try {
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { runOnUiThread { scheduleOutboxRetry(2_000) } }
                })
        } catch (_: Exception) {}
        // A recreated activity (process death, rotation) gets the original launch intent back, so
        // handling it again re-opens the share sheet or thread. Only a fresh launch handles it here.
        if (savedInstanceState == null) {
            handleChatIntent(intent)
            handleShareIntent(intent)
            handleProcessText(intent)
        }

        echoCard.setOnClickListener {
            if (RecorderService.chatCaptureActive) {
                android.widget.Toast.makeText(this, "Recording voice note", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasMic()) { askMic(); return@setOnClickListener }
            toggle()
        }
        // Long-press starts a LABELED session: one optional name up front,
        // carried into every chunk filename. Tap still starts unlabeled.
        echoCard.setOnLongClickListener {
            if (RecorderService.chatCaptureActive) return@setOnLongClickListener true
            if (RecorderService.running) return@setOnLongClickListener false
            if (!hasMic()) { askMic(); return@setOnLongClickListener true }
            promptEchoLabel()
            true
        }
        findViewById<TextView>(R.id.seeAll).setOnClickListener { adapter.expandAll() }
        findViewById<View>(R.id.chatCog).setOnClickListener { showDefaults() }
        findViewById<View>(R.id.echoCog).setOnClickListener { showDefaults() }
        // Per-tab header actions mirror the chat tab: bell = activity inbox
        // (shared unread dots), cog = settings, plus = new chat draft.
        for (bellId in listOf(R.id.bellEcho, R.id.bellNotes, R.id.bellGallery, R.id.bellOps)) {
            try { findViewById<View>(bellId)?.setOnClickListener { showInbox() } } catch (_: Exception) {}
        }
        for (cogId in listOf(R.id.cogNotes, R.id.cogGallery, R.id.cogOps)) {
            try { findViewById<View>(cogId)?.setOnClickListener { showDefaults() } } catch (_: Exception) {}
        }
        for (plusId in listOf(R.id.plusEcho, R.id.plusNotes, R.id.plusGallery, R.id.plusOps)) {
            try {
                findViewById<View>(plusId)?.setOnClickListener {
                    showChat()
                    openDraft()
                }
            } catch (_: Exception) {}
        }
        refresh()
    }

    /** Background inbox backstop (free, no Firebase): WorkManager runs
     *  InboxWorker every 15 min (platform minimum) with the app dead.
     *  KEEP = schedule once, survives reboot, no duplicate workers. */
    private fun scheduleInboxWorker() {
        try {
            val req = androidx.work.PeriodicWorkRequestBuilder<InboxWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build())
                .build()
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "inbox", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) {}
    }

    /** Notifications open their target chat. Ordinary app launches stay on
     *  the home/session list. */
    private fun handleChatIntent(intent: Intent?) {
        val requestedChat = intent?.getStringExtra(ChatNotify.EXTRA_CHAT_ID)
            ?.takeIf { it.isNotBlank() }
        val deep = requestedChat?.takeIf { chatStore.get(it) != null }
        val notificationReply = try {
            intent?.let { androidx.core.app.RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(ChatNotify.REMOTE_REPLY_KEY)?.toString()?.trim()
        } catch (_: Exception) { null }
        if (deep != null) {
            showChat()
            openThread(deep)
            if (!notificationReply.isNullOrBlank()) {
                composerInput.post {
                    if (activeSessionId != deep) return@post
                    composerInput.setText(notificationReply)
                    composerInput.setSelection(notificationReply.length)
                    sendChat()
                }
            }
        } else {
            // Plain re-launch (launcher icon, no notification): do nothing.
            // The open thread stays exactly as it was.
            if (!notificationReply.isNullOrBlank() && deep == null) {
                toast("That chat has been deleted")
            }
            return
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChatIntent(intent)
        handleShareIntent(intent)
        handleProcessText(intent)
    }

    /** Share-sheet entry: SEND / SEND_MULTIPLE routed by the invoked alias.
     *  Terminus chat stages files on the composer; Terminus upload posts
     *  straight to the gallery (queued + retried when locked/offline). */
    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != "android.intent.action.SEND_MULTIPLE") return
        val alias = intent.component?.className ?: ""
        val toUpload = alias.endsWith(".ShareUpload")
        if (!toUpload && !alias.endsWith(".ShareChat")) return
        val uris = mutableListOf<android.net.Uri>()
        if (action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            intent.data?.let { if (it.scheme == "content") uris += it }
        } else {
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        // Shares often carry the same content in EXTRA_STREAM and intent.data:
        // dedupe or one photo stages (and sends) twice with a "2 files" label.
        val distinctUris = uris.distinct()
        // Text/link shares carry no stream: route the text, not silence.
        val sharedText = (intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() })?.trim()
        if (uris.isEmpty() && sharedText.isNullOrBlank()) return
        intent.action = null
        setIntent(intent)
        Thread {
            val files = distinctUris.take(5).mapNotNull {
                try { MediaFiles.saveAttach(this, it) } catch (_: Exception) { null }
            }
            if (files.isEmpty() && sharedText.isNullOrBlank()) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Couldn't read that file", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            runOnUiThread {
                val omitted = distinctUris.size - files.size
                if (omitted > 0) toast(
                    if (distinctUris.size > 5) "Using the first 5 of ${distinctUris.size} shared items"
                    else "$omitted shared item${if (omitted == 1) "" else "s"} couldn't be read")
                if (toUpload) {
                    if (files.isEmpty()) {
                        android.widget.Toast.makeText(this, "Text can't go in the gallery",
                            android.widget.Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    GalleryUploader.queue(this, files)
                    // The upload runs in the background; pending() right after queue() is always >0.
                    android.widget.Toast.makeText(this,
                        "sending ${files.size} to gallery…", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // A share launched from another app always asks for its
                    // destination, even when a chat happened to be open.
                    showShareTargetSheet(files, sharedText)
                }
            }
        }.start()
    }

    /** Text-selection entry (Ask Terminus): selected text from any app
     *  stages straight into the composer, no share sheet. Never clobbers:
     *  an open thread keeps its draft, otherwise a fresh draft opens. */
    private fun handleProcessText(intent: Intent?) {
        if (intent?.action != "android.intent.action.PROCESS_TEXT") return
        val selected = (intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT))?.toString()?.trim()
        if (selected.isNullOrBlank()) return
        intent.action = null
        setIntent(intent)
        showChat()
        if (!isThreadOpen()) openDraft()
        applySharePayload(emptyList(), selected)
        composerInput.hint = "Ask about this"
    }

    /** Stage a share into the open thread: files onto the attach strip,
     *  text appended to the composer without clobbering a draft. Caller
     *  opens the thread first; this only lands the payload. */
    private fun applySharePayload(files: List<java.io.File>, sharedText: String?) {
        if (chatPane.visibility != View.VISIBLE) nav.selectedItemId = R.id.tabChat
        if (files.isNotEmpty()) {
            pendingAttaches.addAll(files)
            showAttach()
        }
        // Shared text (link, quote, caption) lands in the
        // composer; never clobbers a draft already there.
        if (!sharedText.isNullOrBlank()) {
            val cur = composerInput.text.toString()
            composerInput.setText(if (cur.isBlank()) sharedText else "$cur\n$sharedText")
        }
        composerInput.hint = "caption or send"
        composerInput.requestFocus()
    }

    /** Always ask where an external share belongs. Shows the incoming media,
     *  searchable chat history, and an explicit new-chat action. */
    private fun showShareTargetSheet(files: List<java.io.File>, sharedText: String?) {
        nav.selectedItemId = R.id.tabChat
        val chats = try { chatStore.list() } catch (_: Exception) { emptyList() }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val content = layoutInflater.inflate(R.layout.sheet_share_target, null)
        sheet.setContentView(content)
        val file = files.firstOrNull()
        val payloadName = when {
            files.size > 1 -> "${files.size} attachments"
            file != null -> file.name
            else -> "Text share"
        }
        content.findViewById<TextView>(R.id.sharePayloadName).text = payloadName
        val mediaMeta = when {
            files.size == 1 && file != null -> attachmentMeta(file.name, "file://${file.absolutePath}")
            files.size > 1 -> files.take(3).joinToString(" · ") { attachmentTypeMark(it.name) } +
                if (files.size > 3) " · +${files.size - 3}" else ""
            else -> ""
        }
        val textMeta = sharedText?.replace(Regex("\\s+"), " ")?.take(64).orEmpty()
        content.findViewById<TextView>(R.id.sharePayloadMeta).text =
            listOf(mediaMeta, textMeta).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Message" }
        val payloadThumb: ImageView = content.findViewById(R.id.sharePayloadThumb)
        val payloadType: TextView = content.findViewById(R.id.sharePayloadType)
        payloadType.text = if (file == null) "TEXT" else if (files.size > 1) "${files.size}" else attachmentTypeMark(file.name)
        if (file != null && SessionAdapter.isImagePath(file.name)) {
            bindAttachmentThumbnail("file://${file.absolutePath}", payloadThumb, payloadType)
        }

        var chosen = false
        fun finishWith(action: () -> Unit) {
            chosen = true
            sheet.dismiss()
            action()
        }
        content.findViewById<View>(R.id.shareNewChat).setOnClickListener {
            finishWith {
                showChat()
                openDraft()
                applySharePayload(files, sharedText)
            }
        }

        val list: android.widget.ListView = content.findViewById(R.id.shareChatList)
        list.divider = null
        val empty = content.findViewById<TextView>(R.id.shareChatEmpty)
        list.emptyView = empty
        val visible = chats.toMutableList()
        val adapter = object : android.widget.ArrayAdapter<ChatSession>(this, 0, visible) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_share_target, parent, false)
                val chat = getItem(position) ?: return row
                val title = chat.title.takeIf { it.isNotBlank() } ?: "Untitled chat"
                val avatar = row.findViewById<TextView>(R.id.shareChatAvatar)
                avatar.text = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
                val tileColors = intArrayOf(R.color.tile_0, R.color.tile_1, R.color.tile_2, R.color.tile_3,
                    R.color.tile_4, R.color.tile_5, R.color.tile_6, R.color.tile_7)
                val color = androidx.core.content.ContextCompat.getColor(this@MainActivity,
                    tileColors[(chat.id.hashCode() and Int.MAX_VALUE) % tileColors.size])
                avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                row.findViewById<TextView>(R.id.shareChatName).text = title
                val backendBadge = row.findViewById<ImageView>(R.id.shareChatBackend)
                backendBadge.setImageResource(agentIcon(chat.backend))
                backendBadge.contentDescription = backendDisplayName(chat.backend)
                val preview = chat.messages.asReversed().firstOrNull { it.text.isNotBlank() }?.text
                    ?.replace(Regex("\\s+"), " ")?.take(72)
                    .orEmpty()
                row.findViewById<TextView>(R.id.shareChatPreview).text = when {
                    chat.unread > 0 -> "$preview${if (preview.isNotBlank()) " · " else ""}${chat.unread} unread"
                    preview.isNotBlank() -> preview
                    else -> chat.backend.replaceFirstChar { it.uppercase() }
                }
                row.findViewById<TextView>(R.id.shareChatTime).text = SessionAdapter.smartDate(chat.updatedAt)
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val chat = adapter.getItem(position) ?: return@setOnItemClickListener
            finishWith {
                showChat()
                openThread(chat.id)
                applySharePayload(files, sharedText)
            }
        }
        content.findViewById<android.widget.EditText>(R.id.shareChatSearch)
            .addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim()?.lowercase().orEmpty()
                    val filtered = if (query.isBlank()) chats else chats.filter { chat ->
                        chat.title.lowercase().contains(query) || chat.backend.lowercase().contains(query) ||
                            chat.messages.asReversed().take(4).any { it.text.lowercase().contains(query) }
                    }
                    adapter.clear()
                    adapter.addAll(filtered)
                    adapter.notifyDataSetChanged()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        sheet.setOnDismissListener {
            if (!chosen) files.forEach { try { it.delete() } catch (_: Exception) {} }
        }
        sheet.setOnShowListener {
            val height = (resources.displayMetrics.heightPixels * 0.86f).toInt()
            val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottom?.layoutParams = bottom?.layoutParams?.apply { this.height = height }
            try {
                sheet.behavior.peekHeight = height
                sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                sheet.behavior.skipCollapsed = true
            } catch (_: Exception) {}
        }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    /** Files on disk never registered (killed mid-record, app exit) → requeue. */
    private fun recoverOrphans() {
        try {
            val known = chunks.all().map { it.name }.toSet() + ChatVoiceStore(this).all()
            recordingsDir(this).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".m4a") && it.length() > 1024 && it.name !in known }
                ?.forEach { chunks.add(it.name) }
        } catch (_: Exception) {}
    }

    private fun setupWeb(w: WebView) {
        tuneAuthWebView(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = true
        w.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        if (Build.VERSION.SDK_INT >= 29) w.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
        if (Build.VERSION.SDK_INT >= 33) w.settings.isAlgorithmicDarkeningAllowed = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url == lastLanRetry) lastLanRetry = null
            }
            // Deterministic in-app auth: every server request carries the
            // bearer header, cookie or no cookie. Null return falls back to
            // the default loader (cookie backup). Range forwarded so video
            // seeking keeps working through the interception.
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                return authedResponse(req.url.toString(), req.requestHeaders, req.method)
            }
            override fun onReceivedError(
                view: WebView, request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val code = error.errorCode
                val connectish = code == WebViewClient.ERROR_HOST_LOOKUP ||
                    code == WebViewClient.ERROR_CONNECT ||
                    code == WebViewClient.ERROR_TIMEOUT ||
                    code == WebViewClient.ERROR_UNKNOWN
                if (!connectish) return
                if (!OpencodeApi.LAN_FALLBACK_ENABLED) return
                val failing = request.url.toString()
                if (failing == lastLanRetry || failing.startsWith(OpencodeApi.LAN_BASE)) return
                if (!isServerUrl(failing)) return
                lastLanRetry = OpencodeApi.lanSwap(failing)
                OpencodeApi.reportLanGood()
                view.post { view.loadUrl(authedUrl(lastLanRetry!!)) }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url.toString()
                // Phone and email links hand off to the dialer / mail app; the WebView cannot load them.
                if (isContactUrl(u)) {
                    openContactLink(u)
                    return true
                }
                // Text files read IN-APP (viewer sheet), never thread nav.
                if (isTextFile(u)) {
                    openTextFile(if (u.startsWith("file://")) u else authedUrl(u))
                    return true
                }
                // HTML previews in-app (viewer sheet with close), never bare
                // tab navigation (which strands tabs) nor external browser.
                val low = u.lowercase().substringBefore("?").substringBefore("#")
                if (low.endsWith(".html") || low.endsWith(".htm")) {
                    openHtmlViewer(if (u.startsWith("file://")) u else authedUrl(u))
                    return true
                }
                // Files the WebView can't render go straight to download:
                // loading them shows a blank page and no download fires.
                if (isDownloadFile(u)) {
                    downloadFile(u, req.requestHeaders["User-Agent"] ?: "", null, null)
                    return true
                }
                val base = prefs.serverUrl().trimEnd('/')
                if (u.startsWith(base) && !u.contains("mobile=1")) {
                    val sep = if (u.contains("?")) "&" else "?"
                    var target = "$u${sep}mobile=1"
                    val tok = prefs.token()   // in-app pages upload/delete with this: master token, never the GET-only link token
                    if (tok.isNotBlank() && !target.contains("token=")) {
                        target += "&token=" + android.net.Uri.encode(tok)
                    }
                    view.loadUrl(target)
                    return true
                }
                return false
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            // Fullscreen video: the thread player expands here. Without
            // this the fullscreen button silently does nothing.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                try {
                    if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                    fullscreenView = view
                    fullscreenCallback = callback
                    fullscreenDialog = android.app.Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                        setContentView(view, android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                        setCancelable(true)
                        setOnDismissListener { onHideCustomView() }
                        show()
                    }
                } catch (_: Exception) {
                    try { callback.onCustomViewHidden() } catch (_: Exception) {}
                }
            }
            override fun onHideCustomView() {
                try { fullscreenDialog?.dismiss() } catch (_: Exception) {}
                fullscreenDialog = null
                fullscreenView = null
                try { fullscreenCallback?.onCustomViewHidden() } catch (_: Exception) {}
                fullscreenCallback = null
            }
            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams
            ): Boolean {
                filePickerCallback?.onReceiveValue(null)
                filePickerCallback = cb
                try {
                    filePickerLauncher.launch(params.createIntent())
                    return true
                } catch (e: Exception) { /* fall through to generic picker */ }
                return try {
                    val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    }
                    filePickerLauncher.launch(android.content.Intent.createChooser(i, "choose file"))
                    true
                } catch (e: Exception) {
                    filePickerCallback?.onReceiveValue(null)
                    filePickerCallback = null
                    false
                }
            }
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            downloadFile(url, userAgent, contentDisposition, mime)
        }
    }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (ok && uri != null) {
                try {
                    stageAttachUri(uri, "camera.jpg")
                } catch (_: Exception) {}
            }
        }

    private val attachLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                try {
                    // Multi-select returns ClipData; single-pick returns data.
                    // Cap staged files so one share can't flood staging.
                    val uris = mutableListOf<android.net.Uri>()
                    val clip = res.data!!.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val u = try { clip.getItemAt(i).uri } catch (_: Exception) { null }
                            if (u != null) uris.add(u)
                        }
                    } else if (res.data?.data != null) {
                        uris.add(res.data!!.data!!)
                    }
                    var trimmed = false
                    for (u in uris) {
                        if (pendingAttaches.size >= 5) { trimmed = true; break }
                        pendingAttaches.add(MediaFiles.saveAttach(this, u))
                    }
                    afterAttachesStaged(trimmed)
                } catch (e: Exception) {
                    composerInput.hint = "Message (/ for commands)"
                }
            }
        }

    /** Stage one content URI into pending attaches (camera capture path).
     *  Compression to model size happens later in prepareForUpload. */
    private fun stageAttachUri(uri: android.net.Uri, hint: String) {
        if (pendingAttaches.size >= 5) {
            android.widget.Toast.makeText(this,
                "Only 5 files per message", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pendingAttaches.add(MediaFiles.saveAttach(this, uri, hint))
        afterAttachesStaged(false)
    }

    private fun afterAttachesStaged(trimmed: Boolean) {
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
    private fun takePhoto() {
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

    private fun initChat() {
        chatStore = ChatStore(this)
        // A browser card left "live" by a killed app can never update again: close it out.
        try { chatStore.freezeStaleBrowserCards() } catch (_: Exception) {}
        chatPane = findViewById(R.id.chatPane)
        sessionList = findViewById(R.id.sessionList)
        sessionPull = findViewById(R.id.sessionPull)
        sessionPull.setOnRefreshListener { refreshSessions() }
        chatEmpty = findViewById(R.id.chatEmpty)
        sessionSearch = findViewById(R.id.sessionSearch)
        searchToggle = findViewById(R.id.searchToggle)
        searchIcon = findViewById(R.id.searchIcon)
        bellBtn = findViewById(R.id.bellBtn)
        bellBtn.setOnClickListener { showInbox() }
        chatHero = findViewById(R.id.chatHero)
        heroModelBtn = findViewById(R.id.heroModelBtn)
        threadHeader = findViewById(R.id.threadHeader)
        threadTitle = findViewById(R.id.threadTitle)
        threadTitle.setOnClickListener { setThreadTitleExpanded(!threadTitleExpanded) }
        updateThreadTitleDescription()
        threadWeb = findViewById(R.id.threadWeb)
        tuneAuthWebView(threadWeb)
        threadFrame = findViewById(R.id.threadFrame)
        threadDivider = findViewById(R.id.threadDivider)
        goalStrip = findViewById(R.id.goalStrip)
        goalStrip.setOnClickListener { goalManageDialog() }
        goalStripBar = findViewById(R.id.goalStripBar)
        // One-tap exit from watching: the same effect as the sheet's
        // "Remove goal", without a two-tap detour through the sheet.
        findViewById<View>(R.id.goalClear).setOnClickListener { goalClearGoal() }
        composerRow = findViewById(R.id.composerRow)
        composerInput = findViewById(R.id.composerInput)
        chatMic = findViewById(R.id.chatMic)
        chatMicIdleTint = chatMic.imageTintList
        chatRecStatus = findViewById(R.id.chatRecStatus)
        voicePreview = findViewById(R.id.voicePreview)
        voicePreviewLabel = findViewById(R.id.voicePreviewLabel)
        attachPreview = findViewById(R.id.attachPreview)
        attachPreviewLabel = findViewById(R.id.attachPreviewLabel)
        attachThumb = findViewById(R.id.attachThumb)
        findViewById<View>(R.id.attachDrop).setOnClickListener { dropAttach() }
        quoteBar = findViewById(R.id.quoteBar)
        quoteChips = findViewById(R.id.quoteChips)
        quoteEditor = findViewById(R.id.quoteEditor)
        quoteNote = findViewById(R.id.quoteNote)
        findViewById<View>(R.id.quoteEditCancel).setOnClickListener {
            val q = editingQuote
            if (q != null && editingQuoteIsNew) quotes.remove(q)
            closeQuoteEditor()
        }
        findViewById<View>(R.id.quoteDone).setOnClickListener { saveQuoteNote() }
        findViewById<View>(R.id.quoteClear).setOnClickListener { clearQuote() }
        findViewById<View>(R.id.voiceKeep).setOnClickListener { keepVoice() }
        findViewById<View>(R.id.voiceDrop).setOnClickListener { dropVoice() }
        slashList = findViewById(R.id.slashList)
        slashAdapter = android.widget.ArrayAdapter(this, R.layout.item_menu_row, mutableListOf())
        slashList.adapter = slashAdapter
        slashList.setOnItemClickListener { _, _, pos, _ ->
            slashRowCommands.getOrNull(pos)?.let { completeSlash(it) }
        }
        modelChip = findViewById(R.id.modelChip)
        try {
            modelChip.iconSize = dp(12)
            modelChip.iconPadding = dp(4)
            // Official marks carry their own colors; any inherited tint
            // blanks them against the chip (the blank-icon fault).
            modelChip.iconTint = null
        } catch (_: Exception) {}
        attachListBtn = findViewById(R.id.attachListBtn)
        contextRing = findViewById(R.id.contextRing)
        contextRing.setOnClickListener { showContextSheet() }
        findViewById<View>(R.id.threadMenu).setOnClickListener { threadMenuDialog() }
        findViewById<View>(R.id.attachListBtn).setOnClickListener {
            activeSessionId?.let { showChatAttachments(it) }
        }
        sessionList.layoutManager = LinearLayoutManager(this)
        // Tab map changed (Pinned removed: 0 Recent, 1 Unread, 2 Schedules).
        // Old saves: 1 was Pinned (gone → Recent), 2 was Unread (→ 1).
        sessionFilter = when (prefs.sessionFilter()) { 1 -> 0; 2 -> 1; else -> 0 }
        sessionSort = prefs.sessionSort()
        sessControls = findViewById(R.id.sessControls)
        tabAll = findViewById(R.id.tabAll)
        tabUnread = findViewById(R.id.tabUnread)
        tabSchedules = findViewById(R.id.tabSchedules)
        sortBtn = findViewById(R.id.sortBtn)
        iconBackendScope = findViewById(R.id.iconBackendScope)
        // Scope tap lives on the full 48dp frame, not the 20dp glyph:
        // edge taps on the glyph alone used to die silently (multi-tap feel).
        findViewById<View>(R.id.backendScopeBtn).setOnClickListener { backendDialog { renderSessions() } }
        tabAll.setOnClickListener { setSessionFilter(0) }
        tabUnread.setOnClickListener { setSessionFilter(1) }
        tabSchedules.setOnClickListener { setSessionFilter(2) }
        sortBtn.setOnClickListener { showSortMenu() }
        sessionAdapter = SessionAdapter(
            emptyList(),
            onOpen = { openThread(it.id) },
            onDelete = { deleteSessionNow(it) },
            onRename = { s -> renameDialog(s) },
            onFork = { s -> forkChat(s.id, null) },
            onPin = { s ->
                chatStore.setPinned(s.id, !s.pinned)
                renderSessions()
            },
            baseProvider = { prefs.serverUrl() },
            tokenProvider = { prefs.token() },
            onImport = { imp -> importSession(imp) }
        )
        sessionAdapter.onSelectionChanged = { n -> updateSelBar(n) }
        sessionAdapter.onDeleteImport = { imp -> deleteRemoteSession(imp) }
        sessionAdapter.archivedOf = { c -> SessionAdapter.isArchived(c, System.currentTimeMillis(), false) }
        sessionAdapter.onOpenArchived = { setSessionFilter(3) }
        sessionAdapter.onArchive = { c ->
            val was = sessionAdapter.archivedOf(c)
            chatStore.setArchived(c.id, !was)
            toast(if (was) "Moved to Recent" else "Archived")
            renderSessions()
        }
        findViewById<View>(R.id.selDelete).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.selCancel).setOnClickListener {
            if (::sessionAdapter.isInitialized) sessionAdapter.clearSelection()
        }
        sessionList.adapter = sessionAdapter
        threadWeb.settings.javaScriptEnabled = true
        threadWeb.settings.allowFileAccess = true
        threadWeb.settings.domStorageEnabled = true // card expand/collapse state survives page reloads (sessionStorage)
        // Paint the View behind the page in the themed window color so loads
        // never flash white in dark mode (the HTML body paints over it).
        threadWeb.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.t_window))
        // Page itself never scrolls sideways; tables/code scroll internally.
        threadWeb.isHorizontalScrollBarEnabled = false
        // Native gesture handlers own thread messages (ChatMarkdown.__rowAt
        // is hit-test only). Quoting is select text → Quote, never a swipe. Long-press is left
        // to the WebView — native text selection with handles, then the
        // system copy/share menu. No JS gesture actions, no conflicts.
        threadWeb.isLongClickable = true
        // Quote joins the native selection menu (Copy/Share/Select all stay):
        // the subclass adds one item, the system owns the rest.
        threadWeb.onQuote = { text ->
            // Drop the handles so the next passage can be picked straight away.
            threadWeb.evaluateJavascript("(function(){var s=window.getSelection();if(s)s.removeAllRanges();})()", null)
            showQuote("", text)
        }
        val threadGestures = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    // Tap-to-edit: a failed bubble edits in the composer, in
                    // place — no modal. Link taps are excluded (they still
                    // navigate), and the gesture never consumes the event.
                    try { tapMessageToEdit(e.x, e.y) } catch (_: Exception) {}
                    return false
                }
            })
        // Hold a link, number or email to copy it (the link's own text, never a token). Anything else keeps the
        // default long-press (text selection).
        fun copyLink(text: String) {
            try {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
                toast("Copied $text".take(80))
            } catch (_: Exception) {}
            threadWeb.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        threadWeb.setOnLongClickListener {
            val h = threadWeb.hitTestResult
            if (h.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                // A link wrapped around an image: the hit's extra is the IMAGE url (a chip's site icon, a gallery
                // thumbnail), not where the link goes. Ask the WebView for the link's own address.
                val img = h.extra.orEmpty()
                val reply = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                    override fun handleMessage(msg: android.os.Message) {
                        val href = msg.data?.getString("url").orEmpty()
                        val text = copyTextFor(href.ifBlank { img })
                        if (text != null) copyLink(text)
                    }
                }
                threadWeb.requestFocusNodeHref(reply.obtainMessage())
                true
            } else {
                val text = longPressCopy(h.type, h.extra.orEmpty())
                if (text == null) false else { copyLink(text); true }
            }
        }
        LinkMetaStore.init(this)
        ThreadScrollStore.init(this)
        // Alarm links arm the moment a reply or delivery lands, whatever screen is up (never at render time).
        chatStore.onLanded = { _, text ->
            val armed = WakeAlarm.armFromText(this, text)
            if (armed.isNotEmpty()) runOnUiThread {
                toast("alarm ${WakeAlarm.describeAt(armed.min())} armed" + if (WakeAlarm.canFullScreen(this)) "" else " (rings as a notification)")
            }
            else if (!WakeAlarm.canExact(this)) runOnUiThread { toast("couldn't arm the alarm: allow exact alarms, then tap its card") }
        }
        ChatMarkdown.alarmStateLookup = { at -> WakeAlarm.stateOf(this, at) }
        ChatMarkdown.linkMetaLookup = { url ->
            if (!prefs.linkPreviews()) null
            else LinkMetaStore.get(url)?.let { j ->
                try { org.json.JSONObject(j).let { LinkFetcher.Meta(it.optString("url"), it.optString("title"), it.optString("site")) }.takeIf { it.title.isNotBlank() } } catch (_: Exception) { null }
            }
        }
        threadWeb.addJavascriptInterface(LinkPreviewBridge(prefs) { if (::threadWeb.isInitialized) threadWeb else null }, "TerminusLinks")
        threadWeb.addJavascriptInterface(MapBridge(applicationContext, prefs) { if (::threadWeb.isInitialized) threadWeb else null }, "TerminusMap")
        threadWeb.addJavascriptInterface(JobsBridge(prefs, { activeSessionId }) { if (::threadWeb.isInitialized) threadWeb else null }, "TerminusJobs")
        threadWeb.webViewClient = object : WebViewClient() {
            /** First frame of the new page is ready: show it now. onPageFinished waits for every subresource
             *  (attachment images and videos fetched from the server, link icons), so on a slow connection the
             *  chat stayed hidden behind the loading bars until the last picture arrived. The text is already
             *  laid out here; images fill in as they land. */
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (url == "about:blank") return
                if (view.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
                    view.visibility = View.VISIBLE
                }
                setThreadLoading(false)
                // Same pending scroll as onPageFinished, so the reveal lands on the right place, not the top.
                pendingThreadScroll?.let { threadScrollHandler.post(it) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                // openThread replaces the current document directly. Ignore
                // any late completion from an old blank/navigation page so
                // it cannot mark the new thread DOM as ready for JS patches.
                if (url == "about:blank") return
                threadPageLoaded = true
                sendNowKey = ""   // a rebuilt page has no links yet
                refreshSendNowLinks()
                // The page was rebuilt: give the browser card its live state back.
                phoneBrowser?.let { browserCards.onState(it.stateJson(full = true)) }
                // Rebuilt page shows idle speaker icons: restore the stop icon if a reply is still being read.
                replyVoice.restore()
                // First commit reveals a hidden thread view (openThread
                // keeps it invisible to kill the previous-chat flash).
                // Stale finishes after close stay hidden: no open thread.
                if (view.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
                    view.visibility = View.VISIBLE
                }
                setThreadLoading(false)
                // loadData resets page scroll to top — replay the pending
                // scroll (bottom for new replies, saved Y for progress
                // updates) once content is laid out, plus one delayed
                // safety repost. Touch truce still wins: a user touch
                // clears pendingThreadScroll first.
                pendingThreadScroll?.let {
                    threadScrollHandler.post(it)
                    threadScrollHandler.postDelayed(it, 400)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                val u = req.url.toString()
                try { android.util.Log.d("TAPDBG", "thread-tap $u") } catch (_: Exception) {}
                // Phone and email links hand off to the dialer / mail app; the WebView cannot load them.
                if (isContactUrl(u)) {
                    openContactLink(u)
                    return true
                }
                // In-thread action (failure rows): retry a failed turn as a
                // fresh send — same handler as the slash command.
                if (u.startsWith("terminus://retry")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        retryTurn(chat, turn)
                    } catch (_: Exception) {}
                    return true
                }
                // Failed-turn edit: the failed message edits in the composer
                // itself (tap the failed bubble or this link), not a modal.
                // Save rewrites that turn's user row in place and retries it
                // — reconcile-first, so a meanwhile-settled turn delivers
                // instead of doubling.
                if (u.startsWith("terminus://edit")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        enterEditMode(chat, turn)
                    } catch (_: Exception) {}
                    return true
                }
                // Unknown-outcome recheck: ask the server's exact-turnId
                // registry again without resending anything.
                if (u.startsWith("terminus://reconcile")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        reconcileTurn(chat, turn, tap = true)
                    } catch (_: Exception) {}
                    return true
                }
                if (u.startsWith("terminus://sendnow")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        sendNowTap(chat, turn)
                    } catch (_: Exception) {}
                    return true
                }
                if (u.startsWith("terminus://discardqueued")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        discardQueuedTurn(chat, turn)
                    } catch (_: Exception) {}
                    return true
                }
                // Wake directive from the assistant ("wake me in 20 min"):
                // arm the device alarm the moment the link arrives. The app
                // is open by definition here, so arming is always safe.
                if (u == "terminus://browser") { phoneBrowser?.open(); return true }
                if (u.startsWith("terminus://alarm")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val at = uri.getQueryParameter("at")?.toLongOrNull()
                        val msg = uri.getQueryParameter("msg") ?: ""
                        if (at != null && at > System.currentTimeMillis()) {
                            if (!WakeAlarm.canExact(this@MainActivity)) {
                                val opened = WakeAlarm.requestExact(this@MainActivity)
                                toast(if (opened) "allow exact alarms in settings, then tap the alarm link again"
                                    else "open system settings → apps → Terminus → allow exact alarms")
                            } else {
                                if (WakeAlarm.armExplicit(this@MainActivity, at, msg)) {
                                    // Armed either way: without full-screen permission it rings as a notification.
                                    val note = if (WakeAlarm.canFullScreen(this@MainActivity)) "" else " (rings as a notification)"
                                    toast("alarm ${WakeAlarm.describeAt(at)} · ${msg.ifBlank { "Time to wake up" }}$note")
                                    activeSessionId?.let { renderThread(it, scroll = false) }   // the card now says Armed
                                } else toast("couldn't arm the alarm: allow exact alarms in settings")
                            }
                        } else {
                            toast("that alarm time already passed")
                        }
                    } catch (_: Exception) {}
                    return true
                }
                // History paging: grow the rendered tail, anchored.
                if (u == "terminus://more" || u.startsWith("terminus://more?") || u.startsWith("terminus://more#")) {
                    val id = activeSessionId ?: return true
                    growHistory(id)
                    return true
                }
                // Reply actions use the stable stored message index.
                if (u.startsWith("terminus://reply?")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val i = uri.getQueryParameter("i")?.toIntOrNull() ?: return true
                        val action = uri.getQueryParameter("do")
                        val msg = chatStore.get(chat)?.messages?.getOrNull(i) ?: return true
                        if (msg.role != "assistant") return true
                        if (action == "copy") {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", msg.text))
                            toast("reply copied")
                        } else if (action == "speak") replyVoice.speak("$chat:$i", msg.text)
                        else if (action == "more") replyMoreSheet(chat, i, msg.text)
                    } catch (_: Exception) {}
                    return true
                }
                // Code block copy button (thread-ui.js): the block's text rides in the link.
                if (u.startsWith("terminus://copy-code?")) {
                    try {
                        val text = android.net.Uri.parse(u).getQueryParameter("t").orEmpty()
                        if (text.isNotBlank()) {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
                            toast("code copied")
                        }
                    } catch (_: Exception) {}
                    return true
                }
                // Step copy: settled command row → clipboard (label + detail).
                if (u.startsWith("terminus://copy-step?")) {
                    try {
                        val uri = android.net.Uri.parse(u)
                        val chat = uri.getQueryParameter("chat") ?: return true
                        val turn = uri.getQueryParameter("turn") ?: return true
                        val i = uri.getQueryParameter("i")?.toIntOrNull() ?: return true
                        val msg = try { chatStore.get(chat)?.messages?.firstOrNull { it.turnId == turn } } catch (_: Exception) { null }
                        val steps = ChatMarkdown.stepsOf(msg?.stepsJson ?: "")
                        val s = steps.getOrNull(i) ?: return true
                        val text = listOf(s.label, s.detail).filter { it.isNotBlank() }.joinToString("\n")
                        if (text.isBlank()) return true
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("step", text))
                        runOnUiThread { toast("step copied") }
                    } catch (_: Exception) {}
                    return true
                }
                val low = u.lowercase().substringBefore("?").substringBefore("#")
                val isImg = low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
                    low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
                // Images open in a full-screen pinch-zoom viewer, never as
                // a thread navigation (which 401s without the token anyway).
                if (isImg && (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://"))) {
                    openViewer(if (u.startsWith("file://")) u else authedUrl(u))
                    return true
                }
                // Server file/gallery links: text reads IN-APP, HTML previews
                // in-app (viewer sheet), video plays in the in-app viewer
                // (authed through the interceptor); PDFs and other files open
                // outside the thread (system handler). This kills the
                // 401-JSON-in-thread bug on tap.
                if (isServerUrl(u)) {
                    if (isTextFile(u)) openTextFile(u)
                    else if (low.endsWith(".html") || low.endsWith(".htm")) openHtmlViewer(u)
                    else if (ChatMarkdown.isVideoUrl(low)) openViewer(u)
                    else openExternal(u)
                    return true
                }
                if (u.startsWith("file://")) {
                    if (isTextFile(u)) openTextFile(u)
                    else if (low.endsWith(".pdf")) openExternal(u) else openViewer(u)
                    return true
                }
                // External links leave the thread for the system browser.
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    openExternal(u)
                    return true
                }
                return false
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // Dead renderer = blank thread until something reloads it.
                // Force a full re-render of whatever is open; the hash guard
                // is cleared so loadData always re-issues.
                try {
                    threadPageLoaded = false
                    lastThreadHtml = ""
                    val id = activeSessionId
                    if (id != null) renderThread(id, scroll = false)
                    else if (draftOpen) renderDraft()
                } catch (_: Exception) {}
                return true
            }
        }

        var threadTouchDownY = 0f
        var threadTouchDownX = 0f
        var pullDownRawY = 0f
        var pullStartedAtBottom = false
        var pullArmed = false
        val threadTouchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val pullThresholdPx = PULL_THRESHOLD_DP * resources.displayMetrics.density
        val pullDeadzonePx = PULL_DEADZONE_DP * resources.displayMetrics.density
        fun threadAtBottom(): Boolean = try {
            val d = resources.displayMetrics.density
            threadWeb.scrollY + threadWeb.height >= (threadWeb.contentHeight * d).toInt() - 24
        } catch (_: Exception) { false }
        threadWeb.setOnTouchListener { v, e ->
            try { threadGestures.onTouchEvent(e) } catch (_: Exception) {}
            // Pull up at the bottom = manual sync. Observes only (the listener still returns false, so
            // scrolling is untouched): arm on a deliberate upward drag, tick once, sync on release.
            try {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        threadTouchDownX = e.rawX
                        pullDownRawY = e.rawY
                        pullStartedAtBottom = threadAtBottom()
                        pullArmed = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> if (!pullChecking && pullStartedAtBottom && threadAtBottom()) {
                        val drag = pullDownRawY - e.rawY
                        val held = e.eventTime - e.downTime
                        val p = pullProgress(drag, pullDeadzonePx, pullThresholdPx)
                        val armed = pullUpArmed(true, true, drag, e.rawX - threadTouchDownX, pullThresholdPx, held)
                        if (p > 0.15f && held >= PULL_SHOW_AFTER_MS) pullShow(p, armed) else pullHide(animate = false)
                        if (armed && !pullArmed) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        pullArmed = armed
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val fire = pullArmed
                        pullArmed = false
                        if (fire) activeSessionId?.let { syncThread(it, manual = true) } else if (!pullChecking) pullHide()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        pullArmed = false
                        if (!pullChecking) pullHide()
                    }
                }
            } catch (_: Exception) {}
            if (e.action == android.view.MotionEvent.ACTION_DOWN) {
                threadTouchDownY = e.y
                threadHistoryGestureUsed = false
            } else if (e.action == android.view.MotionEvent.ACTION_MOVE) {
                // Arm history paging only for a real downward finger drag
                // (content moving toward older messages), never a tap or a
                // programmatic scroll. One drag can trigger one page load.
                if (!threadHistoryGestureUsed && e.y - threadTouchDownY > threadTouchSlop) {
                    threadTouchScroll = true
                    threadHistoryGestureUsed = true
                }
            }
            if (e.action == android.view.MotionEvent.ACTION_DOWN ||
                e.action == android.view.MotionEvent.ACTION_MOVE) {
                // Touch truce with re-pin: cancel the stale scroll post but
                // immediately pin to the CURRENT position, so the next
                // loadData (which resets to top) replays the touched Y
                // instead of stranding the user at the top with nothing pending.
                val y = try { threadWeb.scrollY } catch (_: Exception) { 0 }
                openAnchor = null
                pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
                pendingThreadScroll = Runnable { try { threadWeb.scrollTo(0, y) } catch (_: Exception) {} }
            }
            false
        }

        jumpLatest = findViewById(R.id.jumpLatest)
        jumpLatest.setOnClickListener { scrollThreadToBottom() }
        threadWeb.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            try {
                val h = v.height
                if (h <= 0) return@setOnScrollChangeListener
                val contentPx = (threadWeb.contentHeight * resources.displayMetrics.density).toInt()
                val gap = contentPx - scrollY - h
                jumpLatest.visibility = if (gap > 300) View.VISIBLE else View.GONE
                // Scroll-up auto-fetch: near the top with hidden history,
                // grow anchored. Guarded + cheap: one check per scroll event,
                // growHistory no-ops when nothing is hidden. Finger-only:
                // programmatic motion (open, bottom pin) never grows.
                if (scrollY <= 200 && threadTouchScroll && threadPageLoaded && !historyGrowing) {
                    val id = activeSessionId
                    if (id != null) {
                        val vis = threadVisible[id] ?: threadPageSize
                        val total = try { chatStore.get(id)?.messages?.size ?: 0 } catch (_: Exception) { 0 }
                        if (total > vis) growHistory(id)
                    }
                }
            } catch (_: Exception) {}
        }

        findViewById<View>(R.id.newChatBtn).setOnClickListener {
            openDraft()
        }
        findViewById<View>(R.id.threadBack).setOnClickListener { closeThread() }
        findViewById<View>(R.id.hintSearch).setOnClickListener { hintTap("/search ") }
        findViewById<View>(R.id.hintFetch).setOnClickListener { hintTap("/fetch ") }
        findViewById<View>(R.id.hintThink).setOnClickListener { hintTap("/think ") }
        heroModelBtn.setOnClickListener {
            openDraft()
        }
        findViewById<View>(R.id.attachBtn).setOnClickListener {
            attachSheetDialog()
        }
        stopBtn = findViewById(R.id.stopBtn)
        stopBtn.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(.88f).scaleY(.88f).setDuration(120L).setInterpolator(easeOut).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(easeOut).start()
            }
            false
        }
        stopBtn.setOnClickListener {
            val id = activeSessionId
            if (id != null) {
                // Same rule as visibility: any live placeholder is stoppable.
                // Interrupting a stale/unknown turn is meaningful (the remote
                // run may still be alive); success drops its placeholder.
                // Re-check under the tap: a stale-visible button corrects
                // itself here instead of acting on dead state.
                val liveNow = hasPlaceholder(id)
                updateStopBtn()
                if (liveNow) {
                    handleCommand(id, "/stop")
                } else {
                    android.widget.Toast.makeText(this, "No reply running", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.sendBtn).setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(.88f).scaleY(.88f).setDuration(120L).setInterpolator(easeOut).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(easeOut).start()
            }
            false
        }
        findViewById<View>(R.id.sendBtn).setOnClickListener { sendChat() }
        chatMic.setOnClickListener { onChatMic() }
        modelChip.setOnClickListener { modelDialog() }
        searchToggle.setOnClickListener { toggleSearch() }
        sessionSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                sessionQuery = s?.toString()?.trim() ?: ""
                // Text in the box means the box is open. After the activity is recreated Android restores the
                // text but not our flag, which left the filter applied with its field hidden.
                if (sessionQuery.isNotBlank()) searchOpen = true
                // Debounced: the query scan walks message bodies per session,
                // so one render per pause, not one per keystroke.
                try { sessionSearch.removeCallbacks(searchRenderDebounce) } catch (_: Exception) {}
                try { sessionSearch.postDelayed(searchRenderDebounce, 200) } catch (_: Exception) { renderSessions() }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        composerInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val t = s?.toString() ?: ""
                val id = activeSessionId
                if (id == null && draftOpen) draftText = t
                renderSlashHints(t)
                updateComposerSlot()

                if (id != null && !t.startsWith("/")) {
                    // Debounced: one store write per pause, not per character.
                    // A send/close/pause always flushes or cancels first, so
                    // the pending text here can never overwrite fresher state.
                    dropPendingDraft()
                    val save = Runnable {
                        pendingDraftSave = null
                        pendingDraftId = null
                        try { chatStore.setDraft(id, t) } catch (_: Exception) {}
                    }
                    pendingDraftSave = save
                    pendingDraftId = id
                    try { draftHandler.postDelayed(save, 350) } catch (_: Exception) { save.run() }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        composerInput.setOnEditorActionListener { _, actionId, _ ->
            if (slashList.visibility == View.VISIBLE && slashShown.isNotEmpty()) {
                completeSlash(slashShown[0]); true
            } else if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendChat(); true }
            else false
        }
        composerInput.setOnKeyListener { _, keyCode, e ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && e.action == android.view.KeyEvent.ACTION_DOWN &&
                slashList.visibility == View.VISIBLE && slashShown.isNotEmpty()) {
                completeSlash(slashShown[0]); true
            } else false
        }
        renderSessions()
        // Load backend capabilities at startup. Attachment choices depend on
        // this catalog; otherwise non-OpenCode chats were temporarily treated
        // as unsupported until the settings sheet happened to refresh it.
        refreshBackends()
        refreshModels()
    }

    /** Empty-state hint row: open (or reuse) a thread, pre-fill the command, focus. */
    private fun hintTap(cmd: String) {
        if (activeSessionId == null && !draftOpen) openDraft()
        composerInput.setText(cmd)
        composerInput.setSelection(cmd.length)
        composerInput.requestFocus()
        composerInput.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(composerInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showChat() {
        echoPane.visibility = View.GONE
        galleryPane.visibility = View.GONE
        opsPane.visibility = View.GONE
        notesPane.visibility = View.GONE
        chatPane.visibility = View.VISIBLE
        crossFade(chatPane)
        migrateBindingsOnce()
        // Cold start with nothing cached loads nothing on its own (imports
        // only refresh on pull, and the hero hides the pull control) — an
        // empty app could never bootstrap. Sync once when imports are empty;
        // failures toast and the list is unchanged.
        if (serverImports.isEmpty()) refreshSessions()
        renderSessions()
        val openId = activeSessionId
        if (openId != null) renderThread(openId) else if (draftOpen) renderDraft()
        syncNavForThread()
        syncInbox()
    }

    /** One-shot upgrade to self-owned session ids: adopt the legacy server
     *  bindings as local remoteIds (fills blanks only, never overwrites).
     *  Flag-first so it runs once even if the fetch fails; a ghost id
     *  self-heals on first send (404 → clear → fresh). */
    private fun migrateBindingsOnce() {
        if (!::chatStore.isInitialized) return
        try { if (prefs.migratedBindings()) return } catch (_: Exception) { return }
        try { prefs.setMigratedBindings() } catch (_: Exception) {}
        Thread {
            try {
                val got = OpencodeApi.fetchBindings(prefs.serverUrl(), prefs.token())
                if (got.isNotEmpty()) {
                    for ((cid, sid) in got) {
                        try {
                            if (chatStore.get(cid)?.remoteId.isNullOrBlank()) chatStore.setRemoteId(cid, sid)
                        } catch (_: Exception) {}
                    }
                    runOnUiThread { renderSessions() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** Header ⌕ toggles the search field (autofocused); ✕ clears + hides. */
    private fun toggleSearch() {
        if (isThreadOpen()) return
        if (searchOpen) hideSearch() else showSearch()
    }

    private fun showSearch() {
        searchOpen = true
        sessionSearch.visibility = View.VISIBLE
        searchIcon.setImageResource(R.drawable.ic_close)
        sessionSearch.requestFocus()
        sessionSearch.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(sessionSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        renderSessions()
    }

    private fun hideSearch() {
        searchOpen = false
        sessionQuery = ""
        sessionSearch.setText("")
        sessionSearch.visibility = View.GONE
        searchIcon.setImageResource(R.drawable.ic_search)
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(sessionSearch.windowToken, 0)
        } catch (_: Exception) {}
        try { sessionSearch.removeCallbacks(searchRenderDebounce) } catch (_: Exception) {}
        renderSessions()
    }

    private fun renderSessions() {
        if (!::chatStore.isInitialized) return
        // Backend scope: the list follows the selected backend (view-only,
        // never migration). Search/tabs compose inside the scope.
        // The car's chat shows whatever backend is selected: you look for it on the phone after a drive.
        val all = backendScoped(chatStore.list(), prefs.defaultBackend(), setOf(CarChatsScreen.askChatId(this)))
        // The list is hidden while a thread is open: skip the sort, the
        // message-body search scan and the adapter diff entirely. The
        // snapshot advances on closeThread's full render. Settles and sends
        // (the hot paths) only refresh the live set below, never diff.
        val inThread = isThreadOpen()
        val nowMs = System.currentTimeMillis()
        // Job threads follow the same rule: a retired job goes quiet and folds away, a live one keeps firing.
        val archived = { c: ChatSession -> SessionAdapter.isArchived(c, nowMs, false) }
        val tabbed = if (inThread) emptyList() else when (sessionFilter) {
            1 -> all.filter { it.unread > 0 }
            3 -> all.filter(archived)
            // Schedules and alerts (everything the system sends rather than a chat you started) live under
            // their own tab; Recent hides the quiet ones (unread or needs-you still surface there as normal).
            // Search (below) still scans everything on the Recent tab.
            2 -> all.filter { isSystemChat(it.id) && !archived(it) }
            else -> if (sessionQuery.isBlank()) all.filter { (!isSystemChat(it.id) || it.unread > 0 || it.waitingOnYou(nowMs)) && !archived(it) } else all
        }
        if (!inThread) {
            refreshScheduleFacts()
            sessionAdapter.archivedView = sessionFilter == 3
            sessionAdapter.archivedCount = when {
                sessionQuery.isNotBlank() -> 0
                sessionFilter == 0 -> all.count { !isSystemChat(it.id) && archived(it) }
                sessionFilter == 2 -> all.count { isSystemChat(it.id) && archived(it) }
                else -> 0
            }
        }
        val filtered = if (inThread || sessionQuery.isBlank()) tabbed
            else tabbed.filter {
                it.title.contains(sessionQuery, true) ||
                // Capped tail scan: full-history scan per keystroke stalled
                // the UI on 3000-message threads. Titles match uncapped.
                it.messages.takeLast(200).any { m -> m.text.contains(sessionQuery, true) }
            }
        // Merge server sessions without touching local state: sessions the
        // app already owns (its local remoteIds) already show as local rows.
        // The rest are not-yet-opened importable rows (All tab only, newest
        // first). The open thread is excluded explicitly too: its session
        // must never resurface as an import row beside itself.
        val bound = try { chatStore.remoteIds() } catch (_: Exception) { emptySet() }
        val buried = try { chatStore.tombstones() } catch (_: Exception) { emptySet() }
        val openId = activeSessionId
        val openRemote = openId?.let { oid ->
            try { chatStore.get(oid)?.remoteId.takeUnless { it.isNullOrBlank() } } catch (_: Exception) { null }
        }
        val shownImports = if (inThread || sessionFilter != 0) emptyList() else serverImports.filter { imp ->
            imp.id !in bound && imp.id !in buried && imp.id != openRemote &&
                (sessionQuery.isBlank() || imp.title.contains(sessionQuery, true))
        }.sortedByDescending { it.updatedAt }
        // "replying…" follows confirmed in-flight turns only (never
        // placeholder freshness) — set before update() so the diff sees it.
        // Hidden list: record the set, skip the diff (see above).
        sessionAdapter.liveChats = TurnActivity.replyingChats(pendingTurns)
        sessionAdapter.refreshDeleting(deletingIds.toSet())
        // Date sections and the brief card only on the plain newest-first list (no search, no tab filter).
        sessionAdapter.sections = sessionSort == "newest" && sessionQuery.isBlank() && sessionFilter == 0
        if (!inThread) sessionAdapter.update(SessionAdapter.rankSearch(filtered, sessionQuery, sessionSort), shownImports)
        val showHero = !inThread && all.isEmpty() && shownImports.isEmpty()
        if (::chatPane.isInitialized) {
            chatPane.setPadding(chatPane.paddingLeft, dp(if (showHero) 8 else 16),
                chatPane.paddingRight, dp(if (showHero) 4 else 8))
        }
        if (!inThread) schedulePrebuild()
        sessionPull.visibility = if (inThread || showHero) View.GONE else View.VISIBLE
        searchToggle.visibility = if (inThread) View.GONE else View.VISIBLE
        // A live query always keeps its field on screen (the filter is visibly applied, never hidden).
        if (!inThread && sessionQuery.isNotBlank()) searchOpen = true
        sessionSearch.visibility = if (!inThread && searchOpen) View.VISIBLE else View.GONE
        if (::searchToggle.isInitialized && !inThread) searchIcon.setImageResource(if (searchOpen) R.drawable.ic_close else R.drawable.ic_search)
        if (::sessControls.isInitialized) {
            sessControls.visibility = if (inThread || showHero) View.GONE else View.VISIBLE
            syncTabStyles()
            // Single scope icon: the selected backend only. Name rides in the
            // frame's description (all versions) and tooltip (26+).
            if (::iconBackendScope.isInitialized) {
                val b = prefs.defaultBackend()
                val label = backendDisplayName(b)
                iconBackendScope.setImageResource(agentIcon(b))
                findViewById<View>(R.id.backendScopeBtn).apply {
                    contentDescription = "backend scope: $label, tap to change"
                    if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = label
                }
            }
            sortBtn.contentDescription = "sort chats: ${sortLabel()}, tap to change"
        }
        chatHero.visibility = if (showHero) View.VISIBLE else View.GONE
        heroModelBtn.visibility = if (showHero) View.VISIBLE else View.GONE
        if (!inThread) composerRow.visibility = if (showHero) View.VISIBLE else View.GONE
        val showNoMatch = !inThread && !showHero && filtered.isEmpty() && shownImports.isEmpty()
        chatEmpty.visibility = if (showNoMatch) View.VISIBLE else View.GONE
        if (showNoMatch) {
            chatEmpty.text = when {
                sessionQuery.isNotBlank() -> "No matches for \"$sessionQuery\""
                sessionFilter == 1 -> "You're all caught up"
                sessionFilter == 2 -> "No schedules yet"
                else -> "No ${backendDisplayName(prefs.defaultBackend())} chats yet"
            }
        }
        renderBell(all)
    }

    /** Ping on settle, never for the thread being watched. Logs the decision
     *  at debug so a stray ping says which guard failed. */
    private fun maybeNotifyTurn(id: String, text: String, wasBg: Boolean) {
        val watching = activeSessionId == id
        android.util.Log.d("terminus", "notifyTurn chat=$id watching=$watching wasBg=$wasBg")
        if (wasBg || !watching) ChatNotify.notifyTurn(this, text, id, try { chatStore.get(id)?.title ?: "" } catch (_: Exception) { "" })
    }

    /** Confirm-free, both-sides, id-bound delete: the captured id (never
     *  "current session") drives the server call + local wipe. Local state
     *  clears immediately; the server call runs detached. */
    private fun deleteSessionNow(s: ChatSession) {
        deleteSessionById(s.id, s.remoteId)
    }

    /** Confirm-free, both-sides, id-bound delete: the captured id (never
     *  "current session") drives the server call + local wipe. The row stays
     *  visible with a Deleting state until the server confirms: success
     *  removes it, failure restores it with an error toast (retry by
     *  deleting again). Never silently gone. */
    private fun deleteSessionById(id: String, remoteId: String) {
        if (remoteId.isBlank()) {
            // Local-only chat: nothing server side to confirm.
            chatStore.delete(id)
            if (activeSessionId == id) closeThread() else renderSessions()
            return
        }
        deletingIds.add(id)
        try { sessionAdapter.refreshDeleting(deletingIds.toSet()) } catch (_: Exception) {}
        chatStore.buryRemote(remoteId)
        val backend = try { chatStore.get(id)?.backend ?: "opencode" } catch (_: Exception) { "opencode" }
        val sid = remoteId
        // The catalog says whether the server can delete for this backend (Claude and Agy
        // cannot: their transcripts are the CLI's own files). Then the chat is removed here and the
        // remote id stays buried so the import list does not bring it back. Unknown = ask the server.
        val serverCanDelete = backendCatalog?.backends?.firstOrNull { it.id == backend }?.capabilities?.deleteSession ?: true
        Thread {
            var ok = false
            try {
                if (serverCanDelete) OpencodeApi.deleteSession(prefs.serverUrl(), prefs.token(), sid, backend, id)
                ok = true
            } catch (_: Exception) {}
            runOnUiThread {
                deletingIds.remove(id)
                try { sessionAdapter.refreshDeleting(deletingIds.toSet()) } catch (_: Exception) {}
                if (ok) {
                    chatStore.delete(id)
                    if (activeSessionId == id) closeThread() else renderSessions()
                } else {
                    try { chatStore.unburyRemote(sid) } catch (_: Exception) {}
                    renderSessions()
                    android.widget.Toast.makeText(this, "Server kept the chat", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Bulk delete from the selection bar: confirm-free like single deletes
     *  (product rule), one toast with the count. Selection clears after. */
    private fun deleteSelected() {
        if (!::sessionAdapter.isInitialized) return
        val ids = sessionAdapter.selectedIds.toList()
        val remoteIds = sessionAdapter.selectedImports.toList()
        if (ids.isEmpty() && remoteIds.isEmpty()) return
        val byId = try { chatStore.list().associateBy { it.id } } catch (_: Exception) { emptyMap() }
        sessionAdapter.clearSelection()
        var n = 0
        for (id in ids) {
            val s = byId[id] ?: continue
            deleteSessionById(s.id, s.remoteId)
            n++
        }
        for (sid in remoteIds) {
            val backend = serverImports.firstOrNull { it.id == sid }?.backend ?: "opencode"
            try { chatStore.buryRemote(sid) } catch (_: Exception) {}
            serverImports = serverImports.filter { it.id != sid }
            n++
            Thread {
                try { OpencodeApi.deleteRemote(prefs.serverUrl(), prefs.token(), sid, backend) }
                catch (_: Exception) { try { chatStore.unburyRemote(sid) } catch (_: Exception) {} }
            }.start()
        }
        renderSessions()
        android.widget.Toast.makeText(this, if (n == 1) "deleted 1 chat" else "deleted $n chats",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Selection action bar under the session controls. */
    private fun updateSelBar(n: Int) {
        val bar = try { findViewById<View>(R.id.selBar) } catch (_: Exception) { null } ?: return
        bar.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            try {
                findViewById<android.widget.TextView>(R.id.selCount).text =
                    if (n == 1) "1 selected" else "$n selected"
            } catch (_: Exception) {}
        }
    }

    private fun setSessionFilter(f: Int) {
        sessionFilter = f.coerceIn(0, 3)
        // The archive view is a detour from Recent, never where the app reopens.
        if (sessionFilter != 3) prefs.setSessionFilter(sessionFilter)
        syncTabStyles()
        renderSessions()
    }

    private fun syncTabStyles() {
        if (!::tabAll.isInitialized) return
        val tabs = listOf(tabAll, tabUnread, tabSchedules)
        tabs.forEachIndexed { i, t ->
            val on = i == sessionFilter
            // Underline-tab style (mock): no pills. Active = dark + bold +
            // underline, inactive = muted with touch ripple preserved.
            if (on) {
                t.setBackgroundResource(R.drawable.tab_bg_active)
            } else {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true)
                t.setBackgroundResource(tv.resourceId)
            }
            t.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this, if (on) R.color.t_ink else R.color.t_muted))
            t.setTypeface(t.typeface, if (on) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL)
        }
    }

    private fun sortLabel(): String = when (sessionSort) {
        "activity" -> "⚡ activity"
        "name" -> "A–Z name"
        else -> "↓ newest"
    }

    /** Sort control: newest (recency) / activity (unread first) / name (A–Z). */
    private fun showSortMenu() {
        val opts = listOf(
            Triple("↓ newest", "newest", ""),
            Triple("⚡ activity", "activity", "unread first"),
            Triple("A–Z name", "name", ""),
        )
        showSelectSheet("Sort chats", opts.map { (label, key, sub) ->
            SelectRow(label, sub, checked = key == sessionSort, action = {
                sessionSort = key
                prefs.setSessionSort(sessionSort)
                renderSessions()
            })
        })
    }

    /** Bell dot: chats with genuinely delivered results the Activity sheet
     *  can open (settled background reply or inbox delivery, exactly once)
     *  — never a live placeholder or tool/status row. The unit is the
     *  reviewable chat, matching the sheet's one-row-per-chat section, so
     *  the count and the sheet can never disagree. Opening the chat clears
     *  it durably. */
    private fun renderBell(scoped: List<ChatSession>? = null) {
        if (!::bellBtn.isInitialized || !::chatStore.isInitialized) return
        // renderSessions passes its already-scoped list: one sort per render,
        // not two. Standalone callers (openThread, notificatons) scan alone.
        val all = scoped ?: try { chatStore.list() } catch (_: Exception) { emptyList() }
        val chats = all.count { it.unread > 0 }
        val total = all.sumOf { it.unread }
        findViewById<View>(R.id.bellDot)?.visibility = if (total > 0) View.VISIBLE else View.GONE
        bellBtn.contentDescription = if (chats > 0) "activity, $chats unread chats" else "activity"
        // Per-tab bells share the same unread state.
        for (dotId in listOf(R.id.bellDotEcho, R.id.bellDotNotes, R.id.bellDotGallery, R.id.bellDotOps)) {
            try {
                findViewById<View>(dotId)?.visibility = if (total > 0) View.VISIBLE else View.GONE
            } catch (_: Exception) {}
        }
    }

    /** Thread UI is open for a persisted session OR an unpersisted draft. */
    private fun isThreadOpen(): Boolean = activeSessionId != null || draftOpen

    /** Expand the title rightward into the model/context/attachment space. */
    private fun setThreadTitleExpanded(expanded: Boolean) {
        if (!::threadTitle.isInitialized || threadTitleExpanded == expanded) return
        threadTitleExpanded = expanded
        updateThreadTitleDescription()
        val params = threadTitle.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if (!expanded) {
            expandedHeaderControlVisibility?.forEach { (view, visibility) -> view.visibility = visibility }
            expandedHeaderControlVisibility = null
            params.width = 0
            params.weight = 1f
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            threadTitle.layoutParams = params
            threadTitle.background = null
            threadTitle.elevation = 0f
            threadTitle.translationZ = 0f
            threadTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            threadTitle.setPadding(0, 0, 0, 0)
            return
        }
        val covered = listOf(
            R.id.modelChip,
            R.id.contextRing,
            R.id.attachListBtn,
        )
            .mapNotNull { findViewById<View>(it) }
        expandedHeaderControlVisibility = covered.map { it to it.visibility }
        covered.forEach { it.visibility = View.GONE }
        params.width = 0
        params.weight = 1f
        params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        threadTitle.layoutParams = params
        threadTitle.background = null
        threadTitle.elevation = 0f
        threadTitle.translationZ = 0f
        threadTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        threadTitle.setPadding(0, 0, 0, 0)
    }

    private fun updateThreadTitleDescription() {
        if (!::threadTitle.isInitialized) return
        val action = if (threadTitleExpanded) "tap to collapse name" else "tap to expand name"
        threadTitle.contentDescription = "${threadTitle.text}, $action"
    }

    /** Draft thread: full thread UI, NO stored session. Persist happens only
     *  on first send or first model pick; < backs out with zero trace. */
    private fun openDraft() {
        setThreadTitleExpanded(false)
        activeSessionId = null
        draftOpen = true
        pingServer()
        syncNavForThread()
        draftText = ""
        sessionPull.visibility = View.GONE
        if (::sessControls.isInitialized) sessControls.visibility = View.GONE
        sessionSearch.visibility = View.GONE
        chatEmpty.visibility = View.GONE
        chatHero.visibility = View.GONE
        heroModelBtn.visibility = View.GONE
        threadHeader.visibility = View.VISIBLE
        modelChip.visibility = View.VISIBLE
        contextChat = null
        contextInfo = null
        paintContextRing()
        threadDivider.visibility = View.VISIBLE
        threadWeb.visibility = View.VISIBLE
        threadFrame.visibility = View.VISIBLE
        setThreadLoading(false)
        composerRow.visibility = View.VISIBLE
        composerInput.setText("")
        composerInput.hint = "Message (/ for commands)"
        pendingAttaches.clear()
        hideAttachStrip()
        pendingVoice = null
        clearQuote()
        if (::voicePreview.isInitialized) voicePreview.visibility = View.GONE
        lastThreadHtml = ""
        threadPageLoaded = false
        lastFullMsgSig = ""
        pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
        pendingStepPatch = null
        renderDraft()
        updateChatRecUI()
    }

    /** Empty-thread render for drafts (no session row exists yet). */
    private fun renderDraft() {
        val draftBackend = prefs.defaultBackend()
        val model = prefs.defaultModel(draftBackend).ifBlank { ChatStore.defaultModel() }
        threadTitle.text = "new chat"
        updateThreadTitleDescription()
        paintModelChip(draftBackend, model)
        val html = ChatMarkdown.threadHtml("new chat", model, emptyList(), LinkToken.forUrl(this@MainActivity, prefs), dark = isDark())
        if (html == lastThreadHtml) return
        lastThreadHtml = html
        threadPageLoaded = false
        lastFullMsgSig = "draft"
        threadWeb.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }

    /** Persist the draft on demand (first send / first model pick),
     *  carrying composer text + quote + staged attachment into the session. */
    private fun ensureDraftSession(model: String? = null): String {
        val existing = activeSessionId
        if (existing != null) return existing
        val typed = composerInput.text.toString()
        val hint = composerInput.hint.toString()
        val staged = pendingAttaches.toList()
        val stagedQuotes = quotes.map { it.copy() }
        val cfg = newChatConfig()
        val s = chatStore.create(cfg.backend, model ?: cfg.model)
        draftOpen = false
        openThread(s.id)
        if (typed.isNotEmpty()) {
            composerInput.setText(typed)
            composerInput.setSelection(typed.length)
        }
        pendingAttaches.addAll(staged)
        if (stagedQuotes.isNotEmpty()) { quotes.clear(); quotes.addAll(stagedQuotes); renderQuotes() }
        if (hint.isNotBlank()) composerInput.hint = hint
        return s.id
    }

    private fun openThread(id: String) {
        // A chat that no longer exists (deleted, or a stale approval/inbox target) must not half-open: the state
        // below (active id, saved open chat, thread nav) is set before the chat is read, and bailing after it
        // left the app thinking a thread was open with nothing on screen.
        if (chatStore.get(id) == null) return
        catchUpBackground(id)
        pullReset()
        stopDictation()
        if (activeSessionId != id) {
            setThreadTitleExpanded(false)
            replyVoice.stop()
        }
        draftOpen = false
        draftText = ""
        // A thread switch abandons edit mode (its draft belongs to the old box).
        editingTurn = null
        editingStashByTurn.clear()
        activeSessionId = id
        pingServer()
        // One session = one chat: opening any other chat strips a stray live
        // placeholder left from the buggy era (frozen records are kept); the
        // owner chat keeps its live card.
        try {
            if (!browserCards.owns(id)) chatStore.removeLiveBrowserCard(id)
        } catch (_: Exception) {}
        syncNavForThread()
        prefs.setOpenChatId(id)
        val s = chatStore.get(id) ?: return
        // Capture where the unread run starts BEFORE markRead clears the counter: the thread opens there,
        // not at the bottom. A needs-you flag has no row of its own, so it keeps the bottom.
        val firstUnread = if (s.needsYou.isBlank()) firstUnreadIndex(s.messages, s.unread) else -1
        openAnchor = if (firstUnread >= 0) Triple(id, firstUnread, s.messages.size) else null
        openRestore = if (firstUnread < 0) (leftScrollY[id]?.takeIf { it >= 0 } ?: if (leftScrollY.containsKey(id)) null else ThreadScrollStore.get(id, s.messages.size))?.let { id to it } else null
        lastRenderScroll = true
        lastRenderKeepCssY = 0
        chatStore.markRead(id)
        try { ChatNotify.clearChat(this, id) } catch (_: Exception) {}
        renderBell()
        // The list is hidden while the thread is open, but refresh it
        // anyway: the stored unread is already zero, and the adapter's
        // snapshot must advance now or the badge survives off stale rows.
        renderSessions()
        sessionPull.visibility = View.GONE
        if (::sessControls.isInitialized) sessControls.visibility = View.GONE
        sessionSearch.visibility = View.GONE
        chatEmpty.visibility = View.GONE
        chatHero.visibility = View.GONE
        heroModelBtn.visibility = View.GONE
        threadHeader.visibility = View.VISIBLE
        modelChip.visibility = View.VISIBLE
        threadDivider.visibility = View.VISIBLE
        // Hidden until the new page commits (revealed in onPageFinished):
        // the old page paints until the new loadData lands, so a visible
        // view flashes the previous chat. about:blank can't win that race —
        // the blank load never paints before loadData cancels it. Blank
        // flash beats stale content.
        threadWeb.visibility = View.INVISIBLE
        setThreadLoading(true)
        if (::threadFrame.isInitialized) threadFrame.visibility = View.VISIBLE
        composerRow.visibility = View.VISIBLE
        if (::jumpLatest.isInitialized) jumpLatest.visibility = View.GONE
        composerInput.setText(s.draft)
        composerInput.hint = "Message (/ for commands)"
        pendingAttaches.clear()
        hideAttachStrip()
        pendingVoice = null
        clearQuote()
        if (::voicePreview.isInitialized) voicePreview.visibility = View.GONE
        // Invalidate the render cache and load the new document directly.
        // An about:blank navigation here races with loadData and can leave the
        // WebView empty if its late completion wins. The view stays invisible
        // until the new page commits (see onPageFinished), so no stale
        // pixels survive the switch.
        lastThreadHtml = ""
        threadVisible.remove(id)
        historyGrowing = false
        threadTouchScroll = false
        threadPageLoaded = false
        lastFullMsgSig = ""
        pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
        pendingStepPatch = null
        updateStopBtn()
        renderThread(id)
        // Outstanding turn (e.g. after process death): resume live steps.
        val tail = s.messages.lastOrNull()
        if (tail != null && tail.role == "assistant" && tail.turnId.isNotBlank() && !tail.settled) {
            ensureStream(id)
        }
        // Have the server close out anything finished or dead behind this chat, including a finished
        // opencode reply the app never received after the turn looked settled (no gesture, no layout
        // change; a no-op when there is nothing to settle).
        syncThread(id)
        // Providers can assign names after the first turn; adopt one when
        // available, while keeping the local first-message title as fallback.
        syncServerTitle(id)
        updateChatRecUI()
        syncInbox(minGapMs = 10_000)
        goalCache = null
        paintGoalStrip()
        refreshGoalStrip(id)
    }

    /** Remember where this chat is scrolled to, in memory and on disk (see ThreadScrollStore). */
    private fun rememberThreadScroll(id: String) {
        if (!threadPageLoaded) return
        val d = resources.displayMetrics.density.coerceAtLeast(1f)
        val y = threadWeb.scrollY
        val atBottom = y + threadWeb.height >= (threadWeb.contentHeight * d).toInt() - 48
        val css = if (atBottom) -1 else (y / d).toInt()
        leftScrollY[id] = css
        try { ThreadScrollStore.put(id, css, chatStore.get(id)?.messages?.size ?: -1) } catch (_: Exception) {}
    }

    private fun closeThread() {
        pullReset()
        val id = activeSessionId
        setThreadTitleExpanded(false)
        stopDictation()
        replyVoice.stop()
        editingTurn = null
        editingStashByTurn.clear()
        // A debounced draft may hold older text: drop it, then persist the
        // composer's current text synchronously — close must never lose it.
        dropPendingDraft()
        if (id != null) chatStore.setDraft(id, composerInput.text.toString())
        // Replies that settled while this thread was open were read in place (no full render, so no markRead):
        // leaving must not hand them back as unread.
        if (id != null) try { chatStore.markRead(id) } catch (_: Exception) {}
        if (id != null) {
            try {
                rememberThreadScroll(id)
            } catch (_: Exception) {}
        }
        stopStream()
        activeSessionId = null
        pingServer()
        openAnchor = null
        openRestore = null
        syncNavForThread()
        historyGrowing = false
        threadTouchScroll = false
        goalCache = null
        try { if (::goalStripBar.isInitialized) goalStripBar.visibility = View.GONE } catch (_: Exception) {}
        // Leaving must not strand in-flight turns: if anything is still
        // outstanding anywhere, wake a stream for it (same reaper rule as
        // socket death — the reply still settles + pings from the list).
        try {
            val outstanding = chatStore.chatsWithLive().lastOrNull()
            if (outstanding != null) {
                progressHandler.postDelayed({
                    if (streamChat == null && chatStore.hasLive(outstanding)) startStream(outstanding)
                }, 1500)
            }
        } catch (_: Exception) {}
        // Draft close: nothing was ever stored — zero trace by construction.
        draftOpen = false
        syncNavForThread()
        draftText = ""
        composerInput.setText("")
        pendingAttaches.clear()
        hideAttachStrip()
        pendingVoice = null
        clearQuote()
        if (::voicePreview.isInitialized) voicePreview.visibility = View.GONE
        threadHeader.visibility = View.GONE
        modelChip.visibility = View.GONE
        if (::contextRing.isInitialized) contextRing.visibility = View.GONE
        threadDivider.visibility = View.GONE
        threadWeb.visibility = View.GONE
        if (::threadFrame.isInitialized) threadFrame.visibility = View.GONE
        composerRow.visibility = View.GONE
        if (::jumpLatest.isInitialized) jumpLatest.visibility = View.GONE
        chatRecStatus.visibility = View.GONE
        slashList.visibility = View.GONE
        heroModelBtn.visibility = View.GONE
        lastThreadHtml = ""
        threadPageLoaded = false
        lastFullMsgSig = ""
        pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
        pendingStepPatch = null
        pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
        pendingThreadScroll = null
        renderSessions()
    }

    /** tel: opens the dialer with the number filled in (never places the call itself); mailto:/sms: open the
     *  matching app. */
    private fun openContactLink(u: String) {
        val action = if (u.startsWith("tel:")) android.content.Intent.ACTION_DIAL else android.content.Intent.ACTION_SENDTO
        try {
            startActivity(android.content.Intent(action, android.net.Uri.parse(u)))
        } catch (_: Exception) {
            toast("No app to open that")
        }
    }

    /** The model a turn is sent with. Blank = the backend's default, but Claude gets it spelled out: with no
     *  --model a resumed session keeps whatever model it last ran on, so "Default" after picking haiku
     *  silently stayed on haiku. The chip shows the same default (chipText). */
    private fun sendModelFor(backend: String, chatModel: String?): String? {
        val m = chatModel.orEmpty()
        if (m.isNotBlank()) return m
        if (chatModel == null || backend != Prefs.BACKEND_CLAUDE) return null
        // A provider/model id is another backend's default; Claude only takes plain aliases (sonnet, haiku, ...).
        return prefs.defaultModel(backend).ifBlank { serverDefaultModel }.takeIf { it.isNotBlank() && !it.contains('/') }
    }

    /** Compact thread chip: short model + chevron. Short = id.substringAfter("/").
     *  Blank model follows that backend's default (chat default first). Never blank.
     *  No manual truncation: the view ellipsizes end against its maxWidth. */
    private var serverDefaultModel: String = ""

    /** Compact thread chip: [backend icon] [short model]. The icon carries
     *  the backend (set at the call sites); text never repeats it. */
    private fun chipText(backend: String, model: String): String {
        if (model.isBlank()) {
            val short = shortModelLabel(prefs.defaultModel(backend).ifBlank { serverDefaultModel }).take(8)
            return if (short.isNotBlank()) "Default ($short)" else "Model"
        }
        val short = shortModelLabel(model).take(8)
        return if (short.isNotBlank()) short else "Model"
    }

    private fun fmtTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    /** The ring fills against the model's window; without one it fills against the auto-compact ceiling.
     *  No data yet reads as an empty 0% ring, never hidden mid-thread. */
    /** Backends with no usage reporting get no ring: an eternal 0% lies.
     *  Backends declare it (capabilities.usage = false): Agy has no token
     *  accounting the server can read from their CLI surface. */
    private fun usageBackend(): Boolean {
        val b = activeSessionId?.let { try { chatStore.get(it)?.backend } catch (_: Exception) { null } }
            ?: prefs.defaultBackend()
        val cb = Prefs.cannonBackend(b)
        // Server-declared (capabilities.usage); until the catalog loads, assume it reports.
        return backendCatalog?.backends?.firstOrNull { it.id == cb }?.capabilities?.usage ?: true
    }

    private fun paintContextRing() {
        if (!::contextRing.isInitialized) return
        if (!usageBackend()) { contextRing.visibility = View.GONE; return }
        val c = contextInfo
        val denom = when {
            c == null -> 0L
            c.window > 0 -> c.window
            c.compactAt > 0 -> c.compactAt.toLong()
            else -> 0L
        }
        val ceiling = when {
            c == null || c.compactAt <= 0 -> null
            c.window > 0 -> c.compactAt.toFloat() / c.window
            else -> 1f
        }
        if (c == null || c.used <= 0 || denom <= 0) {
            contextRing.set(0f, ceiling)
            contextRing.visibility = View.VISIBLE
            contextRing.contentDescription = "context empty"
            return
        }
        contextRing.set(c.used.toFloat() / denom, ceiling, c.used)
        contextRing.visibility = View.VISIBLE
        contextRing.contentDescription = "context ${ContextRingView.fmtTokens(c.used)} of ${ContextRingView.fmtTokens(denom)} used"
    }

    private var contextTrailing: Runnable? = null

    /** Usage moves after every model request (claude, codex) and every settle (all): refresh the open chat's
     *  ring now, or once the throttle window ends. A throttled call is deferred, never dropped, so the last
     *  change of a burst always lands. Safe from any thread. */
    private fun pokeContext(id: String) {
        progressHandler.post {
            if (activeSessionId != id) return@post
            contextTrailing?.let { progressHandler.removeCallbacks(it) }
            val r = Runnable {
                contextTrailing = null
                if (activeSessionId == id) chatStore.get(id)?.let { refreshContext(id, it.model) }
            }
            contextTrailing = r
            val wait = CONTEXT_THROTTLE_MS - (System.currentTimeMillis() - contextFetchAt)
            if (wait <= 0) r.run() else progressHandler.postDelayed(r, wait)
        }
    }

    private fun refreshContext(id: String, model: String, totals: Boolean = false, done: (() -> Unit)? = null) {
        if (!usageBackend()) return
        val now = System.currentTimeMillis()
        if (!totals && now - contextFetchAt < CONTEXT_THROTTLE_MS) {
            if (done == null) pokeContext(id)
            return
        }
        contextFetchAt = now
        Thread {
            val info = try { OpencodeApi.fetchContext(prefs.serverUrl(), prefs.token(), id, model, totals) } catch (_: Exception) { null }
            runOnUiThread {
                // The list's third line warns when a chat's context is nearly full: keep each chat's last fill.
                info?.let { c ->
                    val denom = if (c.window > 0) c.window else c.compactAt.toLong()
                    if (denom > 0) {
                        val pct = ((c.used * 100) / denom).toInt().coerceIn(0, 100)
                        if (sessionAdapter.contextPct[id] != pct) sessionAdapter.contextPct = sessionAdapter.contextPct + (id to pct)
                    }
                }
                if (activeSessionId == id) {
                    if (info != null) contextInfo = info
                    paintContextRing()
                    done?.invoke()
                }
            }
        }.start()
    }

    private fun showContextSheet() {
        val id = activeSessionId ?: return
        val s = chatStore.get(id) ?: return
        refreshContext(id, s.model, totals = true) {
            val c = contextInfo
            fun row(label: String, sub: String) = SelectRow(label, sub, checked = false, action = {})
            if (c == null) {
                showSelectSheet("Context", listOf(row("Context", "nothing recorded yet")))
                return@refreshContext
            }
            val rows = mutableListOf<SelectRow>()
            rows += row("Context", when {
                c.used <= 0 -> "nothing recorded yet"
                c.window > 0 -> "${fmtTokens(c.used)} of ${fmtTokens(c.window)} · ${100L * c.used / c.window}%"
                else -> "${fmtTokens(c.used)} tokens · window unknown"
            })
            if (c.used > 0) rows += row("Last request",
                "in ${fmtTokens(c.input)} · out ${fmtTokens(c.output)} · cache read ${fmtTokens(c.cacheRead)} · write ${fmtTokens(c.cacheWrite)}")
            if (c.totalTokens > 0) {
                val totalScope = when (c.backend) {
                    "codex" -> "cumulative thread total"
                    "claude" -> "tracked during this server run"
                    "opencode" -> "from up to 300 history messages"
                    else -> "recorded total"
                }
                rows += row("Chat total", "${fmtTokens(c.totalTokens)} tokens · $totalScope")
            }
            rows += row("Cost", when {
                c.costUsd == null -> "not reported"
                c.backend == "claude" -> String.format(java.util.Locale.US, "$%.2f (API-price estimate)", c.costUsd)
                else -> String.format(java.util.Locale.US, "$%.2f", c.costUsd)
            })
            rows += row("Model", listOf(c.model, c.backend).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "unknown" })
            rows += row("Auto-compact", if (c.compactAt > 0) {
                val limit = c.compactAt.toLong()
                val status = when {
                    c.used <= 0 -> "current usage unknown"
                    c.used >= limit -> "threshold reached"
                    else -> "${fmtTokens(limit - c.used)} remaining"
                }
                "at ${fmtTokens(limit)} tokens · $status"
            } else "Default · threshold not reported")
            showSelectSheet("Context", rows)
        }
    }

    private fun paintModelChip(backend: String, model: String) {
        // Badge back on the chip: 12dp so the mark reads instead of blobbing.
        try { modelChip.setIconResource(agentIcon(backend)) } catch (_: Exception) {}
        modelChip.text = chipText(backend, model)
    }


    /** Schedule threads (`schedule:` / `job:`) have no backend of their own: they follow the
     *  global default, so the picker and the next turn both use the backend the user switched to.
     *  A switch parks the old backend's session id and resumes the new backend's own (ChatStore
     *  keeps one per backend). A stored id in the wrong shape for the current backend (a ses_ id
     *  left on claude) is dropped.
     *  Returns true when the thread changed (model reset to that backend's default). */
    private fun alignScheduleChatBackend(id: String): Boolean {
        if (!isScheduleChat(id)) return false
        val s = chatStore.get(id) ?: return false
        val def = prefs.defaultBackend()
        val switched = Prefs.cannonBackend(s.backend) != def
        if (switched) chatStore.setBackendAndModel(id, def, prefs.defaultModel(def))
        // Re-read: a switch just swapped in the new backend's id.
        val active = chatStore.get(id)?.remoteId.orEmpty()
        val foreign = active.isNotBlank() && !remoteIdFits(def, active)
        if (foreign) chatStore.setRemoteId(id, "")
        return switched || foreign
    }

    private val UUID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Remote id shape per backend: opencode ids are ses_-prefixed, claude ids are UUIDs,
     *  codex ids are opaque. Pure. */
    private fun remoteIdFits(backend: String, remote: String): Boolean = when (Prefs.cannonBackend(backend)) {
        Prefs.BACKEND_OPENCODE -> remote.startsWith("ses_")
        Prefs.BACKEND_CLAUDE -> UUID_SHAPE.matches(remote)
        else -> true
    }

    // ---- Built-page cache -------------------------------------------------------------------------------------
    // Opening a chat used to rebuild its whole HTML (markdown for ~60 rows) on the UI thread every time. A page
    // is now kept per chat, keyed by everything that goes into it, and the most recent chats are built in the
    // background while the list is on screen. A hit skips the build; the WebView still parses and lays out.
    private class PageBuild(val html: String, val at: Long)
    private val pageCache = object : LinkedHashMap<String, PageBuild>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageBuild>?) = size > 6
    }
    private val pageExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "page-prebuild").apply { isDaemon = true } }
    /** Chips and alarm cards read time-dependent state (alarm armed/passed, link titles landing): a built page
     *  is only trusted for a short while. A changed chat changes the key and never reuses an old page. */
    private val PAGE_TTL_MS = 45_000L
    private val prebuildRun = Runnable { prebuildThreads() }

    private fun pageKey(
        id: String, s: ChatSession, shown: List<ChatMessage>, vis: Int, effScroll: Boolean, keepCssY: Int,
        anchorIdx: Int, dark: Boolean, token: String, doneSteps: Int,
    ): String =
        // A bottom-pinned page ignores keepCssY and an anchor only matters when pinning: normalise so the
        // background build and the real open agree on one key.
        "$id|${s.title.hashCode()}|${s.model}|${s.messages.size}|${shown.hashCode()}|$vis|$effScroll|" +
            "${if (effScroll) -1 else keepCssY}|${if (effScroll) anchorIdx else -1}|$dark|${token.hashCode()}|$doneSteps|" +
            // Alarm cards print armed/cancelled from prefs, and the browser-card assets arrive after startup: a
            // tap on a card or a late asset must not be answered with a page built before it.
            "${WakeAlarm.signature(this)}|${ChatMarkdown.pbScript.hashCode()}|${ChatMarkdown.pbCss.hashCode()}|" +
            "${ChatMarkdown.threadCardsCss.hashCode()}|${ChatMarkdown.threadUiJs.hashCode()}"

    private fun cachedPage(key: String): String? {
        val hit = pageCache[key] ?: return null
        if (System.currentTimeMillis() - hit.at > PAGE_TTL_MS) { pageCache.remove(key); return null }
        return hit.html
    }

    private fun schedulePrebuild() {
        threadScrollHandler.removeCallbacks(prebuildRun)
        threadScrollHandler.postDelayed(prebuildRun, 1500)
    }

    /** List on screen: build the pages the next taps are most likely to open (three most recently active chats),
     *  with the same plan openThread/renderThread will use, so the real open finds them ready. */
    private fun prebuildThreads() {
        if (!::chatStore.isInitialized || isThreadOpen() || draftOpen) return
        val dark = isDark()
        val token = LinkToken.forUrl(this@MainActivity, prefs)
        val recent = try {
            chatStore.list().filter { it.messages.isNotEmpty() && !isScheduleChat(it.id) }
                .sortedByDescending { it.updatedAt }.take(3)
        } catch (_: Exception) { return }
        for (c in recent) {
            val id = c.id
            if (pendingTurns[id]?.isNotEmpty() == true || checking.contains(id) || !progressLive[id].isNullOrEmpty()) continue
            val msgs = c.messages
            val total = msgs.size
            // Mirror of openThread's open plan (unread anchor, or the saved place).
            val firstUnread = if (c.needsYou.isBlank()) firstUnreadIndex(msgs, c.unread) else -1
            val restoreY = if (firstUnread < 0)
                (leftScrollY[id]?.takeIf { it >= 0 } ?: if (leftScrollY.containsKey(id)) null else ThreadScrollStore.get(id, total))
            else null
            val effScroll = restoreY == null
            val keepCssY = restoreY ?: 0
            val anchorIdx = if (effScroll && firstUnread >= 0) firstUnread else -1
            var vis = threadVisible[id] ?: threadPageSize
            if (anchorIdx >= 0 && total - anchorIdx > vis) vis = total - anchorIdx + 4
            val shown = if (total > vis) msgs.takeLast(vis) else msgs.toList()
            val done = progressDone[id] ?: emptyList()
            val key = pageKey(id, c, shown, vis, effScroll, keepCssY, anchorIdx, dark, token, done.size)
            if (cachedPage(key) != null) continue
            val title = c.title
            val model = c.model
            val doneCopy = done.toList()
            val rows = shown.toList()
            pageExec.execute {
                val html = try {
                    ChatMarkdown.threadHtml(
                        title, model, rows, token, null, doneCopy,
                        autoScroll = effScroll,
                        keepScrollY = if (effScroll) -1 else keepCssY,
                        dark = dark,
                        hiddenCount = total - rows.size,
                        liveBox = false,
                        chatId = id,
                        baseIndex = total - rows.size,
                        anchorIdx = anchorIdx,
                    )
                } catch (_: Throwable) { return@execute }
                runOnUiThread { pageCache[key] = PageBuild(html, System.currentTimeMillis()) }
            }
        }
    }

    /** Placeholder bars behind the hidden thread page while it builds (see threadSkeleton in the layout). */
    private fun setThreadLoading(on: Boolean) {
        val sk = findViewById<View?>(R.id.threadSkeleton) ?: return
        sk.animate().cancel()
        if (on) {
            sk.alpha = 0.7f
            sk.visibility = View.VISIBLE
        } else if (sk.visibility == View.VISIBLE) {
            // Short fade so the real thread replaces the placeholder without a pop.
            sk.animate().alpha(0f).setDuration(140).withEndAction { sk.visibility = View.GONE }.start()
        }
    }

    private fun renderThread(id: String, scroll: Boolean = true) {
        // Async history/turn callbacks can arrive after the user switches
        // chats. Never let an old chat overwrite the currently open WebView.
        if (activeSessionId != id) return
        alignScheduleChatBackend(id)
        val s = chatStore.get(id) ?: return
        // Fresh open backfills from the backend's own history (opencode
        // message rows), so existing chats paint real usage without
        // needing a new turn first. totals bypasses the 3s throttle.
        val freshContext = contextChat != id
        if (freshContext) { contextChat = id; contextInfo = null; paintContextRing() }
        refreshContext(id, s.model, totals = freshContext)
        chatStore.markRead(id)
        renderBell()
        val liveNow = try { chatStore.liveTurns(id).map { it.first }.toSet() } catch (_: Exception) { emptySet() }
        unknownSurfaced[id]?.retainAll(liveNow)
        val staleTurns = chatStore.sweepStalePlaceholders(id)
            .filter { !(unknownSurfaced[id]?.contains(it) ?: false) }
        if (staleTurns.isNotEmpty()) {
            // Timeout is not proof of failure: reconcile against the
            // server's exact-turnId registry FIRST. The unknown row only
            // appears when the turn is genuinely still unsettled — the old
            // order added the row unconditionally, leaving permanent noise
            // behind replies that landed a second later. While the check
            // runs, the checking indicator shows (same "…" shimmer as a
            // tap-check) so a quiet-but-alive turn visibly still works.
            for (turn in staleTurns) {
                if (unknownSurfaced[id]?.contains(turn) == true) continue
                val checkKey = "$id:$turn"
                // Auto-reconcile cooldown: one check per turn per minute.
                // Without this every render re-fired network + showChecking
                // + callback renders while a turn stayed stale — full HTML
                // rebuilds in a loop, the v143+ jank during long turns.
                // Tap-checks call reconcileTurn directly, never cooled.
                val now = System.currentTimeMillis()
                val last = lastAutoReconcileAt[checkKey] ?: 0L
                if (now - last < 60_000) continue
                if (!checkingTurns.add(checkKey)) continue
                lastAutoReconcileAt[checkKey] = now
                if (lastAutoReconcileAt.size > 100) {
                    lastAutoReconcileAt.entries.sortedBy { it.value }.take(50).forEach { lastAutoReconcileAt.remove(it.key) }
                }
                showChecking(id)
                reconcileTurn(id, turn) { settled ->
                    checkingTurns.remove(checkKey)
                    if (settled) return@reconcileTurn
                    if (unknownSurfaced[id]?.contains(turn) == true) return@reconcileTurn
                    try {
                        val stillLive = try {
                            chatStore.liveTurns(id).map { it.first }.toSet()
                        } catch (_: Exception) { emptySet() }
                        if (turn !in stillLive) return@reconcileTurn
                    } catch (_: Exception) { return@reconcileTurn }
                    unknownSurfaced.getOrPut(id) { mutableSetOf() }.add(turn)
                    // Terminal, not "still trying": the turn is dead, so say
                    // so on its own bubble when there is one; otherwise a
                    // single standalone note (replaced, never stacked).
                    try { chatStore.dropAllUnknownRows(id) } catch (_: Exception) {}
                    chatStore.dropLastFailureNote(id)
                    try { chatStore.dropFailureNoteForTurn(id, turn) } catch (_: Exception) {}
                    if (!chatStore.setUserSendFailed(id, turn, true)) {
                        chatStore.addMessage(id, ChatMessage("system",
                            TurnActivity.formatErrorNote("", "", id, turn)))
                    }
                    if (activeSessionId == id) renderThread(id)
                    renderSessions()
                }
            }
            updateStopBtn()
        }
        threadTitle.text = s.title
        updateThreadTitleDescription()
        paintModelChip(s.backend, s.model)
        if (::attachListBtn.isInitialized) {
            attachListBtn.visibility =
                if (s.messages.any { !it.attachment.isNullOrBlank() ||
                        (it.role == "assistant" && extractMentionedFiles(it.text).isNotEmpty()) })
                    View.VISIBLE else View.GONE
        }
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        // A render that arrives while the previous reload is still loading must not re-read the live
        // scroll (it reads 0 then): repeat what that render asked for. See scrollPlan.
        val reloading = !threadPageLoaded && lastThreadHtml.isNotEmpty()
        val restore = openRestore?.takeIf { it.first == id }
        openRestore = null
        val (effScroll, keepCssY) =
            if (scroll && restore != null) false to restore.second
            else scrollPlan(scroll, reloading, lastRenderScroll, lastRenderKeepCssY, (threadWeb.scrollY / density).toInt())
        lastRenderScroll = effScroll
        lastRenderKeepCssY = keepCssY
        if (openAnchor?.let { it.first == id && it.third != s.messages.size } == true) openAnchor = null
        val anchorIdx = if (effScroll) openAnchor?.takeIf { it.first == id }?.second ?: -1 else -1
        var vis = threadVisible[id] ?: threadPageSize
        // The first unread row must be on the page: widen the window to include it.
        if (anchorIdx >= 0 && s.messages.size - anchorIdx > vis) {
            vis = s.messages.size - anchorIdx + 4
            threadVisible[id] = vis
        }
        val shown = if (s.messages.size > vis) s.messages.takeLast(vis) else s.messages
        val liveBoxNow = pendingTurns[id]?.isNotEmpty() == true || checking.contains(id)
        val tokenNow = LinkToken.forUrl(this@MainActivity, prefs)
        val darkNow = isDark()
        val doneNow = progressDone[id] ?: emptyList()
        // Live progress changes by the second: only a quiet chat is served from (or stored in) the page cache.
        val cacheable = !liveBoxNow && progressLive[id].isNullOrEmpty()
        val pageK = if (cacheable) pageKey(id, s, shown, vis, effScroll, keepCssY, anchorIdx, darkNow, tokenNow, doneNow.size) else ""
        val buildT0 = android.os.SystemClock.uptimeMillis()
        val cachedHtml = if (cacheable) cachedPage(pageK) else null
        val html = cachedHtml ?: ChatMarkdown.threadHtml(
            s.title, s.model, shown, tokenNow,
            progressLive[id], doneNow,
            autoScroll = effScroll,
            keepScrollY = if (effScroll) -1 else keepCssY,
            dark = darkNow,
            hiddenCount = s.messages.size - shown.size,
            liveBox = liveBoxNow,
            chatId = id,
            baseIndex = s.messages.size - shown.size,
            anchorIdx = anchorIdx,
        ).also { if (cacheable) pageCache[pageK] = PageBuild(it, System.currentTimeMillis()) }
        android.util.Log.d("ThreadOpen", "page ${if (cachedHtml != null) "cache-hit" else "built"} ${android.os.SystemClock.uptimeMillis() - buildT0}ms rows=${shown.size} bytes=${html.length}")
        // Never reload identical HTML — skip loadData when the hash matches,
        // but still refresh the stop button (turn state isn't in the hash).
        // A requested bottom scroll (open-from-notification, fresh send)
        // still fires: identical content doesn't mean we're at the bottom.
        updateStopBtn()
        if (html == lastThreadHtml) {
            if (effScroll) { if (anchorIdx >= 0) anchorThreadScroll(anchorIdx) else scrollThreadToBottom() }
            // The open-thread hide-until-commit must never strand the view:
            // a skipped reload fires no onPageFinished, so reveal here.
            if (threadWeb.visibility != View.VISIBLE && (activeSessionId != null || draftOpen)) {
                threadWeb.visibility = View.VISIBLE
            }
            setThreadLoading(false)
            return
        }
        lastThreadHtml = html
        // Structural render (new messages / settle / expander): the page
        // reloads, so step-patch state resets until onPageFinished fires.
        threadPageLoaded = false
        lastFullMsgSig = threadMsgSig(id)
        // Stamp the sig cache with the just-rendered truth: the next live
        // patch must compare against this page, not a pre-render entry.
        msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
        lastStepPatched[id] = progressLive[id]?.size ?: 0
        lastStepPatchAt = android.os.SystemClock.uptimeMillis()
        pendingStepPatch?.let { progressHandler.removeCallbacks(it) }
        pendingStepPatch = null
        threadWeb.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        if (effScroll) { if (anchorIdx >= 0) anchorThreadScroll(anchorIdx) else scrollThreadToBottom() }
        else restoreThreadScroll(keepCssY)
    }

    /** Cached structural signature: threadMsgSig walks the whole history,
     *  and it ran on every step event + text paint (several per second on
     *  the UI thread, cost growing with chat length — the background lag
     *  that worsened the longer a chat got). 1s TTL: worst case a page/store
     *  drift waits one beat before the next event catches it. */
    private val msgSigCache = mutableMapOf<String, Pair<String, Long>>()
    private fun threadMsgSigCached(id: String): String {
        val now = android.os.SystemClock.uptimeMillis()
        val hit = msgSigCache[id]
        if (hit != null && now - hit.second < 1000) return hit.first
        val s = threadMsgSig(id)
        if (msgSigCache.size > 50) msgSigCache.clear()
        msgSigCache[id] = s to now
        return s
    }

    /** Structural signature for the open thread: settled message ids/roles/
     *  text + settled-step count. Live (streaming) text is excluded, so step
     *  patches and live-text patches skip full reloads while a turn runs. */
    private fun threadMsgSig(id: String): String {
        val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return "$id|none"
        return threadMsgSigFor(id, s.messages)
    }

    /** Same signature over an explicit message list: lets the incremental
     *  append verify the page matches everything *before* the new row. */
    private fun threadMsgSigFor(id: String, msgs: List<ChatMessage>): String {
        val done = progressDone[id]?.size ?: 0
        val live = msgs.count { it.role == "assistant" && it.turnId.isNotBlank() && !it.settled }
        return "$id|${msgs.size}|${msgs.filter { it.settled || it.role != "assistant" }.map { it.role + ":" + it.text.hashCode() + ":" + it.sendFailed }.hashCode()}|$done|$live"
    }

    /** Incremental append of one settled trailing row without a WebView
     *  reload. Returns false whenever the page can't take it (not loaded,
     *  paged window, first row over the empty state, non-trailing row,
     *  live row, or any drift) — the caller falls back to renderThread(),
     *  i.e. today's behavior. A failed JS insert self-heals the same way
     *  via the evaluate callback. */
    private fun appendRowDom(id: String, m: ChatMessage): Boolean {
        try {
            if (activeSessionId != id || !threadPageLoaded) return false
            // Pointer rows never patch incrementally (they render as labels
            // on user rows): fall back to a full render so labels land.
            if (isPointer(m)) return false
            // Live rows ride the delta/step patch paths, never this one
            // (their ids + the one-indicator rule need a full render).
            if (m.role == "assistant" && m.turnId.isNotBlank() && !m.settled) return false
            val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return false
            val vis = threadVisible[id] ?: threadPageSize
            if (s.messages.size > vis) return false // paged: counts shift
            if (s.messages.size <= 1) return false // first row replaces the empty state
            val idx = s.messages.indexOfLast { it === m }
            if (idx < 0 || idx != s.messages.size - 1) return false
            // The loaded page must match everything before the new row.
            if (threadMsgSigFor(id, s.messages.dropLast(1)) != lastFullMsgSig) return false
            val prevTs = s.messages[idx - 1].ts
            val frag = org.json.JSONObject.quote(
                ChatMarkdown.daySepBetween(prevTs, m.ts) + ChatMarkdown.rowHtml(m, idx, LinkToken.forUrl(this@MainActivity, prefs)))
            val js = "(function(){if(typeof window.__appendRow!=='function')return 'missing';" +
                "return window.__appendRow(" + frag + ");})();"
            threadWeb.evaluateJavascript(js) { res ->
                if (res == null || res == "null" || !res.contains("ok")) {
                    runOnUiThread { if (activeSessionId == id) renderThread(id) }
                } else {
                    // Page now carries the new row: resync so step/live
                    // patches keep patching instead of reloading.
                    lastFullMsgSig = threadMsgSig(id)
                    msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** In-place settle without a WebView reload: the live placeholder row
     *  for a turn is replaced by its settled html (same slot, no scroll
     *  jump). Twin of appendRowDom with the same discipline — any doubt
     *  falls back to a full render. Pointer rows and paged windows stay
     *  on the full path. */
    private fun settleRowDom(id: String, turnId: String, clearLive: Boolean): Boolean {
        try {
            if (activeSessionId != id || !threadPageLoaded) return false
            if (turnId.isBlank()) return false
            val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return false
            val vis = threadVisible[id] ?: threadPageSize
            if (s.messages.size > vis) return false // paged: positions shift
            val idx = s.messages.indexOfLast { it.role == "assistant" && it.turnId == turnId && it.settled }
            if (idx < 0) return false
            val m = s.messages[idx]
            if (isPointer(m)) return false
            val frag = org.json.JSONObject.quote(ChatMarkdown.rowHtml(m, idx, LinkToken.forUrl(this@MainActivity, prefs)))
            val js = "(function(){if(typeof window.__settleRow!=='function')return 'missing';" +
                "return window.__settleRow(" + org.json.JSONObject.quote(turnId) + "," + frag + "," +
                (if (clearLive) "true" else "false") + ");})();"
            threadWeb.evaluateJavascript(js) { res ->
                if (res == null || res == "null" || !res.contains("ok")) {
                    runOnUiThread { if (activeSessionId == id) renderThread(id) }
                } else {
                    // Page now carries the settled row: resync so step/live
                    // patches keep patching instead of reloading.
                    lastFullMsgSig = threadMsgSig(id)
                    msgSigCache[id] = lastFullMsgSig to android.os.SystemClock.uptimeMillis()
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** Live step arrival: DOM-patch #prog when the page is loaded and only
     *  steps changed; any structural change falls back to full render. */
    private fun onLiveSteps(id: String) {
        updateStopBtn()
        if (activeSessionId != id) return
        if (!threadPageLoaded) {
            renderThread(id, scroll = false)
            return
        }
        if (threadMsgSigCached(id) != lastFullMsgSig) {
            renderThread(id, scroll = false)
            return
        }
        scheduleStepPatch(id)
    }

    /** Coalesce step bursts: at most one DOM patch per 800ms. */
    private fun scheduleStepPatch(id: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val wait = STEP_PATCH_MIN_MS - (now - lastStepPatchAt)
        if (wait > 0) {
            if (pendingStepPatch == null) {
                val r = Runnable {
                    pendingStepPatch = null
                    patchStepsDom(id)
                }
                pendingStepPatch = r
                progressHandler.postDelayed(r, wait)
            }
            return
        }
        patchStepsDom(id)
    }

    private fun escStepHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Append only the unseen step rows to #prog + pin the box to bottom.
     *  Falls back to a full render when the box is missing (page not ready
     *  or structural drift). Never reloads for step-only updates. */
    private fun patchStepsDom(id: String) {
        if (activeSessionId != id || !threadPageLoaded) {
            if (activeSessionId == id) renderThread(id, scroll = false)
            return
        }
        if (threadMsgSigCached(id) != lastFullMsgSig) {
            renderThread(id, scroll = false)
            return
        }
        val steps = progressLive[id] ?: emptyList()
        val from = lastStepPatched[id] ?: 0
        if (from >= steps.size) {
            lastStepPatchAt = android.os.SystemClock.uptimeMillis()
            return
        }
        val rows = StringBuilder()
        for (i in from until steps.size) {
            val st = steps[i]
            rows.append("<div class='prow'>▸ ")
                .append(escStepHtml(st.label.ifBlank { st.kind }))
                .append("</div>")
            if (st.detail.isNotBlank()) rows.append("<div class='pdet'>")
                .append(escStepHtml(st.detail)).append("</div>")
        }
        val htmlFrag = org.json.JSONObject.quote(rows.toString())
        val js = "(function(){var b=document.getElementById('prog');if(!b)return 'missing';" +
            "var sh=b.querySelector('.shimmer');if(sh)sh.remove();" +
            "var ps=document.getElementById('pstat');if(ps)ps.remove();" +
            "b.insertAdjacentHTML('beforeend'," + htmlFrag + ");" +
            "b.scrollTop=b.scrollHeight;return 'ok';})();"
        lastStepPatchAt = android.os.SystemClock.uptimeMillis()
        lastStepPatched[id] = steps.size
        try {
            threadWeb.evaluateJavascript(js) { res ->
                if (res != null && res.contains("missing")) {
                    runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("terminus", "patchStepsDom: ${e.message}")
            runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
        }
    }

    /** Live status row (see the "status" stream event): kept last in #prog, removed when blank. */
    private fun patchStatusDom(text: String) {
        val js = "(function(t){var b=document.getElementById('prog');var p=document.getElementById('pstat');" +
            "if(!t){if(p)p.remove();return;}if(!b)return;var sh=b.querySelector('.shimmer');if(sh)sh.remove();" +
            "if(!p){p=document.createElement('div');p.id='pstat';p.className='prow pstat';}b.appendChild(p);" +
            "p.textContent='\u25B8 '+t;b.scrollTop=b.scrollHeight;})(" + org.json.JSONObject.quote(text) + ");"
        try { threadWeb.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    /** Paint live reply text into the standing row without a reload.
     *  Falls back to a full render when the row is missing (page not ready
     *  or structural drift). Throttled by the caller. */
    private fun patchLiveTextDom(id: String, turnId: String, text: String) {
        if (activeSessionId != id || !threadPageLoaded) {
            if (activeSessionId == id) renderThread(id, scroll = false)
            return
        }
        if (threadMsgSigCached(id) != lastFullMsgSig) {
            renderThread(id, scroll = false)
            return
        }
        val frag = org.json.JSONObject.quote(
            "<div class='prow live'>" + escStepHtml(text.takeLast(8000)) + "</div>")
        val sel = org.json.JSONObject.quote("#live-$turnId")
        val js = "(function(){var el=document.querySelector($sel)||document.getElementById('live');if(!el)return 'missing';" +
            "el.classList.add('live');el.classList.remove('typing');" +
            "var atBottom=(window.innerHeight+window.scrollY)>=document.body.scrollHeight-40;" +
            "el.innerHTML=" + frag + ";" +
            "if(window.__follow!==false&&atBottom)window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));return 'ok';})();"
        try {
            threadWeb.evaluateJavascript(js) { res ->
                if (res != null && res.contains("missing")) {
                    runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("terminus", "patchLiveTextDom: ${e.message}")
            runOnUiThread { if (activeSessionId == id) renderThread(id, scroll = false) }
        }
    }

    /** Cancel the SSE stream read (unblocks the socket + drops the guard). */
    private fun stopStream() {
        streamChat = null
        OpencodeApi.cancelStream()
        try { streamThread?.interrupt() } catch (_: Exception) {}
        streamThread = null
    }

    /** One multiplexed GET /api/stream per open chat. Lifecycle contract:
     *  `done` settles one turn by turnId and never closes the stream;
     *  verify display/stream issues in /debug/chat.
     *  Step rows append into the reasoning rows; each `done` (turnId) settles
     *  its own placeholder in place. Dropped connections reconnect and
     *  replays are deduped. */
    private fun ensureStream(id: String) {
        if (streamChat == id) return
        startStream(id)
    }

    private fun startStream(id: String) {
        // Stream adoption: one live stream only. If the previous owner still
        // has turns in flight, schedule a reaper that takes the stream back
        // once the new owner goes idle — an away reply must never stall for
        // lack of a listener. Never steals from a chat with live turns.
        val prev = streamChat
        stopStream()
        streamChat = id
        if (prev != null && prev != id) {
            try {
                if (chatStore.hasLive(prev)) {
                    progressHandler.postDelayed({
                        val cur = streamChat
                        // Take the stream back only from an idle owner; a chat
                        // with live turns keeps its listener.
                        if (cur != null && cur != prev && !chatStore.hasLive(cur) && chatStore.hasLive(prev)) {
                            startStream(prev)
                        }
                    }, 5000)
                }
            } catch (_: Exception) {}
        }
        // NOTE: live/settled step maps persist across (re)starts by design:
        // overlapping turns share the one merged live box, and the settled
        // expander groups every finished run. settleProgress() owns cleanup.
        val t = Thread {
            try {
                val rsid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                OpencodeApi.streamChat(
                    prefs.serverUrl(), prefs.token(), id, rsid,
                    isAlive = { streamChat == id },
                    since = try { streamSince(id) } catch (_: Exception) { 0L },
                    onEvent = { ev ->
                        if (streamChat != id) return@streamChat
                        when (ev.event) {
                            "step" -> {
                                try {
                                    val o = org.json.JSONObject(ev.data)
                                    val step = OpencodeApi.ProgressStep(
                                        o.optString("kind", "text"),
                                        o.optString("label", ""),
                                        o.optString("detail", ""),
                                        o.optLong("at", 0L)
                                    )
                                    // NOTE: step events carry no turnId, so they
                                    // must not refresh any placeholder clock — a
                                    // generic progress tick used to make every
                                    // unsettled turn in the chat look live.
                                    // Per-turn delta paths (setLiveText) still
                                    // stamp their own turn attributably.
                                    // Changed-files ride the same per-chat
                                    // accumulation: the settling turn takes
                                    // them (same discipline as progressLive).
                                    val cur = (progressLive[id] ?: emptyList()) + step
                                    progressLive[id] = cur.takeLast(20)
                                    // Each step follows a model request: the ring follows the turn as it grows.
                                    pokeContext(id)
                                    try {
                                        val fa = o.optJSONArray("files")
                                        if (fa != null && fa.length() > 0) {
                                            val set = changedLive.getOrPut(id) { mutableSetOf() }
                                            for (i in 0 until fa.length()) {
                                                val f = fa.optString(i).trim()
                                                if (f.isNotBlank()) set.add(f)
                                            }
                                            while (set.size > 12) set.remove(set.first())
                                        }
                                    } catch (_: Exception) {}
                                    runOnUiThread { if (activeSessionId == id) onLiveSteps(id) }
                                } catch (_: Exception) {}
                            }
                            "delta" -> {
                                // Live text paints straight into the standing
                                // placeholder, so the reply is visible even if
                                // `done` is ever delayed or lost; `done` still
                                // settles canonically (dedupe by turnId).
                                try {
                                    val o = org.json.JSONObject(ev.data)
                                    val frag = o.optString("delta", "")
                                    var turn = o.optString("turnId", "")
                                    if (frag.isNotBlank()) {
                                        if (turn.isBlank()) {
                                            // Fallback only when ownership is certain (one
                                            // pending turn). Under overlap (2+) a blank-
                                            // turnId delta is dropped rather than guessed
                                            // into the newest turn's placeholder — done
                                            // still settles every turn with full text.
                                            val pending = pendingTurns[id]
                                            turn = try {
                                                if (pending != null && pending.size == 1) pending.first() else ""
                                            } catch (_: Exception) { "" }
                                        }
                                        if (turn.isNotBlank() && (pendingTurns[id]?.contains(turn) == true)) {
                                            // Buffered deltas: accumulate in memory, persist throttled
                                            // (a full store rewrite per token degrades with history size).
                                            // DOM still paints every 400ms; `done` settles canonically.
                                            val buf = deltaBuf.getOrPut(turn) { StringBuilder() }
                                            if (buf.length < 32768) buf.append(frag.take(32768 - buf.length))
                                            val full = buf.toString()
                                            val now = System.currentTimeMillis()
                                            if (now - (deltaPersistAt[turn] ?: 0L) > DELTA_PERSIST_MS) {
                                                deltaPersistAt[turn] = now
                                                if (!chatStore.setLiveText(id, turn, full)) deltaBuf.remove(turn)
                                            }
                                            if (deltaBuf.containsKey(turn) && (now - (deltaRenderAt[turn] ?: 0L) > 400 || !deltaRenderAt.containsKey(turn))) {
                                                // Leading edge: the first fragment of a turn
                                                // paints immediately (no entry yet), bursts
                                                // stay throttled at 400ms. Same certainty
                                                // gates as above — attribution unchanged.
                                                deltaRenderAt[turn] = now
                                                runOnUiThread { if (activeSessionId == id) patchLiveTextDom(id, turn, full) }
                                            } else if (deltaBuf.containsKey(turn)) {
                                                val t = turn
                                                runOnUiThread {
                                                    if (deltaTrail.containsKey(t)) return@runOnUiThread
                                                    val r = Runnable {
                                                        deltaTrail.remove(t)
                                                        val b = deltaBuf[t] ?: return@Runnable
                                                        val text = b.toString()
                                                        deltaRenderAt[t] = System.currentTimeMillis()
                                                        deltaPersistAt[t] = System.currentTimeMillis()
                                                        if (!chatStore.setLiveText(id, t, text)) deltaBuf.remove(t)
                                                        if (activeSessionId == id) patchLiveTextDom(id, t, text)
                                                    }
                                                    deltaTrail[t] = r
                                                    deltaHandler.postDelayed(r, 450)
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            "status" -> {
                                // Transient "what is happening now" line (Claude thinking token count,
                                // tool being composed): one row updated in place at the end of the live
                                // box; "" or the next step row removes it. Never stored.
                                try {
                                    val o = org.json.JSONObject(ev.data)
                                    val turn = o.optString("turnId", "")
                                    val text = o.optString("text", "").take(120)
                                    if (turn.isBlank() || pendingTurns[id]?.contains(turn) == true) {
                                        runOnUiThread { if (activeSessionId == id && threadPageLoaded) patchStatusDom(text) }
                                    }
                                } catch (_: Exception) {}
                            }
                            "done" -> {
                                var text = ""
                                var err = ""
                                var turn = ""
                                var mid = ""
                                var at = 0L
                                var code = ""
                                try {
                                    val o = org.json.JSONObject(ev.data)
                                    text = o.optString("text", "")
                                    err = o.optString("error", "")
                                    turn = o.optString("turnId", "")
                                    mid = o.optString("messageID", "")
                                    if (mid.isBlank()) mid = o.optString("messageId", "")
                                    at = o.optLong("at", 0L)
                                    code = o.optString("code", "")
                                } catch (_: Exception) {}
                                val ft = text
                                val fe = err
                                val fturn = turn
                                val fmid = mid
                                val fcode = code
                                // A reply ended: jobs it started may have been announced while this chat had no
                                // stream (the phone keeps one), so fetch the chat's job log now.
                                runOnUiThread { catchUpBackground(id, force = true) }
                                if (at > 0) {
                                    try {
                                        val prev = doneHighWater[id] ?: 0L
                                        if (at > prev) {
                                            doneHighWater[id] = at
                                            prefs.setDoneHighWater(id, at)
                                        }
                                    } catch (_: Exception) {}
                                }
                                runOnUiThread { finishStreamTurn(id, fturn, ft, fe, fmid, fcode) }
                            }
                            "inbox" -> {
                                runOnUiThread { syncInbox() }
                            }
                            // Context compaction (manual or automatic) lands
                            // outside any turn: a durable tool row, never the
                            // live box (which only paints while a turn runs).
                            // Started rows collapse into one; ended/failed
                            // replaces the started row with the outcome.
                            "compact" -> {
                                var status = ""
                                var reason = ""
                                var detail = ""
                                try {
                                    val o = org.json.JSONObject(ev.data)
                                    status = o.optString("status", "")
                                    reason = o.optString("reason", "")
                                    detail = o.optString("detail", "")
                                } catch (_: Exception) {}
                                val fstatus = status
                                val freason = reason
                                val fdetail = detail
                                runOnUiThread { finishCompactRow(id, fstatus, freason, fdetail) }
                            }
                            "background" -> {
                                // A background job's start or landing. Durable
                                // row, not a live-box step: it must show even
                                // when no turn is running (the landing usually
                                // arrives after the turn settled).
                                val bg = try { OpencodeApi.parseBackground(org.json.JSONObject(ev.data)) } catch (_: Exception) { null }
                                val fstatus = bg?.status ?: ""
                                val flabel = bg?.label ?: ""
                                val fdetail = bg?.detail ?: ""
                                val ffull = bg?.full ?: ""
                                runOnUiThread {
                                    // Logged on the server with a per-chat seq: a row already applied (live or by
                                    // catch-up) is not applied twice; a gap is filled by the next catch-up.
                                    if (bg != null && bg.seq > 0) {
                                        if (bg.seq <= bgSeqOf(id)) return@runOnUiThread
                                        setBgSeq(id, bg.seq)
                                    }
                                    // Malformed frames (blank status) touch neither the
                                    // tally nor the thread: they used to decrement the
                                    // count and pin nothing. Elapsed rides the finish
                                    // row (A2/D2); redelivered landings collapse via
                                    // the trailing exact-match drop below.
                                    if (fstatus.isBlank()) return@runOnUiThread
                                    val localMs = backgroundJobs.note(id, fstatus)
                                    val elapsedMs = if ((bg?.elapsedMs ?: -1L) >= 0) bg!!.elapsedMs else localMs
                                    onBackgroundRow(id, fstatus, flabel, fdetail,
                                        if (elapsedMs < 0) "" else BackgroundUi.elapsedText(elapsedMs),
                                        ffull)
                                    // Last job gone: release the stream we held.
                                    if (fstatus != "started" && !backgroundJobs.any(id) && streamChat == id) {
                                        idleStreamHandoff(id)
                                    }
                                }
                            }
                            "idle" -> {
                                runOnUiThread {
                                    serverIdle[id] = true
                                    updateStopBtn()
                                    reconcileTurnState(id)
                                }
                                // Session-idle is the server's word that no
                                // turn is running — but it can race a fresh
                                // start, so verify against live session state
                                // before clearing the indicator. Confirmed
                                // idle drops the in-flight set NOW (no 90s
                                // wait); the placeholders stay put for late
                                // `done` recovery either way.
                                Thread {
                                    val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                                    val working = if (sid.isBlank()) null
                                        else OpencodeApi.fetchProgressWorking(prefs.serverUrl(), prefs.token(), id, sid, chatBackendOf(id))
                                    // Server-settled turns are done regardless of the
                                    // progress reading: drop them first, so a turn the
                                    // registry already closed can't keep "replying…" up.
                                    val inFlight = pendingTurns[id]?.toSet() ?: emptySet()
                                    val settledNow = inFlight.filter { t ->
                                        try {
                                            OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), id, t, chatBackendOf(id)).settled
                                        } catch (_: Exception) { false }
                                    }.toSet()
                                    val remaining = inFlight - settledNow
                                    val next = TurnActivity.resolveIdle(remaining, working)
                                        ?: if (settledNow.isEmpty()) null else remaining
                                    // Confirmed idle: recover lost-done texts
                                    // off-thread first (network), so the UI
                                    // block below only settles what's left.
                                    if (working == false) recoverOrphanTexts(id)
                                    if (next != null && next != pendingTurns[id]) {
                                        runOnUiThread {
                                            if (next.isEmpty()) pendingTurns.remove(id)
                                            else pendingTurns[id] = next.toMutableSet()
                                            sessionAdapter.refreshLiveChats(TurnActivity.replyingChats(pendingTurns))
                                            if (activeSessionId == id) renderThread(id)
                                            // Stale orphans still standing
                                            // settle now (fresh turns spared).
                                            if (working == false) {
                                                settleDeadTurns(id, onlyStale = true, "cleared, nothing was running")
                                            }
                                        }
                                    }
                                }.start()
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                // Connect/HTTP failure → fallback below.
            }
            if (streamChat == id) {
                // Reconnect the multiplexed stream if the socket ended (or
                // hit its 30m cap). Cached per-turn dones replay; client-side
                // dedupe makes this safe and keeps queue drains reliable.
                // Reconnects even when this thread isn't open: an away turn
                // must still settle (sessions list + ping), never stall
                // silently until the user returns.
                runOnUiThread {
                    if (streamChat == id) {
                        streamChat = null
                        // Awake while anything is in flight: prefer this chat,
                        // else the most recent other chat with live turns.
                        // Another thread's live stream is never killed for this.
                        // A running background job or a looping goal also keeps
                        // this chat's stream wanted: their events never come twice.
                        val held = streamWanted(id)
                        val target = if (hasPlaceholder(id) || held) id
                            else try { chatStore.chatsWithLive().lastOrNull() } catch (_: Exception) { null }
                        if (target != null) {
                            progressHandler.postDelayed({
                                if (streamChat == null && (chatStore.hasLive(target) || (held && target == id))) startStream(target)
                            }, 1500)
                        }
                    }
                }
            }
        }
        streamThread = t
        t.start()
    }

    /** Compaction outcome as a durable row (no turn involved). Started
     *  rows collapse into one; ended/failed swaps the started row for the
     *  outcome so auto runs leave exactly one row. */
    private fun finishCompactRow(id: String, status: String, reason: String, detail: String) {
        // Trailing-row exact matcher: drop every started variant.
        fun dropStarted() {
            chatStore.dropLastMatching(id, "compacting context…")
            chatStore.dropLastMatching(id, "compacting context (auto)…")
            chatStore.dropLastMatching(id, "compacting context (manual)…")
        }
        val scope = if (reason == "auto" || reason == "manual") " ($reason)" else ""
        when (status) {
            "started" -> {
                dropStarted()
                postNote(id, "event", "compacting context$scope…")
            }
            // The event reaches every open stream for the chat; an identical
            // trailing outcome is kept as is, never doubled.
            "ended" -> {
                dropStarted()
                val text = "context compacted$scope, history kept"
                if (!chatStore.lastNoteIs(id, text)) postNote(id, "event", text)
            }
            "failed" -> {
                dropStarted()
                val msg = detail.ifBlank { "compaction failed" }
                val text = "compaction failed$scope ($msg)"
                if (!chatStore.lastNoteIs(id, text)) postNote(id, "event", text)
            }
            else -> return
        }
        renderSessions()
    }

    /** A background shell's start or finish landed. Appends a durable event
     *  row (never a live-box step): the finish usually arrives after the turn
     *  settled, when no live box exists. Both ends stay in the thread. */
    private val bgSeqPrefs by lazy { getSharedPreferences("background-seq", MODE_PRIVATE) }
    private fun bgSeqOf(id: String): Long = bgSeqPrefs.getLong(id, -1L)
    private fun setBgSeq(id: String, seq: Long) { bgSeqPrefs.edit().putLong(id, seq).apply() }
    private val bgCatchUpAt = HashMap<String, Long>()

    /** Background rows the phone missed (app closed or offline when a job started or ended): fetched from the
     *  server's per-chat log after the last applied seq. A chat seen for the first time starts at the server's
     *  current seq instead of replaying old rows. Throttled per chat. */
    private fun catchUpBackground(id: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if ((!force && now - (bgCatchUpAt[id] ?: 0L) < 10_000) || prefs.token().isBlank()) return
        bgCatchUpAt[id] = now
        val after = bgSeqOf(id)
        Thread {
            val r = try { OpencodeApi.fetchBackground(prefs.serverUrl(), prefs.token(), id, maxOf(after, 0L)) } catch (_: Exception) { null }
                ?: return@Thread
            runOnUiThread {
                if (bgSeqOf(id) < 0) { setBgSeq(id, r.seq); return@runOnUiThread }
                var applied = false
                for (e in r.events) {
                    if (e.seq <= bgSeqOf(id)) continue
                    setBgSeq(id, e.seq)
                    val localMs = backgroundJobs.note(id, e.status)
                    val ms = if (e.elapsedMs >= 0) e.elapsedMs else localMs
                    onBackgroundRow(id, e.status, e.label, e.detail, if (ms < 0) "" else BackgroundUi.elapsedText(ms), e.full)
                    applied = true
                }
                if (applied && activeSessionId == id) renderThread(id)
                // A job still running: hold this chat's stream so its finish lands live.
                if (applied && backgroundJobs.any(id) && streamChat != id) ensureStream(id)
            }
        }.start()
    }

    private fun onBackgroundRow(id: String, status: String, label: String, detail: String, elapsed: String, full: String = "") {
        if (status.isBlank()) return
        val text = BackgroundUi.rowText(status, label, detail, elapsed, full)
        // Twins collapse: a redelivered landing refreshes the one trailing
        // row instead of stacking an identical twin.
        try { chatStore.dropLastMatching(id, text) } catch (_: Exception) {}
        postNote(id, "event", text)
    }

    /** `done` arrived over the stream: settle its own turn in place (send-once).
     *  Duplicate dones (reconnect replay,
     *  overlapping covering turns) settle nothing twice. Stream stays open
     *  while other turns are still pending; the queue drains when idle. */
    /** Serialize live steps for per-reply history. */
    private fun stepsJson(steps: List<OpencodeApi.ProgressStep>): String = try {
        val arr = org.json.JSONArray()
        steps.takeLast(40).forEach {
            arr.put(org.json.JSONObject()
                .put("kind", it.kind).put("label", it.label).put("detail", it.detail).put("at", it.at))
        }
        arr.toString()
    } catch (_: Exception) { "" }

    /** Reconcile one stale turn against the server's exact-turnId registry.
     *  Settled → the normal `done` path (the kept placeholder settles;
     *  messageID dedupe + merge rule apply, so no duplicate row). Unknown
     *  (or unreachable) → the honest row stands; the user decides. Never
     *  resends: recovery reads, it doesn't execute. */
    private fun reconcileTurn(id: String, turnId: String, tap: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        if (tap) showChecking(id)
        Thread {
            try {
                val r = OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), id, turnId, chatBackendOf(id))
                if (r.settled) {
                    runOnUiThread {
                        hideChecking(id)
                        finishStreamTurn(id, turnId, r.text, r.error, r.messageID, r.code)
                        try { done?.invoke(true) } catch (_: Exception) {}
                    }
                    return@Thread
                }
                // Server-confirmed running: re-stamp the placeholder so a
                // quiet-but-alive turn keeps its stop button and never
                // looks orphaned. Anything else stays unknown.
                if (r.running) {
                    runOnUiThread {
                        try { chatStore.touchLive(id, turnId) } catch (_: Exception) {}
                        updateStopBtn()
                    }
                }
            } catch (_: Exception) {
                // Transport failure: stay unknown; the tap tries again.
            }
            runOnUiThread {
                hideChecking(id)
                try { done?.invoke(false) } catch (_: Exception) {}
            }
        }.start()
    }

    /** Ask the server to reconcile this chat's unsettled turns, then read each one it closed out through
     *  the normal exact-turn path (dedupe applies). Silent when there is nothing to do or no network. */
    private fun syncThread(id: String, manual: Boolean = false) {
        val t0 = System.currentTimeMillis()
        if (manual) pullStartChecking()
        // The footer shows for at least a beat so a fast answer never reads as a flicker.
        fun finishManual(msg: String, then: (() -> Unit)? = null) {
            if (!manual) return
            val wait = (650 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)
            progressHandler.postDelayed({ pullFinish(msg); then?.invoke() }, wait)
        }
        Thread {
            val r = try {
                val rsid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                OpencodeApi.syncChat(prefs.serverUrl(), prefs.token(), id, rsid, chatBackendOf(id))
            } catch (_: Exception) {
                runOnUiThread { finishManual("Couldn't reach the server") }
                return@Thread
            }
            val turns = (r.settled + r.lost).distinct()
            runOnUiThread {
                backendWaitingTurns[id] = r.waiting.toSet()
                finishManual(pullSyncMessage(turns.size, r.running.isNotEmpty()))
                if (activeSessionId == id) refreshSendNowLinks()
                for (t in turns) reconcileTurn(id, t)
            }
        }.start()
    }

    // ---- pull-up footer: a small pill at the bottom of the thread. A refresh glyph rises and turns as
    // you drag, "Release to check" says what the gesture does, it spins while the server is asked, and
    // the outcome reads as one short line before it slides away. Built in code so no layout changes.
    private var pullBox: android.widget.LinearLayout? = null
    private var pullIcon: android.widget.TextView? = null
    private var pullLabel: android.widget.TextView? = null
    private var pullSpin: android.animation.ObjectAnimator? = null
    private var pullChecking = false
    private val pullHideRun = Runnable { pullChecking = false; pullHide() }

    private fun pullEnsure(): android.widget.LinearLayout? {
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

    private fun pullStyle() {
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
    private fun pullShow(p: Float, armed: Boolean) {
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

    private fun pullHide(animate: Boolean = true) {
        val box = pullBox ?: return
        if (box.visibility != View.VISIBLE) return
        pullSpin?.cancel(); pullSpin = null
        if (!animate) { box.visibility = View.GONE; box.alpha = 0f; return }
        val dp = resources.displayMetrics.density
        box.animate().alpha(0f).translationY(16 * dp).setDuration(180L)
            .withEndAction { box.visibility = View.GONE }.start()
    }

    private fun pullReset() {
        try {
            progressHandler.removeCallbacks(pullHideRun)
            pullChecking = false
            pullHide(animate = false)
        } catch (_: Exception) {}
    }

    private fun pullStartChecking() {
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

    private fun pullFinish(msg: String) {
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
    private fun showChecking(id: String) {
        checking.add(id)
        progressHandler.postDelayed({ hideChecking(id) }, 15_000)
        if (activeSessionId == id) renderThread(id)
    }
    private fun hideChecking(id: String) {
        if (checking.remove(id) && activeSessionId == id) renderThread(id)
    }

    /** Short toast on the UI thread (link taps, check outcomes). */
    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun finishStreamTurn(id: String, turnId: String, text: String, error: String, messageID: String, code: String = "") {
        // Purged chat (/clear): the local record is gone and stays gone. A
        // late done must drop here — settling further would re-render, ping,
        // or (legacy path) stop another chat's live stream.
        try { if (chatStore.get(id) == null) return } catch (_: Exception) { return }
        if (turnId.isBlank()) {
            finishLegacyTurn(id, text, error)
            return
        }
        // A settled turn explains itself (reply or error row): its stale
        // "outcome unknown" row, if any, goes with it.
        try { chatStore.dropUnknownRows(id, turnId) } catch (_: Exception) {}
        // Abandoned by retry: the fresh turn carries the prompt, so the
        // original's late outcome drops wholesale (TTL-pruned, same
        // discipline as deliveredTurns). Abandon, never merge — merging a
        // superseded outcome risks the twins F1 removed.
        val abandoned = synchronized(supersededTurns) {
            val m = supersededTurns[id] ?: return@synchronized false
            val now = System.currentTimeMillis()
            m.entries.removeAll { now - it.value > deliveredTtlMs }
            m.containsKey(turnId)
        }
        if (abandoned) {
            dropTurnBuf(turnId)
            chatStore.dropPlaceholder(id, turnId)
            if (activeSessionId == id) renderThread(id)
            renderSessions()
            return
        }
        // Late `done` for an already-delivered turn: route purely.
        // Merged turns (claimed messageID — opencode fused the prompt into
        // a rendered reply, the P5 overlap shape) and rendered text drop;
        // a kept partial for this same turn upgrades in place; only
        // genuinely new text settles or appends. One row per reply.
        // Comparisons are whitespace-normalized: a trailing-space variant
        // is the same reply, not a twin.
        if (!markDelivered(id, turnId)) {
            dropTurnBuf(turnId)
            pendingTurns[id]?.remove(turnId)
            turnRestore.remove(turnId)
            // Fast path: a blank replayed done carries nothing to upgrade —
            // skip the message scans and renders entirely. Reconnect replays
            // are mostly these; each one used to cost a full settle attempt.
            if (text.isBlank()) return
            // Exact redelivery: this turn already settled with this exact
            // text, so there is nothing to scan, merge, or render. The
            // first delivery did all of that. Anything even slightly
            // different falls through to the merge rule below.
            if (isExactRedelivery(id, turnId, text)) {
                reconcileTurnState(id)
                return
            }
            val route = try {
                val msgs = chatStore.get(id)?.messages ?: emptyList()
                val norm = TurnActivity.normText(text)
                // Memory claim plus durable row check: post-restart replays
                // must merge against rows, not lost memory.
                val claimed = messageID.isNotBlank() &&
                    ((settledMessages[id] ?: emptySet()).contains(messageID) ||
                        rowHasMessageId(msgs, messageID))
                TurnActivity.lateDoneRoute(
                    claimed,
                    upgradeTurnIndex(msgs, turnId, text) >= 0,
                    turnRendered(msgs, turnId),
                    messageID.isNotBlank(),
                    msgs.any { it.role == "assistant" && TurnActivity.normText(it.text) == norm },
                )
            } catch (_: Exception) { LateDone.DROP }
            if (route != LateDone.DROP &&
                chatStore.settleTurn(id, turnId, text, stepsJson(progressLive[id] ?: emptyList()), messageID)) {
                claimMessageId(id, messageID)
                noteDeliveredText(id, turnId, text)
                stampSettledChanged(id, turnId, messageID)
                clearStepsIfIdle(id)
                noteLanFallback(id)
                syncServerTitle(id)
                if (activeSessionId == id) {
                    val noLive = (pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)
                    if (!settleRowDom(id, turnId, noLive)) renderThread(id)
                    pokeContext(id)
                    renderSessions()
                } else {
                    renderSessions()
                }
                if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                    idleStreamHandoff(id)
                }
                reconcileTurnState(id)
                return
            }
            if (route == LateDone.UPGRADE && chatStore.replaceTurnText(id, turnId, text,
                    stepsJson(progressLive[id] ?: emptyList()), messageID)) {
                claimMessageId(id, messageID)
                noteDeliveredText(id, turnId, text)
                stampSettledChanged(id, turnId, messageID)
                clearStepsIfIdle(id)
                noteLanFallback(id)
                syncServerTitle(id)
                if (activeSessionId == id) {
                    val noLive = (pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)
                    if (!settleRowDom(id, turnId, noLive)) renderThread(id)
                    pokeContext(id)
                    renderSessions()
                } else {
                    renderSessions()
                }
                if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                    idleStreamHandoff(id)
                }
                reconcileTurnState(id)
                return
            }
            // UPGRADE computed but replace failed (store moved under us):
            // armor — genuinely new text still appends rather than vanishing.
            if (route == LateDone.APPEND || (route == LateDone.UPGRADE && text.isNotBlank())) {
                val appended = ChatMessage("assistant", text,
                    stepsJson = stepsJson(progressLive[id] ?: emptyList()),
                    messageID = messageID,
                    changedFiles = takeChanged(id),
                    settled = true)
                chatStore.addMessage(id, appended)
                claimMessageId(id, messageID)
                clearStepsIfIdle(id)
                noteLanFallback(id)
                syncServerTitle(id)
                val wasBg = !appForeground
                if (activeSessionId == id) {
                    if (!appendRowDom(id, appended)) renderThread(id)
                    renderSessions()
                } else {
                    renderSessions()
                }
                maybeNotifyTurn(id, text, wasBg)
                if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                    idleStreamHandoff(id)
                }
                reconcileTurnState(id)
                return
            }
            chatStore.dropPlaceholder(id, turnId)
            // DROP: merged or replayed. Also sweep a stale same-turn partial
            // the claimed reply supersedes - it must not stand beside the
            // full row as a leftover twin.
            if (text.isNotBlank()) chatStore.dropStalePartial(id, turnId, text)
            if (activeSessionId == id) renderThread(id)
            renderSessions()
            if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                idleStreamHandoff(id)
            }
            if (!(waitQueue[id]?.isEmpty() ?: true) && !isTurnActive(id)) drainWaitQueue(id)
            return
        }
        pendingTurns[id]?.remove(turnId)
        val restoreText = turnRestore.remove(turnId) ?: ""
        dropTurnBuf(turnId)
        // Merge rule (reactive, no new protocol): opencode merged this turn's
        // prompt into an already-rendered reply — same messageID, one reply
        // row. The placeholder settles into a pointer row in its own slot
        // (no move, no unread bump); distinct messageIDs settle normally.
        // The row check backs the memory claim set across process death.
        if (messageID.isNotBlank() &&
            ((settledMessages[id] ?: emptySet()).contains(messageID) ||
                chatStore.hasMessageId(id, messageID))) {
            android.util.Log.d("ChatTurn", "merged turn settled as pointer (same messageID)")
            // Pointer settle + conditional last-contributor move in one
            // synchronized call (replaces the earliest-anchor rule).
            if (!chatStore.settleAsPointer(id, turnId, POINTER_TEXT, messageID)) {
                chatStore.dropPlaceholder(id, turnId)
            }
            noteDeliveredText(id, turnId, text)
            stampSettledChanged(id, turnId, messageID)
            if (activeSessionId == id) renderThread(id)
            renderSessions()
            if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                idleStreamHandoff(id)
            }
            if (!(waitQueue[id]?.isEmpty() ?: true) && !isTurnActive(id)) drainWaitQueue(id)
            return
        }
        if (text.isNotBlank()) {
            val wasBg = !appForeground
            // settleTurn is the single writer: false means the placeholder is
            // gone (stopped/cleared) or this text already settled — a late or
            // replayed `done`. Drop it, log only; never append a second row
            // and never ping for a dead turn.
            if (!chatStore.settleTurn(id, turnId, text, stepsJson(progressLive[id] ?: emptyList()), messageID)) {
                // Untracked-turn done (e.g. server-initiated goal continuation
                // with no phone placeholder): route it like a late done
                // instead of dropping. APPEND lands genuinely new text;
                // anything else keeps the old drop.
                val msgs2 = chatStore.get(id)?.messages ?: emptyList()
                val norm2 = TurnActivity.normText(text)
                val claimed2 = messageID.isNotBlank() &&
                    ((settledMessages[id] ?: emptySet()).contains(messageID) ||
                        rowHasMessageId(msgs2, messageID))
                val route2 = try {
                    TurnActivity.lateDoneRoute(
                        claimed2,
                        upgradeTurnIndex(msgs2, turnId, text) >= 0,
                        turnRendered(msgs2, turnId),
                        messageID.isNotBlank(),
                        msgs2.any { it.role == "assistant" && TurnActivity.normText(it.text) == norm2 },
                    )
                } catch (_: Exception) { LateDone.DROP }
                if (route2 == LateDone.APPEND && text.isNotBlank()) {
                    val appended = ChatMessage("assistant", text,
                        stepsJson = stepsJson(progressLive[id] ?: emptyList()),
                        messageID = messageID,
                        changedFiles = takeChanged(id),
                        settled = true)
                    chatStore.addMessage(id, appended)
                    claimMessageId(id, messageID)
                    clearStepsIfIdle(id)
                    noteLanFallback(id)
                    syncServerTitle(id)
                    val wasBg2 = !appForeground
                    if (activeSessionId == id) {
                        if (!appendRowDom(id, appended)) renderThread(id)
                        renderSessions()
                    } else {
                        renderSessions()
                    }
                    maybeNotifyTurn(id, text, wasBg2)
                    if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
                        idleStreamHandoff(id)
                    }
                    reconcileTurnState(id)
                    return
                }
                android.util.Log.d("ChatTurn", "late/duplicate done dropped")
                reconcileTurnState(id)
                return
            }
            if (messageID.isNotBlank()) {
                val sm = settledMessages.getOrPut(id) { mutableSetOf() }
                sm.add(messageID)
                while (sm.size > 50) { sm.remove(sm.first()) }
            }
            noteDeliveredText(id, turnId, text)
            stampSettledChanged(id, turnId, messageID)
            noteLanFallback(id)
            syncServerTitle(id)
            if (activeSessionId == id) {
                val noLive = (pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)
                if (!settleRowDom(id, turnId, noLive)) renderThread(id)
                pokeContext(id)
                renderSessions()
            } else {
                renderSessions()
            }
            // Ping unless the user watches this exact thread: background-app
            // and background-chat settles both deserve a ping, otherwise an
            // away reply lands with no notif and no visible response.
            maybeNotifyTurn(id, text, wasBg)
        } else {
            // Error with a blank done-text: before dropping, rescue any
            // persisted partial output as its own settled row (genuine
            // model text, attributed to this turn), then show the error
            // separately below it. A failed turn must not silently delete
            // useful intermediate output. No ping, no messageID claim.
            val partial = chatStore.placeholderText(id, turnId)
            if (partial != null) {
                chatStore.settleTurn(id, turnId, partial, stepsJson(progressLive[id] ?: emptyList()))
                noteDeliveredText(id, turnId, partial)
                stampSettledChanged(id, turnId, messageID)
                clearStepsIfIdle(id)
            } else {
                chatStore.dropPlaceholder(id, turnId)
            }
            val msg = error.ifBlank { "network error" }
            val expired = msg.contains("expired", ignoreCase = true)
            // Expired sessions need an informational row; transient failures
            // stay attached to the original user bubble with its retry link.
            var errorNote: ChatMessage? = null
            if (expired) {
                // Dead session: no action needed — the next send 404-heals
                // (clear → resend fresh) on its own. Plain note, no tap.
                errorNote = ChatMessage("event",
                    "session gone, starting fresh on your next message")
                chatStore.addMessage(id, errorNote)
                if (restoreText.isNotBlank() && activeSessionId == id) {
                    composerInput.setText(restoreText)
                }
            } else {
                // Transient: text goes back in the box AND a tap retries it
                // as a fresh turn (stashed separately, so later typing in
                // the box is never clobbered by the retry).
                if (restoreText.isNotBlank()) {
                    failedRestore[turnId] = restoreText
                    while (failedRestore.size > 20) { failedRestore.remove(failedRestore.keys.first()) }
                }
                if (!chatStore.setUserSendFailed(id, turnId, true, msg)) {
                    // Older/imported sends may not have a matching user row:
                    // fall back to a standalone note (short text, no prefix).
                    try { chatStore.dropFailureNoteForTurn(id, turnId) } catch (_: Exception) {}
                    val note = TurnActivity.formatErrorNote(msg, code, id, turnId)
                    val noteMsg = ChatMessage("error", note)
                    errorNote = noteMsg
                    chatStore.addMessage(id, noteMsg)
                }
                if (restoreText.isNotBlank() && activeSessionId == id) {
                    composerInput.setText(restoreText)
                }
            }
            if (activeSessionId == id) {
                val appended = errorNote
                // The flag lives on the user bubble, so a full render is
                // needed to paint it; the session-gone event (no bubble
                // change) can still append incrementally.
                val painted = expired && appended != null && appendRowDom(id, appended)
                if (!painted) renderThread(id)
            }
            renderSessions()
        }
        if ((pendingTurns[id]?.isEmpty() ?: true) && !hasPlaceholder(id)) {
            idleStreamHandoff(id)
        }
        reconcileTurnState(id)
        refreshGoalStrip(id)
    }

    /** Chat went fully idle: sleep the stream — unless another chat still
     *  has turns in flight, in which case the stream adopts it. One live
     *  stream, always owned by work if work exists anywhere. */
    private fun idleStreamHandoff(id: String) {
        settleProgress(id)
        // An outstanding background job means its finish row is still coming;
        // a looping goal pushes its own turn with no phone placeholder: keep
        // listening while either is live. The terminal event (or a settled
        // goal) calls back here to release it.
        if (streamWanted(id)) {
            if (streamChat != id) ensureStream(id)
            return
        }
        val other = try {
            chatStore.chatsWithLive().filter { it != id }.lastOrNull()
        } catch (_: Exception) { null }
        if (other != null) startStream(other)
        else stopStream()
    }

    /** True while this chat has a reason to keep its stream open with no live
     *  turn: an outstanding background job, or an active loop goal on the open thread. */
    private fun streamWanted(id: String): Boolean {
        if (backgroundJobs.any(id)) return true
        val g = goalCache
        return g != null && g.loop && g.status == "active" && activeSessionId == id
    }

    private fun hasPlaceholder(id: String): Boolean {
        return try { chatStore.hasLive(id) } catch (_: Exception) { false }
    }

    /** Legacy single-turn settle (old server without turnIds). Idempotent:
     *  settles nothing twice — a replayed `done` with no standing
     *  placeholder drops when its text already renders, and orphans append
     *  only when genuinely new (better delivered once than lost). */
    private fun finishLegacyTurn(id: String, text: String, error: String) {
        stopStream()
        if (text.isNotBlank()) {
            val msgs = try { chatStore.get(id)?.messages } catch (_: Exception) { null }
            if (!hasPlaceholder(id)) {
                val dupe = msgs?.any { it.role == "assistant" && it.text == text } ?: false
                if (dupe) {
                    android.util.Log.d("ChatTurn", "duplicate legacy done dropped")
                    return
                }
            } else {
                val last = msgs?.lastOrNull { it.role == "assistant" }
                if (last != null && last.text == text) {
                    android.util.Log.d("ChatTurn", "duplicate legacy done dropped")
                    reconcileTurnState(id)
                    return
                }
            }
            val wasBg = !appForeground
            settleProgress(id)
            chatStore.dropLastAssistant(id, "…")
            chatStore.addMessage(id, ChatMessage("assistant", text, settled = true))
            noteLanFallback(id)
            syncServerTitle(id)
            if (activeSessionId == id) {
                renderThread(id)
                renderSessions()
            } else {
                renderSessions()
            }
            // Ping unless the user watches this exact thread: background-app
            // and background-chat settles both deserve a ping, otherwise an
            // away reply lands with no notif and no visible response.
            maybeNotifyTurn(id, text, wasBg)
        } else {
            progressLive.remove(id)
            chatStore.dropLastAssistant(id, "…")
            if (!chatStore.failLatestUserSend(id)) {
                chatStore.dropLastFailureNote(id)
                chatStore.addMessage(id, ChatMessage(
                    "tool", "Failed to send, your text was kept in the box"))
            }
            if (activeSessionId == id) renderThread(id)
            renderSessions()
        }
        reconcileTurnState(id)
    }

    /** Turn settled to idle: fold the merged live steps into the grouped
     *  settled history (capped). The single live box clears; the expander
     *  keeps every finished run's steps. */
    private fun settleProgress(id: String) {
        val live = progressLive.remove(id) ?: emptyList()
        if (live.isNotEmpty()) progressDone[id] = ((progressDone[id] ?: emptyList()) + live).takeLast(60)
    }

    /** True while the phone still tracks a turn as pending for this chat
     *  with an unsettled placeholder (one rule: TurnModel.runningTurnIds).
     *  pendingTurns is process-local and can outlive a lost `done`, so
     *  orphans rely on the stale sweep, not on a clock here. */
    /** Inside a thread the bottom nav shrinks to a slim icon-only strip, so
     *  tab taps are less likely to land on the composer, and it stays
     *  reachable. Outside a thread it is the normal labelled bar — the slim
     *  strip only ever means "you are in a chat". Callers on non-chat panes
     *  pass false explicitly: reading the selected tab here races the
     *  selection callback (it fires before the selection applies), which
     *  stuck the strip on the way out and the bar on the way in. */
    private fun syncNavForThread(inThread: Boolean = (activeSessionId != null || draftOpen)) {
        if (!::nav.isInitialized) return
        // The app header (title, online dot, bell, new, settings) stays in open chats too: it is how you see
        // the server is reachable and reach the other actions. The thread header sits below it.
        findViewById<View?>(R.id.chatTopBar)?.visibility = View.VISIBLE
        nav.visibility = View.VISIBLE
        nav.labelVisibilityMode = if (inThread) {
            com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_UNLABELED
        } else {
            com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_LABELED
        }
        nav.itemIconSize = if (inThread) dp(20) else dp(24)
        nav.layoutParams = nav.layoutParams.apply {
            height = if (inThread) dp(40) else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    /** The step list belongs to the chat, not to whichever turn settled first:
     *  it only clears once no other turn in this chat is still running. */
    private fun clearStepsIfIdle(id: String) {
        if (pendingTurns[id]?.isEmpty() ?: true) progressLive[id] = emptyList()
    }

    /** One path for every status row: store it, append it (or re-render),
     *  refresh the list. Replaces the hand-rolled add + append-or-render
     *  blocks that drifted apart. */
    private fun postNote(id: String, role: String, text: String) {
        val m = ChatMessage(role, text)
        chatStore.addMessage(id, m)
        if (activeSessionId == id && !appendRowDom(id, m)) renderThread(id)
        renderSessions()
    }

    /** Catch-and-log for hot paths: silent catches stay silent nowhere that
     *  matters. Debug-level so release logcat stays quiet. */
    private inline fun <T> safe(tag: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.d("terminus", "$tag: ${e.message}")
            null
        }
    }

    private fun isTurnActive(id: String): Boolean {
        if (serverIdle[id] == true) return false
        return hasFreshTurn(id)
    }

    /** Fresh-turn check shared by the stop button and the live thinking
     *  box: a live placeholder with recent activity. Orphans keep their
     *  late-`done` settle chance but earn neither icon nor box. */
    private fun hasFreshTurn(id: String): Boolean {
        return try {
            val msgs = chatStore.get(id)?.messages ?: return false
            runningTurnIds(msgs, pendingTurns[id]?.toSet() ?: emptySet(), serverIdle = false).isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun updateStopBtn() {
        if (!::stopBtn.isInitialized) return
        val id = activeSessionId
        // Stop follows FRESH live-turn state only: a placeholder with no
        // activity for 90s+ is an orphan (lost done), not a running turn.
        // The placeholder itself stays for a late done; only the button
        // hides, via the 30s refresh below. serverIdle is session-level
        // and goes stale across overlapping turns — it must never gate Stop.
        val active = id != null && hasFreshTurn(id)
        // Composer stop: its own slot inside the field, shown only while a
        // live turn is running. The mic is a separate button out by send, so
        // it always stays visible and never swaps out for the stop. While a
        // voice note records, the mic keeps its tap-to-stop behaviour.
        val showStop = active && !chatRecActive
        stopBtn.visibility = if (showStop) View.VISIBLE else View.GONE
        stopPulse?.cancel()
        stopPulse = null
        stopBtn.alpha = 1f
        progressHandler.removeCallbacks(stopRefresh)
        if (active) progressHandler.postDelayed(stopRefresh, 30_000)
        // Freshness transition with the list visible: one list re-render so
        // a stale "replying…" row clears the moment the stop does.
        if (stopWasActive && !active && !isThreadOpen()) renderSessions()
        stopWasActive = active
        refreshSendNowLinks()
    }

    /** Composer trailing slot: the waveform overlays the send arrow until
     *  there is something to send — typed text or a staged attachment —
     *  then the arrow alone shows through. Recording always keeps the
     *  waveform visible — it is the tap-to-stop control, so a typed draft
     *  must never hide it mid-recording. */
    private fun updateComposerSlot() {
        if (!::chatMic.isInitialized || !::composerInput.isInitialized) return
        val typed = composerInput.text?.toString()?.isNotBlank() == true
        val ready = typed || pendingAttaches.isNotEmpty() || quotes.isNotEmpty()
        chatMic.visibility = if (ready && !chatRecActive) View.GONE else View.VISIBLE
    }

    /** A reply is running and this chat can Send-now a waiting message.
     *  Opencode queueing is app-side (hold + local release), so it needs no
     *  server capability; other backends read the catalog flag. */
    private fun canSteer(id: String): Boolean {
        if (!hasFreshTurn(id)) return false
        val b = try { chatStore.get(id)?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
        if (Prefs.cannonBackend(b) == Prefs.BACKEND_OPENCODE) return true
        return capsForBackend(backendCatalog, b).steer
    }

    /** Turns waiting behind the running reply, oldest first. App-held
     *  entries (opencode sends behind a running reply) count everywhere;
     *  posted turns count only where the server queues them. */
    private fun waitingTurnIds(id: String): List<String> {
        return try {
            val msgs = chatStore.get(id)?.messages ?: return emptyList()
            val b = try { chatStore.get(id)?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
            waitingTurnIdsOf(
                msgs,
                Prefs.cannonBackend(b) == Prefs.BACKEND_OPENCODE,
                backendWaitingTurns[id]?.toList().orEmpty(),
                waitQueue[id]?.map { it.turnId }.orEmpty(),
                steeredTurnIds,
            )
        } catch (_: Exception) { emptyList() }
    }

    /** Send-now and discard controls under each message waiting behind the running reply. */
    private fun refreshSendNowLinks() {
        if (!::threadWeb.isInitialized || !threadPageLoaded) return
        val id = activeSessionId
        // Nothing is running here, so nothing is waiting: forget this chat's folded turns.
        if (id != null && steeredTurnIds.isNotEmpty() && !hasFreshTurn(id)) {
            try { chatStore.get(id)?.messages?.forEach { steeredTurnIds.remove(it.turnId) } } catch (_: Exception) {}
        }
        val waiting = if (id != null && isThreadOpen() && canSteer(id)) waitingTurnIds(id) else emptyList()
        val key = (id ?: "") + "|" + waiting.joinToString(",")
        if (key == sendNowKey) return
        sendNowKey = key
        val turns = org.json.JSONArray(waiting).toString()
        val chat = org.json.JSONObject.quote(id ?: "")
        threadWeb.evaluateJavascript(
            "(function(w,c){try{" +
                "document.querySelectorAll('.row.user.queued-message').forEach(function(r){if(w.indexOf(r.getAttribute('data-turn'))<0){r.classList.remove('queued-message');var old=r.querySelector('.sendnow-undo');if(old)old.remove();}});" +
                "document.querySelectorAll('.sendnow').forEach(function(e){if(w.indexOf(e.getAttribute('data-turn'))<0)e.remove();});" +
                "w.forEach(function(t){var r=document.querySelector('.row.user[data-turn=\"'+t+'\"]');if(!r)return;" +
                "r.classList.add('queued-message');if(!r.querySelector('.sendnow-undo')){var u=document.createElement('a');u.className='sendnow-undo';u.href='terminus://discardqueued?chat='+encodeURIComponent(c)+'&turn='+encodeURIComponent(t);u.setAttribute('aria-label','Discard queued message');u.setAttribute('title','Discard queued message');u.innerHTML='<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M6 6l12 12M18 6L6 18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>';r.appendChild(u);}" +
                "var n=r.nextElementSibling;if(n&&n.classList.contains('sendnow'))return;" +
                "var d=document.createElement('div');d.className='sendnow';d.setAttribute('data-turn',t);" +
                "var a=document.createElement('a');a.href='terminus://sendnow?chat='+encodeURIComponent(c)+'&turn='+encodeURIComponent(t);" +
                "a.textContent='Send now';d.appendChild(a);r.parentNode.insertBefore(d,r.nextSibling);});" +
                "}catch(e){}})($turns,$chat);",
            null,
        )
    }

    /** Discard one waiting message. Local /wait rows are removed immediately;
     *  backend-held turns are removed only after the backend confirms they
     *  were still queued, so tapping × cannot interrupt a turn that started. */
    private fun discardQueuedTurn(chat: String, turn: String) {
        val local = waitQueue[chat]
        val idx = local?.indexOfFirst { it.turnId == turn } ?: -1
        if (idx >= 0 && local != null) {
            local.removeAt(idx)
            if (local.isEmpty()) waitQueue.remove(chat)
            try { WaitStore(this).put(chat, local.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }, it.turnId) }) } catch (_: Exception) {}
            chatStore.discardQueuedTurn(chat, turn)
            afterDiscardQueued(chat)
            return
        }
        val backend = chatBackendOf(chat)
        Thread {
            val cancelled = try { OpencodeApi.postCancelQueued(prefs.serverUrl(), prefs.token(), chat, turn, backend) }
                catch (e: Exception) {
                    android.util.Log.d("terminus", "discard queued failed: ${e.message}")
                    null
                }
            runOnUiThread {
                if (cancelled == null) {
                    toast("Couldn't reach the server; message is still queued")
                    return@runOnUiThread
                }
                if (!cancelled) {
                    toast("That message has already started and can't be discarded")
                    return@runOnUiThread
                }
                chatStore.discardQueuedTurn(chat, turn)
                pendingTurns[chat]?.remove(turn)
                markDeliveredBulk(chat, listOf(turn))
                turnRestore.remove(turn)
                dropTurnBuf(turn)
                afterDiscardQueued(chat)
            }
        }.start()
    }

    private fun afterDiscardQueued(chat: String) {
        try { chatStore.flushNow() } catch (_: Exception) {}
        if (activeSessionId == chat) renderThread(chat)
        renderSessions()
        updateStopBtn()
        reconcileTurnState(chat)
        refreshSendNowLinks()
    }

    /** Release one explicitly queued message with a normal POST now. The row
     *  is reused in place (no second bubble): failure status, retry and edit
     *  attach like any direct send. */
    private fun releaseQueuedTurn(chat: String, turn: String, steer: Boolean = false): Boolean {
        val q = waitQueue[chat] ?: return false
        val idx = q.indexOfFirst { it.turnId == turn }
        if (idx < 0) return false
        val p = q.removeAt(idx)
        try { WaitStore(this).put(chat, q.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
        updateStopBtn()
        sendVisibleTurn(chat, p.text, p.files, displayText = p.text, appendUser = false, reuseUserTurnId = p.turnId, steer = steer)
        return true
    }

    /** Tap on "Send now": ask the server to fold that waiting message into the running reply. */
    private fun sendNowTap(chat: String, turn: String) {
        // The link goes at once: a tap means "go", never a second bubble.
        // If it didn't take, it comes back below.
        steeredTurnIds.add(turn)
        refreshSendNowLinks()
        // App-held queue: release locally with a normal POST now. Server-held
        // (codex): fold via /api/steer.
        if ((waitQueue[chat]?.any { it.turnId == turn }) == true) {
            val backend = chatBackendOf(chat)
            val steer = backend == Prefs.BACKEND_CODEX && canSteer(chat)
            runOnUiThread {
                if (!releaseQueuedTurn(chat, turn, steer)) steeredTurnIds.remove(turn)
                refreshSendNowLinks()
            }
            return
        }
        val backend = chatBackendOf(chat)
        Thread {
            val folded = try {
                OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), chat, turn, backend)
            } catch (e: Exception) {
                android.util.Log.d("terminus", "send now failed: ${e.message}")
                false
            }
            runOnUiThread {
                if (folded) steeredTurnIds.add(turn) else { steeredTurnIds.remove(turn); toast("Couldn't send now, it stays in line") }
                refreshSendNowLinks()
            }
        }.start()
    }

    private fun markDelivered(id: String, turnId: String): Boolean {
        // Check-then-add, genuinely atomic (synchronized: the stream thread
        // and UI thread race duplicate redeliveries here — an unsynchronized
        // check passes twice and appends twin rows). A turnId replay is a
        // hard dupe; anything else proceeds — messageID twins are handled
        // downstream by the settledMessages merge rule. No nested locks
        // anywhere in this file, so no ordering hazard.
        if (turnId.isBlank()) return true
        synchronized(deliveredTurns) {
            val now = System.currentTimeMillis()
            val turns = deliveredTurns.getOrPut(id) { mutableMapOf() }
            turns.entries.removeAll { now - it.value > deliveredTtlMs }
            if (turns.containsKey(turnId)) return false
            turns[turnId] = now
            return true
        }
    }

    /** Pour known in-flight turns into dedupe (stop/settle paths pour, never
     *  check): same TTL + prune as the checked path. */
    private fun markDeliveredBulk(id: String, turnIds: List<String>) {
        synchronized(deliveredTurns) {
            val now = System.currentTimeMillis()
            val turns = deliveredTurns.getOrPut(id) { mutableMapOf() }
            for (t in turnIds) if (t.isNotBlank()) turns[t] = now
            turns.entries.removeAll { now - it.value > deliveredTtlMs }
        }
    }

    /** Claim a settled messageID for the merge rule (cap 50/chat). Shared
     *  by every settle path so late dones and replays see the same claims. */
    private fun claimMessageId(id: String, messageID: String) {
        if (messageID.isBlank()) return
        synchronized(settledMessages) {
            val sm = settledMessages.getOrPut(id) { mutableSetOf() }
            sm.add(messageID)
            while (sm.size > 50) { sm.remove(sm.first()); }
        }
    }

    /** Stage a quote (selection Quote, swipe, or the row menu), then ask for its optional note. Quotes pile up
     *  in the strip above the composer and all go out with the next send. */
    @Suppress("UNUSED_PARAMETER")
    private fun showQuote(author: String, excerpt: String) {
        val clean = QuoteDraft.clean(excerpt)
        if (clean.isBlank()) return
        val existing = quotes.firstOrNull { it.excerpt == clean }
        if (existing == null) {
            if (quotes.size >= QuoteDraft.MAX_QUOTES) { toast("Up to ${QuoteDraft.MAX_QUOTES} quotes per message"); return }
            quotes.add(QuoteItem(clean))
        }
        openQuoteEditor(existing ?: quotes.last(), isNew = existing == null)
    }

    private fun renderQuotes() {
        if (!::quoteBar.isInitialized) return
        quoteChips.removeAllViews()
        val d = resources.displayMetrics.density
        for (q in quotes) {
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.quote_chip)
                setPadding((10 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (6 * d).toInt() }
                setOnClickListener { openQuoteEditor(q, isNew = false) }
                contentDescription = "quote: ${q.excerpt.take(60)}"
            }
            val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            // Note first when there is one; otherwise just the quote, one line.
            if (q.comment.isNotBlank()) col.addView(TextView(this).apply {
                text = q.comment
                setTextColor(getColor(R.color.t_ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                maxWidth = (200 * d).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = "\u275D " + q.excerpt.replace('\n', ' ')
                setTextColor(getColor(R.color.t_quote))
                maxLines = 1
                maxWidth = (200 * d).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(col)
            card.addView(TextView(this).apply {
                text = "✕"
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.t_muted))
                layoutParams = android.widget.LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
                contentDescription = "remove quote"
                setOnClickListener {
                    quotes.remove(q)
                    if (editingQuote === q) closeQuoteEditor() else renderQuotes()
                }
            })
            quoteChips.addView(card)
        }
        quoteBar.visibility = if (quotes.isEmpty() && editingQuote == null) View.GONE else View.VISIBLE
        // While a note is open the editor already shows its quote: the chip row would only repeat it and squeeze
        // the thread. One quote needs no "clear all" next to its own ✕.
        findViewById<View>(R.id.quoteChipRow).visibility = if (editingQuote != null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.quoteClear).visibility = if (quotes.size > 1) View.VISIBLE else View.GONE
        updateComposerSlot()
        if (quotes.isNotEmpty()) quoteChips.post { (quoteChips.parent as? android.widget.HorizontalScrollView)?.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Optional note on one quote, inline above the chips (no modal). */
    private fun openQuoteEditor(q: QuoteItem, isNew: Boolean) {
        editingQuote = q
        editingQuoteIsNew = isNew
        findViewById<TextView>(R.id.quoteEditExcerpt).text = q.excerpt.replace('\n', ' ')
        quoteNote.setText(q.comment)
        quoteNote.setSelection(q.comment.length)
        quoteEditor.visibility = View.VISIBLE
        renderQuotes()
        quoteNote.requestFocus()
        quoteNote.post {
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(quoteNote, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun saveQuoteNote() {
        editingQuote?.comment = quoteNote.text.toString().trim()
        closeQuoteEditor()
    }

    private fun closeQuoteEditor() {
        editingQuote = null
        editingQuoteIsNew = false
        if (::quoteEditor.isInitialized) {
            quoteEditor.visibility = View.GONE
            quoteNote.setText("")
            if (::composerInput.isInitialized) composerInput.requestFocus()
        }
        renderQuotes()
    }

    private fun clearQuote() {
        quotes.clear()
        closeQuoteEditor()
    }

    /** True for URLs served by our server (gallery/files/ops/notes/echo),
     *  on Tailnet (LAN fallback host only when the flag is on). */
    private fun isServerUrl(u: String): Boolean {
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val base = prefs.serverUrl().trimEnd('/')
        if (base.isNotBlank() && u.startsWith(base)) return true
        if (OpencodeApi.LAN_FALLBACK_ENABLED && u.startsWith(OpencodeApi.LAN_BASE)) return true
        return ChatMarkdown.serverLocalPath(u)
    }

    /** Route an attachment/file URL exactly like a thread tap: images in
     *  the viewer, server text in-app, everything else to the system. */
    private fun routeAttachmentUrl(raw: String): Boolean {
        val u = raw.trim()
        if (u.isBlank()) return false
        val low = u.lowercase().substringBefore("?").substringBefore("#")
        val isImg = low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
            low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
        if (isImg && (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://"))) {
            openViewer(if (u.startsWith("file://")) u else authedUrl(u))
            return true
        }
        if (isServerUrl(u)) {
            if (isTextFile(u)) openTextFile(authedUrl(u))
            else openExternal(authedUrl(u))
            return true
        }
        if (u.startsWith("file://")) {
            if (isTextFile(u)) openTextFile(u)
            else if (low.endsWith(".pdf")) openExternal(u) else openViewer(u)
            return true
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            openExternal(u)
            return true
        }
        return false
    }

    /** 📎 in the thread header: every attachment in this chat, newest
     *  first. Button shows only when the chat actually has attachments. */
    private fun showChatAttachments(id: String) {
        // Field attachments (you sent) + files the assistant mentioned in
        // prose (it sent): same sheet, identical URL mapping. Retroactive —
        // old chats gain their agent artifacts with no migration.
        val atts = try {
            val msgs = chatStore.get(id)?.messages ?: emptyList()
            fun isAvailableAttachment(url: String): Boolean {
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) return true
                if (!url.startsWith("file://", true)) return false
                return try {
                    File(java.net.URI(url)).isFile
                } catch (_: Exception) {
                    // Older saved paths may not be URI-escaped (for example,
                    // paths containing spaces); still check the actual path.
                    File(url.removePrefix("file://")).isFile
                }
            }
            val fielded = msgs.flatMap { m ->
                m.allAttachments.mapNotNull { raw ->
                    val a = raw.trim().takeUnless { it.isBlank() } ?: return@mapNotNull null
                    val url = if (a.startsWith("http://") || a.startsWith("https://") || a.startsWith("file://")) a
                        else "file://$a"
                    if (isAvailableAttachment(url)) Pair(fileNameOf(url), url) else null
                }
            }
            val mentioned = msgs
                .filter { it.role == "assistant" }
                .flatMap { extractMentionedFiles(it.text) }
                .mapNotNull { p ->
                    val url = if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("file://")) p
                        else "file://$p"
                    if (isAvailableAttachment(url)) Pair(fileNameOf(url), url) else null
                }
            (fielded + mentioned).distinctBy { it.second }.asReversed()
        } catch (_: Exception) { emptyList() }
        if (atts.isEmpty()) {
            android.widget.Toast.makeText(this, "no attachments in this chat", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val shownAtts = atts.toMutableList()
        fun openAttachment(which: Int) {
            val pair = shownAtts.getOrNull(which) ?: return
            val (name, url) = pair
            val low = url.lowercase().substringBefore("?").substringBefore("#")
            val previewable = isTextFile(url) || low.endsWith(".pdf") ||
                low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
                low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")
            if (previewable) {
                // Images open in the gallery viewer (swipe across all chat
                // images); text reads in-app; PDFs try the system handler.
                if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
                    low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".avif")) {
                    val imgs = atts.filter { (_, u) ->
                        val l = u.lowercase().substringBefore("?").substringBefore("#")
                        l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") ||
                            l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".avif")
                    }
                    val at = imgs.indexOfFirst { it.second == url }.coerceAtLeast(0)
                    openViewerGallery(imgs, at)
                } else routeAttachmentUrl(url)
            } else {
                // No in-app preview and possibly no system handler (e.g. no
                // PDF reader): download first, system-open second.
                showSelectSheet(name, listOf(
                    SelectRow("Share", "Send this file to another app", action = { shareAttachment(name, url) }),
                    SelectRow("Download", "No in-app preview for this file type", action = { downloadUrl(name, url) }),
                    SelectRow("Open with…", action = { routeAttachmentUrl(url) }),
                ))
            }
        }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val content = layoutInflater.inflate(R.layout.sheet_simple_menu, null)
        sheet.setContentView(content)
        content.findViewById<TextView>(R.id.menuTitle).text = "Attachments (${atts.size})"
        val list: android.widget.ListView = content.findViewById(R.id.menuList)
        list.divider = null
        fun attachmentCategory(name: String): String {
            val mime = MediaFiles.mimeFor(name).lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                SessionAdapter.isImagePath(name) -> "Images"
                mime.startsWith("video/") -> "Video"
                mime.startsWith("audio/") -> "Audio"
                mime.startsWith("text/") || mime == "application/pdf" ||
                    ext in setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv", "json") -> "Documents"
                else -> "Files"
            }
        }
        val categories = listOf("Images", "Video", "Audio", "Documents", "Files")
            .filter { category -> atts.any { attachmentCategory(it.first) == category } }
        var selectedCategory = if ("Images" in categories) "Images" else categories.first()
        var attachPick: (Pair<String, String>) -> Unit = {}
        val adapter = object : android.widget.ArrayAdapter<Pair<String, String>>(this, 0, shownAtts) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_attachment, parent, false)
                val (name, url) = getItem(position) ?: return row
                val image: ImageView = row.findViewById(R.id.attachmentThumb)
                val type: TextView = row.findViewById(R.id.attachmentType)
                row.findViewById<TextView>(R.id.attachmentName).text = name
                row.findViewById<TextView>(R.id.attachmentMeta).text = attachmentMeta(name, url)
                val item = Pair(name, url)
                row.setOnClickListener { attachPick(item) }
                row.findViewById<View>(R.id.attachmentShareButton).setOnClickListener {
                    shareAttachment(name, url)
                }
                image.tag = null
                image.setImageDrawable(null)
                image.visibility = View.GONE
                type.visibility = View.VISIBLE
                type.text = attachmentTypeMark(name)
                if (SessionAdapter.isImagePath(url)) bindAttachmentThumbnail(url, image, type)
                return row
            }
        }
        list.adapter = adapter
        val tabs = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            addView(tabs, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        (content as android.widget.LinearLayout).addView(tabScroll, 1,
            android.widget.LinearLayout.LayoutParams(-1, -2))
        val title = content.findViewById<TextView>(R.id.menuTitle)
        val tabViews = linkedMapOf<String, TextView>()
        fun selectCategory(category: String) {
            selectedCategory = category
            shownAtts.clear()
            shownAtts.addAll(atts.filter { attachmentCategory(it.first) == category })
            adapter.notifyDataSetChanged()
            title.text = "Attachments · $category (${shownAtts.size})"
            tabViews.forEach { (label, view) ->
                val chosen = label == category
                view.setTextColor(getColor(if (chosen) R.color.t_ink else R.color.t_muted))
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(getColor(if (chosen) R.color.t_chip else R.color.t_surface))
                    setStroke(dp(1), getColor(R.color.t_line))
                }
            }
        }
        categories.forEach { category ->
            val count = atts.count { attachmentCategory(it.first) == category }
            val tab = TextView(this).apply {
                text = "$category  $count"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { selectCategory(category) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            tabs.addView(tab, lp)
            tabViews[category] = tab
        }
        selectCategory(selectedCategory)
        fun pickAttachment(pick: Pair<String, String>) {
            sheet.dismiss()
            // Post so the sheet's window is gone before the viewer/dialog opens on top of it.
            // Main-thread handler, not list.post: the list is detached once the sheet dismisses, and a
            // view post on a detached view never runs.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { openAttachment(shownAtts.indexOf(pick).coerceAtLeast(0)) }
                catch (e: Exception) {
                    android.widget.Toast.makeText(this, "couldn't open ${pick.first}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Rows carry their own click: a focusable/clickable child (the share button) can swallow the
        // ListView's item click, so the row handles the tap itself and the list listener is only a fallback.
        attachPick = { pickAttachment(it) }
        list.setOnItemClickListener { _, _, position, _ -> shownAtts.getOrNull(position)?.let { pickAttachment(it) } }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    private fun attachmentTypeMark(name: String): String {
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase()
        return when {
            SessionAdapter.isImagePath(name) -> ext?.take(4) ?: "IMAGE"
            MediaFiles.mimeFor(name).startsWith("video/") -> "VIDEO"
            MediaFiles.mimeFor(name).startsWith("audio/") -> "AUDIO"
            ext != null -> ext.take(4)
            else -> "FILE"
        }
    }

    private fun attachmentMeta(name: String, url: String): String {
        val mime = MediaFiles.mimeFor(name)
        val kind = when {
            SessionAdapter.isImagePath(name) -> "Image"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            mime == "application/pdf" -> "PDF document"
            mime.startsWith("text/") -> "Text document"
            else -> "File"
        }
        val size = try {
            val file = if (url.startsWith("file://")) File(url.removePrefix("file://")) else null
            file?.takeIf { it.isFile }?.length()?.let { bytes ->
                if (bytes < 1024L) "$bytes B" else if (bytes < 1024L * 1024L)
                    "${bytes / 1024L} KB" else "${bytes / (1024L * 1024L)} MB"
            }
        } catch (_: Exception) { null }
        return if (size == null) kind else "$kind · $size"
    }

    /** Small, bounded thumbnail reads for the attachment sheet. Server
     *  credentials are sent only to the configured Terminus server. */
    private fun bindAttachmentThumbnail(url: String, image: ImageView, type: TextView) {
        image.tag = url
        attachmentPreviewCache.get(url)?.let { bitmap ->
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            type.visibility = View.GONE
            return
        }
        attachmentPreviewExec.execute {
            val bitmap = loadAttachmentThumbnail(url)
            if (bitmap != null) attachmentPreviewCache.put(url, bitmap)
            image.post {
                if (image.tag != url) return@post
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    type.visibility = View.GONE
                }
            }
        }
    }

    private fun loadAttachmentThumbnail(url: String): android.graphics.Bitmap? {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
                connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    if (isServerUrl(url)) {
                        val token = prefs.token()
                        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                if (connection!!.responseCode !in 200..299) return null
                connection!!.inputStream.use { readPreviewBytes(it) } ?: return null
            } else {
                val file = File(url.removePrefix("file://"))
                if (!file.isFile || file.length() <= 0 || file.length() > 12L * 1024 * 1024) return null
                file.inputStream().use { readPreviewBytes(it) } ?: return null
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256 && sample < 32) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readPreviewBytes(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > 12 * 1024 * 1024) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun shareAttachment(name: String, url: String) {
        pruneShareExports()
        Thread {
            var export: File? = null
            var downloadConnection: java.net.HttpURLConnection? = null
            try {
                val dir = File(cacheDir, "share-out").apply { mkdirs() }
                val safeName = name.ifBlank { "attachment" }.takeLast(120)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
                export = File(dir, "${System.currentTimeMillis()}_$safeName")
                val input = when {
                    url.startsWith("file://") -> File(url.removePrefix("file://")).inputStream()
                    !url.startsWith("http://") && !url.startsWith("https://") -> File(url).inputStream()
                    else -> {
                        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            instanceFollowRedirects = true
                            if (isServerUrl(url)) {
                                val token = prefs.token()
                                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                            }
                        }
                        downloadConnection = conn
                        if (conn.responseCode !in 200..299) {
                            conn.disconnect()
                            downloadConnection = null
                            throw java.io.IOException("download failed")
                        }
                        conn.inputStream
                    }
                }
                try {
                    input.use { source ->
                        export!!.outputStream().use { sink ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 50L * 1024 * 1024) throw java.io.IOException("file is over 50 MB")
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    downloadConnection?.disconnect()
                    downloadConnection = null
                }
                if (export!!.length() <= 0) throw java.io.IOException("empty file")
                runOnUiThread {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "$packageName.files", export!!)
                        val mime = android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                            ?: MediaFiles.mimeFor(name)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = mime.ifBlank { "application/octet-stream" }
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, name)
                            clipData = android.content.ClipData.newUri(contentResolver, name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Share attachment").apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                        val cleanup = export!!
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            { try { cleanup.delete() } catch (_: Exception) {} },
                            24L * 60 * 60 * 1000)
                    } catch (_: Exception) {
                        try { export?.delete() } catch (_: Exception) {}
                        toast("couldn't share attachment")
                    }
                }
            } catch (e: Exception) {
                try { downloadConnection?.disconnect() } catch (_: Exception) {}
                try { export?.delete() } catch (_: Exception) {}
                runOnUiThread { toast(if (e.message?.contains("50 MB") == true) e.message!! else "couldn't prepare attachment") }
            }
        }.start()
    }

    /** Cache exports are temporary handoff copies, never attachment storage.
     *  Keep them available long enough for another app to consume the URI,
     *  then prune on launch and before the next share. */
    private fun pruneShareExports() {
        try {
            val dir = File(cacheDir, "share-out")
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            files.filter { it.lastModified() < cutoff }.forEach {
                try { it.delete() } catch (_: Exception) {}
            }
            var kept = 0L
            files.filter { it.exists() && it.lastModified() >= cutoff }.forEach { file ->
                kept += file.length()
                if (kept > 100L * 1024 * 1024) try { file.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun shareIconButton(tint: Int): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_share)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        contentDescription = "Share attachment"
        val value = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
            setBackgroundResource(value.resourceId)
        } else setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    /** URLs stay clean: in-app auth rides the cookie jar (see
     *  ensureServerCookie), never ?token=. External handoffs get the same
     *  plain URL — no secret minted anywhere in-app, by anyone. */
    private fun authedUrl(u: String): String = u

    /** Cookie auth for in-app WebViews: the pass lives in the jar, never
     *  in URLs. Planted on boot and every token save; cleared with a
     *  blank token. Media requests from a local page can count
     *  third-party, so every WebView also gets tuneAuthWebView. */
    private fun ensureServerCookie() {
        try {
            val cm = android.webkit.CookieManager.getInstance()
            val base = prefs.serverUrl().trimEnd('/')
            if (base.isBlank()) return
            val tok = prefs.token()
            cm.setCookie(base, if (tok.isBlank()) "headless-token=; Max-Age=0; Path=/"
                else "headless-token=$tok; Path=/")
            try { cm.flush() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun tuneAuthWebView(w: WebView) {
        try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (_: Exception) {}
        ensureServerCookie()
    }

    /** Extensions the WebView cannot render inline: tapping them must
     *  download, never loadUrl (an intercepted PDF renders as a blank
     *  page and the download listener never fires for it). */
    private fun isDownloadFile(u: String): Boolean {
        val low = u.lowercase().substringBefore("?").substringBefore("#")
        return low.endsWith(".pdf") || low.endsWith(".doc") || low.endsWith(".docx") ||
            low.endsWith(".xls") || low.endsWith(".xlsx") || low.endsWith(".ppt") ||
            low.endsWith(".pptx") || low.endsWith(".zip") || low.endsWith(".rar") ||
            low.endsWith(".7z") || low.endsWith(".gz") || low.endsWith(".apk") ||
            low.endsWith(".mp3") || low.endsWith(".wav") || low.endsWith(".ogg") ||
            low.endsWith(".m4a") || low.endsWith(".flac") || low.endsWith(".epub") ||
            low.endsWith(".csv")
    }

    /** DownloadManager with the bearer header (shares and gallery files
     *  carry cookie auth the download service doesn't have). */
    private fun downloadFile(url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        try {
            val tok = try { prefs.token() } catch (_: Exception) { "" }
            val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE)
                as android.app.DownloadManager
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime)
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                if (tok.isNotBlank()) addRequestHeader("Authorization", "Bearer $tok")
                if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(name)
                setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name)
                allowScanningByMediaScanner()
            }
            dm.enqueue(req)
            android.widget.Toast.makeText(this, "downloading $name",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { echoSub.text = "download blocked" }
    }

    /** Fetch a server URL with the bearer header and hand WebView the bytes.
     *  GET-only: POSTs (gallery uploads) must reach the default loader
     *  untouched, otherwise the body dies here and the page reports a
     *  fake success. The page carries its own bearer header on uploads.
     *  Null for anything not ours (default loader handles it) and on any
     *  failure (falls back to cookie auth). Runs on a WebView thread;
     *  prefs reads are safe there. The original request headers ride along
     *  (Accept especially: the gallery shell serves HTML only to text/html),
     *  with Authorization ours. */
    private fun authedResponse(u: String, headers: Map<String, String>, method: String = "GET", allowCache: Boolean = true): android.webkit.WebResourceResponse? {
        try {
            if (!method.equals("GET", true)) return null
            if (!u.startsWith("http://") && !u.startsWith("https://")) return null
            if (!isServerUrl(u)) return null
            val tok = try { prefs.token() } catch (_: Exception) { "" }
            if (tok.isBlank()) return null
            // Pictures already fetched once come straight from disk: this path bypasses the WebView's own cache,
            // so every chat open re-downloaded every image over the tailnet (slow away from home wifi).
            val cacheable = allowCache && headers["Range"].isNullOrBlank() && WebAssetCache.eligible(u)
            if (cacheable) WebAssetCache.get(this, u)?.let { WebAssetCache.revalidate(this, u, tok); return it }
            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            for ((k, v) in headers) {
                if (k.equals("Authorization", true)) continue
                if (k.equals("Range", true)) continue
                conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty("Authorization", "Bearer $tok")
            headers["Range"]?.takeUnless { it.isBlank() }?.let { conn.setRequestProperty("Range", it) }
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            val code = conn.responseCode
            if (code != 200 && code != 206) return null
            val mime = conn.contentType?.substringBefore(";")?.trim()
                ?.takeIf { it.contains("/") }
                ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(u.substringAfterLast(".", "").substringBefore("?").lowercase())
                ?: "application/octet-stream"
            if (cacheable && code == 200 && mime.startsWith("image/")) {
                val len = conn.contentLength
                if (len in 1..WebAssetCache.MAX_FILE) {
                    WebAssetCache.put(this, u, mime, conn.inputStream, len)?.let { return it }
                    // The body was (partly) consumed by the failed store: fetch it again, uncached.
                    try { conn.disconnect() } catch (_: Exception) {}
                    return authedResponse(u, headers, method, allowCache = false)
                }
            }
            val headers = mutableMapOf<String, String>()
            conn.headerFields["Content-Range"]?.firstOrNull()?.let { headers["Content-Range"] = it }
            conn.headerFields["Accept-Ranges"]?.firstOrNull()?.let { headers["Accept-Ranges"] = it }
            conn.headerFields["Content-Length"]?.firstOrNull()?.let { headers["Content-Length"] = it }
            return android.webkit.WebResourceResponse(
                mime, null, code,
                if (code == 206) "Partial Content" else "OK",
                headers, conn.inputStream)
        } catch (_: Exception) {
            return null
        }
    }

    /** True for .txt/.md/.markdown taps (query/fragment ignored). */
    private fun isTextFile(u: String): Boolean {
        val low = u.lowercase().substringBefore("?").substringBefore("#")
        return low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".markdown")
    }

    private fun fileNameOf(u: String): String = try {
        val p = u.substringBefore("?").substringBefore("#").substringAfterLast("/")
        java.net.URLDecoder.decode(p, "UTF-8").takeIf { it.isNotBlank() } ?: "file"
    } catch (_: Exception) {
        u.substringBefore("?").substringBefore("#").substringAfterLast("/").takeIf { it.isNotBlank() } ?: "file"
    }

    /** In-app text sheet: fetch bytes (token auto-attached by caller),
     *  2MB cap (larger → offer system-open). txt = pre-wrapped monospace;
     *  md = ChatMarkdown thread CSS, read-only. ✕ closes, no thread nav. */
    private fun openTextFile(url: String) {
        Thread {
            try {
                val isLocal = url.startsWith("file://")
                val name = fileNameOf(url)
                val markdown = url.lowercase().substringBefore("?").substringBefore("#")
                    .let { it.endsWith(".md") || it.endsWith(".markdown") }
                val bytes: ByteArray = if (isLocal) {
                    val f = java.io.File(java.net.URI(url))
                    if (f.length() > 2 * 1024 * 1024) {
                        runOnUiThread { offerSystemOpen(name, url) }
                        return@Thread
                    }
                    f.readBytes()
                } else {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    conn.connect()
                    val len = conn.contentLengthLong
                    if (len > 2 * 1024 * 1024) {
                        try { conn.disconnect() } catch (_: Exception) {}
                        runOnUiThread { offerSystemOpen(name, url) }
                        return@Thread
                    }
                    val cap = 2 * 1024 * 1024
                    val out = java.io.ByteArrayOutputStream()
                    try {
                        conn.inputStream.use { ins ->
                            val buf = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > cap) {
                                    runOnUiThread { offerSystemOpen(name, url) }
                                    return@Thread
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    } finally {
                        try { conn.disconnect() } catch (_: Exception) {}
                    }
                    out.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8)
                runOnUiThread { openTextViewer(name, markdown, text, url) }
            } catch (_: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "can't open file", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** In-app HTML viewer: bottom sheet with a sandboxed WebView + close.
     *  Scripts run (pages need them) but file access is off and no bridges
     *  are added; back/outside-tap dismisses, so a page can never trap a
     *  tab. Falls back to system-open on any failure. */
    private fun openHtmlViewer(url: String) {
        try {
            val ctx = this
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.CreamBottomSheet)
            val dens = resources.displayMetrics.density
            val ink = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_ink)
            val cream = androidx.core.content.ContextCompat.getColor(ctx, R.color.t_surface)
            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(cream)
            }
            val header = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                val pad = (12 * dens).toInt()
                setPadding(pad, pad, pad, (4 * dens).toInt())
            }
            val title = TextView(ctx).apply {
                text = fileNameOf(url)
                setTextColor(ink)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val close = TextView(ctx).apply {
                text = "✕ close"
                setTextColor(ink)
                textSize = 15f
                setPadding((12 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt(), (4 * dens).toInt())
                setOnClickListener { try { sheet.dismiss() } catch (_: Exception) {} }
            }
            val share = shareIconButton(ink).apply {
                setOnClickListener { shareAttachment(fileNameOf(url), url) }
            }
            header.addView(title)
            header.addView(share)
            header.addView(close)
            root.addView(header)
            val heightPx = (resources.displayMetrics.heightPixels * 0.75).toInt()
            val web = WebView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
            }
            tuneAuthWebView(web)
            root.addView(web)
            sheet.setContentView(root)
            sheet.show()
            // The sheet must not steal vertical drags: WebView is not a
            // nested-scrolling child, so a draggable sheet would dismiss on
            // scroll-up instead of scrolling the page. ✕ and back dismiss.
            try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
            try { web.loadUrl(url) } catch (_: Exception) {
                try { sheet.dismiss() } catch (_: Exception) {}
                openExternal(url)
            }
        } catch (_: Exception) {
            try { openExternal(url) } catch (_: Exception) {}
        }
    }

    /** Save bytes to public Downloads (MediaStore, no permission needed on
     *  Q+). Toast confirms; failures never throw into callers. */
    private fun downloadBytes(name: String, bytes: ByteArray, mime: String) {
        Thread {
            try {
                if (bytes.isEmpty()) {
                    runOnUiThread { android.widget.Toast.makeText(this, "nothing to download", android.widget.Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val safe = name.ifBlank { "download" }.replace(Regex("[/\\\\]"), "_")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/terminus")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("media store refused")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("media store write failed")
                runOnUiThread { android.widget.Toast.makeText(this, "saved to Downloads/terminus", android.widget.Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "download failed", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Fetch a file:// or http(s) URL (token auto-attached for server URLs),
     *  then save it to Downloads. 25MB cap. */
    private fun downloadUrl(name: String, url: String) {
        Thread {
            try {
                val bytes: ByteArray = if (url.startsWith("file://")) {
                    java.io.File(java.net.URI(url)).readBytes()
                } else {
                    val conn = java.net.URL(url).openConnection()
                        as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    // Cookie jar does not apply to raw connections: the pass
                    // travels as a header, never in the URL.
                    if (isServerUrl(url)) {
                        val tok = prefs.token()
                        if (tok.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $tok")
                    }
                    conn.connect()
                    if (conn.responseCode !in 200..299) throw java.io.IOException("http ${conn.responseCode}")
                    val out = java.io.ByteArrayOutputStream()
                    conn.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 25 * 1024 * 1024) throw java.io.IOException("over 25MB")
                            out.write(buf, 0, n)
                        }
                    }
                    out.toByteArray()
                }
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast(".", "").lowercase()) ?: ""
                downloadBytes(name, bytes, mime)
            } catch (_: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "download failed", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Over-2MB text: offer the system handler instead of in-app render. */
    private fun offerSystemOpen(name: String, url: String) {
        try {
            showSelectSheet(name, listOf(
                SelectRow("Share", "Send this file to another app", action = { shareAttachment(name, url) }),
                SelectRow("Open externally", "File is over 2 MB", action = { openExternal(url) }),
                SelectRow("Cancel"),
            ))
        } catch (_: Exception) {}
    }

    /** Text viewer sheet: title = filename, ✕ closes. Viewer WebView keeps
     *  text taps in-app; anything else leaves for the system handler. */
    private fun openTextViewer(name: String, markdown: Boolean, text: String, sourceUrl: String) {
        try {
            val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                    this@MainActivity, R.color.t_surface))
            }
            val bar = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            val title = android.widget.TextView(this).apply {
                this.text = name
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@MainActivity, R.color.t_ink))
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(16, 16, 8, 16)
            }
            val close = android.widget.Button(this).apply {
                this.text = "✕"
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@MainActivity, R.color.t_ink))
                setOnClickListener { dlg.dismiss() }
            }
            val dl = android.widget.Button(this).apply {
                this.text = "⤓"
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@MainActivity, R.color.t_ink))
                setOnClickListener { downloadBytes(name, text.toByteArray(), if (markdown) "text/markdown" else "text/plain") }
            }
            val share = shareIconButton(androidx.core.content.ContextCompat.getColor(
                this@MainActivity, R.color.t_ink)).apply {
                setOnClickListener { shareAttachment(name, sourceUrl) }
            }
            bar.addView(title, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(share, android.widget.LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(dl, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            bar.addView(close, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            val w = WebView(this).apply {
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
            tuneAuthWebView(w)
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    return authedResponse(req.url.toString(), req.requestHeaders, req.method)
                }
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url.toString()
                    if (isContactUrl(u)) {
                        openContactLink(u)
                        return true
                    }
                    if (isTextFile(u)) {
                        openTextFile(u)
                        return true
                    }
                    openExternal(u)
                    return true
                }
            }
            root.addView(bar, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(w, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f))
            dlg.setContentView(root)
            dlg.setCancelable(true)
            dlg.setOnDismissListener { try { w.destroy() } catch (_: Exception) {} }
            dlg.show()
            val html = if (markdown) {
                ChatMarkdown.threadHtml(name, "", listOf(ChatMessage("assistant", text)), LinkToken.forUrl(this@MainActivity, prefs), dark = isDark())
            } else {
                val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val bg = if (isDark()) "#1A1712" else "#F5F1E6"
                val fg = if (isDark()) "#ECE7DA" else "#262220"
                "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<style>body{font-family:monospace;font-size:15px;line-height:1.5;margin:0;padding:12px;" +
                    "background:$bg;color:$fg;overflow-wrap:break-word;word-wrap:break-word}" +
                    "pre{white-space:pre-wrap;word-break:break-word;margin:0;font-family:monospace}</style>" +
                    "</head><body><pre>" + esc + "</pre></body></html>"
            }
            w.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        } catch (_: Exception) {}
    }

    /** Full-screen image viewer with swipe/‹ › across the chat's images,
     *  counter in the bar, download next to close. Single-url callers get
     *  a one-item gallery (no arrows, download still works). */
    private fun openViewer(url: String) {
        openViewerGallery(listOf(Pair(fileNameOf(url), url)), 0)
    }

    private fun openViewerGallery(items: List<Pair<String, String>>, start: Int) {
        if (items.isEmpty()) return
        try {
            var idx = start.coerceIn(items.indices)
            val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val bar = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            fun label(): String {
                val name = items[idx].first.ifBlank { "image" }
                return if (items.size > 1) "$name (${idx + 1}/${items.size})" else name
            }
            val title = android.widget.TextView(this).apply {
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(16, 16, 8, 16)
            }
            val w = WebView(this).apply {
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
            tuneAuthWebView(w)
            fun show() {
                title.text = label()
                w.loadUrl(items[idx].second)
            }
            val prev = android.widget.Button(this).apply {
                text = "‹"
                setOnClickListener { if (idx > 0) { idx--; show() } }
            }
            val next = android.widget.Button(this).apply {
                text = "›"
                setOnClickListener { if (idx < items.size - 1) { idx++; show() } }
            }
            val dl = android.widget.Button(this).apply {
                text = "⤓"
                setOnClickListener { downloadUrl(items[idx].first, items[idx].second) }
            }
            val share = shareIconButton(android.graphics.Color.WHITE).apply {
                setOnClickListener { shareAttachment(items[idx].first, items[idx].second) }
            }
            val close = android.widget.Button(this).apply {
                text = "✕"
                setOnClickListener { dlg.dismiss() }
            }
            // Swipe across images: mostly-horizontal fling past 120px. Pinch
            // zoom still works — vertical or short gestures pass through.
            var downX = 0f
            var downY = 0f
            w.setOnTouchListener { _, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (kotlin.math.abs(dx) > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                            if (dx < 0 && idx < items.size - 1) { idx++; show() }
                            else if (dx > 0 && idx > 0) { idx--; show() }
                        }
                    }
                }
                false
            }
            // One client only: a second assignment silently replaces the first, which is what
            // adds the auth header to server images (they loaded blank without it).
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    return authedResponse(req.url.toString(), req.requestHeaders, req.method)
                }
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url.toString()
                    if (isContactUrl(u)) {
                        openContactLink(u)
                        return true
                    }
                    if (isTextFile(u)) {
                        openTextFile(if (u.startsWith("file://")) u else authedUrl(u))
                        return true
                    }
                    return false
                }
            }
            bar.addView(prev)
            bar.addView(title, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(next)
            bar.addView(share, android.widget.LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(dl)
            bar.addView(close)
            // Single image: arrows would be dead buttons — hide them.
            if (items.size < 2) {
                prev.visibility = View.GONE
                next.visibility = View.GONE
            }
            root.addView(bar, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(w, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f))
            dlg.setContentView(root)
            dlg.setCancelable(true)
            dlg.setOnDismissListener { try { w.destroy() } catch (_: Exception) {} }
            dlg.show()
            show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "couldn't open image (${e.message ?: "viewer error"})",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** PDFs and other files open outside the thread (system handler first;
     *  error notice when no handler exists — never thread navigation). */
    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "no app to open this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Scroll policy per render. New replies go to the bottom via instant
     *  in-engine JS (also injected in the HTML itself); progress-only
     *  re-renders restore the saved Y. Queuing cancels the previous render's
     *  posts first: stacked renders (open + title sync + inbox) used to pile
     *  animated pageDowns that landed at different heights — the open-chat
     *  shake. Latest render wins; delayed reposts survive image-driven
     *  height shifts. A real touch-drag on threadWeb cancels the pending post. */
    private fun scrollThreadToBottom() {
        threadTouchScroll = false
        pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
        val r = Runnable {
            threadWeb.evaluateJavascript("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));", null)
        }
        pendingThreadScroll = r
        threadScrollHandler.post(r)
        threadScrollHandler.postDelayed(r, 250)
        // Keyboard close/resize animates ~200-300ms and can eat the earlier
        // posts: one late repost lands after layout settles.
        threadScrollHandler.postDelayed(r, 500)
    }

    /** Open at a message: its top just under the header. Same repost rhythm as the bottom pin (layout
     *  and images shift heights); a touch cancels the posts, and the page script gives up on touch too. */
    private fun anchorThreadScroll(idx: Int) {
        threadTouchScroll = false
        pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
        val r = Runnable {
            threadWeb.evaluateJavascript(
                "(function(){var e=document.getElementById('m$idx');if(!e)return;" +
                    "window.scrollTo(0,Math.max(0,e.getBoundingClientRect().top+window.scrollY-8));})();",
                null)
        }
        pendingThreadScroll = r
        threadScrollHandler.post(r)
        threadScrollHandler.postDelayed(r, 250)
        threadScrollHandler.postDelayed(r, 500)
    }

    /** History auto-fetch guard: one in-flight grow per tap/scroll. */
    private var historyGrowing = false
    /** True once a finger drives the thread WebView. Resets on thread
     *  open: only user scrolls may auto-fetch history, never the
     *  programmatic motion of open/bottom-pin. */
    private var threadTouchScroll = false
    /** At most one history page per physical drag, including while a fling
     *  continues after ACTION_UP. */
    private var threadHistoryGestureUsed = false

    /** Grow rendered history keeping the first visible row anchored, so
     *  loading earlier (tap or scroll-up auto-fetch) never jumps to a
     *  random message. Falls back to plain grow when no row is visible. */
    private fun growHistory(id: String) {
        if (historyGrowing) return
        val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return
        val vis = threadVisible[id] ?: threadPageSize
        if (s.messages.size <= vis) return
        historyGrowing = true
        threadTouchScroll = false
        val find = "(function(){var els=document.querySelectorAll('[id^=\"m\"]');" +
            "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();" +
            "if(r.bottom>0){return els[i].id+':'+Math.round(r.top);}}return '';})()"
        try {
            threadWeb.evaluateJavascript(find) { res ->
                if (activeSessionId != id) {
                    historyGrowing = false
                    return@evaluateJavascript
                }
                val clean = (res ?: "").trim().trim('"')
                val anchor = clean.substringBefore(":").takeIf { it.startsWith("m") }
                val top = clean.substringAfter(":", "").toIntOrNull() ?: 0
                threadVisible[id] = vis + threadPageSize
                renderThread(id, scroll = false)
                if (anchor != null) {
                    pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (activeSessionId != id) {
                            historyGrowing = false
                            return@Runnable
                        }
                        threadTouchScroll = false
                        threadWeb.evaluateJavascript(
                            "(function(){var e=document.getElementById('$anchor');if(!e)return;" +
                                "var r=e.getBoundingClientRect();" +
                                "window.scrollTo(0,window.scrollY+r.top-($top));})();",
                            null)
                        historyGrowing = false
                    }
                    pendingThreadScroll = r
                    threadScrollHandler.post(r)
                    threadScrollHandler.postDelayed(r, 400)
                } else {
                    historyGrowing = false
                }
            }
        } catch (_: Exception) { historyGrowing = false }
    }

    /** Keep the user's reading position across a progress-only reload. */
    private fun restoreThreadScroll(cssY: Int) {
        threadTouchScroll = false
        pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
        val r = Runnable {
            threadWeb.evaluateJavascript("window.scrollTo(0, $cssY);", null)
        }
        pendingThreadScroll = r
        threadScrollHandler.post(r)
        threadScrollHandler.postDelayed(r, 60)
    }

    /** Slash autocomplete combines Terminus actions with the active backend's
     *  native actions. Backend commands are fetched on demand and never leak
     *  across chats using different backends. */
    private fun mergedCommands(): List<SlashCommand> {
        val seen = LinkedHashMap<String, SlashCommand>()
        ChatStore.COMMANDS.forEach { seen[it.name.lowercase()] = it }
        if (liveCommandsBackend == activeBackend()) {
            liveCommands.forEach { seen.putIfAbsent(it.name.lowercase(), it) }
        }
        if (activeBackend() == "codex") {
            codexCommands.forEach { seen.putIfAbsent(it.name.lowercase(), it) }
        }
        return seen.values.toList()
    }

    private val codexCommands = listOf(
        SlashCommand("/usage", "show Codex usage and reset credits"),
        SlashCommand("/resets", "list available Codex reset credits"),
        SlashCommand("/redeem", "redeem an available Codex reset credit"),
    )

    private val localSlashCommands: Set<String>
        get() = ChatStore.COMMANDS.map { it.name.lowercase() }.toSet()

    /** Backend commands and skills, refetched when the backend changes or the list is 5 min old, so new
     *  skills show up without a restart. A failed fetch keeps the old list and is retried on the next "/". */
    private fun refreshCommands(then: (() -> Unit)? = null) {
        val backend = activeBackend()
        val fresh = liveCommandsBackend == backend && System.currentTimeMillis() - liveCommandsAt < COMMANDS_TTL_MS
        if (fresh) { then?.invoke(); return }
        if (commandsLoading && then == null) return
        commandsLoading = true
        Thread {
            val fetched = try { OpencodeApi.fetchCommands(prefs.serverUrl(), prefs.token(), backend) } catch (_: Exception) { null }
            runOnUiThread {
                if (fetched != null && activeBackend() == backend) {
                    liveCommands = fetched
                    liveCommandsBackend = backend
                    liveCommandsAt = System.currentTimeMillis()
                }
                commandsLoading = false
                if (then != null) then() else renderSlashHints(composerInput.text.toString())
            }
        }.start()
    }

    private fun renderSlashHints(t: String) {
        if (completingSlash) return
        if (!isThreadOpen() || !t.startsWith("/")) {
            slashList.visibility = View.GONE
            return
        }
        // Args started: the token is complete, hide the list instead of
        // cluttering while the user types arguments.
        if (t.contains(" ") || t.contains("\n")) {
            slashList.visibility = View.GONE
            return
        }
        val q = t.split(" ").first().lowercase()
        refreshCommands()
        val matches = mergedCommands().filter { it.name.lowercase().startsWith(q) }
        if (matches.isEmpty()) {
            slashList.visibility = View.GONE
            return
        }
        val local = matches.filter { localSlashCommands.contains(it.name.lowercase()) }
        val skills = matches.filter { it.skill && !localSlashCommands.contains(it.name.lowercase()) }
        val backend = matches.filterNot { it.skill || localSlashCommands.contains(it.name.lowercase()) }
        val rows = mutableListOf<String>()
        val rowCommands = mutableListOf<SlashCommand?>()
        if (local.isNotEmpty()) {
            rows += "APP COMMANDS"
            rowCommands += null
            local.forEach { cmd ->
                rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
                rowCommands += cmd
            }
        }
        val backendCommandsKnown = liveCommandsBackend == activeBackend()
        if (backend.isNotEmpty() || backendCommandsKnown) {
            val backendLabel = when (activeBackend()) {
                "codex" -> "CODEX COMMANDS"
                "opencode" -> "OPENCODE COMMANDS"
                else -> "${activeBackend().uppercase()} COMMANDS"
            }
            rows += backendLabel
            rowCommands += null
            if (backend.isEmpty()) {
                rows += if (activeBackend() == "opencode") "  No custom commands available" else "  No backend commands available"
                rowCommands += null
            } else {
                backend.forEach { cmd ->
                    rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
                    rowCommands += cmd
                }
            }
        }
        if (skills.isNotEmpty()) {
            rows += "SKILLS"
            rowCommands += null
            skills.forEach { cmd ->
                rows += if (cmd.desc.isBlank()) "  ${cmd.name}" else "  ${cmd.name}  ·  ${cmd.desc}"
                rowCommands += cmd
            }
        }
        // Tab/enter completes the first row the user can see, in list order.
        slashShown = rowCommands.filterNotNull()
        slashRowCommands = rowCommands
        slashAdapter.clear()
        slashAdapter.addAll(rows)
        slashAdapter.notifyDataSetChanged()
        slashList.visibility = View.VISIBLE
    }

    /** Tap / enter completion: swap the slash token, keep any typed args. */
    private fun completeSlash(cmd: SlashCommand) {
        val cur = composerInput.text.toString()
        val rest = if (cur.contains(" ")) cur.substringAfter(" ").trim() else ""
        val done = cmd.name + if (rest.isNotBlank()) " $rest" else " "
        completingSlash = true
        try {
            composerInput.setText(done)
            composerInput.setSelection(done.length)
        } finally {
            completingSlash = false
        }
        composerInput.requestFocus()
    }

    private val chatPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val chatPoll = object : Runnable {
        override fun run() {
            if (!chatRecActive) return
            scanChatChunks()
            updateChatRecUI()
            chatPollHandler.postDelayed(this, 1000)
        }
    }

    /** Chat mic: dictate in-app (waits through pauses); tap again to stop. Falls back to the system popup. */
    private fun onChatMic() {
        if (dictating) { stopDictation(); return }
        if (!hasMic()) { toast("Allow the mic, then tap again"); askMic(); return }
        startDictation()
    }

    /** System speech popup: one phrase per tap. Used when this phone has no in-app recognizer. */
    private fun onChatMicIntent() {
        if (!isThreadOpen()) openDraft()
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
                "Message terminus — say ‘${prefs.voiceSendPhrase()}’ to send")
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }
        try {
            speechLauncher.launch(i)
        } catch (_: Exception) {
            toast("This phone has no speech recognition")
        }
    }

    private fun dictJoin(a: String, b: String): String =
        if (a.isBlank()) b else if (b.isBlank()) a else a.trimEnd() + " " + b

    /** If the rendered text currently ends with the send word, start (or keep) the send timer;
     *  anything else stands it down. Fired from the idle tick, so a send word that never gets a
     *  final result still sends after a short silence. */
    private fun armSendIfReady() {
        if (!dictating || !prefs.voiceSendEnabled()) { dictSendArmedAt = 0L; return }
        val phrase = Regex.escape(prefs.voiceSendPhrase())
        if (Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$").containsMatchIn(composerInput.text.toString())) {
            if (dictSendArmedAt == 0L) dictSendArmedAt = android.os.SystemClock.elapsedRealtime()
        } else dictSendArmedAt = 0L
    }

    /** The armed send word survived its silence: strip it and send what remains. */
    private fun fireArmedSend() {
        if (!dictating || dictSendArmedAt == 0L) return
        dictSendArmedAt = 0L
        commitPartial()
        renderDictation("")
        if (!dictating) return
        val phrase = Regex.escape(prefs.voiceSendPhrase())
        val sendSuffix = Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$")
        val said = composerInput.text.toString()
        if (!sendSuffix.containsMatchIn(said)) return
        dictDone = said.replace(sendSuffix, "").trimEnd().let { full ->
            if (dictBase.isNotBlank() && full.startsWith(dictBase)) full.removePrefix(dictBase).trim() else full
        }
        renderDictation("")
        if (!dictating) return
        stopDictation()
        if (composerInput.text.isNotBlank()) sendChat(voiceSendCommand = true)
    }

    /** The phrase heard so far becomes final text (a pause must never discard it). */
    private fun commitPartial() {
        if (dictPartial.isNotBlank()) dictDone = dictJoin(dictDone, dictPartial)
        dictPartial = ""
    }

    private fun firstWord(t: String): String =
        t.trimStart().substringBefore(' ').lowercase().trim('.', ',', '!', '?')

    private fun startDictation() {
        if (!isThreadOpen()) openDraft()
        replyVoice.stop()
        dictating = true
        dictBase = composerInput.text.toString()
        dictDone = ""
        dictRendered = dictBase
        dictPartial = ""
        dictSendArmedAt = 0L
        dictErrors = 0
        dictLastHeard = android.os.SystemClock.elapsedRealtime()
        setDictationUi(true)
        startMacDictation()
        dictHandler.postDelayed(dictIdleCheck, 500)
    }

    /** Dictation runs on saywhat on the Mac when it can (local, live text); the phone's recogniser takes
     *  over the moment it can't: Mac unreachable, saywhat missing, or the link dropping mid-dictation. */
    private fun startMacDictation() {
        val d = MacDictation(prefs.serverUrl(), prefs.token(),
            onEvent = { e -> if (dictating && macDict != null) { if (e.final) dictHeardFinal(e.text, relisten = false) else dictHeardPartial(e.text) } },
            onFail = { why, worked ->
                if (dictating && macDict != null) {
                    macDict = null
                    commitPartial(); renderDictation("")
                    if (worked) toast("Mac dictation stopped ($why); using the phone")
                    if (dictating) listenOnce()
                }
            })
        macDict = d
        d.start()
    }

    private fun listenOnce() {
        if (!dictating) return
        // No in-app recogniser on this phone: the system popup, one phrase per tap.
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { stopDictation(); onChatMicIntent(); return }
        val r = try {
            recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(dictListener); recognizer = it
            }
        } catch (_: Exception) { stopDictation(); onChatMicIntent(); return }
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }
        try { r.startListening(i) } catch (_: Exception) { toast("Voice input isn't available"); stopDictation() }
    }

    /** Show what has been heard so far in the composer. If the person edited the box meanwhile, they take over. */
    private fun renderDictation(partial: String) {
        if (composerInput.text.toString() != dictRendered) { stopDictation(); return }
        val joined = dictJoin(dictBase, dictJoin(dictDone, partial))
        composerInput.setText(joined)
        composerInput.setSelection(composerInput.text.length)
        dictRendered = composerInput.text.toString()
    }

    /** The phrase being spoken, as heard so far (either engine). */
    private fun dictHeardPartial(t: String) {
        if (!dictating || t.isEmpty()) return
        // Some engines start a fresh phrase after a pause without a final result for the old one.
        if (dictPartial.isNotEmpty() && t.length < dictPartial.length && firstWord(t) != firstWord(dictPartial)) commitPartial()
        dictPartial = t
        dictLastHeard = android.os.SystemClock.elapsedRealtime(); dictErrors = 0
        renderDictation(t)
        armSendIfReady()
    }

    /** A finished phrase (either engine). [relisten]: the phone recogniser needs restarting after each one. */
    private fun dictHeardFinal(said: String, relisten: Boolean) {
        if (!dictating) return
        dictErrors = 0
        if (said.isNotEmpty()) dictLastHeard = android.os.SystemClock.elapsedRealtime()
        // A spoken “send” at the end of a phrase is an action marker, not message text.
        val phrase = Regex.escape(prefs.voiceSendPhrase())
        val sendSuffix = Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$")
        val send = prefs.voiceSendEnabled() && said.isNotEmpty() && sendSuffix.containsMatchIn(said)
        val text = if (send) said.replace(sendSuffix, "").trimEnd() else said
        dictDone = dictJoin(dictDone, text)
        dictPartial = ""
        renderDictation("")
        if (send) {
            stopDictation()
            if (composerInput.text.isNotBlank()) sendChat(voiceSendCommand = true)
        } else {
            armSendIfReady()
            if (relisten) dictHandler.postDelayed({ listenOnce() }, 75)
        }
    }

    private val dictListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() { dictLastHeard = android.os.SystemClock.elapsedRealtime() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!dictating) return
            val t = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            dictHeardPartial(t)
        }
        override fun onResults(results: android.os.Bundle?) {
            if (!dictating) return
            val said = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty().ifEmpty { dictPartial }
            dictHeardFinal(said, relisten = true)
        }
        override fun onError(error: Int) {
            if (!dictating) return
            when (error) {
                android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                android.speech.SpeechRecognizer.ERROR_CLIENT -> {
                    // Nothing heard / engine hiccup: keep waiting. Back off so a stuck engine can't spin;
                    // the idle check ends dictation if nobody speaks.
                    commitPartial(); renderDictation("")
                    dictErrors++
                    if (error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == android.speech.SpeechRecognizer.ERROR_CLIENT) {
                        try { recognizer?.cancel() } catch (_: Exception) {}
                    }
                    dictHandler.postDelayed({ listenOnce() }, minOf(150L + dictErrors * 250L, 1500L))
                }
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    toast("Allow the mic to dictate"); stopDictation()
                }
                else -> {
                    val why = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        android.speech.SpeechRecognizer.ERROR_NETWORK -> "check your connection"
                        android.speech.SpeechRecognizer.ERROR_SERVER -> "speech service unavailable"
                        android.speech.SpeechRecognizer.ERROR_AUDIO -> "microphone unavailable"
                        else -> "error $error"
                    }
                    toast("Voice input stopped ($why)"); stopDictation()
                }
            }
        }
    }

    private val dictIdleCheck = object : Runnable {
        override fun run() {
            if (!dictating) return
            // A trailing send word that survives its silence is the command, finals or none.
            if (dictSendArmedAt != 0L && android.os.SystemClock.elapsedRealtime() - dictSendArmedAt > SEND_ARM_MS) {
                fireArmedSend()
                return
            }
            if (android.os.SystemClock.elapsedRealtime() - dictLastHeard > DICT_IDLE_MS) { stopDictation(); return }
            dictHandler.postDelayed(this, 500)
        }
    }

    /** End dictation, keeping whatever is in the composer. */
    private fun stopDictation() {
        if (!dictating) return
        dictating = false
        dictSendArmedAt = 0L
        dictHandler.removeCallbacksAndMessages(null)
        macDict?.stop(); macDict = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        setDictationUi(false)
    }

    /** While dictating: a small chip above the composer, tap it to stop. The send/mic button is left alone. */
    private fun setDictationUi(on: Boolean) {
        updateScreenOn() // dictating is already set; hold or release the screen with it
        if (!::chatMic.isInitialized) return
        val dp = resources.displayMetrics.density
        val st = chatRecStatus
        if (on) {
            val label = android.text.SpannableString("●  Listening, tap to stop")
            label.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.t_ok)), 0, 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            st.text = label
            st.typeface = android.graphics.Typeface.DEFAULT
            st.textSize = 13f
            st.setTextColor(getColor(R.color.t_ink))
            st.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.t_chip)); cornerRadius = 16 * dp
            }
            st.setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            // Width lives on the base LayoutParams: no cast (the parent here is a FrameLayout).
            st.layoutParams = st.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
            st.requestLayout()
            st.setOnClickListener { stopDictation() }
            st.visibility = View.VISIBLE
        } else {
            // Give the line back to the old recorder's look (it is shared).
            st.setOnClickListener(null); st.isClickable = false
            st.background = null; st.setPadding(0, 0, 0, 0)
            st.typeface = android.graphics.Typeface.MONOSPACE
            st.textSize = 14f
            st.setTextColor(getColor(R.color.t_err))
            st.layoutParams = st.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
            st.requestLayout()
            updateChatRecUI()
        }
        updateComposerSlot()
    }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val said = res.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()
            if (res.resultCode == RESULT_OK && !said.isNullOrBlank()) {
                // A final spoken “send” is an action marker, not message text.
                // Show the recognized draft first, then use the normal send path.
                val phrase = Regex.escape(prefs.voiceSendPhrase())
                val sendSuffix = Regex("(?i)(?:^|\\s+)(?:and\\s+)?$phrase[.!?]*$")
                val sendCommand = prefs.voiceSendEnabled() && sendSuffix.containsMatchIn(said)
                val message = if (sendCommand) said.replace(sendSuffix, "").trimEnd() else said
                val cur = composerInput.text.toString()
                val joined = if (cur.isBlank()) message else cur.trimEnd() + " " + message
                composerInput.setText(joined)
                composerInput.setSelection(joined.length)
                if (sendCommand && joined.isNotBlank()) sendChat(voiceSendCommand = true)
            }
        }

    /** Overflow for a settled assistant reply: everything the row icons
     *  do, plus quote — same sheet style as the thread menu. */
    private fun replyMoreSheet(chat: String, i: Int, text: String) {
        if (text.isBlank()) return
        val key = "$chat:$i"
        showSelectSheet("Reply", listOf(
            SelectRow("Copy", "copy reply text", action = {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
                toast("reply copied")
            }),
            SelectRow("Read aloud", "speak this reply", action = { replyVoice.speak(key, text) }),
            SelectRow("Quote", "reply with this quoted", action = { showQuote("assistant", text) }),
        ))
    }

    /** Legacy voice-note recorder path, unused by the chat mic now. */
    private fun onChatMicRecord() {
        if (!hasMic()) { askMic(); return }
        if (chatRecActive) { stopChatRec(); return }
        ensureNotifPermission()
        if (!isThreadOpen()) openDraft()
        chatRecActive = true
        chatRecStartingAt = android.os.SystemClock.elapsedRealtime()
        if (RecorderService.running && !RecorderService.chatCaptureActive) {
            chatRecActive = false
            chatRecStartingAt = 0L
            android.widget.Toast.makeText(this, "Stop Echo recording before recording a chat voice note", android.widget.Toast.LENGTH_LONG).show()
            updateChatRecUI()
            return
        }
        chatChunkBaseline = ChatVoiceStore(this).all().toSet()
        if (!RecorderService.running) {
            val i = Intent(this, RecorderService::class.java)
                .putExtra(RecorderService.EXTRA_CHAT_CAPTURE, true)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        chatPollHandler.removeCallbacks(chatPoll)
        chatPollHandler.post(chatPoll)
        updateChatRecUI()
    }

    private fun stopChatRec() {
        chatRecActive = false
        chatRecStartingAt = 0L
        chatPollHandler.removeCallbacks(chatPoll)
        stopService(Intent(this, RecorderService::class.java))
        updateChatRecUI()
        echoCard.postDelayed({ refresh() }, 600)
        // Final local chat draft lands on service destroy — pick it up late.
        chatPane.postDelayed({ scanChatChunks() }, 1200)
    }

    /** Show freshly closed local chat captures for approve/discard. */
    private fun scanChatChunks() {
        if (!isThreadOpen()) return
        if (pendingVoice != null) return
        val known = try { ChatVoiceStore(this).all().toSet() } catch (_: Exception) { return }
        val fresh = (known - chatChunkBaseline).sorted()
                .map { File(recordingsDir(this), it) }
                .filter { it.exists() && it.length() > 1024 }
        if (fresh.isEmpty()) return
        // Preview-first: stage the note with ✓/✕, never auto-send to the agent.
        val f = fresh.first()
        chatChunkBaseline = chatChunkBaseline + f.name
        pendingVoice = f
        val dur = audioDurationSec(f)
        voicePreviewLabel.text = "voice note" + if (dur > 0) " (%d:%02d)".format(dur / 60, dur % 60) else ""
        voicePreview.visibility = View.VISIBLE
    }

    /** ✓: send the approved voice note through the normal chat send path. */
    private fun keepVoice() {
        val f = pendingVoice ?: return
        pendingVoice = null
        voicePreview.visibility = View.GONE
        ChatVoiceStore(this).forget(f.name)
        pendingAttaches.add(f)
        showAttach()
        sendChat()
    }

    /** ✕: discard this local voice draft. */
    private fun dropVoice() {
        val f = pendingVoice
        pendingVoice = null
        voicePreview.visibility = View.GONE
        if (pendingAttaches.isEmpty()) composerInput.hint = "Message (/ for commands)"
        // Cancel removes only this local chat draft; no Echo upload is involved.
        if (f != null) {
            try { ChatVoiceStore(this).forget(f.name) } catch (_: Exception) {}
            try { f.delete() } catch (_: Exception) {}
            scanChatChunks()
        }
    }

    private fun updateChatRecUI() {
        updateScreenOn()
        if (!::chatMic.isInitialized) return
        if (dictating) { updateComposerSlot(); return }
        val starting = (android.os.SystemClock.elapsedRealtime() - chatRecStartingAt) < 3000L
        if (chatRecActive && !RecorderService.running && !starting) {
            // Service died or was stopped elsewhere — settle pending chunks, exit chat mode.
            chatRecActive = false
            chatPollHandler.removeCallbacks(chatPoll)
            scanChatChunks()
        }
        val live = chatRecActive
        if (live) {
            val startTs = if (RecorderService.startedAt > 0) RecorderService.startedAt else chatRecStartingAt
            val s = ((android.os.SystemClock.elapsedRealtime() - startTs) / 1000).coerceAtLeast(0)
            chatMic.setImageResource(R.drawable.ic_stop)
            chatMic.setBackgroundResource(R.drawable.rec_bg)
            // Red recording oval: white glyph, never the theme ink (which is
            // near-black in light mode and would vanish on the red).
            chatMic.imageTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            if (micPulse == null) {
                micPulse = android.animation.ValueAnimator.ofFloat(1f, 1.08f).apply {
                    duration = 450
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = easeOut
                    addUpdateListener { a ->
                        val v = a.animatedValue as Float
                        chatMic.scaleX = v; chatMic.scaleY = v
                    }
                    start()
                }
            }
            chatRecStatus.visibility = View.VISIBLE
            chatRecStatus.text = "recording • %02d:%02d (tap to stop)".format(s / 60, s % 60)
        } else {
            chatMic.setImageResource(R.drawable.ic_echo)
            chatMic.imageTintList = chatMicIdleTint
            chatMic.setBackgroundResource(0)
            chatRecStatus.visibility = View.GONE
            micPulse?.cancel(); micPulse = null
            chatMic.scaleX = 1f; chatMic.scaleY = 1f
        }
        updateComposerSlot()
    }

    private fun audioDurationSec(f: File): Int = try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(f.absolutePath)
        val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        try { mmr.release() } catch (_: Exception) {}
        (ms / 1000).toInt().coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    /** Title precedence: manual override > opencode title > first-line
     *  fallback. Legacy builds used one lock for both manual edits and
     *  promoted titles, so first-line locks are recognized and healed. */
    private fun syncServerTitle(id: String) {
        val before = chatStore.get(id) ?: return
        // Codex and Agy can expose a name for reading even though remote renaming
        // is unsupported; other backends use their title capability.
        val canWriteTitle = capsForBackend(backendCatalog, before.backend).title
        val canReadTitle = before.backend == "codex" || before.backend == "agy" || canWriteTitle
        if (!canReadTitle) return
        val localTitle = before.title
        val isFallback = localTitle == "new chat" || localTitle == "untitled" ||
            localTitle.startsWith("📎 ") || chatStore.autoTitleLines(id).contains(localTitle)

        // A locked title matching the generated fallback is a legacy false
        // lock; clear it so the server name can win. A genuine manual name
        // stays locked and is pushed too (migrates pre-v58 renames).
        if (before.titleLocked && !isFallback) {
            val sid = before.remoteId
            if (canWriteTitle && sid.isNotBlank()) Thread {
                try { OpencodeApi.postTitleSet(prefs.serverUrl(), prefs.token(), sid, localTitle, before.backend, id) } catch (_: Exception) {}
            }.start()
            return
        }
        if (before.titleLocked) chatStore.unlockTitle(id)
        val rsid = before.remoteId
        if (rsid.isBlank()) return
        Thread {
            try {
                val t = OpencodeApi.fetchTitle(prefs.serverUrl(), prefs.token(), rsid, before.backend, id).trim()
                if (t.isBlank()) return@Thread
                runOnUiThread {
                    val current = chatStore.get(id) ?: return@runOnUiThread
                    // A manual rename may land while this fetch is in flight.
                    if (current.titleLocked) return@runOnUiThread
                    val currentIsFallback = current.title == "new chat" || current.title == "untitled" ||
                        current.title.startsWith("📎 ") || chatStore.autoTitleLines(id).contains(current.title)
                    if (current.title == localTitle || currentIsFallback) {
                        if (t != current.title) chatStore.setServerTitle(id, t)
                        renderSessions()
                        if (activeSessionId == id) renderThread(id)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** LAN fallback notice: disabled while tailnet-only (flag off = no-op).
     *  Code path kept for re-enable. */
    private fun noteLanFallback(id: String?) {
        if (!OpencodeApi.LAN_FALLBACK_ENABLED) return
        if (!OpencodeApi.consumeLanNotice()) return
        if (id == null || chatStore.get(id) == null) return
        chatStore.addMessage(id, ChatMessage("tool", "tailnet unreachable, using local wifi"))
        if (activeSessionId == id) renderThread(id)
        renderSessions()
    }

    // ---- Delivery inbox (schedules + alerts) ----

    /** A local hand-off or approval is waiting on the person: flag the chat that asked (or clear the flag).
     *  A blank or unknown chat id is ignored. The flag clears when the chat opens (ChatStore.markRead). */
    private fun flagNeedsYou(chatId: String, reason: String, on: Boolean) {
        if (!::chatStore.isInitialized || chatId.isBlank() || chatStore.get(chatId) == null) return
        if (on) chatStore.setNeedsYou(chatId, reason, System.currentTimeMillis() + needsYouTtlMs("phone-browser"))
        else chatStore.clearNeedsYou(chatId)
        renderSessions()
    }

    /** Job → stable thread: per-job `schedule:<name>`, everything else
     *  (reminders, one-shots, system notes) → the single `alerts` thread.
     *  A valid `chat` override on the entry wins: targeted delivery lands
     *  in that exact session thread instead. */
    private fun targetChatFor(job: String, chat: String = ""): String {
        if (chat.matches(Regex("[A-Za-z0-9_:-]{1,80}"))) return chat
        return if (job == "alerts" || job == "system") "alerts" else "schedule:$job"
    }

    private fun targetTitleFor(job: String): String =
        if (job == "alerts" || job == "system") "Alerts" else job

    /** Quiet hours kill pings, dot remains. Default 22:00–07:00. */
    private fun inQuietHours(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val s = prefs.quietStart()
        val e = prefs.quietEnd()
        return if (s <= e) h in s until e else h >= s || h < e
    }

    private fun fmtTime(at: Long): String = try {
        java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(at))
    } catch (_: Exception) { "" }

    /** Backstop pull: merge every inbox entry since the last cursor into its
     *  job thread. Open-chat delivery is silent; elsewhere the bell dot
     *  always ticks and a system ping fires outside quiet hours. */
    /** Last time the inbox was pulled, so opening chats back to back does not refetch it each time. */
    private var lastInboxPullAt = 0L

    /** `minGapMs` > 0 skips the pull when one ran that recently (used on chat open). Every other caller pulls now. */
    private fun syncInbox(minGapMs: Long = 0L) {
        if (!::chatStore.isInitialized) return
        val now = System.currentTimeMillis()
        if (minGapMs > 0 && now - lastInboxPullAt < minGapMs) return
        lastInboxPullAt = now
        Thread {
            val items = try {
                OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, prefs.lastInboxSync())
            } catch (_: Exception) { return@Thread }
            if (items.isEmpty()) return@Thread
            val fresh = mergeInboxStore(items)
            // Alarm links arm at sync time — no open, no tap, ever.
            // Idempotent per link; cancelled times stay dead.
            try {
                for ((_, e) in fresh) WakeAlarm.armFromText(this, "${e.title}\n${e.body}")
            } catch (_: Exception) {}
            runOnUiThread {
                for ((cid, e) in fresh) {
                    if (activeSessionId == cid) {
                        renderThread(cid)
                    } else if (!inQuietHours()) {
                        ensureNotifPermission()
                        val chat = chatStore.get(cid)
                        if (e.kind == "event" && chat?.waitingOnYou() == true) {
                            ChatNotify.notifyNeedsYou(this, chat.title, chat.needsYou, cid)
                        } else if (e.kind == "reply") {
                            ChatNotify.notifyTurn(this, e.body, cid, chat?.title.orEmpty())
                        } else {
                            ChatNotify.notifyInbox(this, e.job, inboxPreview(e.body.ifBlank { e.title }), cid)
                        }
                    }
                }
                // Already looking at it: no dot for the visible tab.
                when (nav.selectedItemId) {
                    R.id.tabNotes -> tabDots.remove("notes")
                    R.id.tabGallery -> tabDots.remove("gallery")
                }
                paintTabDots()
                noteLanFallback(activeSessionId)
                renderSessions()
            }
        }.start()
    }

    /** Merge fetched deliveries into their job threads (store writes only,
     *  safe off the UI thread). Same text AND same server timestamp = replay
     *  → drop; same text with a newer timestamp = legit repeat → deliver.
     *  Returns the newly stored rows for the caller's UI/ping pass, and
     *  advances the sync cursor past everything seen. Shared by the backstop
     *  pull and by clear-all, which merges the full log before ACKing it —
     *  so entries this device never synced are kept locally, never lost. */
    private fun mergeInboxStore(items: List<OpencodeApi.InboxItem>): List<Pair<String, OpencodeApi.InboxItem>> {
        val fresh = mutableListOf<Pair<String, OpencodeApi.InboxItem>>()
        var maxAt = prefs.lastInboxSync()
        for (e in items) {
            maxAt = maxOf(maxAt, e.at)
            // Watcher entries never open job threads: they light per-tab
            // dots only (cursor still advances above). No ping, no thread.
            if (e.job == "watch") {
                tabDots.add(watchAreaOf(e.body.ifBlank { e.title }))
                continue
            }
            // Routing, dedupe and the needs-you flag live in landInboxEntry (shared with InboxWorker).
            val cid = landInboxEntry(chatStore, e) ?: continue
            fresh += cid to e
        }
        prefs.setLastInboxSync(maxAt)
        return fresh
    }

    /** Bell: plain read list — rolling last 24h, newest first. No clear
     *  button by design: old rows age out on their own, and everything
     *  already lives in the job threads as reviewable history. Tap a row
     *  to open the job chat at that message. */
    private fun showInbox() {
        Thread {
            val since = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val items = try {
                OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, since)
                    .sortedByDescending { it.at }.take(30)
            } catch (_: Exception) { null }
            runOnUiThread {
                // Bell ≡ openable items: chats with unread replies (local,
                // settled results) head the sheet; server inbox entries
                // follow. Either source can light the bell, both open here —
                // a lit bell never leads to an empty sheet.
                val unreadChats = try {
                    chatStore.list().filter { it.unread > 0 }
                } catch (_: Exception) { emptyList() }
                if (items == null && unreadChats.isEmpty()) {
                    android.widget.Toast.makeText(this, "Activity unavailable", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if ((items?.isEmpty() ?: true) && unreadChats.isEmpty()) {
                    android.widget.Toast.makeText(this, "nothing in the last 24 hours", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val events = (items ?: emptyList())
                val rows = activityEntries(
                    unreadChats.map { Triple(it.id, it.title, it.unread) },
                    events.map { e ->
                        Triple(e.job,
                            (e.title.ifBlank { e.body }).replace(Regex("\\s+"), " ").trim().take(80),
                            e.at)
                    },
                )
                showActivitySheet(rows) { which ->
                    when (val r = rows[which]) {
                        is ActivityEntry.Header -> {}
                        is ActivityEntry.Chat -> {
                            showChat()
                            openThread(r.id)
                        }
                        is ActivityEntry.Event -> {
                            val e = events.getOrNull(r.index) ?: return@showActivitySheet
                            val cid = inboxTarget(e.job, e.chat, e.kind) { chatStore.get(it) != null }
                            chatStore.ensure(cid, targetTitleFor(e.job))
                            showChat()
                            openThread(cid)
                        }
                    }
                }
            }
        }.start()
    }

    /** Structured Activity rows: section headers plus title/preview/time
     *  rows with an unread badge. Same tap behavior as before. */
    private fun activitySheetAdapter(rows: List<ActivityEntry>): android.widget.ListAdapter {
        val dens = resources.displayMetrics.density
        val ink = androidx.core.content.ContextCompat.getColor(this, R.color.t_ink)
        val muted = androidx.core.content.ContextCompat.getColor(this, R.color.t_muted)
        val chip = androidx.core.content.ContextCompat.getColor(this, R.color.t_chip)
        fun pad(v: View, h: Int, top: Int, bottom: Int) {
            v.setPadding((h * dens).toInt(), (top * dens).toInt(), (h * dens).toInt(), (bottom * dens).toInt())
        }
        fun line(text: String, size: Float, color: Int, bold: Boolean): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        fun headerView(reuse: View?, title: String): View {
            val tv = (reuse as? TextView) ?: line("", 12f, muted, true)
            (tv as TextView).text = title.uppercase()
            pad(tv, 16, 10, 4)
            return tv
        }
        fun chatRow(reuse: View?, title: String, unread: Int): View {
            val root = (reuse as? android.widget.LinearLayout)?.apply { removeAllViews() }
                ?: android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            pad(root, 16, 8, 8)
            root.minimumHeight = (52 * dens).toInt()
            val top = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            val titleView = line(title, 15f, ink, true).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val badge = line("$unread new", 13f, ink, true).apply {
                setBackgroundColor(chip)
                val bPad = (4 * dens).toInt()
                setPadding((8 * dens).toInt(), bPad, (8 * dens).toInt(), bPad)
            }
            top.addView(titleView)
            top.addView(badge)
            root.addView(top)
            root.addView(line(
                "$unread unread repl" + if (unread == 1) "y" else "ies", 13f, muted, false))
            return root
        }
        fun eventRow(reuse: View?, job: String, preview: String, at: Long): View {
            val root = (reuse as? android.widget.LinearLayout)?.apply { removeAllViews() }
                ?: android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            pad(root, 16, 8, 8)
            root.minimumHeight = (52 * dens).toInt()
            val top = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            val jobView = line(job, 15f, ink, true).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            top.addView(jobView)
            top.addView(line(fmtTime(at), 13f, muted, false))
            root.addView(top)
            if (preview.isNotBlank()) {
                root.addView(line(preview, 14f, muted, false).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            return root
        }
        return object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 3
            override fun getItemViewType(p: Int) = when (rows[p]) {
                is ActivityEntry.Header -> 0
                is ActivityEntry.Chat -> 1
                is ActivityEntry.Event -> 2
            }
            // Headers display only; rows below stay tappable.
            override fun isEnabled(p: Int) = rows[p] !is ActivityEntry.Header
            override fun getView(p: Int, reuse: View?, parent: android.view.ViewGroup): View =
                when (val r = rows[p]) {
                    is ActivityEntry.Header -> headerView(reuse, r.title)
                    is ActivityEntry.Chat -> chatRow(reuse, r.title, r.unread)
                    is ActivityEntry.Event -> eventRow(reuse, r.job, r.preview, r.at)
                }
        }
    }

    private fun sendChat(voiceSendCommand: Boolean = false, steer: Boolean = false) {
        stopDictation()
        // A note still open inline goes out with its quote, as if Done was tapped.
        if (editingQuote != null) saveQuoteNote()
        val text = composerInput.text.toString().trim()
        val attaches = pendingAttaches.toList()
        if (text.isEmpty() && attaches.isEmpty() && quotes.isEmpty()) return
        // Tap debounce (any content, 600ms): a finger bounce on send is one
        // physical tap, not intent. Never suppress on text equality — an
        // intentional repeat ("?", "retry") is its own turn with its own
        // turnId (the idempotency key); transport retries reuse that key and
        // the server dedupes them, so distinct sends stay distinct.
        val now = System.currentTimeMillis()
        if (TurnActivity.sendDebounced(now, lastSendAt)) {
            android.util.Log.d("ChatTurn", "send bounce debounced")
            return
        }
        lastSendAt = now
        // Edit mode: the box holds a failed turn's edited text — send
        // resends that turn instead of starting a new one.
        val et = editingTurn
        val openId = activeSessionId
        if (et != null && openId != null && et.first == openId && et.second.isNotBlank()) {
            if (text.isBlank()) {
                toast("Write a message first")
                return
            }
            saveEditAndResend(openId, et.second, text)
            return
        } else if (et != null) {
            cancelEditMode()
        }
        // Draft slash commands that need no session: never persist for these.
        if (activeSessionId == null && text.startsWith("/")) {
            val cmd = text.split("\\s+".toRegex(), limit = 2)[0].lowercase()
            val arg = text.split("\\s+".toRegex(), limit = 2).getOrNull(1)?.trim() ?: ""
            when (cmd) {
                "/new" -> {
                    openDraft()
                    slashList.visibility = View.GONE
                    clearQuote()
                    return
                }
                "/sessions" -> {
                    closeThread()
                    slashList.visibility = View.GONE
                    clearQuote()
                    return
                }
                "/search" -> {
                    closeThread()
                    searchOpen = true
                    sessionSearch.setText(arg)
                    sessionQuery = arg
                    sessionSearch.visibility = View.VISIBLE
                    searchIcon.setImageResource(R.drawable.ic_close)
                    slashList.visibility = View.GONE
                    clearQuote()
                    renderSessions()
                    return
                }
            }
        }
        // Empty-state composer: persist the draft on first real send only.
        if (activeSessionId == null) ensureDraftSession()
        val id = activeSessionId ?: return
        if (text.startsWith("/")) {
            // Commands bypass the send queue and act immediately.
            handleCommand(id, text)
            composerInput.setText("")
            slashList.visibility = View.GONE
            clearQuote()
            return
        }
        // Queue-by-default: a backlog that outlived its turn drains in
        // order before the new send; opencode sends behind a running
        // reply hold in the app queue instead of blasting through.
        if ((waitQueue[id]?.size ?: 0) > 0 && !isTurnActive(id)) {
            // Backlog exists but the turn already settled (e.g. failed path
            // kept it): drain in order, then send this one through too.
            drainWaitQueue(id)
        }
        val wireText = QuoteDraft.wire(quotes, text)
        // Queue-by-default on opencode: a send behind a running reply would
        // otherwise blast straight into it. Hold it in the app queue (row +
        // Send-now link); it drains in order or releases early via its link.
        if (Prefs.cannonBackend(chatBackendOf(id) ?: prefs.defaultBackend()) == Prefs.BACKEND_OPENCODE && isTurnActive(id)) {
            enqueueWait(id, wireText, attaches, text)
            if (activeSessionId == id) renderThread(id)
            renderSessions()
            return
        }
        // Codex sends can request turn/steer; OpenCode uses its normal chat
        // request and accepts subsequent messages without a special route.
        val steerNow = steer && canSteer(id)
        val behindRunning = isTurnActive(id) && Prefs.cannonBackend(chatBackendOf(id) ?: prefs.defaultBackend()) != Prefs.BACKEND_OPENCODE
        sendVisibleTurn(id, wireText, attaches, text, voiceSendCommand = voiceSendCommand, steer = steerNow)
        if (behindRunning) pollWaiting(id)
    }

    /** A server-queued send (codex, claude) becomes a Send-now row while the server says it is
     *  waiting. Keep asking while the reply runs, so the row clears when the queued turn starts
     *  (or the reply ends) instead of sticking until the next manual sync. */
    private fun pollWaiting(id: String, tries: Int = 0) {
        progressHandler.postDelayed({
            if (!isTurnActive(id)) {
                backendWaitingTurns.remove(id)
                if (activeSessionId == id) refreshSendNowLinks()
                return@postDelayed
            }
            Thread {
                val r = try {
                    val rsid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                    OpencodeApi.syncChat(prefs.serverUrl(), prefs.token(), id, rsid, chatBackendOf(id))
                } catch (_: Exception) { null }
                runOnUiThread {
                    if (r == null) {
                        if (tries < POLL_WAITING_MAX) pollWaiting(id, tries + 1)
                        return@runOnUiThread
                    }
                    backendWaitingTurns[id] = r.waiting.toSet()
                    for (t in (r.settled + r.lost).distinct()) reconcileTurn(id, t)
                    if (activeSessionId == id) refreshSendNowLinks()
                    if (r.waiting.isNotEmpty() && tries < POLL_WAITING_MAX) pollWaiting(id, tries + 1)
                }
            }.start()
        }, if (tries == 0) 700L else POLL_WAITING_GAP_MS)
    }

    /** Tap a failed bubble to edit that message in the composer itself —
     *  no modal. Send resends the failed turn with the edited text; back
     *  cancels and restores whatever was typed. Only bubbles carrying the
     *  failure flag respond. */
    /** Visible slice + its global base index, mirroring renderThread. */
    private fun visibleThreadRows(id: String): Pair<List<ChatMessage>, Int>? {
        val s = try { chatStore.get(id) } catch (_: Exception) { return null } ?: return null
        val all = s.messages
        val vis = threadVisible[id] ?: threadPageSize
        val base = if (all.size > vis) all.size - vis else 0
        return (if (all.size > vis) all.takeLast(vis) else all) to base
    }

    private fun tapMessageToEdit(x: Float, y: Float) {
        val id = activeSessionId ?: return
        if (!threadPageLoaded) return
        try {
            threadWeb.evaluateJavascript("window.__rowAt($x,$y)") { res ->
                try {
                    if (res == null || res == "null") return@evaluateJavascript
                    val o = org.json.JSONObject(res)
                    // Link taps navigate; only a bare failed bubble edits.
                    if (o.optString("l") == "1") return@evaluateJavascript
                    if (o.optString("r") != "user") return@evaluateJavascript
                    val idx = o.optString("i").toIntOrNull() ?: return@evaluateJavascript
                    val (msgs, base) = visibleThreadRows(id) ?: return@evaluateJavascript
                    val m = msgs.getOrNull(idx - base) ?: return@evaluateJavascript
                    if (m.role != "user" || m.turnId.isBlank() || !m.sendFailed) return@evaluateJavascript
                    runOnUiThread { enterEditMode(id, m.turnId) }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun enterEditMode(chat: String, turn: String) {
        if (activeSessionId != chat) return
        val origWire = try { chatStore.userTurnFor(chat, turn)?.text } catch (_: Exception) { null }
        val display = if (!origWire.isNullOrBlank()) TurnActivity.stripQuotePrefix(origWire)
            else retryInputs(chat, turn)?.third
        if (display.isNullOrBlank()) {
            toast("Message lost — type it again")
            return
        }
        editingTurn = chat to turn
        editingStashByTurn[turn] = try { composerInput.text.toString() } catch (_: Exception) { "" }
        composerInput.setText(display)
        composerInput.setSelection(display.length)
        composerInput.hint = "Editing failed message — send to resend"
        composerInput.requestFocus()
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(composerInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
        toast("Send to resend, back to cancel")
    }

    /** Exit edit mode. Back restores the displaced draft; a resolved retry
     *  drops it (the resend already cleared the box). */
    private fun cancelEditMode() {
        val (chat, turn) = editingTurn ?: return
        editingTurn = null
        val stash = editingStashByTurn.remove(turn) ?: ""
        if (activeSessionId == chat) {
            if (stash.isNotBlank()) {
                composerInput.setText(stash)
                composerInput.setSelection(stash.length)
            }
            composerInput.hint = "Message (/ for commands)"
        }
    }

    /** Leave edit mode without resolving: the composer hint restores.
     *  Stashes stay — cancel/resend/re-enter/thread-switch consume them. */
    private fun clearEditState() {
        editingTurn = null
        try { composerInput.hint = "Message (/ for commands)" } catch (_: Exception) {}
    }

    private fun saveEditAndResend(chat: String, turn: String, edited: String) {
        val e = edited.trim()
        if (e.isBlank()) {
            toast("Write a message first")
            return
        }
        val origWire = try { chatStore.userTurnFor(chat, turn)?.text } catch (_: Exception) { null }
        val quote = if (!origWire.isNullOrBlank() && origWire.startsWith(">") && "\n" in origWire)
            origWire.substringBefore("\n") + "\n" else ""
        val wire = quote + e
        try { chatStore.updateUserText(chat, turn, wire) } catch (_: Exception) {}
        try { failedRestore[turn] = wire } catch (_: Exception) {}
        if (activeSessionId == chat) renderThread(chat)
        renderSessions()
        retryTurn(chat, turn)
    }

    /** Tap-to-retry a failed turn. Reconcile-first with three outcomes:
     *  settled → deliver through the normal path (and say "already replied"
     *  when the text already renders); running → note it, resend nothing;
     *  unknown/unreachable → resend as a FRESH turn (warned may-run-twice)
     *  with the ORIGINAL wire inputs recovered from the persisted user row
     *  (attachments + quote survive restarts; memory stash is legacy
     *  fallback). The original retires as superseded first, so its late
     *  `done` drops instead of twinning. Every dead end answers visibly —
     *  links never go silent. */
    private fun retryTurn(chat: String, turn: String) {
        showChecking(chat)
        Thread {
            val inputs = retryInputs(chat, turn)
            // Reconcile the server's own view first (best effort, every backend): a turn stuck "running"
            // with nothing behind it, or a finished reply that never arrived, settles here instead of
            // leaving Retry to answer "still running" forever.
            try {
                val rsid = try { chatStore.get(chat)?.remoteId ?: "" } catch (_: Exception) { "" }
                OpencodeApi.syncChat(prefs.serverUrl(), prefs.token(), chat, rsid, chatBackendOf(chat))
            } catch (_: Exception) { /* no news: the exact lookup below decides */ }
            try {
                val r = OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), chat, turn, chatBackendOf(chat))
                when (TurnActivity.retryOutcome(r.settled, r.running, inputs != null, settledError = r.error.isNotBlank() && r.text.isBlank())) {
                    RetryOutcome.SETTLE -> {
                        val rendered = r.text.isNotBlank() && try {
                            val norm = TurnActivity.normText(r.text)
                            chatStore.get(chat)?.messages?.any {
                                it.role == "assistant" && TurnActivity.normText(it.text) == norm
                            } ?: false
                        } catch (_: Exception) { false }
                        val ft = r.text; val fe = r.error; val fm = r.messageID
                        runOnUiThread {
                            hideChecking(chat)
                            clearEditState()
                            finishStreamTurn(chat, turn, ft, fe, fm, r.code)
                            if (rendered) toast("That message already has a reply")
                        }
                        return@Thread
                    }
                    RetryOutcome.WAIT -> {
                        runOnUiThread {
                            hideChecking(chat)
                            clearEditState()
                            // The server is still working on this: it never failed. Put the message back to
                            // "running" (no error, no instruction to stop it) and let the reply land when it
                            // does, like any other send.
                            try { chatStore.setUserSendFailed(chat, turn, false) } catch (_: Exception) {}
                            try { chatStore.dropAllUnknownRows(chat) } catch (_: Exception) {}
                            try { chatStore.dropLastFailureNote(chat) } catch (_: Exception) {}
                            unknownSurfaced[chat]?.remove(turn)
                            serverIdle[chat] = false
                            pendingTurns.getOrPut(chat) { mutableSetOf() }.add(turn)
                            val hasPlaceholder = try {
                                chatStore.get(chat)?.messages?.any { it.role == "assistant" && it.turnId == turn } == true
                            } catch (_: Exception) { false }
                            if (!hasPlaceholder) chatStore.addMessage(chat, ChatMessage("assistant", "…", turnId = turn))
                            if (activeSessionId == chat) renderThread(chat)
                            renderSessions()
                            ensureStream(chat)
                            toast("Still working on it")
                        }
                        return@Thread
                    }
                    RetryOutcome.DEADLINK -> {
                        runOnUiThread {
                            hideChecking(chat)
                            clearEditState()
                            toast("Message lost — type it again")
                        }
                        return@Thread
                    }
                    RetryOutcome.RESEND -> { /* fall through to resend below */ }
                }
            } catch (_: Exception) {
                // Unreachable registry: resend iff inputs exist (warned).
                if (inputs == null) {
                    runOnUiThread {
                        hideChecking(chat)
                        clearEditState()
                        toast("Message lost — type it again")
                    }
                    return@Thread
                }
            }
            val ready = inputs ?: run {
                runOnUiThread {
                    hideChecking(chat)
                    clearEditState()
                    toast("Message lost — type it again")
                }
                return@Thread
            }
            runOnUiThread {
                toast("Resending (may run twice)")
                resendNewTurn(chat, turn, ready.first, ready.second, ready.third)
            }
        }.start()
    }

    @Volatile private var outboxRunning = false
    private val outboxHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val outboxTick = Runnable { flushOutbox() }

    /** Try the waiting sends again soon, and keep trying every minute while any wait. */
    private fun scheduleOutboxRetry(delayMs: Long = 60_000) {
        outboxHandler.removeCallbacks(outboxTick)
        outboxHandler.postDelayed(outboxTick, delayMs)
    }

    /** Send every message that is waiting for a connection, oldest first, once the Mac answers. They never
     *  reached the server, so this sends them for the first time (no duplicate risk) and leaves the composer
     *  and the open chat alone. Triggered by a new network, the live link coming up, app resume and a timer. */
    private fun flushOutbox() {
        if (!::chatStore.isInitialized || outboxRunning) return
        val waiting = try {
            chatStore.list().flatMap { c ->
                c.messages.filter { it.role == "user" && it.sendFailed && Outbox.isWaiting(it.failError) && it.turnId.isNotBlank() }
                    .map { Triple(c.id, it, it.ts) }
            }.sortedBy { it.third }
        } catch (_: Exception) { emptyList() }
        if (waiting.isEmpty()) return
        outboxRunning = true
        Thread {
            val up = OpencodeApi.pingOk(prefs.serverUrl(), "opencode") ||
                try { OpencodeApi.fetchInbox(prefs.serverUrl(), prefs.token(), null, System.currentTimeMillis()); true } catch (_: Exception) { false }
            runOnUiThread {
                outboxRunning = false
                if (!up) {
                    // Still unreachable: refresh the reason (Tailscale back on but Mac asleep, ...) and wait.
                    val why = Outbox.PREFIX + Outbox.reason(this, prefs.serverUrl())
                    for ((chat, m, _) in waiting) if (m.failError != why) try { chatStore.setUserSendFailed(chat, m.turnId, true, why) } catch (_: Exception) {}
                    activeSessionId?.let { a -> if (waiting.any { it.first == a }) renderThread(a) }
                    scheduleOutboxRetry()
                    return@runOnUiThread
                }
                for ((chat, m, _) in waiting) {
                    val files = m.allAttachments.filter { it.isNotBlank() }.map { java.io.File(it) }.filter { it.exists() }
                    val newId = newTurnId()
                    try {
                        chatStore.setUserSendFailed(chat, m.turnId, false)
                        chatStore.rebindUserTurn(chat, m.turnId, newId)
                    } catch (_: Exception) { continue }
                    startTurn(chat, m.text, files, restoreText = TurnActivity.stripQuotePrefix(m.text), fixedTurnId = newId)
                }
                renderSessions()
            }
        }.start()
    }

    /** Recover resend inputs: persisted user row first (restart-safe,
     *  attachments + quote prefix intact), memory stash legacy fallback.
     *  Null = dead link. Triple = wire text, files, display text. */
    private fun retryInputs(chat: String, turn: String): Triple<String, List<java.io.File>, String>? {
        try {
            chatStore.userTurnFor(chat, turn)?.let { row ->
                val files = row.allAttachments.filter { it.isNotBlank() }
                    .map { java.io.File(it) }.filter { it.exists() }
                return Triple(row.text, files, TurnActivity.stripQuotePrefix(row.text))
            }
        } catch (_: Exception) {}
        val stash = failedRestore[turn] ?: turnRestore[turn]
        if (!stash.isNullOrBlank()) return Triple(stash, emptyList(), stash)
        return null
    }

    /** Resend previously-failed/unknown inputs as a genuinely new turn. The
     *  original retires as superseded first (placeholder gone, late outcome
     *  drops); the persisted user row is reused, so no second user bubble
     *  and attachments ride along. */
    private fun resendNewTurn(chat: String, turn: String, wire: String, files: List<java.io.File>, display: String) {
        failedRestore.remove(turn)
        turnRestore.remove(turn)
        if (chatStore.get(chat) == null) {
            hideChecking(chat)
            return
        }
        pendingTurns[chat]?.remove(turn)
        dropTurnBuf(turn)
        chatStore.dropPlaceholder(chat, turn)
        // Its own failure line clears on the way out (Claude-style): if this
        // resend fails, a fresh note lands in its place via the normal path.
        // Both drops: trailing (usual case) and targeted (an older note for
        // this turn buried under later rows must not survive the success).
        try { chatStore.dropLastFailureNote(chat) } catch (_: Exception) {}
        try { chatStore.dropFailureNoteForTurn(chat, turn) } catch (_: Exception) {}
        supersededTurns.getOrPut(chat) { mutableMapOf() }[turn] = System.currentTimeMillis()
        hideChecking(chat)
        if (activeSessionId != chat) openThread(chat)
        val kept = try { composerInput.text.toString() } catch (_: Exception) { "" }
        dropPendingDraft()
        sendVisibleTurn(chat, wire, files, display, appendUser = false, reuseUserTurnId = turn)
        if (kept.isNotBlank() && kept != display) {
            composerInput.setText(kept)
            composerInput.setSelection(kept.length)
        }
        // An edit-mode resend resolves the edit here: restore the displaced
        // draft (if any), leave edit mode, restore the hint. The resend
        // already cleared the box.
        val editStash = editingStashByTurn.remove(turn) ?: ""
        val wasEditing = editingTurn?.first == chat && editingTurn?.second == turn
        if (wasEditing) {
            editingTurn = null
            if (editStash.isNotBlank()) {
                composerInput.setText(editStash)
                composerInput.setSelection(editStash.length)
            }
            composerInput.hint = "Message (/ for commands)"
        }
        renderSessions()
        updateStopBtn()
    }

    /** Visible send: composer clears first so the tap feels instant, then
     *  one store pass, then its own turn POSTs — even mid-turn. Resends pass
     *  appendUser=false to reuse the persisted user row (no second bubble). */
    private fun sendVisibleTurn(id: String, wireText: String, attaches: List<java.io.File>, displayText: String, appendUser: Boolean = true, reuseUserTurnId: String = "", voiceSendCommand: Boolean = false, nativeCommandName: String = "", steer: Boolean = false, steerRequired: Boolean = false) {
        val session = chatStore.get(id)
        // Heal stale stub values ("default"/"fast"/"pro" predate live models).
        // The "/" shape is opencode-only: other backends use opaque ids
        // (sonnet, gpt-5.x-codex), which this must never wipe. Blank model
        // means follow that backend's default (server resolves it).
        val backend = try { session?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
        val model = if (backend == "opencode") session?.model?.takeIf { it.contains("/") } else sendModelFor(backend, session?.model)
        if (session != null && backend == "opencode" && !session.model.isBlank() && model == null) chatStore.setModel(id, "")
        val effort = session?.effort?.takeIf { it == "low" || it == "medium" || it == "high" }
        clearQuote()
        composerInput.setText("")
        composerInput.hint = "Message (/ for commands)"
        if (voiceSendCommand) {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(composerInput.windowToken, 0)
            composerInput.clearFocus()
        }
        // The send owns the draft from here (appendUserTurn clears it): a
        // debounced keystroke write must not land afterwards.
        dropPendingDraft()
        pendingAttaches.clear()
        hideAttachStrip()
        slashList.visibility = View.GONE
        val first = attaches.firstOrNull()
        val quoteTitle = if (wireText.startsWith(">") && displayText.isNotBlank()) displayText.trim().take(42).lineSequence().first() else null
        val attachTitle = if (displayText.isBlank() && first != null && session?.title == "new chat") "📎 ${AttachName.friendly(first.name).take(40)}" else null
        // The turn owns its id from birth: the user row carries it so a later
        // retry can recover the exact wire text + attachment (restart-safe —
        // user rows persist, the memory stash does not).
        val turnId = newTurnId()
        if (appendUser) {
            chatStore.appendUserTurn(id, ChatMessage("user", wireText, attachment = first?.absolutePath, moreAttachments = attaches.drop(1).map { it.absolutePath }, turnId = turnId, voiceSendCommand = voiceSendCommand), quoteTitle, attachTitle)
        } else if (reuseUserTurnId.isNotBlank()) {
            chatStore.rebindUserTurn(id, reuseUserTurnId, turnId)
        }
        startTurn(id, wireText, attaches,
            // A quoted send restores whole (quotes + notes as markdown) on failure, not just the typed part.
            restoreText = if (wireText.startsWith(">")) wireText else displayText, fixedTurnId = turnId, nativeCommandName = nativeCommandName, steer = steer, steerRequired = steerRequired)
    }

    /** Fresh turn id: millis + random, unique across restarts and queue drains. */
    private fun newTurnId(): String =
        "t-${System.currentTimeMillis().toString(36)}-${(0..0xffffff).random().toString(36)}"

    /** Serial queue pipe: visible immediately, POSTs only when
     *  the running turn settles — one reply per message. */
    private fun enqueueWait(id: String, wireText: String, attaches: List<java.io.File>, displayText: String) {
        val files = attaches.toList()
        val turnId = newTurnId()
        // Visible now so ordering never slides behind the reply. The row
        // carries its turn id from birth so a Send-now link can release
        // exactly this message, and drain keeps the same id.
        chatStore.addMessage(id, ChatMessage("user", wireText, attachment = files.firstOrNull()?.absolutePath, moreAttachments = files.drop(1).map { it.absolutePath }, turnId = turnId))
        val s = chatStore.get(id)
        if (wireText.startsWith(">") && displayText.isNotBlank() && s?.title == "new chat" && !(s.titleLocked)) {
            chatStore.rename(id, displayText.trim().take(42).lineSequence().first())
        }
        if (displayText.isBlank() && files.isNotEmpty() && s?.title == "new chat") {
            chatStore.rename(id, "📎 ${AttachName.friendly(files.first().name).take(40)}")
        }
        waitQueue.getOrPut(id) { mutableListOf() }.add(PendingSend(wireText, files, "", turnId))
        try { WaitStore(this).put(id, waitQueue[id]!!.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
        clearQuote()
        composerInput.setText("")
        composerInput.hint = "Message (/ for commands)"
        dropPendingDraft()
        chatStore.setDraft(id, "")
        pendingAttaches.clear()
        hideAttachStrip()
        slashList.visibility = View.GONE
        if (activeSessionId == id) renderThread(id)
        renderSessions()
        updateStopBtn()
    }

    /** Single reconciler: a waiting backlog with no active turn must drain;
     *  hint + stop button always reflect the result. Call at the end of
     *  every settle/failure path so stale UI cannot survive a missed branch. */
    private fun reconcileTurnState(id: String) {
        if (!(waitQueue[id]?.isEmpty() ?: true) && !isTurnActive(id)) {
            drainWaitQueue(id)
        } else {
            updateStopBtn()
        }
    }
    /** Stop-flush: deliver the app-held backlog to the backend, then stop.
     *  Codex needs explicit steer acknowledgements because an accepted chat
     *  POST can otherwise sit in Terminus's queue and be canceled by Stop. */
    private fun flushWaitQueue(id: String, afterDispatch: ((Boolean) -> Unit)? = null) {
        val backend = chatBackendOf(id)
        val isCodex = backend == Prefs.BACKEND_CODEX
        val localIds = waitQueue[id]?.map { it.turnId }?.toSet().orEmpty()
        val postedCodexTurns = if (isCodex) waitingTurnIds(id).filter { it !in localIds } else emptyList()
        val pending = waitQueue.remove(id) ?: mutableListOf()
        try { WaitStore(this).clear(id) } catch (_: Exception) {}
        updateStopBtn()
        if (pending.isEmpty() && postedCodexTurns.isEmpty()) {
            afterDispatch?.invoke(true)
            return
        }
        if (chatStore.get(id) == null) {
            afterDispatch?.invoke(false)
            return
        }
        if (activeSessionId == id) renderThread(id)
        if (isCodex) {
            // Promote existing server-queued messages first, in transcript
            // order. Then submit each local item and steer it before moving
            // to the next so concurrent turn/steer calls cannot reorder them.
            Thread {
                var allSubmitted = true
                for (turn in postedCodexTurns) {
                    val ok = try { OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), id, turn, backend) }
                    catch (_: Exception) { false }
                    if (ok) steeredTurnIds.add(turn) else {
                        allSubmitted = false
                        break
                    }
                }
                fun sendAt(index: Int) {
                    if (index >= pending.size) {
                        afterDispatch?.invoke(allSubmitted)
                        return
                    }
                    if (!allSubmitted) {
                        val remaining = pending.drop(index).toMutableList()
                        if (remaining.isNotEmpty()) {
                            waitQueue[id] = remaining
                            try { WaitStore(this@MainActivity).put(id, remaining.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
                        }
                        runOnUiThread { updateStopBtn() }
                        afterDispatch?.invoke(false)
                        return
                    }
                    val p = pending[index]
                    val turnId = if (p.turnId.isNotBlank()) p.turnId
                        else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
                    try {
                        startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId,
                            onSubmitted = { accepted ->
                                if (!accepted) {
                                    allSubmitted = false
                                    sendAt(index + 1)
                                } else {
                                    val steered = try { OpencodeApi.postSteer(prefs.serverUrl(), prefs.token(), id, turnId, backend) }
                                        catch (_: Exception) { false }
                                    if (steered) steeredTurnIds.add(turnId) else allSubmitted = false
                                    sendAt(index + 1)
                                }
                            })
                    } catch (_: Exception) {
                        allSubmitted = false
                        sendAt(index + 1)
                    }
                }
                sendAt(0)
            }.start()
        } else {
            val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
            val allSubmitted = java.util.concurrent.atomic.AtomicBoolean(true)
            val dispatchDone: (Boolean) -> Unit = { success ->
                if (!success) allSubmitted.set(false)
                if (remaining.decrementAndGet() == 0) afterDispatch?.invoke(allSubmitted.get())
            }
            for (p in pending) {
                val turnId = if (p.turnId.isNotBlank()) p.turnId
                    else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
                try {
                    startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId, onSubmitted = dispatchDone)
                } catch (_: Exception) { dispatchDone(false) }
            }
        }
        renderSessions()
    }
    /** Drain backlog in order, all at once: every waiting message POSTs in
     *  quick succession instead of one-per-settle, then each settles in
     *  place on its own turn. A failed POST re-queues that message and the
     *  rest behind it. */
    private fun drainWaitQueue(id: String) {
        val pending = waitQueue.remove(id) ?: return
        try { WaitStore(this).clear(id) } catch (_: Exception) {}
        updateStopBtn()
        if (pending.isEmpty() || chatStore.get(id) == null) return
        if (isTurnActive(id)) {
            // Raced a new turn — put it back.
            waitQueue[id] = pending.toMutableList()
            try { WaitStore(this).put(id, pending.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
            updateStopBtn()
            return
        }
        if (activeSessionId == id) renderThread(id)
        for ((index, p) in pending.withIndex()) {
            // The queued bubble was stored without a turn id; stamp the drained
            // turn's id now so failure status, retry and edit attach to it like
            // any direct send — otherwise its failure note can never attach and
            // floats as a standalone row forever. Rows queued with an id already
            // (Send-now release targets) keep it.
            val turnId = if (p.turnId.isNotBlank()) p.turnId
                else newTurnId().also { chatStore.markWaitSending(id, p.text, it) }
            try {
                startTurn(id, p.text, p.files, restoreText = p.text, fixedTurnId = turnId)
            } catch (_: Exception) {
                val remaining = pending.drop(index).toMutableList()
                waitQueue[id] = remaining
                try { WaitStore(this).put(id, remaining.map { WaitStore.Entry(it.text, it.files.map { f -> f.absolutePath }) }) } catch (_: Exception) {}
                updateStopBtn()
                return
            }
        }
        renderSessions()
    }

    /** Open one reasoning box ("…") per turn + stream + POST. Send-through:
     *  overlapping turns each carry their own turnId and settle in place. */
    private val sessionGates = mutableMapOf<String, java.lang.Object>()
    private val sessionAcquiring = mutableSetOf<String>()
    private fun sessionGate(id: String): java.lang.Object = synchronized(sessionGates) {
        sessionGates.getOrPut(id) { java.lang.Object() }
    }

    private fun startTurn(id: String, wireText: String, files: List<java.io.File>, restoreText: String, fixedTurnId: String = "", nativeCommandName: String = "", steer: Boolean = false, steerRequired: Boolean = false, onSubmitted: ((Boolean) -> Unit)? = null) {
        val session = chatStore.get(id)
        // Heal stale stub values ("default"/"fast"/"pro" predate live models).
        // The "/" shape is opencode-only: other backends use opaque ids
        // (sonnet, gpt-5.x-codex), which this must never wipe. Blank model
        // means follow that backend's default (server resolves it).
        val backend = try { session?.backend ?: prefs.defaultBackend() } catch (_: Exception) { prefs.defaultBackend() }
        val model = if (backend == "opencode") session?.model?.takeIf { it.contains("/") } else sendModelFor(backend, session?.model)
        if (session != null && backend == "opencode" && !session.model.isBlank() && model == null) chatStore.setModel(id, "")
        val effort = session?.effort?.takeIf { it == "low" || it == "medium" || it == "high" }
        val turnId = fixedTurnId.ifBlank { "t-${System.currentTimeMillis().toString(36)}-${(0..0xffffff).random().toString(36)}" }
        // Job/alert deliveries the chat's agent never saw ride along with this message (wire only).
        val sendText = DeliveryContext.wrap(DeliveryContext.collect(session?.messages ?: emptyList(), turnId), wireText)
        replyVoice.warmUp()   // warm the voice engine while the reply is generated
        serverIdle[id] = false
        turnRestore[turnId] = restoreText
        // A run that starts on an idle chat begins with an empty step list.
        if (pendingTurns[id].isNullOrEmpty()) progressLive[id] = emptyList()
        pendingTurns.getOrPut(id) { mutableSetOf() }.add(turnId)
        lastAskedAt[id] = System.currentTimeMillis()
        chatStore.addMessage(id, ChatMessage("assistant", "…", turnId = turnId))
        renderThread(id)
        renderSessions()
        ensureStream(id)
        ensureNotifPermission()
        // Live reply via the terminus opencode proxy. Input stays cleared on
        // success; on failure the error lands inline and the text is restored.
        // The attachment file already lives in filesDir/media (durable keep);
        // the base64 below is the transfer copy only — encoded off the UI
        // thread so image sends don't jank the tap.
        Thread {
            // Server takes at most 3 attachments per turn: send the first 3 that prepare, name the rest.
            val prepared = files.map { it to MediaFiles.prepareForUpload(it) }
            val okFiles = prepared.filter { it.second != null }
            val sent = okFiles.take(3)
            val unreadable = prepared.filter { it.second == null }.map { it.first.name }
            val overflow = okFiles.drop(3).map { it.first.name }
            val wireFiles = sent.map { val u = it.second!!; OpencodeApi.ChatAttachment(u.first, u.second, u.third) }
            if (unreadable.isNotEmpty() || overflow.isNotEmpty()) {
                val parts = mutableListOf<String>()
                if (unreadable.isNotEmpty()) parts += "not sent (unreadable or over 5MB): " + unreadable.joinToString(", ")
                if (overflow.isNotEmpty()) parts += "not sent (limit is 3 files per message): " + overflow.joinToString(", ")
                runOnUiThread {
                    postNote(id, "tool", parts.joinToString("; ") + ". Kept on device." +
                        if (wireFiles.isEmpty()) " Message sent text-only." else "")
                }
            }
            // Single POST per turn (double-POST fix): exactly one retry, only
            // on socket-level aborts. turnId binds this POST to its own "…".
            // The chat owns its opencode session id (remoteId): blank means
            // "create", and the reply's sessionID is adopted. A 404 `no
            // session` means opencode lost it — clear, note it, resend once
            // fresh (same turnId; the failed attempt reserved nothing).
            var accept: OpencodeApi.ChatAccept? = null
            var failure: Exception? = null
            var sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
            // Only initial session acquisition serializes: a blank send
            // while another blank send is acquiring waits (≤30s) for the
            // adopted id, then posts normally into that same session — one
            // chat, one session. Post-acquisition sends stay concurrent.
            val gate = sessionGate(id)
            var creator = false
            synchronized(gate) {
                sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                if (sid.isBlank()) {
                    if (sessionAcquiring.add(id)) creator = true
                    else {
                        var waited = 0
                        while (sessionAcquiring.contains(id) && waited < 30000) {
                            try { (gate as java.lang.Object).wait(1000) } catch (_: InterruptedException) {}
                            waited += 1000
                        }
                        sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                        if (sid.isBlank() && sessionAcquiring.add(id)) creator = true
                    }
                }
            }
            try {
            for (attempt in 0..2) {
                try {
                    // Title handoff: only a manual override travels. Auto stubs
                    // stay local so opencode titles the fresh session itself;
                    // the sync then adopts the genuine title. Sending the
                    // fallback would stamp it server-side and we'd "follow"
                    // our own echo forever.
                    val lockedTitle = session?.title?.takeIf { session?.titleLocked == true }
                    accept = OpencodeApi.postChat(
                        prefs.serverUrl(), prefs.token(),
                        chatId = id, sessionID = sid.ifBlank { null }, title = lockedTitle, text = sendText, model = model,
                        attachments = wireFiles, turnId = turnId,
                        backend = try { chatStore.get(id)?.backend } catch (_: Exception) { null },
                        effort = effort,
                        cwd = try { chatStore.get(id)?.cwd?.ifBlank { null } } catch (_: Exception) { null },
                        nativeCommandName = nativeCommandName.ifBlank { null },
                        steer = steer,
                        steerRequired = steerRequired)
                    failure = null
                    break
                } catch (e: Exception) {
                    failure = e
                    val m = (e.message ?: "").lowercase()
                    // Dead remote session heals by resending fresh: opencode
                    // "no session" and backend "session_not_found" alike.
                    // The chat keeps its backend; only the dead remote id is
                    // dropped (a mismatched id heals the same way).
                    if (attempt == 0 && sid.isNotBlank() && ("no session" in m || "session_not_found" in m)) {
                        sid = ""
                        try { chatStore.setRemoteId(id, "") } catch (_: Exception) {}
                        continue
                    }
                    val retryable = attempt == 0 &&
                        (m.contains("abort") || m.contains("reset") || m.contains("broken pipe") ||
                            m.contains("econnreset") || m.contains("sockettimeout") || m.contains("timeout"))
                    if (!retryable) break
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                }
            }
            val adopted = accept?.sessionID.takeUnless { it.isNullOrBlank() }
            if (adopted != null && adopted != sid) {
                sid = adopted
                try { chatStore.setRemoteId(id, adopted) } catch (_: Exception) {}
            }
            } finally {
                if (creator) synchronized(gate) { sessionAcquiring.remove(id); (gate as java.lang.Object).notifyAll() }
            }
            val ok = accept
            val accepted = ok != null && (ok.accepted || !ok.text.isNullOrBlank())
            onSubmitted?.invoke(accepted && (!steerRequired || ok?.steered == true))
            if (ok != null && (ok.accepted || !ok.text.isNullOrBlank())) {
                // Accepted: the outcome arrives over /api/stream (`done` with
                // this turnId) — startStream is already running. Legacy sync
                // reply (older server) settles immediately with the turnId.
                runOnUiThread { syncThread(id) }
                if (!ok.accepted) {
                    val replyText = ok.text ?: ""
                    runOnUiThread { finishStreamTurn(id, turnId, replyText, "", "") }
                }
            } else {
                val msg = failure?.message ?: "network error"
                // Never reached the Mac: the message waits in the thread and goes out by itself (Outbox).
                val waiting = Outbox.neverSent(failure) &&
                    (try { chatStore.userTurnFor(id, turnId) } catch (_: Exception) { null }) != null
                val waitReason = if (waiting) Outbox.PREFIX + Outbox.reason(this, prefs.serverUrl()) else ""
                runOnUiThread {
                    pendingTurns[id]?.remove(turnId)
                    turnRestore.remove(turnId)
                    dropTurnBuf(turnId)
                    chatStore.dropPlaceholder(id, turnId)
                    if (waiting && chatStore.setUserSendFailed(id, turnId, true, waitReason)) {
                        if (activeSessionId == id) renderThread(id)
                        renderSessions()
                        reconcileTurnState(id)
                        scheduleOutboxRetry()
                        return@runOnUiThread
                    }
                    if (!chatStore.setUserSendFailed(id, turnId, true, msg)) {
                        chatStore.addMessage(id, ChatMessage(
                            "tool", "send failed ($msg), your text was kept in the box"))
                    }
                    if (activeSessionId == id) {
                        composerInput.setText(restoreText)
                        renderThread(id)
                    }
                    renderSessions()
                    reconcileTurnState(id)
                }
            }
        }.start()
    }

    /** Fork a chat: local history duplicated + server session forked onto the
     *  twin id. Id-bound throughout; the twin opens active. Live placeholders
     *  never copy (their turns settle into the source thread). */
    /** Thread overflow: rename, pin, fork, delete for the open chat.
     *  Same actions as the list row menu, without leaving the thread. */
    /** Shared select-style sheet: title plus two-line rows with optional
     *  check, same rows as the model/backend selects. Replaces the plain
     *  text popups so every sheet reads as one component. */
    private data class SelectRow(
        val title: String,
        val sub: String = "",
        val checked: Boolean = false,
        val action: () -> Unit = {},
        /** Optional leading logo (drawable id, 0 = none). */
        val icon: Int = 0,
        /** Shown but not pickable (an unavailable backend). */
        val dim: Boolean = false,
    )

    private fun showSelectSheet(title: String, rows: List<SelectRow>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val v = layoutInflater.inflate(R.layout.sheet_simple_menu, null)
        sheet.setContentView(v)
        val titleView: TextView = v.findViewById(R.id.menuTitle)
        titleView.text = title
        val list: android.widget.ListView = v.findViewById(R.id.menuList)
        list.divider = null
        val adapter = object : android.widget.ArrayAdapter<SelectRow>(this, R.layout.item_select, rows) {
            override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup): View {
                val row = convert ?: layoutInflater.inflate(R.layout.item_select, parent, false)
                val r = getItem(pos) ?: return row
                val name: TextView = row.findViewById(R.id.selectName)
                val sub: TextView = row.findViewById(R.id.selectSub)
                val check: TextView = row.findViewById(R.id.selectCheck)
                name.text = r.title
                if (r.sub.isBlank()) sub.visibility = View.GONE
                else { sub.visibility = View.VISIBLE; sub.text = r.sub }
                check.visibility = if (r.checked) View.VISIBLE else View.GONE
                val icon: android.widget.ImageView = row.findViewById(R.id.selectIcon)
                if (r.icon != 0) { icon.setImageResource(r.icon); icon.visibility = View.VISIBLE } else icon.visibility = View.GONE
                row.alpha = if (r.dim) 0.45f else 1f
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            sheet.dismiss()
            try { rows[pos].action() } catch (_: Exception) {}
        }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    private fun showActivitySheet(rows: List<ActivityEntry>, onSelect: (Int) -> Unit) {
        // Lightweight inbox reads as a center modal, not a sheet: the bell
        // sits top, the list is short, glance-and-pick needs no thumb zone.
        val list = android.widget.ListView(this).apply {
            divider = null
            adapter = activitySheetAdapter(rows)
        }
        val dlg = AlertDialog.Builder(this, R.style.CreamDialog)
            .setTitle("Activity")
            .setView(list)
            .setNegativeButton("close", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            if (rows.getOrNull(position) !is ActivityEntry.Header) {
                dlg.dismiss()
                onSelect(position)
            }
        }
        dlg.show()
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()

    /** Session goal UI: arm sheet, status strip, manage sheet.
     *  The strip shows only when the open chat has a goal; tap opens
     *  pause/resume/edit/remove. Server owns state; client only renders. */
    private var goalCache: OpencodeApi.GoalInfo? = null

    /** Set goal as a bottom sheet (select-style, prefillable for edits).
     *  Arm re-arms: server resets stuck/continuations, keeps verdicts. */
    private fun goalDialog(prefill: String? = null, loopDefault: Boolean = false) {
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

    private fun goalManageDialog() {
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

    private fun goalAction(action: String) {
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
    private fun refreshGoalStrip(id: String) {
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

    private fun paintGoalStrip() {
        if (!::goalStrip.isInitialized || !::goalStripBar.isInitialized) return
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
                setColor(if (tint == 0) base else pastel(base, androidx.core.content.ContextCompat.getColor(this@MainActivity, tint)))
            }
            goalStripBar.background = bg
        } catch (_: Exception) {}
    }

    /** The strip's ✕: stop watching this chat now. Same effect as the
     *  sheet's "Remove goal" (server + local), minus the detour. */
    private fun goalClearGoal() {
        if (activeSessionId == null) return
        goalAction("remove")
    }

    /** Pastel wash: mostly base, a breath of state. */
    private fun pastel(base: Int, tint: Int): Int {
        fun ch(b: Int, t: Int): Int = (b * 4 + t) / 5
        return android.graphics.Color.rgb(
            ch(android.graphics.Color.red(base), android.graphics.Color.red(tint)),
            ch(android.graphics.Color.green(base), android.graphics.Color.green(tint)),
            ch(android.graphics.Color.blue(base), android.graphics.Color.blue(tint)),
        )
    }

    /** Loop trail: server nudge events become divider rows, deduplicated
     *  by timestamp. Renders in place without moving reading position. */
    private fun syncGoalNudges(id: String, g: OpencodeApi.GoalInfo?) {
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

    /** Plus sheet: attach rows plus Set goal, GPT-style. Same rows on
     *  every backend: all of them accept files, so capability-gating here
     *  only ever hid working features behind a stale catalog. */
    private fun attachSheetDialog() {
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

    private fun pickAttach(mime: String, kind: String) {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = mime
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            attachLauncher.launch(android.content.Intent.createChooser(i, kind))
        } catch (_: Exception) {}
    }

    private fun threadMenuDialog() {
        val id = activeSessionId ?: return
        val s = try { chatStore.get(id) } catch (_: Exception) { null } ?: return
        val goalState = goalCache?.let { GoalUi.manageSub(it) } ?: ""
        showSelectSheet(s.title.ifBlank { "untitled" }, listOf(
            SelectRow("Rename", s.title.ifBlank { "untitled" }.take(60), action = { renameDialog(s) }),
            SelectRow(
                if (s.pinned) "Unpin" else "Pin",
                "keep at top",
                checked = s.pinned,
                action = {
                    chatStore.setPinned(s.id, !s.pinned)
                    renderSessions()
                    renderThread(s.id)
                },
            ),
            SelectRow("Export chat", "save as a text file", action = { exportChat(s) }),
            SelectRow("Fork", "copy history to a new chat", action = { forkChat(s.id, null) }),
            SelectRow("Set goal", goalState.ifBlank { "loop until done" }, action = { goalDialog() }),
            SelectRow("Delete", action = { deleteSessionNow(s) }),
        ))
    }

    /** Plain-text export: one block per user, assistant or error message, handed to the share sheet as a .txt. */
    private fun exportChat(s: ChatSession) {
        pruneShareExports()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val who = backendDisplayName(s.backend)
        val body = buildString {
            append(s.title.ifBlank { "untitled" }).append('\n')
            append("Backend: ").append(who).append(" · Model: ").append(s.model.ifBlank { "default" }).append('\n')
            append("Exported: ").append(stamp.format(java.util.Date())).append("\n\n")
            val kept = s.messages.filter { it.role == "user" || it.role == "assistant" || it.role == "error" }
            for (m in kept) {
                val text = m.text.trim()
                if (text.isEmpty() && m.allAttachments.isEmpty()) continue
                val label = when (m.role) {
                    "user" -> "You"
                    "assistant" -> who
                    else -> "Error"
                }
                append("[").append(stamp.format(java.util.Date(m.ts))).append("] ").append(label).append(":\n")
                if (text.isNotEmpty()) append(text).append('\n')
                for (a in m.allAttachments) append("(attachment: ").append(a.substringAfterLast('/')).append(")\n")
                append('\n')
            }
        }
        Thread {
            try {
                val dir = File(cacheDir, "share-out").apply { mkdirs() }
                val safe = s.title.ifBlank { "chat" }.take(60).replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "chat" }
                val file = File(dir, "${System.currentTimeMillis()}_$safe.txt")
                file.writeText(body)
                runOnUiThread {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, s.title)
                            clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Export chat").apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "Couldn't open the share sheet", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Couldn't write the export", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun forkChat(id: String, messageID: String?) {
        val s = chatStore.get(id) ?: return
        if (!capsForBackend(backendCatalog, s.backend).fork) {
            val note = ChatMessage("tool", "${backendFailurePrefix(s.backend)}fork is unavailable")
            chatStore.addMessage(id, note)
            if (activeSessionId == id) renderThread(id)
            renderSessions()
            return
        }
        val sid = s.remoteId
        val fork = chatStore.create(s.backend, s.model)
        chatStore.rename(fork.id, s.title + " (fork)", locked = true)
        s.messages.filter { !(it.role == "assistant" && it.turnId.isNotBlank() && !it.settled) }.forEach { chatStore.addMessage(fork.id, it.copy()) }
        // The twin must exist on disk before it opens, not after a debounce.
        try { chatStore.flushNow() } catch (_: Exception) { }
        openThread(fork.id)
        if (sid.isBlank()) {
            val local = ChatMessage("tool",
                "fork is local-only (nothing sent yet), history copied, agent starts fresh")
            chatStore.addMessage(fork.id, local)
            if (activeSessionId == fork.id && !appendRowDom(fork.id, local)) renderThread(fork.id)
            return
        }
        Thread {
            try {
                val forkSid = OpencodeApi.postFork(prefs.serverUrl(), prefs.token(), sid, messageID, s.backend, fork.id)
                runOnUiThread {
                    if (forkSid.isNotBlank()) chatStore.setRemoteId(fork.id, forkSid)
                    postNote(fork.id, "event", "forked with agent context")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val local = ChatMessage("tool",
                        "fork is local-only (${e.message ?: "server fork failed"}), history copied, agent starts fresh")
                    chatStore.addMessage(fork.id, local)
                    if (activeSessionId == fork.id && !appendRowDom(fork.id, local)) renderThread(fork.id)
                }
            }
        }.start()
    }

    /** Pull-to-refresh: fetch server sessions and merge without touching
     *  local state. Mapped chats keep everything (keyed by session id);
     *  unmapped rows render importable below. Failures end the spinner
     *  with a toast; the list is unchanged. */
    private fun refreshSessions() {
        if (sessionsLoading) return
        sessionsLoading = true
        Thread {
            try {
                val remote = OpencodeApi.fetchSessions(prefs.serverUrl(), prefs.token(), prefs.defaultBackend())
                runOnUiThread {
                    sessionsLoading = false
                    try { sessionPull.isRefreshing = false } catch (_: Exception) {}
                    serverImports = remote
                    renderSessions()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    sessionsLoading = false
                    try { sessionPull.isRefreshing = false } catch (_: Exception) {}
                    android.widget.Toast.makeText(this,
                        "Couldn't refresh your chats (${e.message ?: "no connection"})",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Delete a server-only session (import row). Real provider delete
     *  where supported (opencode remove, codex thread/delete); anything
     *  else fails explicitly and the row comes back honestly.
     *  Tombstone before the request so a racing refresh can't resurrect it. */
    private fun deleteRemoteSession(imp: OpencodeApi.RemoteSession) {
        try { chatStore.buryRemote(imp.id) } catch (_: Exception) {}
        serverImports = serverImports.filter { it.id != imp.id }
        renderSessions()
        Thread {
            try {
                OpencodeApi.deleteRemote(prefs.serverUrl(), prefs.token(), imp.id, imp.backend)
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Deleted from the server",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                try { chatStore.unburyRemote(imp.id) } catch (_: Exception) {}
                runOnUiThread {
                    refreshSessions()
                    android.widget.Toast.makeText(this,
                        "Couldn't delete it from the server (${e.message ?: "no connection"})",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Import a server session: fresh local chat adopting the session id as
     *  its remoteId — no server call (there is no map anymore). The
     *  transcript backfills from /api/history when the server still has it
     *  (the thread opens with its messages); otherwise the thread opens
     *  empty with agent context, as before. The double-open guard re-checks
     *  after the fetch so a double-tap can't twin the chat. */
    private fun importSession(imp: OpencodeApi.RemoteSession) {
        try {
            if (chatStore.remoteIds().contains(imp.id)) {
                android.widget.Toast.makeText(this, "That chat is already in your list",
                    android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Exception) {}
        Thread {
            val history = try {
                OpencodeApi.fetchHistory(prefs.serverUrl(), prefs.token(), imp.id, imp.backend)
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                try {
                    if (chatStore.remoteIds().contains(imp.id)) {
                        android.widget.Toast.makeText(this, "That chat is already in your list",
                            android.widget.Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                } catch (_: Exception) {}
                // Preserve the backend that owns this remote session.
                val s = chatStore.create(imp.backend, prefs.defaultModel(imp.backend))
                if (imp.title.isNotBlank()) chatStore.rename(s.id, imp.title, locked = true)
                chatStore.setRemoteId(s.id, imp.id)
                chatStore.unburyRemote(imp.id)
                var n = 0
                for (h in history) {
                    chatStore.addMessage(s.id, ChatMessage(
                        h.role, h.text, ts = h.ts, settled = true))
                    n++
                }
                if (n == 0) chatStore.addMessage(s.id, ChatMessage("event", "imported with agent context"))
                try { chatStore.flushAsync() } catch (_: Exception) {}
                serverImports = serverImports.filter { it.id != imp.id }
                openThread(s.id)
                if (activeSessionId == s.id) renderThread(s.id)
                if (n > 0) {
                    android.widget.Toast.makeText(this, "imported $n messages",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Settle verified-dead turns without a remote kill. onlyStale=false is
     *  the explicit user tap or an authoritative 404: partials rescue,
     *  blanks drop, late dones barred like a successful stop, note posted.
     *  onlyStale=true (automatic idle/ticker paths) never amputates: server
     *  idle plus 90s silence is NOT evidence of death for long coding turns
     *  in quiet tool gaps — amputating pours the turn into delivered and
     *  drops its placeholder, so the eventual reply has nowhere to land.
     *  Instead each target reconciles by exact turnId: settled ones finish
     *  normally, everything else stands untouched (no note, no pour, no
     *  drop) with retry available. */
    private fun settleDeadTurns(id: String, onlyStale: Boolean, note: String) {
        val live = try { chatStore.liveTurns(id) } catch (_: Exception) { emptyList() }
        // Automatic paths arrive pre-verified (working == false); the tap
        // path verified via the 404. Either way the target decision is the
        // unit-tested pure rule, never ad-hoc filtering at the call site.
        val targets = TurnActivity.autoSettleTargets(live, System.currentTimeMillis(), false, onlyStale)
        if (targets.isEmpty()) return
        if (onlyStale) {
            for (t in targets) reconcileTurn(id, t)
            return
        }
        markDeliveredBulk(id, targets.toList())
        targets.forEach { dropTurnBuf(it) }
        pendingTurns[id]?.clear()
        for (t in targets) {
            val partial = try { chatStore.placeholderText(id, t) } catch (_: Exception) { null }
            if (partial != null) {
                try { chatStore.settleTurn(id, t, partial, stepsJson(progressLive[id] ?: emptyList())) } catch (_: Exception) {}
            } else {
                try { chatStore.dropPlaceholder(id, t) } catch (_: Exception) {}
            }
        }
        try { chatStore.flushAsync() } catch (_: Exception) {}
        settleProgress(id)
        val cleared = ChatMessage("tool", note)
        chatStore.addMessage(id, cleared)
        if (activeSessionId == id && !appendRowDom(id, cleared)) renderThread(id)
        renderSessions()
        updateStopBtn()
        updateStopBtn()
        if (!(waitQueue[id]?.isEmpty() ?: true)) drainWaitQueue(id)
    }

    /** One-shot dead-turn verification for the ticker: a confirmed-idle
     *  session means nothing will ever arrive, so stale orphans settle on
     *  their own. Working or unverifiable sessions keep observing. */
    /** Deliver orphans already SETTLED server-side whose `done` never
     *  arrived (dead stream, locally-closed turn). Only registry-confirmed
     *  settled turns with real text route through the normal path; the
     *  caller still runs its stale-settle after (it re-reads live turns,
     *  so delivered ones are never double-touched). Safe off the UI
     *  thread; transport failures leave orphans for the settle. */
    private fun recoverOrphanTexts(id: String) {
        val orphans = try {
            chatStore.liveTurns(id).map { it.first }.filter { it.isNotBlank() }
        } catch (_: Exception) { return }
        for (t in orphans) {
            try {
                val r = OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), id, t, chatBackendOf(id))
                // Error-only settlements count too: the error note must reach
                // the thread, not dissolve into a generic receipt.
                if (r.settled && (r.text.isNotBlank() || r.error.isNotBlank())) {
                    val ft = r.text; val fe = r.error; val fm = r.messageID
                    runOnUiThread { finishStreamTurn(id, t, ft, fe, fm, r.code) }
                }
            } catch (_: Exception) {
                // Unknown/unreachable: left for the stale-settle.
            }
        }
    }

    private fun verifyStopIdle(id: String) {
        try {
            val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
            if (sid.isBlank()) return
            val working = OpencodeApi.fetchProgressWorking(prefs.serverUrl(), prefs.token(), id, sid, chatBackendOf(id))
            if (working == false) {
                recoverOrphanTexts(id)
                runOnUiThread { settleDeadTurns(id, onlyStale = true, "cleared, nothing was running") }
            }
        } catch (_: Exception) {
        } finally {
            autoVerifying.remove(id)
        }
    }

    private fun stopBackendTurn(id: String, onFinished: (() -> Unit)? = null) {
        val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
        if (sid.isBlank()) {
            postNote(id, "tool", "nothing running, nothing sent yet")
            onFinished?.invoke()
            return
        }
        Thread {
            try {
                OpencodeApi.postStop(prefs.serverUrl(), prefs.token(), sid, chatBackendOf(id), id)
                runOnUiThread {
                    // Cancelled: drop every pending placeholder so no late
                    // done can resurrect a reply; late dones are ignored.
                    val inFlight = pendingTurns[id]?.toList() ?: emptyList()
                    markDeliveredBulk(id, inFlight)
                    inFlight.forEach { dropTurnBuf(it) }
                    pendingTurns[id]?.clear()
                    chatStore.dropAllPlaceholders(id)
                    try { chatStore.flushNow() } catch (_: Exception) {}
                    settleProgress(id)
                    idleStreamHandoff(id)
                    val stopped = ChatMessage("event", "interrupted")
                    chatStore.addMessage(id, stopped)
                    if (activeSessionId == id && !appendRowDom(id, stopped)) renderThread(id)
                    renderSessions()
                    updateStopBtn()
                    updateStopBtn()
                    onFinished?.invoke()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val msg = e.message ?: "network error"
                    if (msg.contains("no session", ignoreCase = true) ||
                        msg.contains("bad sessionid", ignoreCase = true)) {
                        settleDeadTurns(id, onlyStale = false, "nothing was running, cleared")
                        onFinished?.invoke()
                        return@runOnUiThread
                    }
                    postNote(id, "tool", "stop failed ($msg), still checking")
                    updateStopBtn()
                    onFinished?.invoke()
                }
            }
        }.start()
    }

    private fun handleCommand(id: String, raw: String, retriedCommand: Boolean = false) {
        val parts = raw.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim() ?: ""
        val s = chatStore.get(id)
        when (cmd) {
            "/new" -> openDraft()
            "/search" -> {
                closeThread()
                searchOpen = true
                sessionSearch.setText(arg)
                sessionQuery = arg
                sessionSearch.visibility = View.VISIBLE
                searchIcon.setImageResource(R.drawable.ic_close)
                if (arg.isBlank()) {
                    sessionSearch.requestFocus()
                    sessionSearch.post {
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(sessionSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                renderSessions()
            }
            "/fetch" -> {
                // No special fetch path: the URL goes to the agent as a
                // normal message, which fetches with its own tools.
                if (arg.isBlank()) {
                    postNote(id, "tool", "Usage: /fetch <url>")
                } else {
                    sendVisibleTurn(id, "Fetch and summarize this URL: $arg", emptyList(), "/fetch $arg")
                }
            }
            "/think" -> {
                // No separate careful mode: the question goes to the agent
                // as a normal message with an explicit thoroughness ask.
                if (arg.isBlank()) {
                    postNote(id, "tool", "Usage: /think <question>")
                } else {
                    sendVisibleTurn(id, "Think carefully and answer thoroughly: $arg", emptyList(), "/think $arg")
                }
            }
            "/clear" -> {
                // Terminal clear: settled history goes, the session and
                // live turns stay, server untouched. In-flight replies
                // land on their standing placeholders as normal.
                val live = try { pendingTurns[id]?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
                chatStore.clearHistory(id, live)
                postNote(id, "event", "history cleared")
                try { chatStore.markRead(id) } catch (_: Exception) {}
                if (activeSessionId == id) renderThread(id)
                renderSessions()
            }
            "/stop" -> {
                val hasLocalWaiting = !(waitQueue[id]?.isEmpty() ?: true)
                val hasPostedCodexWaiting = chatBackendOf(id) == Prefs.BACKEND_CODEX && waitingTurnIds(id).isNotEmpty()
                if (hasLocalWaiting || hasPostedCodexWaiting) {
                    if (!stopInProgress.add(id)) return
                    // Flush queued messages to the backend and wait for every
                    // chat POST response before issuing the stop request.
                    flushWaitQueue(id) { allSubmitted ->
                        runOnUiThread {
                            if (allSubmitted) {
                                stopBackendTurn(id) { stopInProgress.remove(id) }
                            } else {
                                stopInProgress.remove(id)
                                postNote(id, "tool", "some queued messages couldn't reach the backend; stop wasn't sent. Retry the failed messages, then stop")
                                updateStopBtn()
                            }
                        }
                    }
                    return
                }
                val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                if (sid.isBlank()) {
                    postNote(id, "tool", "nothing running, nothing sent yet")
                } else Thread {
                    try {
                        OpencodeApi.postStop(prefs.serverUrl(), prefs.token(), sid, chatBackendOf(id), id)
                        runOnUiThread {
                            // Cancelled: drop every pending placeholder so no
                            // late `done` can resurrect a reply; late dones
                            // arrive with known turnIds and are ignored.
                            // Mark all known in-flight turns delivered so their
                            // late dones are ignored on arrival.
                            val inFlight = pendingTurns[id]?.toList() ?: emptyList()
                            markDeliveredBulk(id, inFlight)
                            inFlight.forEach { dropTurnBuf(it) }
                            pendingTurns[id]?.clear()
                            chatStore.dropAllPlaceholders(id)
                            // Explicit user kill: the drop must be durable now,
                            // not after the write-debounce.
                            try { chatStore.flushNow() } catch (_: Exception) {}
                            settleProgress(id)
                            // Shared handoff, not a bare stop: another chat
                            // with live turns adopts the stream, otherwise it
                            // sleeps. Stopping one chat must not strand the rest.
                            idleStreamHandoff(id)
                            val stopped = ChatMessage("event", "interrupted")
                            chatStore.addMessage(id, stopped)
                            if (activeSessionId == id && !appendRowDom(id, stopped)) renderThread(id)
                            renderSessions()
                            updateStopBtn()
                            updateStopBtn()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            val msg = e.message ?: "network error"
                            // Authoritative "nothing there" (server 404: the
                            // session is gone, e.g. killed by an opencode
                            // restart mid-turn): keeping the placeholder
                            // would stick Stop forever with no remote turn
                            // to stop. Settle orphans locally — partial
                            // text is kept as the reply — and bar late
                            // dones exactly like a successful stop.
                            // Anything else (503, transport) is unknown:
                            // keep observing for the eventual `done`.
                            if (msg.contains("no session", ignoreCase = true) ||
                                msg.contains("bad sessionid", ignoreCase = true)) {
                                settleDeadTurns(id, onlyStale = false, "nothing was running, cleared")
                                return@runOnUiThread
                            }
                            postNote(id, "tool", "stop failed ($msg), still checking")
                            updateStopBtn()
                        }
                    }
                }.start()
            }
            "/compact" -> {
                val backend = chatBackendOf(id) ?: prefs.defaultBackend()
                if (backendCatalog == null && Prefs.cannonBackend(backend) != Prefs.BACKEND_OPENCODE) {
                    // No catalog yet (app just opened, server was restarting): the fallback caps say "no" for
                    // everything, which is not an answer. Ask the server once, then run the command.
                    Thread {
                        val cat = try { OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token()) } catch (_: Exception) { null }
                        runOnUiThread {
                            if (cat != null) { backendCatalog = cat; handleCommand(id, raw) }
                            else postNote(id, "event", "can't reach the server to check compact, try again in a moment")
                        }
                    }.start()
                    return
                }
                if (!capsForBackend(backendCatalog, backend).compact) {
                    postNote(id, "event", "${backendFailurePrefix(backend)}has no /compact (its sessions can't be summarised from here)")
                    return
                }
                val sid = try { chatStore.get(id)?.remoteId ?: "" } catch (_: Exception) { "" }
                if (sid.isBlank()) {
                    postNote(id, "event", "nothing to compact, nothing sent yet")
                } else {
                    // Compaction emits no turn events, so the stream stays
                    // quiet throughout — an optimistic row is the only feed.
                    val working = ChatMessage("event", "compacting context…")
                    chatStore.addMessage(id, working)
                    if (activeSessionId == id && !appendRowDom(id, working)) renderThread(id)
                    Thread {
                        var serverMsg = ""
                        var serverSummary = ""
                        var failWhy = ""
                        try {
                            val r = OpencodeApi.postCompact(prefs.serverUrl(), prefs.token(), sid, backend, id)
                            serverMsg = r.first
                            serverSummary = r.second
                        } catch (e: Exception) {
                            // The server says why (a reply still running, session gone, nothing to compact):
                            // show that instead of a bare "failed".
                            failWhy = (e.message ?: "").removePrefix("/api/compact:").trim().take(120)
                        }
                        runOnUiThread {
                            // Center-stage receipt: the full summary as its own
                            // divider + markdown row. No summary (older server
                            // or failed read-back) falls back to the short
                            // receipt tool row.
                            val done = if (serverSummary.isNotBlank()) ChatMessage("compact", serverSummary)
                                else ChatMessage("event", serverMsg.ifBlank { if (failWhy.isNotBlank()) "compaction failed ($failWhy)" else "compaction failed" })
                            // The live feed may have already posted its own
                            // outcome row; the manual receipt (with summary)
                            // replaces it rather than stacking.
                            chatStore.dropLastMatching(id, "compacting context…")
                            chatStore.dropLastMatching(id, "compacting context (auto)…")
                            chatStore.dropLastMatching(id, "compacting context (manual)…")
                            chatStore.dropLastMatching(id, "context compacted, history kept")
                            chatStore.dropLastMatching(id, "context compacted (auto), history kept")
                            chatStore.dropLastMatching(id, "context compacted (manual), history kept")
                            chatStore.addMessage(id, done)
                            if (activeSessionId == id && !appendRowDom(id, done)) renderThread(id)
                            renderSessions()
                        }
                    }.start()
                }
            }
            "/delete" -> { chatStore.delete(id); closeThread() }
            "/sessions" -> closeThread()
            "/models" -> modelDialog(arg)
            "/model" -> {
                if (arg.isBlank()) {
                    val cur = s?.model?.takeIf { it.contains("/") }
                    chatStore.addMessage(id, ChatMessage("tool",
                        "Model for this chat: ${cur?.let { midId(it) } ?: "Default (server default)"}"))
                    renderThread(id)
                } else if (s == null) {
                    renderThread(id)
                } else {
                    val target = s
                    Thread {
                        val b = activeBackend()
                        var list = modelsForBackend(b)
                        var catalogUnavailable = false
                        if (list.isEmpty()) {
                            try {
                                list = OpencodeApi.fetchModels(prefs.serverUrl(), prefs.token(), b)
                            } catch (_: Exception) {
                                catalogUnavailable = true
                            }
                        }
                        val hit = list.firstOrNull { it.id.equals(arg, true) }
                            ?: list.firstOrNull { it.short.equals(arg, true) }
                        runOnUiThread {
                            if (catalogUnavailable) {
                                remoteModelsByBackend.remove(b)
                                unavailableModelCatalogs.add(b)
                            } else {
                                remoteModelsByBackend[b] = list
                                unavailableModelCatalogs.remove(b)
                            }
                            if (hit != null) {
                                chatStore.setModel(id, hit.id)
                                renderThread(id)
                            } else if (list.isNotEmpty()) {
                                chatStore.addMessage(id, ChatMessage("tool",
                                    "Unknown model \"$arg\". Try /models to browse ${list.size} models."))
                                renderThread(id)
                            } else {
                                chatStore.addMessage(id, ChatMessage("tool",
                                    if (unavailableModelCatalogs.contains(b))
                                        "Live model list unavailable for $b. Check the server, then try /models again."
                                    else "No models are currently available for $b."))
                                renderThread(target.id)
                            }
                            renderSessions()
                        }
                    }.start()
                }
            }
            "/current" -> {
                val cur = s?.model?.takeIf { it.contains("/") }
                chatStore.addMessage(id, ChatMessage("tool",
                    "Model for this chat: ${cur?.let { midId(it) } ?: "Default (server default)"}"))
                renderThread(id)
            }
            "/cwd" -> {
                // Per-chat working directory for codex turns. Blank shows
                // current; anything else is any folder (the server expands ~
                // and re-resolves every turn).
                if (s == null) {
                    renderThread(id)
                } else if (arg.isBlank()) {
                    val cur = s.cwd.ifBlank { "~/Desktop" }
                    chatStore.addMessage(id, ChatMessage("tool", "Working dir for this chat: $cur"))
                    renderThread(id)
                } else {
                    // Shape check only (~/..., or absolute); the server owns
                    // home expansion.
                    val want = arg.trim()
                    if (!want.startsWith("~/") && !want.startsWith("/")) {
                        chatStore.addMessage(id, ChatMessage("tool",
                            "Working dir must start with ~/ or /, got: ${want.take(80)}"))
                        renderThread(id)
                    } else {
                        chatStore.setCwd(id, want)
                        renderThread(id)
                        renderSessions()
                    }
                }
            }
            "/fork" -> {
                if (s != null) forkChat(id, arg.trim().ifBlank { null })
            }
            "/usage" -> {
                if (chatBackendOf(id) == "codex") {
                    if (arg.isNotBlank()) {
                        chatStore.addMessage(id, ChatMessage("tool", "Usage: /usage"))
                        renderThread(id)
                    } else {
                        Thread {
                            val result = try {
                                OpencodeApi.postBackendCommand(prefs.serverUrl(), prefs.token(), "codex", cmd)
                            } catch (e: Exception) { "Codex command failed: ${e.message ?: "network error"}" }
                            runOnUiThread { appendCommandResult(id, result) }
                        }.start()
                    }
                } else if (chatBackendOf(id) == "claude" && liveCommands.any { liveCommandsBackend == "claude" && it.name.equals(cmd, true) }) {
                    // Claude's own /usage (plan windows, reset times) runs through the CLI like any native command.
                    sendVisibleTurn(id, raw, emptyList(), raw)
                } else showChatUsageCommand(id, cmd)
            }
            "/cost", "/context" -> showChatUsageCommand(id, cmd)
            "/status" -> showBackendStatusCommand(id)
            "/resets", "/redeem" -> {
                if (chatBackendOf(id) != "codex") {
                    chatStore.addMessage(id, ChatMessage("tool", "$cmd is available in Codex chats."))
                    renderThread(id)
                } else if (cmd != "/redeem" && arg.isNotBlank()) {
                    chatStore.addMessage(id, ChatMessage("tool", "Usage: $cmd"))
                    renderThread(id)
                } else {
                    Thread {
                        val result = try {
                            OpencodeApi.postBackendCommand(prefs.serverUrl(), prefs.token(), "codex", cmd, arg)
                        } catch (e: Exception) { "Codex command failed: ${e.message ?: "network error"}" }
                        runOnUiThread {
                            val message = ChatMessage("tool", result)
                            chatStore.addMessage(id, message)
                            if (activeSessionId == id && !appendRowDom(id, message)) renderThread(id)
                        }
                    }.start()
                }
            }
            else -> {
                // A command typed before the list loaded (or after it went stale) waits for it once.
                if (liveCommandsBackend != chatBackendOf(id) && !retriedCommand && activeSessionId == id) {
                    refreshCommands { handleCommand(id, raw, retriedCommand = true) }
                    return
                }
                val native = liveCommands.firstOrNull { liveCommandsBackend == chatBackendOf(id) && it.name.equals(cmd, true) }
                if (native != null && native.skill) {
                    // A Terminus skill on a backend without skill commands: ask for it in words, show what was typed.
                    val name = native.name.removePrefix("/")
                    sendVisibleTurn(id, "Use the $name skill." + if (arg.isNotBlank()) " $arg" else "", emptyList(), raw)
                } else if (native != null && chatBackendOf(id) == "opencode") {
                    sendVisibleTurn(id, raw, emptyList(), raw, nativeCommandName = native.name.removePrefix("/"))
                } else if (native != null && chatBackendOf(id) == "claude") {
                    // Claude runs its own slash commands and skills: the text goes through as a normal prompt.
                    sendVisibleTurn(id, raw, emptyList(), raw)
                } else {
                    chatStore.addMessage(id, ChatMessage("tool",
                        "Unknown command \"$cmd\". Type / to see this chat's commands and skills."))
                    renderThread(id)
                }
            }
        }
        renderSessions()
    }

    /** App-side usage commands use the same telemetry as the context ring.
     *  Claude's cost is an API-price estimate, not a subscription invoice or quota meter. */
    private fun showChatUsageCommand(id: String, command: String) {
        val chat = try { chatStore.get(id) } catch (_: Exception) { null }
        if (chat == null) {
            appendCommandResult(id, "No chat is open.")
            return
        }
        Thread {
            val text = try {
                val c = OpencodeApi.fetchContext(prefs.serverUrl(), prefs.token(), id, chat.model, totals = true)
                val lines = mutableListOf<String>()
                if (command == "/context") {
                    lines += if (c.used <= 0) "Context: nothing recorded yet"
                        else if (c.window > 0) "Context: ${fmtTokens(c.used)} of ${fmtTokens(c.window)} tokens (${100L * c.used / c.window}%)"
                        else "Context: ${fmtTokens(c.used)} tokens; window unknown"
                    lines += "Last request: in ${fmtTokens(c.input)}, out ${fmtTokens(c.output)}, cache read ${fmtTokens(c.cacheRead)}, cache write ${fmtTokens(c.cacheWrite)}"
                    lines += if (c.compactAt > 0) "Auto-compact threshold: ${fmtTokens(c.compactAt.toLong())} tokens" else "Auto-compact threshold: backend default"
                } else if (command == "/cost") {
                    lines += when {
                        c.costUsd == null -> "Cost: not reported for this chat yet"
                        c.backend == "claude" -> String.format(java.util.Locale.US, "Cost estimate: $%.4f API-price equivalent (not your Claude subscription charge)", c.costUsd)
                        else -> String.format(java.util.Locale.US, "Cost: $%.4f", c.costUsd)
                    }
                    lines += "Tracked tokens: ${fmtTokens(c.totalTokens)}"
                } else {
                    lines += "Tracked chat usage: ${fmtTokens(c.totalTokens)} tokens across ${c.backend.ifBlank { "unknown backend" }}"
                    lines += if (c.backend == "claude") "Claude plan quota/reset usage is not exposed by this Terminus report." else "Latest context: ${fmtTokens(c.used)} tokens"
                }
                lines.joinToString("\n")
            } catch (e: Exception) { "${command.removePrefix("/")} failed: ${e.message ?: "network error"}" }
            runOnUiThread { appendCommandResult(id, text) }
        }.start()
    }

    private fun appendCommandResult(id: String, text: String) {
        val message = ChatMessage("tool", text)
        chatStore.addMessage(id, message)
        if (activeSessionId == id && !appendRowDom(id, message)) renderThread(id)
    }

    private fun showBackendStatusCommand(id: String) {
        val chat = try { chatStore.get(id) } catch (_: Exception) { null }
        val backend = chat?.backend ?: prefs.defaultBackend()
        Thread {
            val text = try {
                val info = OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token()).backends.firstOrNull { it.id == backend }
                if (info == null) "Status unavailable: $backend is not in the server backend catalog"
                else "${info.label}: ${if (info.available) "available" else "unavailable"}; ${if (info.authenticated) "authenticated" else "login status unknown or required"}; model ${chat?.model?.takeIf { it.isNotBlank() && it != ChatStore.defaultModel() } ?: "server default"}"
            } catch (e: Exception) { "Status check failed: ${e.message ?: "network error"}" }
            runOnUiThread { appendCommandResult(id, text) }
        }.start()
    }

    private val remoteModelsByBackend = mutableMapOf<String, List<RemoteModel>>()
    private val modelLoadingBackends = mutableSetOf<String>()
    private val unavailableModelCatalogs = mutableSetOf<String>()
    private var backendCatalog: BackendCatalog? = null

    private fun chatBackendOf(id: String): String? =
        try { chatStore.get(id)?.backend } catch (_: Exception) { null }

    /** Backend driving pickers for the current context: the open chat owns
     *  its backend permanently (M5); without an open chat the global
     *  default seeds the next new chat. Never derived from a model id. */
    private fun activeBackend(): String =
        activeSessionId?.let { chatStore.get(it)?.backend }?.let { Prefs.cannonBackend(it) }
            ?: prefs.defaultBackend()

    private fun modelsForBackend(backend: String): List<RemoteModel> =
        remoteModelsByBackend[backend].orEmpty()

    /** start...end for long provider/model ids. */
    private fun midId(id: String): String =
        if (id.length <= 28) id else "${id.take(12)}...${id.takeLast(12)}"

    private fun refreshModels(then: ((List<RemoteModel>) -> Unit)? = null) =
        refreshModels(activeBackend(), then)

    private fun refreshModels(backend: String, then: ((List<RemoteModel>) -> Unit)? = null) {
        if (!modelLoadingBackends.add(backend)) return
        Thread {
            val list = try {
                OpencodeApi.fetchModels(prefs.serverUrl(), prefs.token(), backend)
            } catch (_: Exception) { null }
            runOnUiThread {
                modelLoadingBackends.remove(backend)
                if (list != null) {
                    remoteModelsByBackend[backend] = list
                    unavailableModelCatalogs.remove(backend)
                    noteLanFallback(activeSessionId)
                    val id = activeSessionId
                    if (id != null) renderThread(id) else if (draftOpen) renderDraft()
                } else {
                    remoteModelsByBackend.remove(backend)
                    unavailableModelCatalogs.add(backend)
                }
                then?.invoke(list ?: modelsForBackend(backend))
            }
        }.start()
    }

    /** Refresh the backend catalog (descriptors + capabilities). Throws
     *  on failure — callers keep the last catalog when offline. */
    private fun refreshBackends(then: ((BackendCatalog) -> Unit)? = null) {
        Thread {
            val cat = try {
                OpencodeApi.fetchBackends(prefs.serverUrl(), prefs.token())
            } catch (_: Exception) { null }
            runOnUiThread {
                if (cat != null) {
                    backendCatalog = cat
                    noteLanFallback(activeSessionId)
                }
                then?.invoke(cat ?: backendCatalog ?: BackendCatalog(emptyList(), prefs.defaultBackend()))
            }
        }.start()
    }

    private fun modelDialog(prefilter: String = "", asDefault: Boolean = false, onDefaultPick: ((RemoteModel) -> Unit)? = null) {
        val id = activeSessionId
        val backend = activeBackend()
        val backendName = backendDisplayName(backend)
        val current = if (asDefault) prefs.defaultModel(backend)
            else id?.let { chatStore.get(it)?.model }
                ?: prefs.defaultModel(backend).ifBlank { ChatStore.defaultModel() }
        val sheetTitle = if (asDefault) "Default model, $backendName" else "Model for this chat, $backendName"
        // What "follow the default" resolves to right now (blank = unknown).
        val defResolved = prefs.defaultModel(backend).ifBlank { serverDefaultModel }
        val defShort = shortModelLabel(defResolved)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val v = layoutInflater.inflate(R.layout.sheet_model, null)
        val title: TextView = v.findViewById(R.id.modelTitle)
        val box: EditText = v.findViewById(R.id.modelFilter)
        val listView: android.widget.ListView = v.findViewById(R.id.modelList)
        box.setText(prefilter)
        title.text = sheetTitle
        if (modelsForBackend(backend).isEmpty()) title.text = "$sheetTitle · Loading live list…"
        var shown: List<RemoteModel> = emptyList()
        val adapter = object : android.widget.ArrayAdapter<RemoteModel>(
            this, R.layout.item_model, mutableListOf()) {
            override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup): View {
                val row = convert ?: layoutInflater.inflate(R.layout.item_model, parent, false)
                val m = getItem(pos) ?: return row
                val name: TextView = row.findViewById(R.id.modelName)
                val prov: TextView = row.findViewById(R.id.modelProvider)
                val check: TextView = row.findViewById(R.id.modelCheck)
                if (m.id.isBlank()) {
                    // Synthetic default row: always named, checked when
                    // nothing picked, resolves to the effective model.
                    name.text = if (defShort.isNotBlank()) "Default ($defShort)" else "Default"
                    prov.text = "$backendName default"
                    check.visibility = if (current.isBlank()) View.VISIBLE else View.GONE
                } else {
                    name.text = shortModelLabel(m.id).ifBlank { m.id }
                    val sub = m.provider.takeUnless { it.isBlank() }
                        ?: m.id.substringBefore("/").takeUnless { it == m.id }
                        ?: backendName
                    prov.text = sub
                    check.visibility = if (m.id == current) View.VISIBLE else View.GONE
                }
                return row
            }
        }
        listView.adapter = adapter
        fun applyFilter(q: String) {
            val all = modelsForBackend(backend)
            val rest = if (q.isBlank()) all
                else all.filter { it.id.contains(q, true) || it.label.contains(q, true) }
            // Selected model first with its tick, then the Default row,
            // then the rest. Default row still checked when nothing picked.
            val defRow = RemoteModel("", "server default", "Default")
            val matchesDef = q.isBlank() || "default".contains(q, true)
            val sel = if (current.isBlank()) emptyList() else rest.filter { it.id == current }
            val others = rest.filter { it.id != current }
            shown = sel + (if (matchesDef) listOf(defRow) else emptyList()) + others
            adapter.clear()
            adapter.addAll(shown)
            adapter.notifyDataSetChanged()
            title.text = when {
                modelLoadingBackends.contains(backend) -> "$sheetTitle · Loading live list…"
                unavailableModelCatalogs.contains(backend) -> "$sheetTitle · Live list unavailable"
                all.isEmpty() -> "$sheetTitle · Server returned no models"
                else -> "$sheetTitle · ${all.size} models"
            }
        }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        listView.setOnItemClickListener { _, _, pos, _ ->
            val m = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (asDefault) {
                // Default pick: saved as the device default AND applied to
                // the current chat. Pushed to the server best-effort; the
                // cog Save posts the full set anyway.
                prefs.setDefaultModel(backend, m.id)
                if (id != null) { chatStore.setModel(id, m.id); renderThread(id) }
                renderSessions()
                onDefaultPick?.invoke(m)
                pushDefaultModel(backend, m.id)
            } else if (id != null) {
                // Per-chat pick: local record only. The model travels on
                // every send, so no server sync is needed — and pushing
                // here flipped the server default (plus the device echo)
                // for every other chat. Settings owns defaults, not picks.
                chatStore.setModel(id, m.id)
                renderThread(id)
                renderSessions()
                if (backendCatalog?.backends?.firstOrNull { it.id == backend }?.capabilities?.effort == true) effortDialog(id)
            }
            else if (!asDefault) {
                // Draft pick: persist the draft on first model pick, carrying
                // the picked model (composer text/quote/attach ride along).
                val nid = ensureDraftSession(m.id)
                renderThread(nid)
            }
            sheet.dismiss()
        }
        sheet.setContentView(v)
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
        applyFilter(prefilter)
        box.setSelection(box.text.length)
        refreshModels(backend) { applyFilter(box.text.toString()) }
    }

    /** Effort picker, shown right after a model pick in a codex chat
     *  (reuses the row component; nothing new in settings or thread).
     *  Stored per chat, sent with every turn. Other backends ignore it. */
    private fun effortDialog(id: String) {
        val cur = try { chatStore.get(id)?.effort ?: "" } catch (_: Exception) { "" }
        val opts = listOf("low" to "Low", "medium" to "Medium", "high" to "High")
        val effBackend = try { backendDisplayName(chatStore.get(id)?.backend ?: "codex") } catch (_: Exception) { "Codex" }
        showSelectSheet("Effort · $effBackend", opts.map { (value, label) ->
            SelectRow(label, checked = (cur.ifBlank { "medium" }) == value, action = {
                chatStore.setEffort(id, value)
                renderThread(id)
                renderSessions()
            })
        })
    }

    /** POST a default-model pick on its own (prompt key omitted → unchanged).
     *  Uses the per-backend map so the model lands in this backend's slot
     *  even if the server default backend differs. */
    /** Settings-owned sync: push a new device default to the server and
     *  adopt its normalized echo. Never called from per-chat picks. */
    private fun pushDefaultModel(backend: String, modelId: String) {
        Thread {
            try {
                val stored = OpencodeApi.postDefaults(
                    prefs.serverUrl(), prefs.token(),
                    if (backend == "opencode") modelId else null,
                    null, null, mapOf(backend to modelId))
                val normalized = stored.defaultModels[backend] ?: modelId
                runOnUiThread { prefs.setDefaultModel(backend, normalized) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun renameDialog(s: ChatSession) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val v = layoutInflater.inflate(R.layout.sheet_rename, null)
        val box: EditText = v.findViewById(R.id.renameField)
        box.setText(s.title)
        box.setSelection(box.text.length)
        sheet.setContentView(v)
        v.findViewById<View>(R.id.renameSave).setOnClickListener {
            val name = box.text.toString().trim()
            chatStore.rename(s.id, name, locked = true)
            sheet.dismiss()
            renderSessions()
            if (activeSessionId == s.id) renderThread(s.id)
            // Propagate the manual name to the bound provider session.
            if (name.isNotBlank()) {
                val sid = s.remoteId
                if (sid.isNotBlank() && capsForBackend(backendCatalog, s.backend).title) Thread {
                    try { OpencodeApi.postTitleSet(prefs.serverUrl(), prefs.token(), sid, name, s.backend, s.id) }
                    catch (_: Exception) { runOnUiThread {
                        android.widget.Toast.makeText(this, "Renamed here, but couldn't rename it in ${backendDisplayName(s.backend)}", android.widget.Toast.LENGTH_LONG).show()
                    } }
                }.start()
            }
        }
        v.findViewById<View>(R.id.renameCancel).setOnClickListener { sheet.dismiss() }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    private fun showEcho() {
        chatPane.visibility = View.GONE
        echoPane.visibility = View.VISIBLE
        galleryPane.visibility = View.GONE
        opsPane.visibility = View.GONE
        notesPane.visibility = View.GONE
        crossFade(echoPane)
        // Echo is never a thread: full bar, unconditionally.
        syncNavForThread(false)
        refresh()
    }

    private fun pullFor(w: WebView) = when (w) {
        galleryWeb -> galleryPull
        opsWeb -> opsPull
        else -> notesPull
    }

    private fun showWeb(w: WebView, path: String) {
        chatPane.visibility = View.GONE
        echoPane.visibility = View.GONE
        galleryPane.visibility = View.GONE
        opsPane.visibility = View.GONE
        notesPane.visibility = View.GONE
        val pane = pullFor(w).parent as View
        pane.visibility = View.VISIBLE
        crossFade(pane)
        // Non-chat pane, unconditionally: never consult thread state here.
        syncNavForThread(false)
        val base = OpencodeApi.effectiveBase(prefs.serverUrl())
        val tok = prefs.token()
        val urlTok = tok   // pane pages (gallery, notes, ops) POST and DELETE with this token, so it must be the master
        val mob = "mobile=1"
        // A pane opened by an older build may still carry a GET-only link token in its URL: it can
        // read but every upload/delete 401s. Reload it with the master token.
        val staleLink = w.url?.contains("token=l1.") == true
        if (w.url == null || !w.url!!.startsWith(base) || staleLink || w.getTag(R.id.tagAuthedUrl) as? String != base + "|" + tok) {
            w.loadUrl("$base$path?$mob" + if (urlTok.isNotBlank()) "&token=" + android.net.Uri.encode(urlTok) else "")
            w.setTag(R.id.tagAuthedUrl, base + "|" + tok)
        }
    }

    /** Force a web tab back to its root (tab reselected): unconditional,
     *  unlike showWeb's no-op when already homed. A file opened inside the
     *  tab never traps it. */
    private fun resetWebTab(w: WebView, path: String) {
        val base = OpencodeApi.effectiveBase(prefs.serverUrl())
        val tok = prefs.token()
        val urlTok = tok   // same: panes need the master token
        w.loadUrl("$base$path?mobile=1" + if (urlTok.isNotBlank()) "&token=" + android.net.Uri.encode(urlTok) else "")
        w.setTag(R.id.tagAuthedUrl, base + "|" + tok)
    }

    /** Browser card lifecycle (owner chat, live card, frozen card): see BrowserCardController. */
    private val browserCards by lazy {
        BrowserCardController(
            activeChat = { activeSessionId },
            runningTurnChats = { pendingTurns.filter { it.value.isNotEmpty() }.keys },
            askedAt = { lastAskedAt },
            store = { chatStore },
            postRow = { chat, card ->
                chatStore.addMessage(chat, card)
                if (!appendRowDom(chat, card)) renderThread(chat)
            },
            rerender = { chat -> renderThread(chat, scroll = false) },
            runJs = { js -> if (::threadWeb.isInitialized && threadPageLoaded) threadWeb.evaluateJavascript(js, null) },
        )
    }

    /** Volume keys while a chat is open. Single presses behave normally (volume) unless something is
     *  active — then one press stops it (dictation stops and sends, reading stops). Starting needs a
     *  deliberate double-press of the same key, so everyday volume taps never trigger anything. */
    private var lastVolUpAt = 0L
    private var lastVolDownAt = 0L
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!prefs.hotkeysEnabled() || event?.repeatCount != 0 || (activeSessionId == null && !draftOpen))
            return super.onKeyDown(keyCode, event)
        val now = android.os.SystemClock.elapsedRealtime()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                if (dictating) {
                    stopDictation()
                    if (composerInput.text.isNotBlank()) sendChat(voiceSendCommand = true)
                    return true
                }
                if (now - lastVolUpAt < 500) { lastVolUpAt = 0L; onChatMic(); return true }
                lastVolUpAt = now
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (replyVoice.isPlaying()) { replyVoice.stop(); return true }
                if (now - lastVolDownAt < 500) { lastVolDownAt = 0L; readLastReply(); return true }
                lastVolDownAt = now
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Read the newest assistant reply aloud (no-op with a toast when there is none). */
    private fun readLastReply() {
        val id = activeSessionId ?: return
        stopDictation()
        val msgs = try { chatStore.get(id)?.messages } catch (_: Exception) { null } ?: return
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]
            if (m.role == "assistant" && m.text.isNotBlank()) {
                replyVoice.speak("$id:$i", m.text)
                return
            }
        }
        toast("No reply to read aloud yet")
    }

    override fun onBackPressed() {
        if (phoneBrowser?.onBack() == true) return
        if (fullscreenView != null) {
            try { fullscreenDialog?.dismiss() } catch (_: Exception) {}
            return
        }
        if (::sessionAdapter.isInitialized && sessionAdapter.selectionMode) {
            sessionAdapter.clearSelection()
            return
        }
        if (searchOpen && !isThreadOpen() && sessionSearch.visibility == View.VISIBLE) { hideSearch(); return }
        if (::slashList.isInitialized && slashList.visibility == View.VISIBLE) { slashList.visibility = View.GONE; return }
        // Edit mode exits first: back cancels the edit and restores the
        // displaced draft, instead of leaving the thread.
        if (editingTurn != null) { cancelEditMode(); return }
        if (isThreadOpen()) { closeThread(); return }
        if (sessionFilter == 3) { setSessionFilter(0); return }
        // In-pane file views (gallery/notes/ops HTML etc.): system Back walks
        // WebView history first, so an opened file never traps the tab.
        try {
            val panes = listOf(galleryPane to galleryWeb, opsPane to opsWeb, notesPane to notesWeb)
            for ((pane, web) in panes) {
                if (pane.visibility == View.VISIBLE) {
                    try {
                        if (web.canGoBack()) { web.goBack(); return }
                    } catch (_: Exception) {}
                    break
                }
            }
        } catch (_: Exception) {}
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        appForeground = true
        phoneBrowser?.start()
        scheduleOutboxRetry(1_000)
        pingServer()
        refresh()
        syncInbox()
        if (RecorderService.running) timerTick.run()
        Uploader.enqueue(this)
        GalleryUploader.drain(this)
        rehydrateWaitQueue()
        activeSessionId?.let { reconcileTurnState(it); catchUpBackground(it) }
        reattachLiveStreams()
        // Back from the background is when a reply is most likely to have been missed (the stream
        // was suspended): have the server close out anything finished or dead for chats still showing
        // a spinner. Silent when there is nothing to settle.
        try {
            (chatStore.chatsWithLive() + listOfNotNull(activeSessionId)).distinct().forEach { syncThread(it) }
        } catch (_: Exception) {}
    }

    /** Rebuild the queue backlog after process death: marker rows survive
     *  in the transcript while the memory queue does not. Missing files
     *  drop that entry's attachments (text still sends). Idle chats drain
     *  via the normal reconciler; chats with live turns stay queued. */
    private fun rehydrateWaitQueue() {
        val stored = try { WaitStore(this).all() } catch (_: Exception) { return }
        if (stored.isEmpty()) return
        for ((id, entries) in stored) {
            if (chatStore.get(id) == null) {
                try { WaitStore(this).clear(id) } catch (_: Exception) {}
                continue
            }
            if (!(waitQueue[id]?.isEmpty() ?: true)) continue
            waitQueue[id] = entries.map {
                PendingSend(it.text, it.files.map { p -> java.io.File(p) }.filter { f -> f.exists() && f.isFile }, "")
            }.toMutableList()
        }
        for (id in stored.keys) {
            try { reconcileTurnState(id) } catch (_: Exception) {}
        }
    }

    /** B1 recovery: turns that outlived a process death keep live
     *  placeholders on disk but lose every in-memory tracker. Rehydrate
     *  pendingTurns from them and adopt a stream for the work, so a
     *  reconnected server `done` still settles instead of stranding "…".
     *  The rehydrated set is then reconciled against the server (exact
     *  turn registry + live session state): confirmed-settled or
     *  confirmed-idle turns leave the in-flight set at once, so a
     *  restarted process never shows "replying…" for work that is
     *  already over. Placeholders always survive for late-`done`
     *  settlement. No-op when nothing is live; never steals another
     *  chat's stream. */
    private fun reattachLiveStreams() {
        if (!::chatStore.isInitialized) return
        try {
            val live = chatStore.chatsWithLive()
            if (live.isEmpty()) return
            for (cid in live) {
                try {
                    val turns = chatStore.liveTurns(cid).map { it.first }.filter { it.isNotBlank() }
                    if (turns.isNotEmpty()) pendingTurns.getOrPut(cid) { mutableSetOf() }.addAll(turns)
                } catch (_: Exception) {}
            }
            if (streamChat == null) {
                val target = live.lastOrNull()
                if (target != null && chatStore.hasLive(target)) startStream(target)
            }
            Thread {
                var changed = false
                for (cid in live) {
                    try {
                        val persisted = pendingTurns[cid]?.toSet() ?: continue
                        if (persisted.isEmpty()) continue
                        val settled = persisted.filter { t ->
                            try {
                                OpencodeApi.fetchTurn(prefs.serverUrl(), prefs.token(), cid, t, chatBackendOf(cid)).settled
                            } catch (_: Exception) { false }
                        }.toSet()
                        val sid = try { chatStore.get(cid)?.remoteId ?: "" } catch (_: Exception) { "" }
                        val working = if (sid.isBlank()) null
                            else OpencodeApi.fetchProgressWorking(prefs.serverUrl(), prefs.token(), cid, sid, chatBackendOf(cid))
                        val next = TurnActivity.reconcileAfterRestart(persisted, settled, working)
                        if (next != persisted) {
                            if (next.isEmpty()) pendingTurns.remove(cid)
                            else pendingTurns[cid] = next.toMutableSet()
                            changed = true
                        }
                    } catch (_: Exception) {}
                }
                if (changed) {
                    runOnUiThread {
                        try {
                            sessionAdapter.refreshLiveChats(TurnActivity.replyingChats(pendingTurns))
                            val open = activeSessionId
                            if (open != null) renderThread(open)
                        } catch (_: Exception) {}
                    }
                }
            }.start()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        stopDictation() // Android blocks background mic use anyway
        appForeground = false
        phoneBrowser?.onAppPause()
        timerHandler.removeCallbacks(timerTick)
        setPulsing(false)
        if (playingName != null) { stopPlayback(); playingName = null }
        try { activeSessionId?.let { rememberThreadScroll(it) } } catch (_: Exception) {}
        // Backgrounding must never lose the draft or the last 150ms of
        // coalesced store writes: flush both synchronously.
        try { flushPendingDraft() } catch (_: Exception) {}
        try { if (::chatStore.isInitialized) chatStore.flushNow() } catch (_: Exception) {}
        super.onPause()
    }

    private var scheduleFactsAt = 0L

    /** The chat list's schedule lines ("next Sun 18:30 · weekly"): refetched at most every 2 minutes. */
    private fun refreshScheduleFacts() {
        val now = System.currentTimeMillis()
        if (now - scheduleFactsAt < 120_000 || prefs.token().isBlank()) return
        scheduleFactsAt = now
        Thread {
            val facts = try { OpencodeApi.fetchScheduleRows(prefs.serverUrl(), prefs.token()) } catch (_: Exception) { null }
            if (facts != null) runOnUiThread { sessionAdapter.scheduleFacts = facts }
            else scheduleFactsAt = 0L
        }.start()
    }

    private fun pingServer() {
        val backend = activeBackend()
        healthBackend = backend
        Thread {
            val ok = OpencodeApi.pingOk(prefs.serverUrl(), backend)
            runOnUiThread {
                // Ignore a slow response if the user switched chats while it ran.
                if (backend == activeBackend()) {
                    serverOk = ok
                    renderSys()
                }
            }
        }.start()
    }

    private fun hasMic(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        ensureNotifPermission()
    }

    /** POST_NOTIFICATIONS is declared in the manifest; ask on first use
     *  (chat send, record start, tone picker) so milestones/turn pings land. */
    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    /** Display name of the stored chat tone for the cog sheet. */
    private fun toneName(): String {
        val uri = ChatNotify.soundUri(this) ?: return "system default"
        return try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "custom tone"
        } catch (_: Exception) { "custom tone" }
    }

    private fun toggle() {
        val i = Intent(this, RecorderService::class.java)
        if (RecorderService.running) stopService(i)
        else {
            ensureNotifPermission()
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        echoCard.postDelayed({ refresh() }, 600)
    }

    /** One optional label per Echo session (e.g. "walk", "standup").
     *  Sanitized + baked into chunk filenames; blank starts unlabeled. */
    private fun promptEchoLabel() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val view = layoutInflater.inflate(R.layout.sheet_echo_label, null)
        sheet.setContentView(view)
        val input: EditText = view.findViewById(R.id.echoLabelField)
        view.findViewById<View>(R.id.echoLabelCancel).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.echoLabelRecord).setOnClickListener {
            RecorderService.pendingLabel = RecorderService.sanitizeLabel(input.text.toString())
            sheet.dismiss()
            toggle()
        }
        sheet.show()
        try { sheet.behavior.isDraggable = false } catch (_: Exception) {}
    }

    private fun refresh() {
        updateScreenOn()
        val live = RecorderService.running
        if (!live || chatRecActive || RecorderService.chatCaptureActive) {
            if (chatRecActive || RecorderService.chatCaptureActive) {
                echoTitle.text = "voice note"
                echoSub.text = "recording for chat · tap mic to stop"
            } else {
                echoTitle.text = "ready"
                echoSub.text = "tap to record"
            }
            timerText.visibility = android.view.View.GONE
            recChip.visibility = android.view.View.GONE
            waveView.visibility = android.view.View.GONE
            echoOrb.setBackgroundResource(R.drawable.echo_orb)
            setPulsing(false)
        } else {
            val label = RecorderService.activeLabel
            echoTitle.text = if (label.isNotBlank()) "recording · $label" else "recording"
            echoSub.text = chunkLine(RecorderService.chunkNo, uploadedInSession(chunks.all().filter { it.state == ChunkStore.STATE_UPLOADED }.map { it.name }, RecorderService.sessionPrefix))
            timerText.visibility = android.view.View.VISIBLE
            recChip.visibility = android.view.View.VISIBLE
            waveView.visibility = android.view.View.VISIBLE
            echoOrb.setBackgroundResource(R.drawable.echo_orb_stop)
            timerHandler.removeCallbacks(waveTick)
            waveTick.run()
            timerTick.run()
            setPulsing(true)
        }
        adapter.update(chunks.all(), clipLens(chunks.all()))
        findViewById<View>(R.id.echoEmpty).visibility =
            if (chunks.all().isEmpty()) View.VISIBLE else View.GONE
        renderSys()
        if (::chatMic.isInitialized) {
            if (chatRecActive && RecorderService.running) {
                chatPollHandler.removeCallbacks(chatPoll)
                chatPollHandler.post(chatPoll)
                scanChatChunks()
            }
            updateChatRecUI()
        }
    }

    /** Real clip lengths via metadata, cached by name (store keeps names
     *  stable; a missing file simply stays unmeasured). */
    private fun clipLens(items: List<ChunkItem>): Map<String, Long> {
        for (it in items) {
            if (clipLens.containsKey(it.name)) continue
            try {
                val f = File(recordingsDir(this), it.name)
                if (!f.exists()) continue
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (ms != null && ms > 0) clipLens[it.name] = ms
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return clipLens
    }

    private fun renderSys() {
        if (activeBackend() != healthBackend) pingServer()
        val items = chunks.all()
        val queued = items.count { it.state == ChunkStore.STATE_QUEUED }
        val failed = items.count { it.state == ChunkStore.STATE_REJECTED }
        if (!RecorderService.running) {
            echoSub.text = if (playingName != null) "▶ $playingName"
                else if (queued > 0 && !serverOk) "$queued queued · offline"
                else if (queued > 0) "$queued queued · syncing"
                else if (failed > 0) "$failed failed"
                else "tap to record"
        }
        val bytes = try {
            recordingsDir(this).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }
        val dotColor = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(
                this, if (serverOk) R.color.t_ok else R.color.t_muted))
        for (d in serverDots) {
            try { d.backgroundTintList = dotColor } catch (_: Exception) {}
        }
        statQueue.text = "$queued"
        statStore.text = "${bytes / 1048576} MB"
    }

    private fun playChunk(item: ChunkItem) {
        val was = playingName
        stopPlayback()
        if (was == item.name) { playingName = null; refresh(); return }
        try {
            val f = File(recordingsDir(this), item.name)
            if (!f.exists()) { echoSub.text = "file gone"; return }
            player = android.media.MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { playingName = null; refresh() }
                start()
            }
            playingName = item.name
        } catch (e: Exception) {
            echoSub.text = "can't play"
            playingName = null
        }
        refresh()
    }

    private fun stopPlayback() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        updateScreenOn()
    }

    /** Device default model for freshly created chats
     *  ("provider/model", blank = server default). */
    private fun newChatModel(): String = prefs.defaultModel()

    /** Backend+model seeding the next new chat: the global default backend
     *  and that backend's default model. Never the previous chat's pair. */
    private data class ChatConfig(val backend: String, val model: String)
    private fun newChatConfig(): ChatConfig {
        val backend = prefs.defaultBackend()
        return ChatConfig(backend, prefs.defaultModel(backend))
    }

    /** Supported-backends selector for the settings cog. Changing the global default
     *  never touches existing chats — it only seeds the next new chat.
     *  Unavailable backends stay selectable only in label, never applied. */
    private fun backendDialog(onPick: () -> Unit = {}) {
        refreshBackends { cat -> showBackendChoices(cat, onPick) }
    }

    /** Instant toggle to the previous backend (triple-tap chat tab): no
     *  list, no choosing — default flips, list re-scopes, toast confirms.
     *  Falls back to the other primary backend on first use. */
    private fun quickToggleBackend() {
        val cur = prefs.defaultBackend()
        var target = prefs.prevBackend().takeIf { Prefs.validBackend(it) && it != cur }
        if (target == null) {
            target = if (cur == "codex") "opencode" else "codex"
        }
        prefs.setPrevBackend(cur)
        prefs.setDefaultBackend(target)
        activeSessionId?.let { if (alignScheduleChatBackend(it)) renderThread(it) }
        refreshModels(target)
        refreshSessions()
        Thread {
            try {
                OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, target, null)
            } catch (_: Exception) {}
        }.start()
        android.widget.Toast.makeText(this,
            "New chats will use ${backendDisplayName(target)}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Backend choice rows (pure-adjacent: catalog in, ids/labels out).
     *  Unavailable backends render struck-out and never apply. */
    private fun backendChoices(cat: BackendCatalog): Pair<List<String>, List<String>> {
        val ids = if (cat.backends.isNotEmpty()) cat.backends.map { it.id }
            else listOf("opencode", "codex", "claude", "agy")
        val labels = ids.map { id ->
            val info = cat.backends.firstOrNull { it.id == id }
            val base = info?.label ?: backendDisplayName(id)
            if (info != null && !info.available) "$base (unavailable)"
            else if (info != null && !info.authenticated) "$base (login required)"
            else base
        }
        return ids to labels
    }

    private fun showBackendChoices(cat: BackendCatalog, onPick: () -> Unit) {
        val ids = backendChoices(cat).first
        val cur = prefs.defaultBackend()
        val rows = ids.mapIndexed { index, id ->
            val info = cat.backends.firstOrNull { it.id == id }
            // Logo, name, and the model new chats will start on; status replaces the model when it can't run.
            val sub = when {
                info?.available == false -> "Unavailable on the Mac"
                info?.authenticated == false -> "Login required"
                else -> shortModelLabel(prefs.defaultModel(id))
            }
            SelectRow(info?.label ?: backendDisplayName(id), sub,
                checked = id == cur, icon = agentIcon(id), dim = info?.available == false,
                action = {
                    if (!Prefs.validBackend(id) || info?.available == false) return@SelectRow
                    val old = prefs.defaultBackend()
                    if (old != id) prefs.setPrevBackend(old)
                    prefs.setDefaultBackend(id)
                    activeSessionId?.let { if (alignScheduleChatBackend(it)) renderThread(it) }
                    onPick()
                    refreshModels(id)
                    refreshSessions()
                    Thread {
                        try { OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, id, null) } catch (_: Exception) {}
                    }.start()
                })
        }
        showSelectSheet("Backend", rows)
    }

    /** Settings cog: default model + system prompt (server /api/defaults)
     *  plus Server URL + token (local Prefs). Save writes both. */
    private fun showDefaults() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CreamBottomSheet)
        val v = layoutInflater.inflate(R.layout.sheet_defaults, null)
        sheet.setContentView(v)
        val modelValue: TextView = v.findViewById(R.id.defModelValue)
        val backendValue: TextView = v.findViewById(R.id.defBackendValue)
        val promptBox: EditText = v.findViewById(R.id.defSystemPrompt)
        val compactValue: TextView = v.findViewById(R.id.defCompactValue)
        val urlBox: EditText = v.findViewById(R.id.defServerUrl)
        val tokBox: EditText = v.findViewById(R.id.defApiToken)
        val saved: TextView = v.findViewById(R.id.defSaved)
        val voiceSend: android.widget.CheckBox = v.findViewById(R.id.defVoiceSend)
        val voiceSendPhrase: EditText = v.findViewById(R.id.defVoiceSendPhrase)
        val voiceSendHelp: TextView = v.findViewById(R.id.defVoiceSendHelp)
        voiceSend.isChecked = prefs.voiceSendEnabled()
        voiceSendPhrase.setText(prefs.voiceSendPhrase())
        val linkPreviews: android.widget.CheckBox = v.findViewById(R.id.defLinkPreviews)
        linkPreviews.isChecked = prefs.linkPreviews()
        linkPreviews.setOnCheckedChangeListener { _, checked -> prefs.setLinkPreviews(checked) }
        val hotkeys: android.widget.CheckBox = v.findViewById(R.id.defHotkeys)
        hotkeys.isChecked = prefs.hotkeysEnabled()
        hotkeys.setOnCheckedChangeListener { _, checked -> prefs.setHotkeysEnabled(checked) }

        fun wireSection(headerId: Int, bodyId: Int, arrowId: Int, expandedInitially: Boolean) {
            val header = v.findViewById<View>(headerId)
            val body = v.findViewById<View>(bodyId)
            val arrow = v.findViewById<TextView>(arrowId)
            fun render(expanded: Boolean) {
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                arrow.text = if (expanded) "⌃" else "⌄"
                header.contentDescription = "${header.findViewWithTag<TextView>("title")?.text} settings, " +
                    if (expanded) "expanded" else "collapsed"
            }
            render(expandedInitially)
            header.setOnClickListener { render(body.visibility != View.VISIBLE) }
        }
        wireSection(R.id.defSectionChatHeader, R.id.defSectionChatBody, R.id.defSectionChatArrow, true)
        wireSection(R.id.defSectionVoiceHeader, R.id.defSectionVoiceBody, R.id.defSectionVoiceArrow, false)
        wireSection(R.id.defSectionAppearanceHeader, R.id.defSectionAppearanceBody, R.id.defSectionAppearanceArrow, false)
        // Connection opens by itself on first run (no password yet), where it is what the person needs.
        wireSection(R.id.defSectionConnHeader, R.id.defSectionConnBody, R.id.defSectionConnArrow, prefs.token().isBlank())
        wireSection(R.id.defSectionAdvancedHeader, R.id.defSectionAdvancedBody, R.id.defSectionAdvancedArrow, false)

        // One-line summaries on the section cards; the render functions below call summaryRefresh().
        var summaryRefresh: () -> Unit = {}

        fun renderVoicePhrase() {
            val visible = voiceSend.isChecked
            voiceSendPhrase.visibility = if (visible) View.VISIBLE else View.GONE
            voiceSendHelp.visibility = if (visible) View.VISIBLE else View.GONE
        }
        renderVoicePhrase()
        voiceSend.setOnCheckedChangeListener { _, checked ->
            prefs.setVoiceSendEnabled(checked)
            renderVoicePhrase()
            summaryRefresh()
        }
        voiceSendPhrase.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.setVoiceSendPhrase(s?.toString().orEmpty())
                summaryRefresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        (v.findViewById<TextView>(R.id.defBuild)).text =
            "build ${com.terminus.rec.BuildConfig.VERSION_CODE} · ${com.terminus.rec.BuildConfig.VERSION_NAME}"
        fun renderBackend() {
            backendValue.text = backendDisplayName(prefs.defaultBackend())
            summaryRefresh()
        }
        fun renderDefault() {
            val id = prefs.defaultModel(prefs.defaultBackend())
            val picked = shortModelLabel(id).ifBlank { null }
            val eff = picked ?: shortModelLabel(serverDefaultModel).ifBlank { null }
            modelValue.text = if (picked != null) picked
                else if (eff != null) "Default ($eff)" else "server default"
            summaryRefresh()
        }
        renderBackend()
        renderDefault()
        // Silent catalog refresh so capability gates (compact/fork/title)
        // track the live server; offline keeps the last catalog.
        refreshBackends()
        urlBox.setText(prefs.serverUrl())
        tokBox.setText(prefs.token())
        // Chat sound: system tone picker → stored pref → applied to channel.
        val soundValue: android.widget.TextView = v.findViewById(R.id.defSoundValue)
        toneLabel = soundValue
        soundValue.text = toneName()
        sheet.setOnDismissListener { if (toneLabel === soundValue) toneLabel = null }
        v.findViewById<View>(R.id.defSoundRow).setOnClickListener {
            ensureNotifPermission()
            val cur = ChatNotify.soundUri(this)
            val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
            }
            try { soundPickerLauncher.launch(pick) } catch (_: Exception) {}
        }
        v.findViewById<View>(R.id.defChannelSettings).setOnClickListener {
            try { startActivity(ChatNotify.channelSettings(this)) } catch (_: Exception) {}
        }
        // Appearance: System / Light / Dark → stored pref, applied via
        // AppCompatDelegate, activity recreates so the theme swaps cleanly.
        val appearanceValue: TextView = v.findViewById(R.id.defAppearanceValue)
        fun renderAppearance() {
            appearanceValue.text = when (prefs.appearance()) {
                "light" -> "light"
                "dark" -> "dark"
                else -> "system"
            }
            summaryRefresh()
        }
        renderAppearance()
        v.findViewById<View>(R.id.defAppearanceRow).setOnClickListener {
            val opts = listOf("System", "Light", "Dark")
            val cur = when (prefs.appearance()) {
                "light" -> 1
                "dark" -> 2
                else -> 0
            }
            showSelectSheet("Appearance", opts.mapIndexed { index, label ->
                SelectRow(label, checked = index == cur, action = {
                    val mode = when (index) {
                        1 -> "light"
                        2 -> "dark"
                        else -> "system"
                    }
                    prefs.setAppearance(mode)
                    renderAppearance()
                    applyAppearance(mode)
                    // Recreate cleanly; normal launches return to the home list.
                    try { recreate() } catch (_: Exception) {}
                    try { sheet.dismiss() } catch (_: Exception) {}
                })
            })
        }
        var promptDirty = false
        var promptLoaded = false
        val promptPreview: TextView = v.findViewById(R.id.defPromptPreview)
        val promptChevron: TextView = v.findViewById(R.id.defPromptChevron)
        fun renderPromptPreview() {
            val t = promptBox.text.toString()
            promptPreview.text = if (t.isBlank()) "built-in" else "${t.length} chars"
            promptChevron.text = if (promptBox.visibility == View.VISIBLE) "⌃" else "›"
        }
        v.findViewById<View>(R.id.defPromptHeader).setOnClickListener {
            promptBox.visibility = if (promptBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            renderPromptPreview()
        }
        promptBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { promptDirty = true; renderPromptPreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderPromptPreview()
        var compactAt = 0
        fun renderCompact() { compactValue.text = if (compactAt <= 0) "Default" else "${compactAt / 1000}k tokens" }
        renderCompact()
        // Current server values fill the sheet; a user edit in flight wins.
        Thread {
            try {
                val cur = OpencodeApi.fetchDefaults(prefs.serverUrl(), prefs.token())
                runOnUiThread {
                    noteLanFallback(activeSessionId)
                    compactAt = cur.compactAt
                    renderCompact()
                    if (!promptDirty) {
                        promptBox.setText(cur.systemPrompt)
                        // Only a loaded value may travel: if this fetch
                        // failed (offline), the box stays blank and a save
                        // must leave the server prompt untouched, never wipe
                        // it with a blank-means-clear.
                        promptLoaded = true
                    }
                    if (cur.serverDefault.isNotBlank() && cur.serverDefault != serverDefaultModel) {
                        serverDefaultModel = cur.serverDefault
                        val open = activeSessionId
                        if (open != null) renderThread(open)
                    }
                    if (cur.defaultBackend != prefs.defaultBackend()) {
                        prefs.setDefaultBackend(cur.defaultBackend)
                        renderBackend()
                    }
                    var modelsChanged = false
                    for ((k, mid) in cur.defaultModels) {
                        if (Prefs.validBackend(k) && mid != prefs.defaultModel(k)) {
                            prefs.setDefaultModel(k, mid)
                            modelsChanged = true
                        }
                    }
                    if (cur.defaultModels.isEmpty() && cur.defaultModel.isNotBlank() &&
                        cur.defaultModel != prefs.defaultModel(cur.defaultBackend)) {
                        prefs.setDefaultModel(cur.defaultBackend, cur.defaultModel)
                        modelsChanged = true
                    }
                    if (modelsChanged) renderDefault()
                }
            } catch (_: Exception) {}
        }.start()
        v.findViewById<View>(R.id.defBackendRow).setOnClickListener {
            backendDialog { renderBackend(); renderDefault() }
        }
        v.findViewById<View>(R.id.defModelRow).setOnClickListener {
            modelDialog(asDefault = true, onDefaultPick = { renderDefault() })
        }
        v.findViewById<View>(R.id.defCompactRow).setOnClickListener {
            val rows = listOf(0, 80_000, 100_000, 150_000, 200_000, 500_000).map { n ->
                SelectRow(
                    if (n == 0) "Default" else "${n / 1000}k tokens",
                    if (n == 0) "Each backend keeps its own behaviour" else "",
                    checked = n == compactAt,
                    action = {
                        compactAt = n
                        renderCompact()
                        Thread {
                            try { OpencodeApi.postDefaults(prefs.serverUrl(), prefs.token(), null, null, null, null, n) } catch (_: Exception) {}
                        }.start()
                    })
            }
            showSelectSheet("Auto-compact at", rows)
        }
        v.findViewById<View>(R.id.defCancel).setOnClickListener { sheet.dismiss() }
        // Connection: password reveal, a test that never touches saved values, and plain-language results.
        val connStatus: TextView = v.findViewById(R.id.defConnStatus)
        val tokToggle: TextView = v.findViewById(R.id.defTokenToggle)
        var tokShown = false
        tokToggle.setOnClickListener {
            tokShown = !tokShown
            tokBox.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (tokShown) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                 else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            tokBox.setSelection(tokBox.text.length)
            tokToggle.text = if (tokShown) "Hide" else "Show"
        }
        fun colorOf(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
        fun checkConnection(url: String, tok: String, done: (Boolean, String) -> Unit) {
            Thread {
                try {
                    OpencodeApi.fetchDefaults(url, tok)
                    runOnUiThread { done(true, "") }
                } catch (e: Exception) {
                    runOnUiThread { done(false, ConnectionCheck.describe(e)) }
                }
            }.start()
        }
        v.findViewById<View>(R.id.defTestConn).setOnClickListener {
            val url = ConnectionCheck.normalizeUrl(urlBox.text.toString())
            if (url == null) {
                connStatus.text = "Address must start with http:// or https://"
                connStatus.setTextColor(colorOf(R.color.t_err))
                return@setOnClickListener
            }
            connStatus.text = "Testing…"
            connStatus.setTextColor(colorOf(R.color.t_muted))
            checkConnection(url, tokBox.text.toString().trim()) { ok, why ->
                connStatus.text = if (ok) "Connected ✓" else "Can't connect: $why"
                connStatus.setTextColor(colorOf(if (ok) R.color.t_ok else R.color.t_err))
            }
        }
        urlBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { summaryRefresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        tokBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { summaryRefresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        // Live one-line summaries on the cards (shown when a section is collapsed).
        val chatSummary: TextView = v.findViewById(R.id.defChatSummary)
        val voiceSummary: TextView = v.findViewById(R.id.defVoiceSummary)
        val appearanceSummary: TextView = v.findViewById(R.id.defAppearanceSummary)
        val connSummary: TextView = v.findViewById(R.id.defConnSummary)
        val advancedSummary: TextView = v.findViewById(R.id.defAdvancedSummary)
        summaryRefresh = {
            chatSummary.text = "${backendValue.text} · ${modelValue.text}"
            voiceSummary.text = if (voiceSend.isChecked)
                "Send word: \u201C${voiceSendPhrase.text.toString().trim().ifBlank { "send" }}\u201D"
            else "Voice send off"
            appearanceSummary.text = appearanceValue.text.toString().replaceFirstChar { it.uppercase() }
            connSummary.text = ConnectionCheck.hostOf(urlBox.text.toString()) +
                if (tokBox.text.isNullOrBlank()) " · no password" else ""
            advancedSummary.text = "App build " + BuildConfig.VERSION_CODE
        }
        summaryRefresh()

        v.findViewById<View>(R.id.defSave).setOnClickListener {
            saved.visibility = View.GONE
            fun status(text: String, colorId: Int) {
                saved.text = text
                saved.setTextColor(colorOf(colorId))
                saved.visibility = View.VISIBLE
            }
            val newUrl = ConnectionCheck.normalizeUrl(urlBox.text.toString())
            val newTok = tokBox.text.toString().trim()
            if (newUrl == null) {
                status("Not saved: the server address must start with http:// or https://", R.color.t_err)
                v.findViewById<View>(R.id.defSectionConnBody).visibility = View.VISIBLE
                return@setOnClickListener
            }
            val newPrompt = promptBox.text.toString()
            val connectionChanged = newUrl != prefs.serverUrl().trim().trimEnd('/') || newTok != prefs.token()
            // Commit only after the new connection answered, so a typo never replaces a working setup.
            fun commitAndPush() {
                if (connectionChanged) {
                    prefs.setServerUrl(newUrl)
                    prefs.setToken(newTok)
                    LinkToken.refresh(this@MainActivity, prefs)
                    ensureServerCookie()
                    galleryWeb.loadUrl("about:blank")
                    opsWeb.loadUrl("about:blank")
                    notesWeb.loadUrl("about:blank")
                }
                prefs.setVoiceSendEnabled(voiceSend.isChecked)
                prefs.setVoiceSendPhrase(voiceSendPhrase.text.toString())
                pushDefaults(prefs.defaultModel(prefs.defaultBackend()), if (promptLoaded) newPrompt else null, prefs.defaultBackend()) { ok, msg ->
                    if (ok) {
                        status("saved ✓", R.color.t_ok)
                        saved.postDelayed({ try { sheet.dismiss() } catch (_: Exception) {} }, 700)
                    } else {
                        // Local settings are already kept; only the server update failed.
                        status("Saved on this phone. Server not updated: $msg", R.color.t_err)
                    }
                }
            }
            if (connectionChanged) {
                status("Checking connection…", R.color.t_muted)
                checkConnection(newUrl, newTok) { ok, why ->
                    if (ok) commitAndPush()
                    else {
                        status("Not saved: can't connect ($why). Your previous connection is unchanged.", R.color.t_err)
                        v.findViewById<View>(R.id.defSectionConnBody).visibility = View.VISIBLE
                    }
                }
            } else commitAndPush()
        }
        // Let the sheet go nearly full height and start expanded: the settings
        // list is long, and a locked half-height sheet traps the lower half.
        sheet.setOnShowListener {
            val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottom?.layoutParams = bottom?.layoutParams?.apply { height = (resources.displayMetrics.heightPixels * 0.92f).toInt() }
            try {
                sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                sheet.behavior.skipCollapsed = true
            } catch (_: Exception) {}
        }
        sheet.show()
    }

    /** POST device defaults in the background. Null prompt = leave the
     *  server prompt untouched (never send a blank that would clear it).
     *  Token passes through memory only; only the outcome (never values)
     *  reaches the UI. */
    private fun pushDefaults(
        defaultModel: String,
        systemPrompt: String?,
        backend: String = prefs.defaultBackend(),
        done: (Boolean, String) -> Unit,
    ) {
        Thread {
            try {
                val stored = OpencodeApi.postDefaults(
                    prefs.serverUrl(), prefs.token(), defaultModel.takeIf { backend == Prefs.BACKEND_OPENCODE }, systemPrompt, backend,
                    mapOf(backend to defaultModel))
                prefs.setDefaultBackend(stored.defaultBackend.ifBlank { backend })
                for ((k, v) in stored.defaultModels) prefs.setDefaultModel(k, v)
                if (stored.defaultModels.isEmpty() && stored.defaultModel.isNotBlank())
                    prefs.setDefaultModel(backend, stored.defaultModel)
                runOnUiThread {
                    noteLanFallback(activeSessionId)
                    done(true, "")
                }
            } catch (e: Exception) {
                runOnUiThread { done(false, e.message ?: "network error") }
            }
        }.start()
    }

    override fun onDestroy() {
        stopDictation()
        stopPlayback()
        phoneBrowser?.destroy()
        phoneBrowser = null
        approvalPrompt?.destroy()
        approvalPrompt = null
        replyVoice.release()
        super.onDestroy()
    }

    companion object {
        private const val CONTEXT_THROTTLE_MS = 3_000L
        private const val COMMANDS_TTL_MS = 5 * 60_000L
        /** Waiting-row sync cadence while a reply runs: about 100 s at most, then the next manual sync. */
        private const val POLL_WAITING_GAP_MS = 2500L
        private const val POLL_WAITING_MAX = 40

        fun recordingsDir(ctx: android.content.Context): File =
            File(ctx.filesDir, "recordings").apply { mkdirs() }
    }
}

/** A tap target the WebView cannot load itself: the dialer, mail and messaging apps take it instead. */
internal fun isContactUrl(u: String): Boolean {
    val l = u.lowercase()
    return l.startsWith("tel:") || l.startsWith("mailto:") || l.startsWith("sms:")
}

/** What holding a link copies: the number, the address or the URL as a person would paste it, never the
 *  one-time token the app appends to its own server links. Null for app actions (retry, speak...) and files,
 *  which keep their normal long-press. */
internal fun copyTextFor(href: String): String? {
    val h = href.trim()
    val l = h.lowercase()
    fun dec(x: String) = try { java.net.URLDecoder.decode(x.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { x }
    return when {
        l.startsWith("tel:") || l.startsWith("sms:") -> dec(h.substringAfter(":").substringBefore("?")).filter { it.isDigit() || it == '+' }.ifEmpty { null }
        l.startsWith("mailto:") -> dec(h.substringAfter(":").substringBefore("?")).trim().ifEmpty { null }
        l.startsWith("http://") || l.startsWith("https://") ->
            h.replace(Regex("([?&])token=[^&#]*&?"), "$1").trimEnd('?', '&').replace("?&", "?")
        else -> null
    }
}

/** Hold-to-copy for a WebView hit: tel: and mailto: links come back as PHONE / EMAIL hits whose extra is the bare
 *  number or address, ordinary links as ANCHOR hits whose extra is the href. Anything else is not ours to copy. */
internal fun longPressCopy(type: Int, extra: String): String? = when (type) {
    android.webkit.WebView.HitTestResult.PHONE_TYPE -> copyTextFor("tel:$extra")
    android.webkit.WebView.HitTestResult.EMAIL_TYPE -> copyTextFor("mailto:$extra")
    android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> copyTextFor(extra)
    else -> null
}
