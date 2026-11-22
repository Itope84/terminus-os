package com.terminus.rec

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.agentwebview.AgentTab
import dev.agentwebview.ConfirmationHandler
import dev.agentwebview.EventRecord
import dev.agentwebview.HandoffBanner
import dev.agentwebview.HandoffKind
import dev.agentwebview.LinkSecurity
import dev.agentwebview.NavigationPolicy
import dev.agentwebview.SessionConfig
import dev.agentwebview.SessionState
import dev.agentwebview.SessionStatus
import dev.agentwebview.TabEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** In-app agent browser. The server relays JSON commands (server/src/
 *  phone-browser.ts); each goes to AgentTab.handleJson and the reply returns
 *  verbatim. Protocol: see DESIGN.md in github.com/aesrael/agent-webview-android.
 *
 *  Lifetime: the relay is connected from onResume to onPause, because a
 *  WebView cannot do work while the app is off screen. Offline, the server
 *  answers the agent `phone_offline` at once.
 *
 *  Z-order: the WebView must stay attached and visible or it renders nothing
 *  (snapshots and waits break). Closed, the host sits behind the app content;
 *  open, translationZ lifts it above. The app's main layout must be opaque.
 *
 *  Control: the agent drives by default and a shield blocks stray touches.
 *  Take over = a library handoff (agent commands are refused, observe and
 *  screenshot are refused, so the agent never sees what you type). Stop =
 *  cancel the running command and release the browser.
 *
 *  Cleanup: no browser exists until needed. It is released when the agent
 *  sends `close`, on Stop, or after IDLE_MS with no command, no handoff and
 *  the screen closed. Closing the screen alone does not end the session (the
 *  agent may just be thinking). The next command builds a fresh one. */
