package com.terminus.rec

import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File

/** Live reply streaming and settling a turn. Extension functions of [MainActivity], moved out of it unchanged. */

/** Cancel the SSE stream read (unblocks the socket + drops the guard). */
internal fun MainActivity.stopStream() {
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
internal fun MainActivity.ensureStream(id: String) {
    if (streamChat == id) return
    startStream(id)
}

internal fun MainActivity.startStream(id: String) {
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
                                // Last job gone: release the stream we held, unless a reply is still running
                                // here (the stream carries its done; dropping it hid replies until the next send).
                                if (fstatus != "started" && !backgroundJobs.any(id) && streamChat == id && !chatStore.hasLive(id)) {
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
internal fun MainActivity.finishCompactRow(id: String, status: String, reason: String, detail: String) {
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

internal fun MainActivity.bgSeqOf(id: String): Long = bgSeqPrefs.getLong(id, -1L)

internal fun MainActivity.setBgSeq(id: String, seq: Long) { bgSeqPrefs.edit().putLong(id, seq).apply() }

/** Background rows the phone missed (app closed or offline when a job started or ended): fetched from the
 *  server's per-chat log after the last applied seq. A chat seen for the first time starts at the server's
 *  current seq instead of replaying old rows. Throttled per chat. */
internal fun MainActivity.catchUpBackground(id: String, force: Boolean = false) {
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

internal fun MainActivity.onBackgroundRow(id: String, status: String, label: String, detail: String, elapsed: String, full: String = "") {
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
internal fun MainActivity.stepsJson(steps: List<OpencodeApi.ProgressStep>): String = try {
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
internal fun MainActivity.reconcileTurn(id: String, turnId: String, tap: Boolean = false, done: ((Boolean) -> Unit)? = null) {
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
internal fun MainActivity.syncThread(id: String, manual: Boolean = false) {
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

internal fun MainActivity.finishStreamTurn(id: String, turnId: String, text: String, error: String, messageID: String, code: String = "") {
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
internal fun MainActivity.idleStreamHandoff(id: String) {
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
internal fun MainActivity.streamWanted(id: String): Boolean {
    if (backgroundJobs.any(id)) return true
    val g = goalCache
    return g != null && g.loop && g.status == "active" && activeSessionId == id
}

internal fun MainActivity.hasPlaceholder(id: String): Boolean {
    return try { chatStore.hasLive(id) } catch (_: Exception) { false }
}

/** Legacy single-turn settle (old server without turnIds). Idempotent:
 *  settles nothing twice — a replayed `done` with no standing
 *  placeholder drops when its text already renders, and orphans append
 *  only when genuinely new (better delivered once than lost). */
internal fun MainActivity.finishLegacyTurn(id: String, text: String, error: String) {
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
internal fun MainActivity.settleProgress(id: String) {
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
internal fun MainActivity.syncNavForThread(inThread: Boolean = (activeSessionId != null || draftOpen)) {
    if (!navReady) return
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
internal fun MainActivity.clearStepsIfIdle(id: String) {
    if (pendingTurns[id]?.isEmpty() ?: true) progressLive[id] = emptyList()
}

internal fun MainActivity.updateStopBtn() {
    if (!stopBtnReady) return
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
internal fun MainActivity.updateComposerSlot() {
    if (!chatMicReady || !composerInputReady) return
    val typed = composerInput.text?.toString()?.isNotBlank() == true
    val ready = typed || pendingAttaches.isNotEmpty() || quotes.isNotEmpty()
    chatMic.visibility = if (ready && !chatRecActive) View.GONE else View.VISIBLE
}

/** Rebuild the queue backlog after process death: marker rows survive
 *  in the transcript while the memory queue does not. Missing files
 *  drop that entry's attachments (text still sends). Idle chats drain
 *  via the normal reconciler; chats with live turns stay queued. */
internal fun MainActivity.rehydrateWaitQueue() {
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
internal fun MainActivity.reattachLiveStreams() {
    if (!chatStoreReady) return
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
