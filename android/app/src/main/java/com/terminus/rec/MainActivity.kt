package com.terminus.rec

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    internal lateinit var prefs: Prefs
    internal var phoneBrowser: PhoneBrowser? = null
    internal var approvalPrompt: ApprovalPrompt? = null
    internal lateinit var chunks: ChunkStore
    internal lateinit var adapter: ChunkAdapter

    internal lateinit var echoPane: View
    internal lateinit var galleryWeb: WebView
    internal lateinit var opsWeb: WebView
    internal lateinit var notesWeb: WebView
    internal lateinit var galleryPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var opsPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var notesPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var galleryPane: View
    internal lateinit var notesPane: View
    internal lateinit var opsPane: View
    internal lateinit var echoCard: android.view.View
    internal lateinit var echoOrb: android.view.View
    internal lateinit var recChip: TextView
    internal lateinit var waveView: WaveView
    internal lateinit var echoTitle: TextView
    internal lateinit var echoSub: TextView
    internal lateinit var timerText: TextView
    internal lateinit var serverDot: View
    internal var serverDots: MutableList<View> = mutableListOf()
    internal lateinit var statQueue: TextView
    internal lateinit var statStore: TextView
    internal lateinit var nav: BottomNavigationView

    // ---- Chat ----
    internal lateinit var chatStore: ChatStore
    internal lateinit var chatPane: View
    internal lateinit var sessionList: RecyclerView
    internal lateinit var sessionPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var sessionAdapter: SessionAdapter
    /** Last server session list (GET /api/sessions, pull-to-refresh only).
     *  Memory-only: mapped rows stay local chats, the rest render as
     *  importable rows. Never written to the ChatStore. */
    internal var serverImports: List<OpencodeApi.RemoteSession> = emptyList()
    internal var sessionsLoading = false
    internal lateinit var chatEmpty: TextView
    internal lateinit var sessionSearch: EditText
    internal lateinit var searchToggle: View
    internal lateinit var searchIcon: android.widget.ImageView
    internal lateinit var bellBtn: View
    internal var searchOpen = false
    internal lateinit var chatHero: View
    internal lateinit var heroModelBtn: Button
    internal lateinit var threadHeader: View
    internal lateinit var threadTitle: TextView
    internal var threadTitleExpanded = false
    internal var expandedHeaderControlVisibility: List<Pair<View, Int>>? = null
    internal lateinit var threadWeb: QuoteWebView
    /** Wrapper carrying the thread WebView + jump button. It holds layout
     *  weight, so it must hide with the thread — a visible-but-empty frame
     *  steals half the screen from the session list (v151 halving bug). */
    internal lateinit var threadFrame: View
    /** WhatsApp-style jump-to-latest: appears when the thread is scrolled
     *  up, one tap drops to the bottom. Pure position affordance — it never
     *  scrolls on its own and never fights the touch truce. */
    internal lateinit var jumpLatest: View
    internal lateinit var threadDivider: View
    internal lateinit var goalStrip: TextView
    internal lateinit var goalStripBar: android.view.View
    internal lateinit var composerRow: View
    /** Debounced list render for search keystrokes (see sessionSearch watcher):
     *  the query scan walks message bodies, so it runs per pause. hideSearch
     *  renders directly; a stale post after it is a harmless no-op. */
    internal val searchRenderDebounce = Runnable { try { renderSessions() } catch (_: Exception) {} }
    internal lateinit var composerInput: EditText
    internal lateinit var chatMic: ImageView
    /** The layout's own tint for the idle mic icon; the recording state paints it white. */
    internal var chatMicIdleTint: android.content.res.ColorStateList? = null
    internal lateinit var chatRecStatus: TextView
    internal lateinit var stopBtn: View
    internal lateinit var slashList: android.widget.ListView
    internal lateinit var slashAdapter: android.widget.ArrayAdapter<String>
    internal var slashShown: List<SlashCommand> = emptyList()
    internal var slashRowCommands: List<SlashCommand?> = emptyList()
    internal var liveCommands: List<SlashCommand> = emptyList()
    internal var liveCommandsBackend: String = ""
    internal var commandsLoading = false
    internal var liveCommandsAt = 0L
    /** True while completeSlash does its programmatic setText — the
     *  TextWatcher must not re-show the dropdown for that edit. */
    internal var completingSlash = false
    internal var micPulse: android.animation.ValueAnimator? = null
    // Continuous dictation: the in-app recognizer is restarted whenever the engine ends a phrase, so a pause
    // never ends dictation; only a tap, the send word, or DICT_IDLE_MS of silence does.
    internal var recognizer: android.speech.SpeechRecognizer? = null
    internal var macDict: MacDictation? = null // saywhat on the Mac; null = the phone recogniser (or idle)
    internal var dictating = false
    internal var dictBase = ""       // composer text from before dictation began
    internal var dictDone = ""       // phrases already finalized in this dictation
    internal var dictRendered = ""   // what dictation last wrote to the composer (edit detection)
    internal var dictPartial = ""    // phrase still being heard; kept if the engine moves on without a final result
    internal var dictSendArmedAt = 0L // when the rendered text last started ending with the send word (0 = not armed)
    internal val SEND_ARM_MS = 1500L // silence after a trailing send word before it counts as the command
    internal var dictLastHeard = 0L
    internal var dictErrors = 0
    internal val dictHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val DICT_IDLE_MS = 6000L
    internal val easeOut = android.view.animation.DecelerateInterpolator()
    internal lateinit var modelChip: com.google.android.material.button.MaterialButton
    internal lateinit var attachListBtn: View
    internal lateinit var contextRing: ContextRingView
    internal var contextInfo: OpencodeApi.ContextInfo? = null
    internal var contextChat: String? = null
    internal var contextFetchAt = 0L
    internal var activeSessionId: String? = null
    /** Read-aloud for assistant replies (engine, chunking, button state): see ReplyVoice. */
    internal val replyVoice by lazy {
        ReplyVoice(
            act = this,
            runJs = { js -> if (::threadWeb.isInitialized) threadWeb.evaluateJavascript(js, null) },
            toast = { toast(it) },
            onPlaying = { speakPlaying = it; updateScreenOn() },
        )
    }
    /** Mirrors ReplyVoice's speaking state (avoids instantiating it just to ask). */
    internal var speakPlaying = false

    /** Screen stays on while the mic is hot or audio is playing: Echo capture, a chat voice
     *  note, live dictation (a screen timeout would end it mid-sentence), read-aloud, or chunk playback. Cleared the moment everything is idle. */
    internal fun updateScreenOn() {
        val keep = RecorderService.running || chatRecActive || dictating || speakPlaying || playingName != null
        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    /** Lazy draft: thread UI open with NO stored session. activeSessionId
     *  stays null; persist (chatStore.create) only on first send or first
     *  model pick. Backing out via < discards everything. */
    internal var draftOpen = false
    internal var draftText = ""
    internal val pendingAttaches = mutableListOf<java.io.File>()
    /** Per-tab change dots (watcher): in-memory only. Notebook edits dot
     *  the notebook tab, gallery uploads dot artifacts. Visiting the tab
     *  clears its dot. Restart wipes them; dots are hints, not guarantees. */
    internal val tabDots = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    internal fun watchAreaOf(body: String): String =
        if (body.trimStart().startsWith("notebook/")) "notes" else "gallery"

    internal fun paintTabDots() {
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
    internal var pendingCameraUri: android.net.Uri? = null
    internal var pendingVoice: java.io.File? = null
    // Quote-reply: long-press a thread message fills these; the strip above
    // the composer shows excerpt + author, ✕ clears. Send prepends
    // `> excerpt` to the wire text. Hidden on send / thread switch.
    /** Quoted passages staged for the next send, each with an optional note (QuoteDraft.wire formats them). */
    internal val quotes = mutableListOf<QuoteItem>()
    internal lateinit var quoteBar: View
    internal lateinit var quoteChips: android.widget.LinearLayout
    internal lateinit var quoteEditor: View
    internal lateinit var quoteNote: EditText
    /** The quote whose note is open inline; isNew = ✕ discards the quote itself, not just the edit. */
    internal var editingQuote: QuoteItem? = null
    internal var editingQuoteIsNew = false
    internal val waitQueue = mutableMapOf<String, MutableList<PendingSend>>()
    internal val backendWaitingTurns = mutableMapOf<String, Set<String>>()
    internal val stopInProgress = mutableSetOf<String>()
    /** Turns folded into the running reply ("Send now"): they are no longer waiting in line. */
    internal val steeredTurnIds = mutableSetOf<String>()
    /** Chat + waiting turns the page was last told about: skips the script when nothing changed. */
    internal var sendNowKey = ""
    /** Stale turns already surfaced as unknown (chat → turnIds). Placeholders
     *  stay by design, so without this the sweep would re-report (and
     *  re-reconcile) on every render. Pruned to live turns on each render;
     *  a recheck tap bypasses it via reconcileTurn directly. */
    internal val unknownSurfaced = mutableMapOf<String, MutableSet<String>>()
    /** In-flight automatic stale-turn checks (chat:turn). Prevents the
     *  sweep from stacking a reconcile per render while one runs. */
    internal val checkingTurns = mutableSetOf<String>()
    /** Opencode's authoritative session-wide idle signal (distinct from a
     *  tool `step ended`); null until observed in this process. */
    internal val serverIdle = mutableMapOf<String, Boolean>()
    /** In-flight send-through turns per chat (turnIds POSTed, done not yet seen). */
    internal val pendingTurns = mutableMapOf<String, MutableSet<String>>()
    /** When each chat last sent a turn: tells the browser card which chat asked for it. */
    internal val lastAskedAt = mutableMapOf<String, Long>()
    /** Per-turn restore text for failure paths (turnId → user text). */
    internal val turnRestore = mutableMapOf<String, String>()
    /** Failed-turn retry stash (turnId → user text): tap-to-retry resends
     *  this as a FRESH turn without touching the composer's current text. */
    internal val failedRestore = mutableMapOf<String, String>()
    /** Composer edit mode: the box holds (chatId, turnId) of a failed turn
     *  being edited in place — send resends that turn, back cancels.
     *  Stash (turnId → displaced draft) is restored on cancel and after a
     *  successful resend, so entering edit mode never eats typed text. */
    internal var editingTurn: Pair<String, String>? = null
    internal val editingStashByTurn = HashMap<String, String>()
    /** Delivered-turn dedupe: retention beats the server replay window
     *  (turnResults TTL 10min) — entries live 15min, pruned on touch. A
     *  bare count cap can't cover a 50-turn burst inside the window. */
    internal val deliveredTurns = mutableMapOf<String, MutableMap<String, Long>>()
    internal val deliveredTtlMs = 15 * 60 * 1000L
    /** Abandoned turns: a retry resent this prompt as a fresh turn, so the
     *  original's late `done` drops even with new text (same TTL discipline
     *  as deliveredTurns). Answered by abandon-vs-merge: abandon — the fresh
     *  turn already carries the prompt, merging risks twins. */
    internal val supersededTurns = mutableMapOf<String, MutableMap<String, Long>>()
    /** Tap-checks in flight (retry/reconcile links): the open thread shows
     *  its loading box until the check lands, then it clears. UI-thread only;
     *  never a turn — verify/ticker/reconcile ignore it, Stop never shows. */
    internal val checking = mutableSetOf<String>()
    /** Last automatic stale-turn reconcile per chat:turn (see renderThread).
     *  Tap-checks bypass this map entirely. */
    internal val lastAutoReconcileAt = mutableMapOf<String, Long>()
    /** Merged-turn pointer text: generic by design (no quoted reply, no MID),
     *  so regeneration can never stale it. Rendered muted without toolhead. */
    internal val POINTER_TEXT = "answered together with the shared reply"
    /** Opencode replies already rendered (messageID → settled). A later turn
     *  resolving to the same messageID merged server-side: its placeholder
     *  drops silently instead of painting a twin row. Distinct messageIDs
     *  still settle independently — fair game. */
    internal val settledMessages = mutableMapOf<String, MutableSet<String>>()
    internal lateinit var voicePreview: View
    internal lateinit var voicePreviewLabel: TextView
    internal lateinit var attachPreview: View
    internal lateinit var attachPreviewLabel: TextView
    internal lateinit var attachThumb: android.widget.ImageView
    internal val attachmentPreviewExec = java.util.concurrent.Executors.newFixedThreadPool(2)
    internal val attachmentPreviewCache = object : android.util.LruCache<String, android.graphics.Bitmap>(4096) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    internal var sessionQuery = ""
    /** Session list filter tab: 0 = Recent, 1 = Unread, 2 = Schedules. */
    internal var sessionFilter = 0
    /** Session list sort: newest | activity | name. */
    internal var sessionSort = "newest"
    internal lateinit var sessControls: View
    internal lateinit var tabAll: TextView
    internal lateinit var tabUnread: TextView
    internal lateinit var tabSchedules: TextView
    internal lateinit var sortBtn: TextView
    /** Fullscreen video surface for thread WebViews (expand actually works). */
    internal var fullscreenView: View? = null
    internal var fullscreenDialog: android.app.Dialog? = null
    internal var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    internal lateinit var iconBackendScope: ImageView
    /** Full HTML last loaded into threadWeb — identical renders skip
     *  loadData entirely (scroll-touch truce). Compared by value: a hash
     *  collision here would silently skip a genuine update. */
    internal var lastThreadHtml = ""
    /** History paging: messages rendered per thread (default tail).
     *  Reset on thread open; grows by page size per "show earlier" tap. */
    internal val threadVisible = mutableMapOf<String, Int>()
    /** Chats with a server delete in flight (row shows Deleting until the
     *  server confirms or fails). Mirrored into the adapter for render. */
    internal val deletingIds = mutableSetOf<String>()
    internal val threadPageSize = 60
    /** No-blink streaming: the thread page stays loaded during live turns;
     *  step-only updates patch #prog via JS (throttled, coalesced). A false
     *  here (or a changed message signature) falls back to full render. */
    internal var threadPageLoaded = false
    internal var lastStepPatchAt = 0L
    internal var pendingStepPatch: Runnable? = null
    internal val lastStepPatched = mutableMapOf<String, Int>()
    internal var lastFullMsgSig = ""
    internal val STEP_PATCH_MIN_MS = 800L
    /** Live progress steps per chat (current turn) + settled history kept
     *  for the post-turn expander. Memory-only; never persisted. */
    internal val progressLive = mutableMapOf<String, List<OpencodeApi.ProgressStep>>()
    /** Newest applied done settle time per chat (server `at`). Sent as the
     *  stream replay floor (?since=) so reconnects replay only turns
     *  settled after it — never the full retained history. Memory-only;
     *  a fresh process omits the floor and takes the server window. */
    internal val doneHighWater = mutableMapOf<String, Long>()
    /** Settled text (normalized) per delivered turn: a reconnect replay of
     *  the exact same outcome drops with zero message scans and zero
     *  renders. Key "$chat:$turn". TurnIds are unique per turn, so an exact
     *  match can never be a new outcome; anything different falls through
     *  to the normal merge rule. Capped; memory-only (cross-restart replays
     *  are already bounded by the persisted high-water floor). */
    internal val deliveredTextNorm = mutableMapOf<String, String>()
    internal fun noteDeliveredText(id: String, turnId: String, text: String) {
        if (turnId.isBlank() || text.isBlank()) return
        try {
            synchronized(deliveredTextNorm) {
                deliveredTextNorm["$id:$turnId"] = TurnActivity.normText(text)
                while (deliveredTextNorm.size > 200) { deliveredTextNorm.remove(deliveredTextNorm.keys.first()) }
            }
        } catch (_: Exception) {}
    }
    internal fun isExactRedelivery(id: String, turnId: String, text: String): Boolean {
        if (turnId.isBlank() || text.isBlank()) return false
        return try {
            synchronized(deliveredTextNorm) { deliveredTextNorm["$id:$turnId"] == TurnActivity.normText(text) }
        } catch (_: Exception) { false }
    }
    /** Replay floor for (re)connects: memory first, persisted across
     *  restarts second. The server defaults to its recent window when 0. */
    internal fun streamSince(id: String): Long = try {
        maxOf(doneHighWater[id] ?: 0L, prefs.doneHighWater(id))
    } catch (_: Exception) { 0L }
    /** Per-chat changed-files accumulation from step `files` (see step
     *  handler). The settling turn takes and clears it; capped. */
    internal val changedLive = mutableMapOf<String, MutableSet<String>>()
    internal fun takeChanged(id: String): List<String> = try {
        (changedLive.remove(id) ?: emptySet()).toList()
    } catch (_: Exception) { emptyList() }
    /** Stamp the settling turn's reply, then clear the accumulator whether
     *  or not a row matched (a new turn starts clean either way). */
    internal fun stampSettledChanged(id: String, turnId: String, messageID: String) {
        val files = takeChanged(id)
        if (files.isEmpty()) return
        try { chatStore.stampChangedFiles(id, turnId, messageID, files) } catch (_: Exception) {}
    }
    internal val progressDone = mutableMapOf<String, List<OpencodeApi.ProgressStep>>()
    internal val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Draft debounce: keystrokes update the composer immediately; the store
     *  (now cheap, but still IO-bound) persists after a short idle gap, or
     *  synchronously on thread close / send / backgrounding — never lost. */
    internal val draftHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var pendingDraftSave: Runnable? = null
    internal var pendingDraftId: String? = null
    /** Cancel any debounced draft write (a fresher write is taking over). */
    internal fun dropPendingDraft() {
        pendingDraftSave?.let { try { draftHandler.removeCallbacks(it) } catch (_: Exception) {} }
        pendingDraftSave = null
        pendingDraftId = null
    }
    /** Flush a debounced draft write now (must-survive points). */
    internal fun flushPendingDraft() {
        val r = pendingDraftSave ?: return
        val id = pendingDraftId
        dropPendingDraft()
        try { r.run() } catch (_: Exception) {}
        if (id != null) { try { chatStore.flushAsync() } catch (_: Exception) {} }
    }
    internal val stopRefresh = object : Runnable {
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
    internal val autoVerifying = mutableSetOf<String>()
    internal var stopWasActive = false
    internal var stopPulse: android.animation.ObjectAnimator? = null
    /** SSE stream turn: startStream() owns this; stopStream()/settle paths
     *  clear it. Steps ride the stream; the server also polls opencode
     *  every few seconds as a fallback, so steps can arrive twice. */
    internal var streamThread: Thread? = null
    internal var streamChat: String? = null
    /** Background jobs still running, per chat. While any is live the chat's
     *  stream must stay open: the finish row arrives after the turn settled,
     *  and a closed stream would drop it. */
    internal val backgroundJobs = BackgroundJobs()
    /** The single pending scroll-to-bottom post; any touch on threadWeb
     *  cancels it. Exactly one scroll per new render. */
    /** What the last full render asked of the scroll position (see scrollPlan). */
    internal var lastRenderScroll = true
    internal var lastRenderKeepCssY = 0
    /** One-shot "open at the first unread message": (chat, row index, message count at open). Dropped by
     *  a touch, a new message, or closing the thread. */
    internal var openAnchor: Triple<String, Int, Int>? = null
    /** Where each chat was left (css px; -1 = at the bottom). In memory only: reopening a chat with
     *  nothing unread returns there instead of jumping. `openRestore` is the one-shot for this open. */
    internal val leftScrollY = HashMap<String, Int>()
    internal var openRestore: Pair<String, Int>? = null
    internal val threadScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var pendingThreadScroll: Runnable? = null
    // Chat voice notes use RecorderService's local-only capture mode. The
    // recordings enter the normal chat attachment flow only after approval.
    internal var chatRecActive = false
    internal var chatRecStartingAt = 0L
    internal var chatChunkBaseline = setOf<String>()

    internal var player: android.media.MediaPlayer? = null
    internal var playingName: String? = null
    internal var serverOk = false
    internal var healthBackend = ""
    /** Last accepted send tap (bounce debounce). */
    internal var lastSendAt = 0L
    /** Clip lengths by file name (ms); filled lazily, survives refreshes. */
    internal val clipLens = mutableMapOf<String, Long>()
    /** Last render time per turn for live delta painting (throttled). */
    internal val deltaRenderAt = mutableMapOf<String, Long>()
    /** Delta buffering: fragments accumulate here; the store persists at most
     *  every DELTA_PERSIST_MS so streaming cost stays flat as history grows.
     *  DOM paints straight from the buffer; `done` settles canonically. */
    internal val deltaBuf = mutableMapOf<String, StringBuilder>()
    internal val deltaPersistAt = mutableMapOf<String, Long>()
    internal val DELTA_PERSIST_MS = 1500L
    /** Trailing paint per turn: the throttle drops the tail of a burst, so a reply that pauses on a tool call
     *  would sit at its first words ("Let me cap") until the next delta. One scheduled flush closes that gap. */
    internal val deltaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val deltaTrail = mutableMapOf<String, Runnable>()

    /** Drop per-turn transient state (render/persist clocks + delta buffer). */
    internal fun dropTurnBuf(turnId: String) {
        deltaTrail.remove(turnId)?.let { deltaHandler.removeCallbacks(it) }
        deltaBuf.remove(turnId)
        deltaPersistAt.remove(turnId)
        deltaRenderAt.remove(turnId)
    }
    internal var pulse: android.animation.ValueAnimator? = null
    /** Last LAN-retried WebView URL — guards the onReceivedError fallback loop. */
    internal var lastLanRetry: String? = null

    /** True while the activity is resumed. Long opencode turns that finish
     *  with this false fire a Chat-channel notification instead of UI only. */
    @Volatile internal var appForeground = false
    /** Label inside the open cog sheet showing the current chat tone. */
    internal var toneLabel: android.widget.TextView? = null
    /** System tone picker result → stored pref + applied to Chat channel. */
    internal val soundPickerLauncher =
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
    internal fun isDark(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    internal fun applyAppearance(mode: String) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
    }

    /** 150ms ease-out cross-fade for tab pane switches. */
    internal fun crossFade(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(150L).setInterpolator(easeOut).start()
    }

    internal fun setPulsing(on: Boolean) {
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

    internal val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val timerTick = object : Runnable {
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
    internal val waveTick = object : Runnable {
        override fun run() {
            if (!::waveView.isInitialized) return
            if (RecorderService.running && !chatRecActive && !RecorderService.chatCaptureActive) {
                waveView.push(waveLevel(RecorderService.amplitude()))
                timerHandler.postDelayed(this, 120)
            }
        }
    }

    internal var filePickerCallback: ValueCallback<Array<android.net.Uri>>? = null
    internal val filePickerLauncher =
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChatIntent(intent)
        handleShareIntent(intent)
        handleProcessText(intent)
    }

    internal val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (ok && uri != null) {
                try {
                    stageAttachUri(uri, "camera.jpg")
                } catch (_: Exception) {}
            }
        }

    internal val attachLauncher =
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

    /** Compact thread chip: short model + chevron. Short = id.substringAfter("/").
     *  Blank model follows that backend's default (chat default first). Never blank.
     *  No manual truncation: the view ellipsizes end against its maxWidth. */
    internal var serverDefaultModel: String = ""

    internal var contextTrailing: Runnable? = null


    internal val UUID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    internal val pageCache = object : LinkedHashMap<String, PageBuild>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageBuild>?) = size > 6
    }
    internal val pageExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "page-prebuild").apply { isDaemon = true } }
    /** Chips and alarm cards read time-dependent state (alarm armed/passed, link titles landing): a built page
     *  is only trusted for a short while. A changed chat changes the key and never reuses an old page. */
    internal val PAGE_TTL_MS = 45_000L
    internal val prebuildRun = Runnable { prebuildThreads() }

    /** Cached structural signature: threadMsgSig walks the whole history,
     *  and it ran on every step event + text paint (several per second on
     *  the UI thread, cost growing with chat length — the background lag
     *  that worsened the longer a chat got). 1s TTL: worst case a page/store
     *  drift waits one beat before the next event catches it. */
    internal val msgSigCache = mutableMapOf<String, Pair<String, Long>>()
    /** A background shell's start or finish landed. Appends a durable event
     *  row (never a live-box step): the finish usually arrives after the turn
     *  settled, when no live box exists. Both ends stay in the thread. */
    internal val bgSeqPrefs by lazy { getSharedPreferences("background-seq", MODE_PRIVATE) }
    internal val bgCatchUpAt = HashMap<String, Long>()

    // ---- pull-up footer: a small pill at the bottom of the thread. A refresh glyph rises and turns as
    // you drag, "Release to check" says what the gesture does, it spins while the server is asked, and
    // the outcome reads as one short line before it slides away. Built in code so no layout changes.
    internal var pullBox: android.widget.LinearLayout? = null
    internal var pullIcon: android.widget.TextView? = null
    internal var pullLabel: android.widget.TextView? = null
    internal var pullSpin: android.animation.ObjectAnimator? = null
    internal var pullChecking = false
    internal val pullHideRun = Runnable { pullChecking = false; pullHide() }


    /** Short toast on the UI thread (link taps, check outcomes). */
    internal fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    /** One path for every status row: store it, append it (or re-render),
     *  refresh the list. Replaces the hand-rolled add + append-or-render
     *  blocks that drifted apart. */
    internal fun postNote(id: String, role: String, text: String) {
        val m = ChatMessage(role, text)
        chatStore.addMessage(id, m)
        if (activeSessionId == id && !appendRowDom(id, m)) renderThread(id)
        renderSessions()
    }

    /** Catch-and-log for hot paths: silent catches stay silent nowhere that
     *  matters. Debug-level so release logcat stays quiet. */
    internal inline fun <T> safe(tag: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.d("terminus", "$tag: ${e.message}")
            null
        }
    }

    internal fun isTurnActive(id: String): Boolean {
        if (serverIdle[id] == true) return false
        return hasFreshTurn(id)
    }

    /** Fresh-turn check shared by the stop button and the live thinking
     *  box: a live placeholder with recent activity. Orphans keep their
     *  late-`done` settle chance but earn neither icon nor box. */
    internal fun hasFreshTurn(id: String): Boolean {
        return try {
            val msgs = chatStore.get(id)?.messages ?: return false
            runningTurnIds(msgs, pendingTurns[id]?.toSet() ?: emptySet(), serverIdle = false).isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** History auto-fetch guard: one in-flight grow per tap/scroll. */
    internal var historyGrowing = false
    /** True once a finger drives the thread WebView. Resets on thread
     *  open: only user scrolls may auto-fetch history, never the
     *  programmatic motion of open/bottom-pin. */
    internal var threadTouchScroll = false
    /** At most one history page per physical drag, including while a fling
     *  continues after ACTION_UP. */
    internal var threadHistoryGestureUsed = false

    internal val codexCommands = listOf(
        SlashCommand("/usage", "show Codex usage and reset credits"),
        SlashCommand("/resets", "list available Codex reset credits"),
        SlashCommand("/redeem", "redeem an available Codex reset credit"),
    )

    internal val localSlashCommands: Set<String>
        get() = ChatStore.COMMANDS.map { it.name.lowercase() }.toSet()

    internal val chatPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val chatPoll = object : Runnable {
        override fun run() {
            if (!chatRecActive) return
            scanChatChunks()
            updateChatRecUI()
            chatPollHandler.postDelayed(this, 1000)
        }
    }

    internal val dictListener = object : android.speech.RecognitionListener {
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

    internal val dictIdleCheck = object : Runnable {
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

    internal val speechLauncher =
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

    /** Backstop pull: merge every inbox entry since the last cursor into its
     *  job thread. Open-chat delivery is silent; elsewhere the bell dot
     *  always ticks and a system ping fires outside quiet hours. */
    /** Last time the inbox was pulled, so opening chats back to back does not refetch it each time. */
    internal var lastInboxPullAt = 0L

    @Volatile internal var outboxRunning = false
    internal val outboxHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    internal val outboxTick = Runnable { flushOutbox() }

    /** Open one reasoning box ("…") per turn + stream + POST. Send-through:
     *  overlapping turns each carry their own turnId and settle in place. */
    internal val sessionGates = mutableMapOf<String, java.lang.Object>()
    internal val sessionAcquiring = mutableSetOf<String>()
    internal fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()

    /** Session goal UI: arm sheet, status strip, manage sheet.
     *  The strip shows only when the open chat has a goal; tap opens
     *  pause/resume/edit/remove. Server owns state; client only renders. */
    internal var goalCache: OpencodeApi.GoalInfo? = null

    internal val remoteModelsByBackend = mutableMapOf<String, List<RemoteModel>>()
    internal val modelLoadingBackends = mutableSetOf<String>()
    internal val unavailableModelCatalogs = mutableSetOf<String>()
    internal var backendCatalog: BackendCatalog? = null

    /** Browser card lifecycle (owner chat, live card, frozen card): see BrowserCardController. */
    internal val browserCards by lazy {
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
    internal var lastVolUpAt = 0L
    internal var lastVolDownAt = 0L
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
    internal fun readLastReply() {
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

    internal var scheduleFactsAt = 0L

    /** The chat list's schedule lines ("next Sun 18:30 · weekly"): refetched at most every 2 minutes. */
    internal fun refreshScheduleFacts() {
        val now = System.currentTimeMillis()
        if (now - scheduleFactsAt < 120_000 || prefs.token().isBlank()) return
        scheduleFactsAt = now
        Thread {
            val facts = try { OpencodeApi.fetchScheduleRows(prefs.serverUrl(), prefs.token()) } catch (_: Exception) { null }
            if (facts != null) runOnUiThread { sessionAdapter.scheduleFacts = facts }
            else scheduleFactsAt = 0L
        }.start()
    }

    internal fun pingServer() {
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

    // isInitialized is only allowed inside the declaring class, so the extension files ask through these.
    internal val attachListBtnReady: Boolean get() = ::attachListBtn.isInitialized
    internal val attachPreviewReady: Boolean get() = ::attachPreview.isInitialized
    internal val attachThumbReady: Boolean get() = ::attachThumb.isInitialized
    internal val bellBtnReady: Boolean get() = ::bellBtn.isInitialized
    internal val chatMicReady: Boolean get() = ::chatMic.isInitialized
    internal val chatPaneReady: Boolean get() = ::chatPane.isInitialized
    internal val chatStoreReady: Boolean get() = ::chatStore.isInitialized
    internal val composerInputReady: Boolean get() = ::composerInput.isInitialized
    internal val contextRingReady: Boolean get() = ::contextRing.isInitialized
    internal val goalStripReady: Boolean get() = ::goalStrip.isInitialized
    internal val goalStripBarReady: Boolean get() = ::goalStripBar.isInitialized
    internal val iconBackendScopeReady: Boolean get() = ::iconBackendScope.isInitialized
    internal val jumpLatestReady: Boolean get() = ::jumpLatest.isInitialized
    internal val navReady: Boolean get() = ::nav.isInitialized
    internal val quoteBarReady: Boolean get() = ::quoteBar.isInitialized
    internal val quoteEditorReady: Boolean get() = ::quoteEditor.isInitialized
    internal val searchToggleReady: Boolean get() = ::searchToggle.isInitialized
    internal val sessControlsReady: Boolean get() = ::sessControls.isInitialized
    internal val sessionAdapterReady: Boolean get() = ::sessionAdapter.isInitialized
    internal val stopBtnReady: Boolean get() = ::stopBtn.isInitialized
    internal val tabAllReady: Boolean get() = ::tabAll.isInitialized
    internal val threadFrameReady: Boolean get() = ::threadFrame.isInitialized
    internal val threadTitleReady: Boolean get() = ::threadTitle.isInitialized
    internal val threadWebReady: Boolean get() = ::threadWeb.isInitialized
    internal val voicePreviewReady: Boolean get() = ::voicePreview.isInitialized

    companion object {

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