class PhoneBrowser(private val act: AppCompatActivity, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val IDLE_MS = 3 * 60_000L
    /** Leaving the app mid hand-off (to read a 2FA code, say) must not tell the waiting agent the phone vanished. */
    private val BACKGROUND_GRACE_MS = 5 * 60_000L
    private val MAX_STEPS = 80
    private val MAX_NOTES = 20
    private val MAX_SHOTS = 6
    /** With an agent turn still running, a quiet session may live this long before it is released. */
    private val HARD_IDLE_MS = 15 * 60_000L
    private val green = 0xFF1F5C46.toInt()
    private val red = 0xFFB5463C.toInt()
    private val amber = 0xFF8A5A00.toInt()
    private val paper = 0xFFF3EDE2.toInt()
    private val ink = 0xFF1C1B18.toInt()
    private val muted = 0xFF6B675E.toInt()
    private val tray = 0xFFE7E0D2.toInt()

    private lateinit var host: LinearLayout
    private lateinit var page: FrameLayout
    private lateinit var web: WebView
    private lateinit var tab: AgentTab
    private lateinit var shield: View
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var urlField: EditText
    private lateinit var who: TextView
    private lateinit var takeBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var shotBtn: TextView
    /** Non-browser events on the phone channel (approvals): (event, data). */
    var onChannelEvent: ((String, String) -> Unit)? = null
    /** Called after cookies are wiped so the app can re-plant its own server login. */
    var onCookiesCleared: (() -> Unit)? = null
    private lateinit var pill: TextView
    @Volatile private var ready = false
    /** The WebView and its session exist only while there is work: built on
     *  the first command (or when the person opens the browser), released when
     *  the agent sends `close`, on Stop, or after IDLE_MS with nothing going on. */
    @Volatile private var tabLive = false
    /** Chat tag of the agent that built the live browser (null = untagged). A different chat never inherits it:
     *  it is refused while the owner is busy, otherwise the browser is released and rebuilt blank. */
    @Volatile private var ownerTab: String? = null
    private val OWNER_GRACE_MS = 30_000L
    /** App on screen (onResume..onPause). Notifications are only for when it is not. */
    @Volatile private var fg = false
    /** Last page the session showed, kept after it ends so the frozen card can still name it. */
    @Volatile private var lastUrl = ""

    private val idleTick = Runnable { idleCheck() }

    private class Inflight(val job: Job, val replied: AtomicBoolean)
    private val inflight = ConcurrentHashMap<String, Inflight>()
    private val running = AtomicInteger(0)
    @Volatile private var lastActive = 0L
    private val paintQueued = AtomicBoolean(false)

    /** Live status for the chat card; MainActivity forwards it to the thread. */
    var onState: ((String) -> Unit)? = null
    private var lastCore = ""
    /** Screenshot timestamps whose image data the chat card already has. */
    private val sentAts = HashSet<Long>()
    private var sentThumbAt = Long.MIN_VALUE
    private enum class Mode { CORE, INCREMENTAL, FULL }

    /** state: r running, o done, x failed, h waiting for the person. */
    private class Step(val id: Long, var label: String, var state: Char, val startMs: Long, var ms: Long = 0, var detail: String = "")
    private class Note(val text: String, val atMs: Long)
    /** [bytes] (full-size JPEG/PNG) is dropped when the session ends; the small thumbnail stays for the card. */
    private class Shot(val thumb: Bitmap, @Volatile var bytes: ByteArray?, val label: String, val atMs: Long) { var b64: String? = null }
    private val steps = ArrayList<Step>()
    private val notes = ArrayList<Note>()
    private val shots = ArrayList<Shot>()
    @Volatile private var stepsVersion = 0
    @Volatile private var controller = "the agent"
    /** The chat the agent that is driving this browser belongs to (sent by the server with each command).
     *  Notifications deep-link there and that chat is flagged "needs you" while a hand-off or approval waits. */
    @Volatile private var originChat = ""
    /** (chatId, reason, on): MainActivity flags or clears the chat's needs-you state. */
    var onNeedsYou: (String, String, Boolean) -> Unit = { _, _, _ -> }
    @Volatile private var linkProblem: String? = null

    private lateinit var pageWrap: FrameLayout
    private lateinit var stepsScroll: ScrollView
    private lateinit var stepsBody: LinearLayout
    private lateinit var tabPage: TextView
    private lateinit var tabSteps: TextView
    private var listCard: View? = null
    private var showSteps = false
    private var renderedKey = ""
    private val pauseStop = Runnable { stop() }

    @Volatile private var alive = false
    @Volatile private var call: okhttp3.Call? = null
    private var thread: Thread? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    // ---- Build ------------------------------------------------------------

    /** Build views once; call from onCreate after setContentView. */
    fun attach() {
        if (ready) return
        val root = act.findViewById<ViewGroup>(android.R.id.content)

        host = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            isClickable = true
        }
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(4))
        }
        top.addView(label("✕", 20f).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { close() }
        })
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title = label("Browser", 17f).apply { setTypeface(typeface, Typeface.BOLD); setSingleLine(true) }
        sub = label("", 13f).apply { setTextColor(muted) }
        titles.addView(title); titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("Clear logins", 13f).apply {
            setTextColor(red)
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { confirmClearLogins() }
        })
        host.addView(top)

        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        bar.addView(label("‹", 24f).apply { setPadding(dp(12), 0, dp(12), 0); setOnClickListener { if (userHasControl() && web.canGoBack()) web.goBack() } })
        bar.addView(label("›", 24f).apply { setPadding(dp(12), 0, dp(12), 0); setOnClickListener { if (userHasControl() && web.canGoForward()) web.goForward() } })
        urlField = EditText(act).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(ink)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(tray)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isEnabled = false
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { userNavigate(v.text.toString()); true } else false
            }
        }
        bar.addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label("⟳", 20f).apply { setPadding(dp(12), 0, dp(12), 0); setOnClickListener { if (userHasControl()) web.reload() } })
        host.addView(bar)

        val tabs = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        tabPage = chip("Page") { setStepsVisible(false) }
        tabSteps = chip("Steps") { setStepsVisible(true) }
        tabs.addView(tabPage); tabs.addView(tabSteps)
        host.addView(tabs)

        page = FrameLayout(act)
        // The steps list covers the page without detaching it: the WebView keeps its size and keeps rendering,
        // so snapshots and screenshots keep working while the person reads the list.
        pageWrap = FrameLayout(act)
        pageWrap.addView(page, FrameLayout.LayoutParams(-1, -1))
        stepsBody = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(20))
        }
        stepsScroll = ScrollView(act).apply {
            setBackgroundColor(paper)
            visibility = View.GONE
            isFillViewport = true
            addView(stepsBody, ViewGroup.LayoutParams(-1, -2))
        }
        pageWrap.addView(stepsScroll, FrameLayout.LayoutParams(-1, -1))
        host.addView(pageWrap, LinearLayout.LayoutParams(-1, 0, 1f))
        styleTabs()
        shield = View(act).apply { setOnTouchListener { _, _ -> true } }

        val ctl = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(tray)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        who = label("", 14f)
        ctl.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shotBtn = button("📷", 0xFF3A382F.toInt()) { screenshot() }
        takeBtn = button("Take over", 0xFF3A382F.toInt()) { toggleTakeOver() }
        stopBtn = button("Stop", red) { hardStop() }
        ctl.addView(shotBtn); ctl.addView(takeBtn); ctl.addView(stopBtn)
        host.addView(ctl)

        root.addView(host, 0, FrameLayout.LayoutParams(-1, -1))
        host.translationZ = 0f

        pill = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(green)
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener { open() }
        }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(84))
        })

        ready = true
        paint()
    }

    /** Fresh WebView + session. Also the recovery path when the renderer dies. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildTab() {
        web = WebView(act)
        page.addView(web, FrameLayout.LayoutParams(-1, -1))
        page.addView(shield, FrameLayout.LayoutParams(-1, -1))
        // Everything on the public internet is open. The library already
        // refuses non-web schemes and private/LAN/link-local/CGNAT addresses.
        // On top of that, this app's own server is denied by name: the other
        // in-app WebViews hold its login cookie in the same jar.
        val own = try { android.net.Uri.parse(prefs.serverUrl()).host } catch (_: Exception) { null }
        val config = SessionConfig(
            navigation = NavigationPolicy(denyHosts = listOfNotNull(own)),
            confirmSensitive = ConfirmationHandler { req -> askUser(req.reason) },
        )
        tab = AgentTab(web, config)
        tab.bindHandoffUi(page) { t ->
            main.post { open() }
            val chat = originChat
            if (chat.isNotEmpty()) onNeedsYou(chat, "Browser needs you: " + t.reason.take(100), true)
            if (!fg) ChatNotify.notifyBrowser(act, "Browser needs you", t.reason, chat)
        }
        synchronized(steps) {
            steps.clear(); notes.clear(); lastUrl = ""
            steps.add(Step(0, "Browser ready", 'o', System.currentTimeMillis()))
        }
        synchronized(shots) { shots.forEach { try { it.thumb.recycle() } catch (_: Exception) {} }; shots.clear() }
        stepsVersion++
        tab.session.subscribe { rec ->
            when (val e = rec.event) {
                is TabEvent.HandoffResumed, is TabEvent.HandoffTimedOut -> {
                    ChatNotify.clearBrowser(act)
                    val chat = originChat
                    if (chat.isNotEmpty()) onNeedsYou(chat, "", false)
                }
                is TabEvent.RendererGone -> main.post { recover() }
                // A dialog that opens while the person has control is theirs to answer; the agent's own
                // answer_dialog is refused during a hand-off, so without this the page would just hang.
                is TabEvent.DialogOpened -> if (e.kind != "alert") main.post { if (userHasControl()) showHumanDialog(e.kind, e.message, e.defaultValue) }
                else -> {}
            }
            trackStep(rec)
            main.post { paint() }
        }
        tabLive = true
        host.keepScreenOn = true // a screen timeout would stall the agent mid-task
        touchIdle()
    }

    private fun trackStep(rec: EventRecord) {
        var changed = true
        synchronized(steps) {
            when (val e = rec.event) {
                is TabEvent.ActionStarted -> steps.add(Step(e.actionId, e.label, 'r', rec.timeMs))
                is TabEvent.ActionFinished -> {
                    val st = steps.lastOrNull { it.id == e.actionId }
                    if (st != null) {
                        st.state = if (e.status == "ok") 'o' else 'x'
                        st.ms = rec.timeMs - st.startMs
                        if (st.state == 'x') st.detail = failureDetail(e.summary)
                    } else if (e.summary.contains("HANDOFF_ACTIVE")) {
                        changed = false // the agent knocking while the person drives is not a step
                    } else {
                        // refused before it started (stale ref, blocked page, ...): still worth showing
                        val what = e.summary.substringBefore(" \u2192").replace('_', ' ').replaceFirstChar { it.uppercase() }
                        steps.add(Step(e.actionId, what, 'x', rec.timeMs, 0, failureDetail(e.summary)))
                    }
                }
                is TabEvent.AgentNote -> {
                    notes.add(Note(e.text, rec.timeMs))
                    while (notes.size > MAX_NOTES) notes.removeAt(0)
                }
                is TabEvent.HandoffRequested -> steps.add(Step(-e.id, "Needs you: " + e.reason.take(80), 'h', rec.timeMs))
                is TabEvent.HandoffResumed -> endHandoffStep(
                    if (e.by == "auto") "Handed back automatically" else "You handed control back", 'o', e.durationMs,
                )
                is TabEvent.HandoffTimedOut -> endHandoffStep("Hand-off timed out", 'x', e.durationMs)
                else -> changed = false
            }
            while (steps.size > MAX_STEPS) steps.removeAt(0)
        }
        if (changed) stepsVersion++
    }

    private fun endHandoffStep(label: String, state: Char, ms: Long) {
        steps.lastOrNull { it.state == 'h' }?.let { it.label = label; it.state = state; it.ms = ms }
    }

    /** "click → failed (STALE_REF: stale ref @e3 ...)" → the part in brackets. */
    private fun failureDetail(summary: String): String =
        Regex("\\(([^)]*)\\)").find(summary)?.groupValues?.get(1)?.take(90) ?: ""

    /** Renderer crash: replace the WebView, keep the screen as it was. */
    private fun recover() {
        if (!ready || !tabLive) return
        val wasOpen = isOpen()
        val keep = ownerTab
        releaseTab()
        ownerTab = keep
        ensureTab()
        if (wasOpen) open()
        paint()
    }

    private fun ensureTab() {
        if (ready && !tabLive) buildTab()
    }

    /** Ends the session and frees the WebView. Cookies stay in the app's
     *  cookie jar, so logins the person made by hand survive for next time. */
    private fun releaseTab() {
        main.removeCallbacks(idleTick)
        if (!ready || !tabLive) return
        tabLive = false
        ownerTab = null
        host.keepScreenOn = false
        synchronized(shots) { shots.forEach { it.bytes = null } }
        try { tab.close() } catch (_: Exception) {}
        page.removeAllViews()
        try { web.destroy() } catch (_: Exception) {}
        host.translationZ = 0f
        controller = "the agent"
        paint()
    }

    private fun touchIdle() {
        main.removeCallbacks(idleTick)
        if (tabLive) main.postDelayed(idleTick, IDLE_MS)
    }

    /** True while the server says an agent turn is running (pushed over the relay stream). */
    @Volatile private var agentBusy = false
    @Volatile private var lastCmdAt = 0L

    private fun idleCheck() {
        if (!ready || !tabLive) return
        // An open live view no longer keeps an idle session alive: no command and no hand-off means
        // nobody is browsing, so the browser and the view both close (releaseTab lowers the overlay).
        // A running agent turn (long thinking pause between page actions) keeps the page for up to
        // HARD_IDLE_MS since the last command; a finished turn means it can go after IDLE_MS.
        val quiet = System.currentTimeMillis() - lastCmdAt
        val busy = running.get() > 0 || tab.session.activeHandoff != null || (agentBusy && quiet < HARD_IDLE_MS)
        if (busy) touchIdle() else releaseTab()
    }

    // ---- UI helpers -------------------------------------------------------

    private fun label(t: String, sp: Float) = TextView(act).apply { text = t; textSize = sp; setTextColor(ink) }

    private fun rounded(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(22).toFloat() }

    private fun button(t: String, c: Int, onTap: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(c)
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
    }

    private fun userHasControl() = ready && tabLive && tab.session.activeHandoff != null

    fun isOpen() = ready && host.translationZ > 0f

    fun open() {
        if (!ready) return
        // No ensureTab(): opening the view of an ended session must not build a new one.
        // Sessions start only when the agent sends a command (runCmd).
        host.translationZ = dp(100).toFloat()
        pill.visibility = View.GONE
        paint()
    }

    fun close() {
        if (!ready) return
        host.translationZ = 0f
        touchIdle() // the agent may only be thinking; the session ends if nothing follows
        paint()
    }

    /** Back press: true when consumed. */
    fun onBack(): Boolean {
        if (!isOpen()) return false
        if (userHasControl() && web.canGoBack()) web.goBack() else close()
        return true
    }

    /** Coalesced repaint of everything state-dependent. Any thread. */
    private fun paint() {
        if (!paintQueued.compareAndSet(false, true)) return
        main.post {
            paintQueued.set(false)
            if (!ready) return@post
            val st = if (tabLive) tab.session.status() else null
            val handoff = st?.handoff
            if (handoff != null && showSteps) setStepsVisible(false) // the person has to see the page
            val agentDriving = handoff == null
            shield.visibility = if (agentDriving) View.VISIBLE else View.GONE
            urlField.isEnabled = !agentDriving
            val url = st?.url ?: ""
            if (url.isNotBlank() && !urlField.hasFocus()) urlField.setText(url)
            title.text = (if (tabLive) web.title else null)?.takeIf { it.isNotBlank() } ?: "Browser"
            val working = running.get() > 0 || st?.state == SessionState.ACTING || st?.state == SessionState.SETTLING
            who.text = when {
                linkProblem != null -> "● Not connected"
                !alive -> "● Offline"
                !tabLive -> "● No active session"
                handoff != null -> "● You're in control"
                working -> "● Agent working"
                else -> "● Agent in control"
            }
            who.setTextColor(when { !alive -> muted; handoff != null -> amber; else -> green })
            sub.text = when {
                linkProblem != null -> linkProblem
                handoff != null -> handoff.reason
                !tabLive -> "The agent opens one when needed"
                !alive -> "Open Terminus to reconnect"
                else -> "Controlled by $controller"
            }
            takeBtn.text = if (handoff != null) "Give back" else "Take over"
            val n = synchronized(steps) { steps.size }
            tabSteps.text = if (n > 0) "Steps \u00B7 $n" else "Steps"
            if (showSteps && stepsKey(st) != renderedKey) renderSteps()
            // Pill only while the browser is closed and something is happening.
            val recent = working || handoff != null || System.currentTimeMillis() - lastActive < 15_000
            pill.text = if (handoff != null) "● Needs you · Open" else "● Agent browsing · Open"
            pill.visibility = if (!isOpen() && alive && recent) View.VISIBLE else View.GONE
            // Push only changes. `core` (no image data) decides whether anything changed; images ride
            // along once per screenshot, so a repaint never re-sends thumbnails already on the card.
            val core = stateJson(Mode.CORE)
            if (core != lastCore || hasUnsentImages()) {
                val sent = HashSet<Long>()
                val js = buildState(Mode.INCREMENTAL, sent)
                lastCore = core
                synchronized(sentAts) {
                    sentAts.addAll(sent)
                    if (pendingThumbAt != Long.MIN_VALUE) { sentThumbAt = pendingThumbAt; pendingThumbAt = Long.MIN_VALUE }
                }
                onState?.invoke(js)
            }
        }
    }

    private fun statusWord(st: SessionStatus?): String {
        val working = running.get() > 0 || st?.state == SessionState.ACTING || st?.state == SessionState.SETTLING
        return when {
            !tabLive -> if (synchronized(steps) { steps.isNotEmpty() }) "Done" else "Idle"
            !alive -> "Offline"
            st?.handoff != null -> "Needs you"
            working -> "Working"
            else -> "Live"
        }
    }

    private fun hostOf(u: String?): String = try { android.net.Uri.parse(u ?: "").host ?: "" } catch (_: Exception) { "" }

    /** Small JPEG of a shot for the chat card, encoded once and cached. */
    private fun shotB64(sh: Shot): String = sh.b64 ?: run {
        val out = java.io.ByteArrayOutputStream()
        sh.thumb.compress(Bitmap.CompressFormat.JPEG, 60, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP).also { sh.b64 = it }
    }

    private fun hasUnsentImages(): Boolean {
        val rows = synchronized(shots) { shots.takeLast(4).map { it.atMs } }
        val last = rows.lastOrNull() ?: 0L
        return synchronized(sentAts) { rows.any { it !in sentAts } || (if (rows.isEmpty()) sentThumbAt != 0L && sentThumbAt != Long.MIN_VALUE else sentThumbAt != last) }
    }

    /** Full snapshot (every image), for a freshly loaded chat page that has nothing cached. */
    fun stateJson(full: Boolean = false): String = stateJson(if (full) Mode.FULL else Mode.CORE)

    private fun stateJson(mode: Mode): String = buildState(mode, null)

    /** Snapshot for the chat card: status, page url, last steps, each with the screenshots taken
     *  during it (newest four overall). CORE carries no image data; INCREMENTAL carries only images
     *  the card has not been sent yet (recorded in [sent]); FULL carries all of them. A missing
     *  `thumb` key means "unchanged", an empty one means "remove". */
    private fun buildState(mode: Mode, sent: MutableSet<Long>?): String {
        if (!ready) return "null"
        val st = if (tabLive) tab.session.status() else null
        st?.url?.let { if (it.isNotBlank()) lastUrl = it }
        val arr = JSONArray()
        var total = 0
        var note = ""
        val shotRows = synchronized(shots) { shots.takeLast(4).map { Triple(it.atMs, it.label, it) } }
        val already = synchronized(sentAts) { HashSet(sentAts) }
        fun wants(at: Long) = mode == Mode.FULL || (mode == Mode.INCREMENTAL && at !in already)
        synchronized(steps) {
            total = steps.size
            val tail = steps.takeLast(8)
            for (x in tail) {
                val own = JSONArray()
                for ((at, label, sh) in shotRows) {
                    val owner = steps.lastOrNull { it.startMs <= at } ?: steps.last()
                    if (owner === x) {
                        val o = JSONObject().put("t", label).put("at", at)
                        if (wants(at)) { o.put("img", shotB64(sh)); sent?.add(at) }
                        own.put(o)
                    }
                }
                arr.put(JSONObject().put("id", x.id).put("s", x.state.toString()).put("t", x.label)
                    .put("ms", if (x.state == 'r') 0 else x.ms).put("d", x.detail).put("at", x.startMs).put("sh", own))
            }
            note = notes.lastOrNull()?.text ?: ""
        }
        val out = JSONObject().put("status", statusWord(st))
            .put("live", tabLive && alive)
            .put("url", st?.url ?: lastUrl)
            .put("n", total)
            .put("note", note)
            .put("steps", arr)
        val last = shotRows.lastOrNull()
        if (mode == Mode.FULL) out.put("thumb", last?.let { shotB64(it.third) } ?: "")
        else if (mode == Mode.INCREMENTAL) {
            val sentThumb = synchronized(sentAts) { sentThumbAt }
            val lastAt = last?.first ?: 0L
            if (lastAt != sentThumb) {
                out.put("thumb", last?.let { shotB64(it.third) } ?: "")
                pendingThumbAt = lastAt
            }
        }
        return out.toString()
    }
    @Volatile private var pendingThumbAt = Long.MIN_VALUE

    // ---- Controls ---------------------------------------------------------

    private fun toggleTakeOver() {
        if (!ready) return
        if (!tabLive) {
            Toast.makeText(act, "No browser session yet. It starts when the agent opens a page.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (tab.session.activeHandoff != null) tab.session.resumeFromHuman()
            else tab.session.requestHandoff("You took over", kind = HandoffKind.OTHER)
        } catch (e: Exception) {
            Toast.makeText(act, "Couldn't switch control: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
        paint()
    }

    private fun hardStop() {
        if (!ready) return
        for ((id, f) in inflight) {
            f.job.cancel()
            if (f.replied.compareAndSet(false, true)) sendReply(id, errorJson("STOPPED_BY_USER", "the user stopped the browser"))
        }
        releaseTab()
    }

    /** Saves what the page shows to Pictures/Terminus. This is the person's
     *  own capture, so it is not subject to the handoff refusal that hides
     *  the page from the agent. The banner is hidden for the instant of the
     *  copy so it is not in the picture. */
    private fun screenshot() {
        if (!ready || !tabLive || !isOpen() || web.width <= 0 || web.height <= 0) return
        if (showSteps) { setStepsVisible(false); main.postDelayed({ screenshot() }, 200); return }
        val banners = (0 until page.childCount).map { page.getChildAt(it) }.filterIsInstance<HandoffBanner>()
        banners.forEach { it.visibility = View.INVISIBLE }
        val loc = IntArray(2)
        web.getLocationInWindow(loc)
        val bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        val rect = Rect(loc[0], loc[1], loc[0] + web.width, loc[1] + web.height)
        try {
            PixelCopy.request(act.window, rect, bmp, { result ->
                banners.forEach { it.visibility = View.VISIBLE }
                if (result == PixelCopy.SUCCESS) { addShotBitmap(bmp, "Your screenshot"); saveShot(bmp) }
                else Toast.makeText(act, "Screenshot failed", Toast.LENGTH_SHORT).show()
            }, main)
        } catch (_: Exception) {
            banners.forEach { it.visibility = View.VISIBLE }
            Toast.makeText(act, "Screenshot failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveShot(bmp: Bitmap) {
        Thread {
            val ok = try {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "terminus-browser-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Terminus")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = act.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (uri == null) false else {
                    act.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0)
                    act.contentResolver.update(uri, v, null, null)
                    true
                }
            } catch (_: Exception) { false }
            main.post { Toast.makeText(act, if (ok) "Saved to Pictures/Terminus" else "Screenshot failed", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    /** Wipes every cookie the app holds (sites you logged in to by hand), then
     *  lets the app put its own server login back. Page storage is untouched. */
    private fun confirmClearLogins() {
        AlertDialog.Builder(act)
            .setTitle("Clear logins?")
            .setMessage("Signs you out of every site the browser was used on. Terminus itself stays signed in.")
            .setPositiveButton("Clear") { _, _ ->
                val cm = android.webkit.CookieManager.getInstance()
                cm.removeAllCookies {
                    try { cm.flush() } catch (_: Exception) {}
                    main.post {
                        onCookiesCleared?.invoke()
                        Toast.makeText(act, "Logins cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun userNavigate(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || !userHasControl()) return
        val u = if (t.contains("://")) t
            else if (t.contains('.') && !t.contains(' ')) "https://$t"
            else "https://www.google.com/search?q=" + android.net.Uri.encode(t)
        web.loadUrl(u) // the library's client still enforces the navigation policy
    }

    /** Asked before clicks that look irreversible (pay, delete, send ...).
     *  No answer in 90 seconds counts as no (the relay gives up on a command
     *  a little later than that, so the agent hears the answer, not a timeout). Delete this and the
     *  confirmSensitive line in buildTab to let the agent click freely. */
    private suspend fun askUser(reason: String): Boolean {
        main.post { open() }
        val chat = originChat
        if (chat.isNotEmpty()) onNeedsYou(chat, "Browser needs approval: " + reason.take(100), true)
        if (!fg) ChatNotify.notifyBrowser(act, "Browser needs approval", reason, chat)
        val answer = withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                main.post {
                    val d = AlertDialog.Builder(act)
                        .setTitle("Allow this?")
                        .setMessage(reason)
                        .setCancelable(false)
                        .setPositiveButton("Allow") { _, _ -> if (cont.isActive) cont.resume(true) }
                        .setNegativeButton("Deny") { _, _ -> if (cont.isActive) cont.resume(false) }
                        .create()
                    cont.invokeOnCancellation { main.post { try { d.dismiss() } catch (_: Exception) {} } }
                    try { d.show() } catch (_: Exception) { if (cont.isActive) cont.resume(false) }
                }
            }
        }
        ChatNotify.clearBrowser(act)
        if (chat.isNotEmpty()) onNeedsYou(chat, "", false)
        return answer == true
    }

    // ---- Steps panel ------------------------------------------------------

    private fun roundedR(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun chip(t: String, onTap: () -> Unit) = TextView(act).apply {
        text = t; textSize = 13f
        setPadding(dp(16), dp(7), dp(16), dp(7))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }

    private fun styleTabs() {
        fun mark(v: TextView, on: Boolean) { v.background = roundedR(if (on) green else tray, 16); v.setTextColor(if (on) Color.WHITE else ink) }
        mark(tabPage, !showSteps); mark(tabSteps, showSteps)
    }

    fun setStepsVisible(v: Boolean) {
        if (!ready) return
        showSteps = v
        stepsScroll.visibility = if (v) View.VISIBLE else View.GONE
        styleTabs()
        if (v) { listCard = null; renderSteps() }
    }

    private fun stepsKey(st: SessionStatus?) = "$stepsVersion|${synchronized(shots) { shots.size }}|${statusWord(st)}|${hostOf(st?.url)}"

    private fun statusColor(status: String) = when (status) {
        "Live", "Working" -> green
        "Needs you" -> amber
        else -> muted
    }

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedR(0xFFFBF8F1.toInt(), 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun pill(text: String, color: Int) = TextView(act).apply {
        this.text = text; textSize = 11f; setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = roundedR((color and 0x00FFFFFF) or 0x22000000, 10)
    }

    private fun divider() = View(act).apply {
        setBackgroundColor(0x1A000000)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun stepIcon(state: Char): View {
        if (state == 'r') {
            return ProgressBar(act, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(green)
            }
        }
        val bg = when (state) { 'o' -> green; 'x' -> red; else -> amber }
        val glyph = when (state) { 'o' -> "✓"; 'x' -> "✕"; else -> "!" }
        return TextView(act).apply {
            text = glyph; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        }
    }

    private fun stepRow(s: Step): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(stepIcon(s.state), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(s.label, 15f).apply { if (s.state == 'r' || s.state == 'h') setTypeface(typeface, Typeface.BOLD) })
        if (s.detail.isNotEmpty()) col.addView(label(s.detail, 12f).apply { setTextColor(red) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val t = when {
            s.state == 'r' || s.state == 'h' -> ""
            s.ms >= 1000 -> "${s.ms / 1000}s"
            s.ms > 0 -> "${s.ms}ms"
            else -> ""
        }
        if (t.isNotEmpty()) row.addView(label(t, 13f).apply { setTextColor(muted) })
        return row
    }

    private fun renderSteps() {
        val st = if (tabLive) tab.session.status() else null
        val status = statusWord(st)
        renderedKey = stepsKey(st)
        // Follow the newest step unless the person scrolled up to read.
        val prev = listCard
        val follow = prev == null || stepsScroll.scrollY + stepsScroll.height >= prev.bottom - dp(48)
        val list: List<Step>; val noteList: List<Note>
        synchronized(steps) { list = steps.toList(); noteList = notes.toList() }
        val shotList = synchronized(shots) { shots.toList() }
        stepsBody.removeAllViews()

        val head = card()
        val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(label("Session steps", 16f).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(pill(status, statusColor(status)))
        head.addView(line)
        val h = hostOf(st?.url)
        head.addView(label(if (h.isNotBlank()) h else "Controlled by $controller", 13f).apply { setTextColor(muted); setPadding(0, dp(4), 0, 0) })
        stepsBody.addView(head)

        val stepsCard = card()
        if (list.isEmpty()) {
            stepsCard.addView(label("Nothing yet. Steps appear here as the agent works.", 14f).apply { setTextColor(muted) })
        }
        list.forEachIndexed { i, s -> if (i > 0) stepsCard.addView(divider()); stepsCard.addView(stepRow(s)) }
        stepsBody.addView(stepsCard)
        listCard = stepsCard

        if (noteList.isNotEmpty()) {
            val nc = card()
            nc.addView(label("Browser notes", 15f).apply { setTypeface(typeface, Typeface.BOLD) })
            for (n in noteList.takeLast(4)) nc.addView(label(n.text, 14f).apply { setPadding(0, dp(6), 0, 0) })
            stepsBody.addView(nc)
        }

        if (shotList.isNotEmpty()) {
            val sc = card()
            sc.addView(label("Screenshots (${shotList.size})", 15f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8)) })
            val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (sh in shotList) {
                strip.addView(ImageView(act).apply {
                    setImageBitmap(sh.thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedR(tray, 10)
                    clipToOutline = true
                    setOnClickListener { showShot(sh) }
                }, LinearLayout.LayoutParams(dp(120), dp(84)).apply { marginEnd = dp(8) })
            }
            sc.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            stepsBody.addView(sc)
        }

        if (follow) stepsScroll.post { stepsScroll.scrollTo(0, (stepsCard.bottom - stepsScroll.height + dp(12)).coerceAtLeast(0)) }
    }

    private fun showShot(sh: Shot) {
        val raw = sh.bytes
        val bmp = (if (raw != null) BitmapFactory.decodeByteArray(raw, 0, raw.size) else null) ?: sh.thumb
        if (bmp.isRecycled) return
        val iv = ImageView(act).apply { adjustViewBounds = true; setImageBitmap(bmp); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        AlertDialog.Builder(act).setTitle(sh.label).setView(iv).setPositiveButton("Close", null).show()
    }

    private fun addShot(thumb: Bitmap, bytes: ByteArray, label: String) {
        synchronized(shots) {
            shots.add(Shot(thumb, bytes, label, System.currentTimeMillis()))
            while (shots.size > MAX_SHOTS) shots.removeAt(0).thumb.recycle()
        }
        stepsVersion++
        paint()
    }

    private fun thumbOf(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = (bounds.outWidth / 300).coerceAtLeast(1) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** The person's own capture (the 📷 button). */
    private fun addShotBitmap(bmp: Bitmap, label: String) {
        try {
            val w = 720
            val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(1), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (scaled !== bmp) scaled.recycle()
            val bytes = out.toByteArray()
            thumbOf(bytes)?.let { addShot(it, bytes, label) }
        } catch (_: Exception) {}
    }

    /** A screenshot the agent took comes back to the server as base64; keep a copy for the Steps screen. */
    private fun keepAgentShot(reply: String) {
        try {
            val o = JSONObject(reply)
            val data = o.optString("data", "")
            if (data.isEmpty() || !o.optString("mime").startsWith("image/")) return
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            if (bytes.size > 600_000) return
            thumbOf(bytes)?.let { addShot(it, bytes, "Agent screenshot") }
        } catch (_: Exception) {}
    }

    /** Dialog (confirm, prompt, leave-page) that opened while the person has control. */
    private fun showHumanDialog(kind: String, message: String, def: String?) {
        if (!ready || !tabLive) return
        val input = if (kind == "prompt") EditText(act).apply { setText(def ?: ""); setSingleLine(true) } else null
        val title = when (kind) { "confirm", "prompt" -> "This page asks"; else -> "Leave this page?" }
        val b = AlertDialog.Builder(act).setTitle(title).setMessage(message.take(500)).setCancelable(false)
        input?.let { b.setView(it) }
        b.setPositiveButton("OK") { _, _ -> answerHuman(true, input?.text?.toString()) }
        b.setNegativeButton("Cancel") { _, _ -> answerHuman(false, null) }
        try { b.show() } catch (_: Exception) { answerHuman(false, null) }
    }

    private fun answerHuman(accept: Boolean, text: String?) {
        scope.launch { try { tab.session.answerDialogAsHuman(accept, text) } catch (_: Exception) {} }
    }

    // ---- Relay ------------------------------------------------------------

    fun start() {
        fg = true
        main.removeCallbacks(pauseStop)
        if (alive) return
        alive = true
        paint()
        thread = Thread({ loop() }, "phone-browser").also { it.isDaemon = true; it.start() }
    }

    /** Activity.onPause. While a hand-off or command is running, stay connected for a few minutes so the
     *  waiting agent is not told the phone went offline when the person pops out to read a code. */
    fun onAppPause() {
        fg = false
        main.removeCallbacks(pauseStop)
        val busy = ready && tabLive && (running.get() > 0 || tab.session.activeHandoff != null)
        if (busy) main.postDelayed(pauseStop, BACKGROUND_GRACE_MS) else stop()
    }

    fun stop() {
        main.removeCallbacks(pauseStop)
        alive = false
        agentBusy = false
        try { call?.cancel() } catch (_: Exception) {}
        call = null
        paint()
    }

    fun destroy() {
        stop()
        scope.coroutineContext[Job]?.cancel()
        if (!ready) return
        main.removeCallbacks(idleTick)
        ready = false
        if (tabLive) {
            tabLive = false
            try { tab.close() } catch (_: Exception) {}
            try { web.destroy() } catch (_: Exception) {}
        }
        try { act.findViewById<ViewGroup>(android.R.id.content).apply { removeView(host); removeView(pill) } } catch (_: Exception) {}
    }

    private fun loop() {
        var wait = 2000L
        while (alive) {
            try {
                val base = prefs.serverUrl().trimEnd('/')
                val tok = prefs.token()
                if (base.isBlank()) { Thread.sleep(5000); continue }
                val link = LinkSecurity.check(base)
                if (!link.allowed) {
                    if (linkProblem != link.message) { linkProblem = link.message; paint() }
                    Thread.sleep(5000); continue
                }
                if (linkProblem != null) { linkProblem = null; paint() }
                val req = Request.Builder().url("$base/api/pb/stream?v=${BuildConfig.VERSION_CODE}")
                    .header("Accept", "text/event-stream")
                    .apply { if (tok.isNotBlank()) header("Authorization", "Bearer $tok") }
                    .get().build()
                val c = http.newCall(req); call = c
                c.execute().use { res ->
                    if (!res.isSuccessful) throw java.io.IOException("pb stream http ${res.code}")
                    wait = 2000L
                    val src = res.body!!.source()
                    try { src.timeout().timeout(75, TimeUnit.SECONDS) } catch (_: Exception) {}
                    var event = ""; val data = StringBuilder()
                    while (alive) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (event == "agent" && data.isNotEmpty()) {
                                    val b = try { JSONObject(data.toString()).optBoolean("busy", false) } catch (_: Exception) { false }
                                    if (b != agentBusy) { agentBusy = b; main.post { touchIdle() } }
                                }
                                else if (event == "cmd" && data.isNotEmpty()) onCmd(data.toString())
                                else if (event.startsWith("approval") && data.isNotEmpty()) onChannelEvent?.invoke(event, data.toString())
                                event = ""; data.setLength(0)
                            }
                            line.startsWith(":") -> {}
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).removePrefix(" ")) }
                        }
                    }
                }
            } catch (_: Exception) {
                if (!alive) return
            }
            if (!alive) return
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun onCmd(raw: String) {
        val id: String; val command: String; val owner: String
        try {
            val o = JSONObject(raw); id = o.getString("id"); command = o.getString("command")
            owner = o.optString("tab", "").trim()
            val by = o.optString("by", "").trim()
            if (by.isNotEmpty()) controller = by.replaceFirstChar { it.uppercase() }
            val chat = o.optString("chat", "").trim()
            if (chat.isNotEmpty() && chat != "null") originChat = chat
        } catch (_: Exception) { return }
        main.post { runCmd(id, command, owner) }
    }

    private fun isCloseCommand(command: String) =
        try { JSONObject(command).optString("cmd") == "close" } catch (_: Exception) { false }

    /** Main thread: build the browser if there is none, then run the command. */
    private fun runCmd(id: String, command: String, owner: String = "") {
        if (!ready) { sendReply(id, errorJson("BROWSER_UNAVAILABLE", "browser not ready")); return }
        // A session the agent closed (or that died) is replaced, never reused.
        if (tabLive && tab.session.sessionState == SessionState.CLOSED) releaseTab()
        // Another chat's browser is not ours: a stray close/status must not touch it, and a real command either
        // waits (owner busy or just active) or starts from a blank browser so no old steps or page leak across.
        val live = ownerTab
        if (owner.isNotEmpty() && tabLive && live != null && live != owner) {
            val cmdName = try { JSONObject(command).optString("cmd") } catch (_: Exception) { "" }
            if (cmdName == "close") { sendReply(id, JSONObject().put("ok", true).toString()); return }
            if (cmdName == "status") { sendReply(id, errorJson("NO_SESSION", "no browser session")); return }
            val busy = running.get() > 0 || tab.session.activeHandoff != null ||
                System.currentTimeMillis() - lastActive < OWNER_GRACE_MS
            if (busy) {
                sendReply(id, errorJson("BROWSER_BUSY", "another chat is using the browser; wait a little and retry"))
                return
            }
            releaseTab()
        }
        // A stray close/status must not build a WebView (and post a chat card) just to answer "nothing here".
        if (!tabLive) {
            val cmd = try { JSONObject(command).optString("cmd") } catch (_: Exception) { "" }
            if (cmd == "close") { sendReply(id, JSONObject().put("ok", true).toString()); return }
            if (cmd == "status") { sendReply(id, errorJson("NO_SESSION", "no browser session")); return }
        }
        ensureTab()
        if (owner.isNotEmpty() && tabLive) ownerTab = owner
        val replied = AtomicBoolean(false)
        lastActive = System.currentTimeMillis()
        running.incrementAndGet()
        lastCmdAt = System.currentTimeMillis()
        touchIdle()
        paint()
        val closing = isCloseCommand(command)
        val job = scope.launch {
            val reply = try {
                tab.handleJson(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorJson("INTERNAL", (e.message ?: e.javaClass.simpleName).take(200))
            }
            if (replied.compareAndSet(false, true)) sendReply(id, reply)
            if (command.contains("\"screenshot\"")) keepAgentShot(reply)
            if (closing) main.post { releaseTab() }
        }
        inflight[id] = Inflight(job, replied)
        job.invokeOnCompletion {
            inflight.remove(id)
            lastCmdAt = System.currentTimeMillis()
            if (running.get() > 0) running.decrementAndGet()
            lastActive = System.currentTimeMillis()
            main.post { touchIdle(); paint() }
        }
    }

    private fun sendReply(id: String, reply: String) {
        Thread {
            try {
                val base = prefs.serverUrl().trimEnd('/')
                val tok = prefs.token()
                val body = JSONObject().put("id", id).put("reply", reply).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$base/api/pb/reply").post(body)
                    .apply { if (tok.isNotBlank()) header("Authorization", "Bearer $tok") }.build()
                http.newCall(req).execute().use { }
            } catch (_: Exception) { /* server times the command out; the agent gets phone_timeout */ }
        }.start()
    }
}
