package com.terminus.rec

/** Minimal markdown → HTML for the chat thread WebView. No new deps. */
object ChatMarkdown {
    /** Script for the live browser card (assets/pb-card.js). Set once from MainActivity.onCreate; until
     *  then, or in plain JVM tests, it is empty and the card simply stays static. */
    @Volatile var pbScript: String = ""
    /** Styles for the browser card (assets/pb-card.css), same hand-over as [pbScript]. */
    @Volatile var pbCss: String = ""
    /** Shared card look for tool rows, collapsibles, alarm card and code blocks (assets/thread-cards.css). */
    @Volatile var threadCardsCss: String = ""
    /** Thread-wide behaviour: the code-block copy button (assets/thread-ui.js). */
    @Volatile var threadUiJs: String = ""

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    /** Send time per row, device-local 24h clock. */
    private fun rowTime(ts: Long): String =
        try { timeFmt.format(java.util.Date(ts)) } catch (_: Exception) { "" }

    /** Short display form for a changed file path: last two segments,
     *  middle-cut when long. Pure, unit-tested. */
    fun shortPath(p: String): String {
        val t = p.trim().replace("\\", "/")
        if (t.isEmpty()) return ""
        val segs = t.split("/").filter { it.isNotEmpty() }
        if (segs.isEmpty()) return t.take(28)
        val base = segs.takeLast(2).joinToString("/")
        if (base.length <= 28) return base
        return base.take(11) + "..." + base.takeLast(14)
    }

    /** Day chip label, device-local: Today / Yesterday / 24 Oct. */
    private fun dayLabel(ts: Long): String {
        return try {
            val cal = java.util.Calendar.getInstance()
            val today = java.util.Calendar.getInstance()
            cal.timeInMillis = ts
            if (cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) return "Today"
            today.add(java.util.Calendar.DAY_OF_YEAR, -1)
            if (cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) return "Yesterday"
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } catch (_: Exception) { "" }
    }

    private fun dayKey(ts: Long): String {
        return try {
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } catch (_: Exception) { "" }
    }



    /** One message row, exactly as threadHtml emits per row: the
     *  incremental-append path reuses this so fragments match page markup.
     *  Typing placeholders render their shimmer here too (threadHtml still
     *  owns the one-live-indicator skip rule before delegating).
     *  Standalone pointer rows are skipped by threadHtml (appendRowDom
     *  declines them → full render), so the .ptr fallback below only
     *  serves direct calls. */
    /** [startedAt]: for a finished job drawn in its start's place, when the job started (thread-ui.js attaches
     *  the line to the reply that was running then). */
    fun rowHtml(m: ChatMessage, idx: Int, token: String = "", chatId: String = "", startedAt: Long = 0L): String {
        // Pointer rows render as bare muted labels, not boxed rows: they
        // annotate the neighboring send instead of standing as messages.
        // Same output here and in threadHtml (incremental append reuses this).
        if (isPointer(m)) {
            return "<div class='ptr'>" + esc(m.text) + "</div>"
        }
        // Compacted-context receipt: a centered divider plus the full
        // summary as markdown — no bubble, no bare tool header. Matches
        // the reference shape (divider + Objective/Requirements/Decisions).
        if (m.role == "compact") {
            return "<div class='crow' data-idx=\"" + idx + "\" data-role=\"compact\">" +
                "<div class='cdiv'><span>Compaction</span></div>" +
                bodyHtml(m.text, token) + "</div>"
        }
        // Loop nudge: hairline divider reusing the date-divider component,
        // kind-flagged by role so it can be hidden or purged later.
        if (m.role == "goal") {
            return "<div class='crow' data-idx=\"" + idx + "\" data-role=\"goal\">" +
                "<div class='cdiv'><span>" + esc(m.text.ifBlank { "Continuing toward goal" }) + "</span></div></div>"
        }
        // Timeline event: a change of state, not a message. One ruled line,
        // centered, wrapping when long (interrupted, compaction, clear…).
        // Reuses the date/goal divider so every event reads the same.
        // Background lifecycle rows get their own card instead: a pill
        // naming the kind plus the command in mono, with the full output
        // behind a "show output" expander when the run carried one.
        if (m.role == "event") {
            if (isNeedsYouText(m.text)) return needsYouCardHtml(m.text, idx)
            // Background job: one quiet line (spinner while running, ✓/✕ and the time taken once done), the
            // output one tap away. The finish line replaces the start line (threadHtml skips finished starts;
            // thread-ui.js drops a live one when its finish arrives).
            val bg = BackgroundUi.line(m.text)
            if (bg != null) {
                // The line only informs: after BG_SHOWN_MS with no finish it stops claiming "running" (the job
                // itself is untouched, and a late finish still lands as ✓).
                val stale = bg.state == "run" && m.ts > 0 && System.currentTimeMillis() - m.ts > BackgroundUi.SHOWN_RUNNING_MS
                // Stopped from the phone: the server marks the finish "stopped" (no output to show).
                val stopped = bg.state == "failed" && bg.output == "stopped"
                val icon = when { stale -> "·"; stopped -> "■"; bg.state == "done" -> "✓"; bg.state == "failed" -> "✕"; else -> "" }
                val meta = when { stale -> "no result seen"; bg.state == "run" -> "running"; bg.state == "failed" -> listOf(if (stopped) "stopped" else "failed", bg.elapsed).filter { it.isNotBlank() }.joinToString(" · "); else -> bg.elapsed }
                val box = "bgout-" + idx
                var html = "<div class='crow bgline st-" + (if (stale) "stale" else bg.state) + "' id='" + box + "' data-idx=\"" + idx + "\" data-role=\"event\" data-ts=\"" + m.ts + "\" data-at=\"" + (if (startedAt > 0) startedAt else m.ts) + "\" data-bgkey=\"" + escAttr(bg.cmd.hashCode().toString()) + "\">" +
                    "<div class='bghead'>" + (if (icon.isEmpty()) "<span class='bgspin'></span>" else "<span class='bgic'>" + icon + "</span>") +
                    "<span class='bgtxt'>" + esc(bg.cmd) + "</span>" +
                    (if (meta.isNotBlank()) "<span class='bgmeta'>" + esc(meta) + "</span>" else "")
                // Running: a ✕ to stop the job (tap, then confirm). thread-ui.js asks the app (TerminusJobs).
                if (bg.state == "run" && !stale) html += "<span class='bgstop' data-cmd=\"" + escAttr(bg.cmd) + "\" onclick='__bgStop(this)'>✕</span>"
                if (bg.output.isNotBlank() && !stopped) html += "<span class='bgmore' onclick=\"document.getElementById('" + box + "').classList.toggle('open')\">output</span>"
                html += "</div>"
                if (bg.output.isNotBlank() && !stopped) html += "<div class='bgfull'>" + esc(bg.output) + "</div>"
                return html + "</div>"
            }
            return "<div class='crow' data-idx=\"" + idx + "\" data-role=\"event\">" +
                "<div class='cdiv evt'><span>" + esc(m.text) + "</span></div></div>"
        }
        val sb = StringBuilder()
        val cls = when (m.role) { "user" -> "user"; "tool" -> "tool"; "error" -> "error"; "system" -> "sysmsg"; else -> "assistant" }
        val live = m.role == "assistant" && m.turnId.isNotBlank() && !m.settled
        val typing = live && m.text == "…"
        val hasReplyActions = m.role == "assistant" && m.settled && chatId.isNotBlank()
        sb.append("<div class='row ").append(cls).append(if (hasReplyActions) " has-reply-actions" else "").append(if (typing) " typing" else "").append(if (live && !typing) " live" else "").append("'")
        if (live && !typing) sb.append(" id='live-").append(escAttr(m.turnId)).append("'")
        else if (live) sb.append(" id='live'")
        // Stable history anchor: global message index, so growing the
        // rendered tail keeps first-visible-row restore exact.
        else sb.append(" id='m").append(idx).append("'")
        if (m.role == "user" && m.turnId.isNotBlank()) sb.append(" data-turn=\"").append(escAttr(m.turnId)).append("\"")
        sb.append(" data-idx=\"").append(idx).append("\" data-role=\"").append(m.role).append("\"")
        // Replies carry their send time: background job lines attach to the reply running when they started.
        if (m.role == "assistant" && m.ts > 0) sb.append(" data-ts=\"").append(m.ts).append("\"")
        sb.append(">")
        if (m.role == "system") {
            // System message: one consistent treatment everywhere — a small
            // right-aligned muted monospace label, no bubble, no timestamp.
            // Markdown runs so a system note can still carry a tap action.
            sb.append("<div class='sysmsg-lab'>").append(bodyHtml(m.text, token)).append("</div>")
        } else if (typing) {
            sb.append("<div class='prow shimmer'>…</div>")
        } else {
            if (m.role == "tool") sb.append("<div class='toolhead'>▸ tool</div>")
            for (a in m.allAttachments) sb.append(attachmentHtml(a, m.durationSec, token))
            // Your own messages are capped at 30% of the screen (thread-ui.js adds "Show all" when clipped):
            // you rarely reread them, and a long paste shouldn't push the reply off screen.
            if (m.role == "user") sb.append("<div class='ubody'>").append(bodyHtml(m.text, token)).append("</div>")
            else sb.append(bodyHtml(m.text, token))
            if (m.role == "user" && m.voiceSendCommand) {
                sb.append("<div class='cmeta voice-send-meta' title='Sent by voice command'><svg class='voice-send-icon' viewBox='0 0 24 24' aria-hidden='true'><path d='M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v4' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/></svg>sent by voice</div>")
            }
            // Per-reply steps: this turn's reasoning collapsed under its
            // own answer. Legacy sessions (no stepsJson) fall back to the
            // chat-level expander below.
            val mine = stepsOf(m.stepsJson)
            if (m.role == "assistant" && mine.isNotEmpty()) {
                sb.append(settledStepsHtml(mine, chatId, m.turnId, idx))
            }
            // Changed-files footer: what this reply touched. Snapshot at
            // settle time (later turns move the live session, not this).
            if (m.role == "assistant" && m.changedFiles.isNotEmpty()) {
                val shown = m.changedFiles.take(8)
                sb.append("<div class='cmeta'>changed: ")
                sb.append(shown.joinToString(", ") { esc(shortPath(it)) })
                if (m.changedFiles.size > shown.size) {
                    sb.append(" +").append(m.changedFiles.size - shown.size).append(" more")
                }
                sb.append("</div>")
            }
            val t = rowTime(m.ts)
            // Failed bubble: only "send failed · retry" (+ time) lives inside the
            // person's own bubble. The provider's message (quota, auth, ...) is a
            // system notice in its own row after the bubble — see errorNoticeHtml —
            // so it never reads as part of the prompt. See TurnActivity.PROVIDER_CODES
            // for which failures qualify.
            if (m.role == "user" && m.sendFailed && Outbox.isWaiting(m.failError)) {
                // Never reached the Mac: it waits and sends itself when the connection is back.
                sb.append("<div class='sendfail'><span class='failtxt'>waiting · ").append(esc(Outbox.reasonOf(m.failError)))
                sb.append("</span>")
                if (t.isNotBlank()) sb.append("<span>").append(t).append("</span>")
                sb.append("</div>")
            } else if (m.role == "user" && m.sendFailed) {
                sb.append("<div class='sendfail'><span class='failtxt'>send failed")
                if (chatId.isNotBlank() && m.turnId.isNotBlank()) {
                    val retry = "terminus://retry?chat=${urlEncode(chatId)}&turn=${urlEncode(m.turnId)}"
                    sb.append(" · <a href=\"").append(escAttr(retry)).append("\">retry</a>")
                }
                sb.append("</span>")
                if (t.isNotBlank()) sb.append("<span>").append(t).append("</span>")
                sb.append("</div>")
            } else if (!hasReplyActions && t.isNotBlank()) sb.append("<div class='etime'>").append(t).append("</div>")
        }
        sb.append("</div>")
        if (m.role == "user" && m.sendFailed && !Outbox.isWaiting(m.failError)) sb.append(errorNoticeHtml(m.failError))
        if (hasReplyActions) {
            val base = "terminus://reply?chat=${urlEncode(chatId)}&i=$idx"
            sb.append("<div class='reply-actions'>")
            sb.append("<a href=\"").append(escAttr(base + "&do=copy")).append("\" aria-label='Copy reply' title='Copy reply'><svg viewBox='0 0 24 24' aria-hidden='true'><rect x='8.5' y='8.5' width='11' height='11' rx='2.5' fill='none' stroke='currentColor' stroke-width='1.8'/><path d='M15.5 8.5v-3a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round'/></svg></a>")
            sb.append("<a href=\"").append(escAttr(base + "&do=speak")).append("\" data-speech-key=\"").append(escAttr("$chatId:$idx")).append("\" aria-label='Read reply aloud' title='Read reply aloud'><svg viewBox='0 0 24 24' aria-hidden='true'><path d='M4 10v4h4l5 4V6l-5 4H4z' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linejoin='round'/><path d='M16.5 9.5a4 4 0 0 1 0 5M19 7a7.5 7.5 0 0 1 0 10' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round'/></svg></a>")
            sb.append("<a href=\"").append(escAttr(base + "&do=more")).append("\" aria-label='More actions' title='More actions'><svg viewBox='0 0 24 24' aria-hidden='true'><circle cx='12' cy='5' r='1.5' fill='currentColor'/><circle cx='12' cy='12' r='1.5' fill='currentColor'/><circle cx='12' cy='19' r='1.5' fill='currentColor'/></svg></a>")
            val t = rowTime(m.ts)
            if (t.isNotBlank()) sb.append("<div class='etime'>").append(t).append("</div>")
            sb.append("</div>")
        }
        return sb.toString()
    }

    /** "Send now" under a message that is waiting behind the running reply. The app adds and removes
     *  these links by script as turns start and finish (MainActivity.refreshSendNowLinks). */
    /** Set by the app: the state of the alarm at this time, so a card can say Armed / Tap to arm / Cancelled. */
    @Volatile var alarmStateLookup: ((Long) -> WakeAlarm.State?)? = null

    /** Set by the app: a link's stored page title, so links seen before render as chips on first paint. */
    @Volatile var linkMetaLookup: ((String) -> LinkFetcher.Meta?)? = null

    /** The chip for a link whose title is already known (same look as the page script builds). `shownUrl` is the
     *  HTML-escaped URL as written; the title is plain text and is escaped here. */
    internal fun linkChipHtml(shownUrl: String, href: String, title: String): String {
        val t = title.replace(Regex("\\s+"), " ").trim().let { if (it.length > 70) it.take(69) + "\u2026" else it }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val host = try { java.net.URI(shownUrl.replace("&amp;", "&")).host.orEmpty() } catch (_: Exception) { "" }
        val globe = "<span class=\"lkf\">\uD83C\uDF10</span>"
        val icon = if (host.isBlank()) globe else
            "<img class=\"lkf\" alt=\"\" referrerpolicy=\"no-referrer\" src=\"https://www.google.com/s2/favicons?sz=64&amp;domain=$host\" " +
                "onerror=\"this.outerHTML='<span class=&quot;lkf&quot;>&#127760;</span>'\">"
        return "<a class=\"lk lkd\" data-lk=\"1\" data-u=\"$shownUrl\" href=\"$href\">$icon<span>$t</span><span class=\"lka\">\u2197</span></a>"
    }

    /** A bare link the server could title becomes: globe glyph, the page title, a small arrow (see LINK_CHIP_JS). */
    const val LINK_CHIP_CSS = "a.lk.lkn{animation:lkin .22s ease}@keyframes lkin{from{opacity:.3}to{opacity:1}}a.lk.lkd{text-decoration:underline dotted;text-underline-offset:4px;text-decoration-thickness:1.5px}a.lk .lkf{font-size:.9em;margin-right:.25em;text-decoration:none;display:inline-block}a.lk img.lkf{width:1em;height:1em;max-width:1em;vertical-align:-.14em;border-radius:3px;margin-right:.25em;pointer-events:none}a.lk .lka{font-size:.75em;margin-left:.15em;opacity:.7}"
    const val LINK_CHIP_JS = "(function(){var M=window.__lm=window.__lm||{},A={},T=0;" +
        "function chip(a,m){if(a.getAttribute('data-lk'))return;a.setAttribute('data-lk','1');a.classList.add('lkd');a.classList.add('lkn');" +
        "var h='';try{h=new URL(m.url||a.getAttribute('data-u')).hostname}catch(e){}" +
        "while(a.firstChild)a.removeChild(a.firstChild);" +
        "function globe(){var s=document.createElement('span');s.className='lkf';s.textContent='\uD83C\uDF10';return s}" +
        "if(h){var g=document.createElement('img');g.className='lkf';g.alt='';g.referrerPolicy='no-referrer';" +
        "g.src='https://www.google.com/s2/favicons?sz=64&domain='+encodeURIComponent(h);" +
        "g.onerror=function(){if(g.parentNode)g.parentNode.replaceChild(globe(),g)};a.appendChild(g)}else a.appendChild(globe());" +
        "var t=document.createElement('span');var s=m.title||h;t.textContent=s.length>70?s.slice(0,69)+'\u2026':s;a.appendChild(t);" +
        "var r=document.createElement('span');r.className='lka';r.textContent='\u2197';a.appendChild(r)}" +
        "function ask(a){var u=a.getAttribute('data-u');if(M[u]===undefined&&!A[u]){A[u]=1;try{TerminusLinks.want(u)}catch(e){}}}" +
        "var IO=window.IntersectionObserver?new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){IO.unobserve(e.target);ask(e.target)}})},{rootMargin:'1500px'}):null;" +
        "function scan(){var as=document.querySelectorAll('a.lk[data-u]:not([data-lk])');" +
        "for(var i=0;i<as.length;i++){var a=as[i],u=a.getAttribute('data-u');" +
        "if(M[u])chip(a,M[u]);else if(M[u]===undefined&&!a.__w){a.__w=1;if(IO)IO.observe(a);else ask(a)}}}" +
        "window.__setMeta=function(u,m){M[u]=m||false;scan()};" +
        "try{new MutationObserver(function(){clearTimeout(T);T=setTimeout(scan,600)}).observe(document.body,{childList:true,subtree:true})}catch(e){}" +
        "setTimeout(scan,250)})();"
    const val SEND_NOW_CSS = ".sendnow{text-align:right;font-size:13px;line-height:18px;margin:-8px 4px 8px 0}.sendnow a{color:inherit;opacity:.65;text-decoration:none}.row.user.queued-message{position:relative}.row.user.queued-message .sendnow-undo{position:absolute;top:-8px;right:-2px;display:inline-flex;width:22px;height:22px;align-items:center;justify-content:center;border-radius:50%;background:#A15C48;color:#fff;opacity:1;text-decoration:none;-webkit-tap-highlight-color:transparent;outline:none}.row.user.queued-message .sendnow-undo::after{content:'';position:absolute;inset:-8px}.row.user.queued-message .sendnow-undo svg{width:12px;height:12px;display:block}.row.user.queued-message .sendnow-undo:active,.row.user.queued-message .sendnow-undo:focus,.row.user.queued-message .sendnow-undo:focus-visible{background:#A15C48;outline:none;opacity:.8}"

    /** System notice for a failed turn: a centered, ruled line in the same family as the
     *  Compaction and event dividers, red text, the provider's own words (quota, auth, ...).
     *  Its own row, outside the user's bubble. Blank text renders nothing. Pure, unit-tested. */
    fun errorNoticeHtml(text: String): String {
        val why = text.trim().take(500)
        if (why.isBlank()) return ""
        return "<div class='crow' data-role=\"notice\"><div class='cdiv evt err'><span>" + esc(why) + "</span></div></div>"
    }

    /** Day separator row for ts, or "" when the label is blank. */
    fun daySepHtml(ts: Long): String {
        val label = dayLabel(ts)
        if (label.isBlank()) return ""
        return "<div class='daysep'><span>" + esc(label) + "</span></div>"
    }

    /** Separator html between two adjacent rows ("" when same day). */
    fun daySepBetween(prevTs: Long, ts: Long): String {
        if (dayKey(prevTs) == dayKey(ts)) return ""
        return daySepHtml(ts)
    }

    fun threadHtml(
        title: String,
        model: String,
        messages: List<ChatMessage>,
        token: String = "",
        progress: List<OpencodeApi.ProgressStep>? = null,
        doneSteps: List<OpencodeApi.ProgressStep> = emptyList(),
        autoScroll: Boolean = true,
        keepScrollY: Int = -1,
        dark: Boolean = false,
        hiddenCount: Int = 0,
        liveBox: Boolean = true,
        chatId: String = "",
        baseIndex: Int = 0,
        /** Open at this message (global index) instead of the bottom: the first unread one. */
        anchorIdx: Int = -1
    ): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<style>")
        sb.append("html,body{max-width:100%}")
        if (dark) {
            sb.append("body{font-family:sans-serif;font-size:17px;line-height:1.5;margin:0 auto;max-width:44rem;padding:12px;background:#1A1712;color:#ECE7DA;overflow-x:hidden;overflow-wrap:break-word;word-wrap:break-word;word-break:break-word}")
            sb.append(".row{margin:0 0 12px 0;padding:10px 12px;border-radius:12px;max-width:100%;box-sizing:border-box;animation:msgIn .18s ease-out}")
            sb.append(".row.user{margin-left:15%;max-width:85%}")
            sb.append(".row.typing{min-height:0;background:transparent;border:none;padding:0 4px}")
            sb.append(".row.typing .prow{font-size:13px;letter-spacing:3px;color:#A89E8C}")
            sb.append(".row.sysmsg{background:transparent;border:none;padding:0 4px;margin:2px 0 10px;text-align:right;animation:none}")
            sb.append(".sysmsg-lab{display:inline-block;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:1.35;color:#A89E8C;padding:1px 2px;max-width:92%}")
            sb.append(".sysmsg-lab p{margin:0}")
            sb.append("@keyframes msgIn{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}")
            // No pulse anywhere: a pending row is a static "…", never a
            // shimmer, and the live box never breathes. Motion only on a
            // row's first appearance (msgIn above).
            sb.append(".shimmer{animation:none}")
            sb.append("#livebox{animation:none}")
            sb.append(".user{background:#3A352E;color:#ECE7DA}")
            sb.append(".assistant{background:transparent;border:0;color:#ECE7DA}.row.assistant{padding:2px 4px;border-radius:0}")
            sb.append(".error{background:transparent;border:none;color:#E08073;font-size:13px}")
            sb.append(".tool{background:#211D18;border:1px dashed #A89E8C;color:#ECE7DA}.row.tool{padding:6px 10px;font-size:14px}.row.tool .etime{display:none}.row.tool+.row.tool{margin-top:-8px}")
            sb.append(".row.error{background:transparent;border:none;padding:2px 10px;margin:0 0 8px 0}")
            sb.append(".failtxt{color:#E08073}.user .failtxt{color:#F2A29A}")
            sb.append(".bgline{color:#A89E8C}.bgline .bgtxt{color:#C9C0AC}.bgline.st-done .bgic{color:#6FCF97}.bgline.st-failed .bgic,.bgline.st-failed .bgmeta,.bgline .bgstop.armed{color:#E08073}")
            sb.append(".bgpill{display:inline-block;font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:#A89E8C;border:1px solid #3A352E;border-radius:10px;padding:1px 8px;margin-bottom:6px}")
            sb.append(".bgpill.done{color:#6FCF97;border-color:#6FCF97}")
            sb.append(".bgcmd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;color:#ECE7DA;overflow-wrap:break-word}")
            sb.append(".bgfull{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#C9C0AC;white-space:pre-wrap;overflow-wrap:break-word}")
            sb.append(".crow{margin:6px 0 14px 0}.cdiv{display:flex;align-items:center;gap:10px;color:#A89E8C;font-size:13px;margin:4px 0 10px 0}.cdiv:before,.cdiv:after{content:'';flex:1;border-top:1px solid #3A352E}.cdiv span{white-space:nowrap}.cdiv.evt span{white-space:normal;text-align:center;line-height:1.4;padding:0 2px}.cdiv.err span{color:#E08073;overflow-wrap:anywhere}")
            sb.append("ul,ol{margin:6px 0;padding-left:22px}li{margin:2px 0}")
            sb.append("pre{background:#0F0D0A;color:#ECE7DA;padding:10px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;word-break:break-word;overflow-wrap:break-word;max-width:100%;box-sizing:border-box;font-size:14px}")
            sb.append("code{font-family:monospace;background:rgba(255,255,255,.12);padding:1px 4px;border-radius:4px;font-size:15px;overflow-wrap:break-word;word-break:break-word}")
            sb.append("pre code{background:none;padding:0}")
            sb.append(".user code{background:rgba(0,0,0,.12)}")
            sb.append("blockquote{border-left:3px solid #A89E8C;margin:8px 0;padding:4px 10px;color:#C9C0AC}")
            sb.append(".user blockquote{border-left-color:#A89E8C;color:#ECE7DA}")
            sb.append("table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;-webkit-overflow-scrolling:touch;font-size:15px}")
            sb.append("th,td{border:1px solid #3A352E;padding:6px 8px;text-align:left;vertical-align:top;min-width:9em;overflow-wrap:normal;word-break:normal}")
            sb.append("img{max-width:100%;height:auto;border-radius:8px}")
            sb.append("img.timg{cursor:zoom-in}")
            sb.append(".meta{font-size:12px;opacity:.7;margin-top:6px}").append(".morehist{text-align:center;margin:4px 0 12px;font-size:14px}")
            sb.append(".etime{text-align:right;font-size:12px;opacity:.75;margin-top:2px}")
            sb.append(SEND_NOW_CSS)
            sb.append(".row.user.queued-message .sendnow-undo,.row.user.queued-message .sendnow-undo:active,.row.user.queued-message .sendnow-undo:focus{background:#C97B63}")
            sb.append(".sendfail{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:12px;opacity:.82;margin-top:5px}.sendfail a{font-weight:600;color:#7FB3E8;text-decoration:none}")
            sb.append("a.alarmcard{display:flex;gap:10px;align-items:center;background:#211D18;border:1px solid #7FB3E8;border-radius:12px;padding:10px 12px;margin:6px 0;text-decoration:none;color:#ECE7DA}.alarme{font-size:22px}.alarmsub{font-size:13px;opacity:.75}")
            sb.append(pbCss)
            sb.append(".daysep{text-align:center;margin:10px 0 14px}")
            sb.append(".daysep span{display:inline-block;background:#3A352E;color:#ECE7DA;font-size:13px;border-radius:12px;padding:4px 14px}")
            sb.append(".toolhead{font-size:14px;font-weight:bold;margin-bottom:4px}")
            sb.append(".aud{display:flex;align-items:center;gap:8px;margin:4px 0 8px 0}")
            sb.append(".aud audio{flex:1;min-width:0;height:32px}")
            sb.append(".wave{display:flex;align-items:flex-end;gap:2px;height:28px}")
            sb.append(".wave i{width:3px;background:#A89E8C;border-radius:2px;display:block}")
            sb.append(".user .wave i{background:#1A1712}")
            sb.append(".dur{font-family:monospace;font-size:14px;white-space:nowrap}")
            sb.append(".hintchip{display:inline-block;border:1px solid #3A352E;border-radius:18px;padding:10px 14px;margin:4px 6px 0 0;font-size:14px}")
            sb.append(".prog{min-height:40px;max-height:25vh;overflow-y:auto;background:#211D18;border:1px solid #3A352E;border-radius:8px;padding:6px 8px;overscroll-behavior:auto}")
            sb.append(".prow{font-size:14px;color:#C9C0AC;overflow-wrap:break-word}")
            sb.append(".pstat{font-style:italic;opacity:.75}")
            sb.append(".pdet{font-size:12px;color:#A89E8C;margin:0 0 4px 14px;overflow-wrap:break-word}")
            sb.append(".ptr{font-size:12px;color:#A89E8C;margin:-8px 0 12px 14px;overflow-wrap:break-word}")
            sb.append(".cmeta{font-size:12px;color:#A89E8C;margin-top:6px;overflow-wrap:break-word}.voice-send-meta{display:flex;align-items:center;gap:4px}.voice-send-icon{width:13px;height:13px}")
            sb.append(".row.has-reply-actions{margin-bottom:0;padding-bottom:0}.row.has-reply-actions>p:last-of-type{margin-bottom:0!important}.reply-actions{display:flex;align-items:center;gap:4px;margin:0 0 12px 0}.reply-actions .etime{margin:0 0 0 auto}.reply-actions a{display:inline-flex;width:40px;height:40px;align-items:center;justify-content:center;border-radius:10px;color:#A89E8C;text-decoration:none;opacity:.85}.reply-actions a:active{background:rgba(168,158,140,.12)}.reply-actions a.is-reading{color:#ECE7DA;opacity:1}.reply-actions a.is-reading rect{fill:#262220}.reply-actions svg{width:20px;height:20px}")
            sb.append(".steps{margin:0 0 12px 0;font-size:14px;color:#A89E8C}")
            sb.append(".steps .sum{font-size:14px;color:#A89E8C;cursor:pointer}")
            sb.append(".steps .sbody{display:none}")
            sb.append(".steps.open .sbody{display:block}")
            sb.append(".sdur{font-size:12px;opacity:.7}")
            sb.append(".perr{color:#E08073}")
            sb.append(".scopy{font-size:12px;opacity:.7}")
            sb.append("video{width:100%;min-height:220px;max-height:40vh;height:auto;border-radius:8px}")
            sb.append(".vsrc{font-size:12px;opacity:.65;margin-top:2px}")
            sb.append("a{color:#7FB3E8;overflow-wrap:break-word}").append(LINK_CHIP_CSS)
        } else {
        sb.append("body{font-family:sans-serif;font-size:17px;line-height:1.5;margin:0 auto;max-width:44rem;padding:12px;background:#F5F1E6;color:#262220;overflow-x:hidden;overflow-wrap:break-word;word-wrap:break-word;word-break:break-word}")
        sb.append(".row{margin:0 0 12px 0;padding:10px 12px;border-radius:12px;max-width:100%;box-sizing:border-box;animation:msgIn .18s ease-out}")
        sb.append(".row.user{margin-left:15%;max-width:85%}")
        sb.append(".row.typing{min-height:0;background:transparent;border:none;padding:0 4px}")
        sb.append(".row.typing .prow{font-size:13px;letter-spacing:3px;color:#6B645A}")
        sb.append(".row.sysmsg{background:transparent;border:none;padding:0 4px;margin:2px 0 10px;text-align:right;animation:none}")
        sb.append(".sysmsg-lab{display:inline-block;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:1.35;color:#6B645A;padding:1px 2px;max-width:92%}")
        sb.append(".sysmsg-lab p{margin:0}")
        sb.append("@keyframes msgIn{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}")
        // No pulse anywhere: a pending row is a static "…", never a shimmer,
        // and the live box never breathes. Motion only on first appearance.
        sb.append(".shimmer{animation:none}")
        sb.append("#livebox{animation:none}")
        sb.append(".user{background:#262220;color:#F5F1E6}")
        sb.append(".assistant{background:transparent;border:0;color:#262220}.row.assistant{padding:2px 4px;border-radius:0}")
        sb.append(".error{background:transparent;border:none;color:#B3261E;font-size:13px}")
        sb.append(".tool{background:#E4DCCB;border:1px dashed #6B645A;color:#262220}.row.tool{padding:6px 10px;font-size:14px}.row.tool .etime{display:none}.row.tool+.row.tool{margin-top:-8px}")
        sb.append(".row.error{background:transparent;border:none;padding:2px 10px;margin:0 0 8px 0}")
        sb.append(".failtxt{color:#B3261E}.user .failtxt{color:#F2A29A}")
        sb.append(".bgline{color:#6B645A}.bgline .bgtxt{color:#4A453D}.bgline.st-done .bgic{color:#2E7D4F}.bgline.st-failed .bgic,.bgline.st-failed .bgmeta,.bgline .bgstop.armed{color:#B3261E}")
        sb.append(".bgpill{display:inline-block;font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:#6B645A;border:1px solid #D8D1C0;border-radius:10px;padding:1px 8px;margin-bottom:6px}")
        sb.append(".bgpill.done{color:#2E7D4F;border-color:#2E7D4F}")
        sb.append(".bgcmd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;color:#262220;overflow-wrap:break-word}")
        sb.append(".bgfull{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#4A453D;white-space:pre-wrap;overflow-wrap:break-word}")
        sb.append(".crow{margin:6px 0 14px 0}.cdiv{display:flex;align-items:center;gap:10px;color:#6B645A;font-size:13px;margin:4px 0 10px 0}.cdiv:before,.cdiv:after{content:'';flex:1;border-top:1px solid #D8D1C0}.cdiv span{white-space:nowrap}.cdiv.evt span{white-space:normal;text-align:center;line-height:1.4;padding:0 2px}.cdiv.err span{color:#B3261E;overflow-wrap:anywhere}")
        sb.append("ul,ol{margin:6px 0;padding-left:22px}li{margin:2px 0}")
        sb.append("pre{background:#262220;color:#ECE7DA;padding:10px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;word-break:break-word;overflow-wrap:break-word;max-width:100%;box-sizing:border-box;font-size:14px}")
        sb.append("code{font-family:monospace;background:rgba(0,0,0,.08);padding:1px 4px;border-radius:4px;font-size:15px;overflow-wrap:break-word;word-break:break-word}")
        sb.append("pre code{background:none;padding:0}")
        sb.append(".user code{background:rgba(255,255,255,.18)}")
        sb.append("blockquote{border-left:3px solid #6B645A;margin:8px 0;padding:4px 10px;color:#4A453D}")
        sb.append(".user blockquote{border-left-color:#F5F1E6;color:#F5F1E6}")
        sb.append("table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;-webkit-overflow-scrolling:touch;font-size:15px}")
        sb.append("th,td{border:1px solid #D8D1C0;padding:6px 8px;text-align:left;vertical-align:top;min-width:9em;overflow-wrap:normal;word-break:normal}")
        sb.append("img{max-width:100%;height:auto;border-radius:8px}")
        sb.append("img.timg{cursor:zoom-in}")
        sb.append(".meta{font-size:12px;opacity:.7;margin-top:6px}").append(".morehist{text-align:center;margin:4px 0 12px;font-size:14px}")
        sb.append(".etime{text-align:right;font-size:12px;opacity:.75;margin-top:2px}")
        sb.append(SEND_NOW_CSS)
        sb.append(".sendfail{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:12px;opacity:.82;margin-top:5px}.sendfail a{font-weight:600;color:#1B5FAB;text-decoration:none}")
        sb.append("a.alarmcard{display:flex;gap:10px;align-items:center;background:#FFFDF6;border:1px solid #1B5FAB;border-radius:12px;padding:10px 12px;margin:6px 0;text-decoration:none;color:#262220}.alarme{font-size:22px}.alarmsub{font-size:13px;opacity:.75}")
        sb.append(pbCss)
        sb.append(".daysep{text-align:center;margin:10px 0 14px}")
        sb.append(".daysep span{display:inline-block;background:#D8D1C0;color:#262220;font-size:13px;border-radius:12px;padding:4px 14px}")
        sb.append(".toolhead{font-size:14px;font-weight:bold;margin-bottom:4px}")
        sb.append(".aud{display:flex;align-items:center;gap:8px;margin:4px 0 8px 0}")
        sb.append(".aud audio{flex:1;min-width:0;height:32px}")
        sb.append(".wave{display:flex;align-items:flex-end;gap:2px;height:28px}")
        sb.append(".wave i{width:3px;background:#6B645A;border-radius:2px;display:block}")
        sb.append(".user .wave i{background:#ECE7DA}")
        sb.append(".dur{font-family:monospace;font-size:14px;white-space:nowrap}")
        sb.append(".hintchip{display:inline-block;border:1px solid #D8D1C0;border-radius:18px;padding:10px 14px;margin:4px 6px 0 0;font-size:14px}")
        sb.append(".prog{min-height:40px;max-height:25vh;overflow-y:auto;background:#E7E0D0;border:1px solid #D8D1C0;border-radius:8px;padding:6px 8px;overscroll-behavior:auto}")
        sb.append(".prow{font-size:14px;color:#4A453D;overflow-wrap:break-word}")
        sb.append(".pstat{font-style:italic;opacity:.75}")
        sb.append(".pdet{font-size:12px;color:#8A8478;margin:0 0 4px 14px;overflow-wrap:break-word}")
        sb.append(".ptr{font-size:12px;color:#8A8478;margin:-8px 0 12px 14px;overflow-wrap:break-word}")
        sb.append(".cmeta{font-size:12px;color:#8A8478;margin-top:6px;overflow-wrap:break-word}.voice-send-meta{display:flex;align-items:center;gap:4px}.voice-send-icon{width:13px;height:13px}")
        sb.append(".row.has-reply-actions{margin-bottom:0;padding-bottom:0}.row.has-reply-actions>p:last-of-type{margin-bottom:0!important}.reply-actions{display:flex;align-items:center;gap:4px;margin:0 0 12px 0}.reply-actions .etime{margin:0 0 0 auto}.reply-actions a{display:inline-flex;width:40px;height:40px;align-items:center;justify-content:center;border-radius:10px;color:#8A8478;text-decoration:none;opacity:.85}.reply-actions a:active{background:rgba(138,132,120,.12)}.reply-actions a.is-reading{color:#262220;opacity:1}.reply-actions a.is-reading rect{fill:#F5F1E6}.reply-actions svg{width:20px;height:20px}")
        sb.append(".steps{margin:0 0 12px 0;font-size:14px;color:#6B645A}")
        sb.append(".steps .sum{font-size:14px;color:#6B645A;cursor:pointer}")
        sb.append(".steps .sbody{display:none}")
        sb.append(".steps.open .sbody{display:block}")
        sb.append(".sdur{font-size:12px;opacity:.7}")
        sb.append(".perr{color:#B3261E}")
        sb.append(".scopy{font-size:12px;opacity:.7}")
        sb.append("video{width:100%;min-height:220px;max-height:40vh;height:auto;border-radius:8px}")
        sb.append(".vsrc{font-size:12px;opacity:.65;margin-top:2px}")
        sb.append("a{color:#1B5FAB;overflow-wrap:break-word}").append(LINK_CHIP_CSS)
        // Your own bubble is dark in the light theme: links and the retry link there need a light blue.
        sb.append(".user a,.user .sendfail a{color:#A9CCF5}")
        }
        sb.append(threadCardsCss)
        sb.append("</style></head><body>")
        if (hiddenCount > 0) {
            sb.append("<div class='morehist'><a href=\"terminus://more\">↑ show ")
                .append(hiddenCount.toString()).append(" earlier</a></div>")
        }
        if (messages.isEmpty()) {
            sb.append("<div class='row assistant'><b>Start a conversation</b><br>")
            sb.append("Ask anything, attach a photo or file, or dictate with the mic.<br>")
            sb.append("<div><span class='hintchip'>/search</span><span class='hintchip'>/fetch</span><span class='hintchip'>/think</span></div>")
            sb.append("<div class='meta'>model: ").append(esc(model)).append("</div></div>")
        }
        // Shared reasoning box: ONE live #prog per chat. All in-flight
        // turns' steps merge chronologically into that single box (the
        // caller passes one merged list); each in-flight turn keeps only a
        // minimal "…" anchor row so its reply still settles individually
        // underneath in place. Sends still fire immediately (no queueing).
        val hasLive = messages.any { it.role == "assistant" && it.turnId.isNotBlank() && !it.settled }
        var lastDay = ""
        // Pointer rows (a send answered by another turn's reply) never emit and carry no label: the
        // reply right after reads as the answer. Order untouched, indices stable (appendRowDom
        // declines pointers → full render).
        val bgPairs = BackgroundUi.finishPairs(messages)
        val bgFinishes = bgPairs.values.toHashSet()
        messages.forEachIndexed { i, m ->
            val day = dayKey(m.ts)
            if (day.isNotBlank() && day != lastDay) {
                lastDay = day
                sb.append(daySepHtml(m.ts))
            }
            if (isPointer(m)) return@forEachIndexed
            if (i in bgFinishes) return@forEachIndexed
            // A finished job: its finish line stands in the start's place.
            bgPairs[i]?.let { f -> sb.append(rowHtml(messages[f], baseIndex + f, token, chatId, startedAt = m.ts)); return@forEachIndexed }
            // A bare placeholder ("…") is not a row: the activity box below
            // is the one running-state indicator. The placeholder stays in
            // the store and settles into its reply in place (turnId).
            if (m.role == "assistant" && m.turnId.isNotBlank() && !m.settled && m.text == "…") return@forEachIndexed
            sb.append(rowHtml(m, baseIndex + i, token, chatId))
        }
        // The single live box, pinned after all rows while the chat tracks a
        // running turn (pendingTurns non-empty). A stale orphan keeps its
        // late-`done` chance but shows no box.
        if (hasLive && liveBox) {
            val liveTs = messages.lastOrNull { it.role == "assistant" && it.turnId.isNotBlank() && !it.settled }?.ts ?: 0L
            sb.append(progressHtml(progress, live = true, ts = liveTs))
        }
        // Legacy fallback: chat-level history for sessions settled before
        // per-reply steps existed.
        if (doneSteps.isNotEmpty() && messages.none { it.stepsJson.isNotBlank() }) {
            sb.append("<div class='steps' id='stepsbox'><div class='sum' onclick=\"document.getElementById('stepsbox').classList.toggle('open')\">view steps (").append(doneSteps.size).append(") ▸</div>")
            sb.append("<div class='sbody'>")
            sb.append(progressHtml(doneSteps, live = false))
            sb.append("</div></div>")
        }
        // Gesture hit-test for the native handlers (see MainActivity):
        // long-press = copy, tap = edit (no swipe gestures: they fired by accident).
        // No JS gesture actions here — native owns them.
        sb.append("<script>(function(){window.__rowAt=function(x,y){")
        sb.append("var el=document.elementFromPoint(x,y);var row=el?el.closest('.row'):null;")
        sb.append("if(!row)return null;")
        sb.append("return JSON.stringify({i:row.dataset.idx||'0',r:row.dataset.role||'',")
        sb.append("t:window.__rowCopyText(row).slice(0,2000)});};")
        // Copy text for a row: the message body only. Steps expanders,
        // changed footers and timestamps are UI chrome — copying them
        // pasted "view steps (10)" and stray times into other apps.
        // Whitespace collapses: block layout newlines become single breaks.
        sb.append("window.__rowCopyText=function(row){")
        sb.append("var n=row.cloneNode(true);")
        sb.append("var rm=n.querySelectorAll('.steps,.cmeta,.etime,.bgpill,.sendfail');")
        sb.append("for(var i=0;i<rm.length;i++)rm[i].remove();")
        sb.append("var t=n.innerText||'';")
        sb.append("t=t.replace(/[ \\t]+\\n/g,'\\n').replace(/\\n{3,}/g,'\\n\\n').trim();")
        sb.append("return t;};")
        sb.append("window.__rowFullAt=function(x,y){")
        sb.append("var el=document.elementFromPoint(x,y);var row=el?el.closest('.row'):null;")
        sb.append("if(!row)return null;")
        sb.append("return JSON.stringify({i:row.dataset.idx||'0',r:row.dataset.role||'',")
        sb.append("l:(el&&el.closest('a')?'1':'0'),")
        sb.append("t:window.__rowCopyText(row)});};")
        // In-place settle (no reload): the live placeholder row for a turn
        // is replaced by its settled html. Scroll position is untouched
        // (same slot, no height jump beyond content). Returns 'ok',
        // 'missing' (no live row — caller falls back to full render), and
        // optionally drops the shared live box when nothing is live.
        sb.append("window.__settleRow=function(turnId,html,clearLive){")
        sb.append("try{var r=(turnId?document.getElementById('live-'+turnId):null)||document.getElementById('live');")
        sb.append("if(!r)return 'missing';")
        sb.append("var t=document.createElement('template');t.innerHTML=String(html).trim();var n=t.content.firstChild;")
        sb.append("if(!n)return 'missing';r.replaceWith(n);")
        sb.append("if(clearLive){var l=document.getElementById('livebox');if(l)l.remove();}")
        sb.append("return 'ok';}catch(e){return 'missing';}};")
        // Incremental append (no reload): one row (+ optional day separator)
        // lands before the live/steps boxes when present, else at the end.
        // Scrolls only when the user was already at the bottom.
        sb.append("window.__appendRow=function(html){")
        sb.append("try{")
        sb.append("var atBottom=(window.innerHeight+window.scrollY)>=document.body.scrollHeight-40;")
        sb.append("var a=document.getElementById('livebox')||document.getElementById('stepsbox');")
        sb.append("if(a&&a.parentNode){a.insertAdjacentHTML('beforebegin',html);}")
        sb.append("else{document.body.insertAdjacentHTML('beforeend',html);}")
        sb.append("if(window.__follow!==false&&atBottom)window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));")
        sb.append("return 'ok';}catch(e){return 'missing';}};")
        sb.append("})();</script>")
        // No contextmenu suppression: long-press text selection needs the
        // native popup (Copy / Share / Select all) to appear.
        // Follow-state owns ALL auto-scroll: fresh/open/send renders start
        // following; position restores start unfollowed (the restore scroll
        // itself re-enables follow when it lands at the bottom, and any
        // user scroll recomputes it). Every yanker below obeys __follow.
        // A structural render reloads the whole page, which would replay the
        // fade-in on every row. Only the newest two rows keep it.
        sb.append("<script>(function(){var r=document.querySelectorAll('.row');for(var i=0;i<r.length-2;i++)r[i].style.animation='none';})();")
        // Live browser card: assets/pb-card.js, handed over once at startup (see pbScript).
        sb.append(pbScript).append("\n")
        sb.append(threadUiJs).append("\n")
        sb.append(LINK_CHIP_JS).append("\n")
        sb.append("</script>")
        sb.append("<script>(function(){window.__follow=" + (if (autoScroll && anchorIdx < 0) "true" else "false") + ";")
        sb.append("window.addEventListener('scroll',function(){try{window.__follow=(window.innerHeight+window.scrollY)>=document.body.scrollHeight-80;}catch(e){}},{passive:true});")
        sb.append("})();</script>")
        if (autoScroll && anchorIdx >= 0) {
            // Open at the first unread message: its top just under the header. Re-asserted while the
            // layout settles (images, fonts), and given up on the first touch so it never fights a reader.
            sb.append("<script>(function(){var d=false;")
            sb.append("function a(){if(d)return;var e=document.getElementById('m$anchorIdx');if(!e)return;")
            sb.append("window.scrollTo(0,Math.max(0,e.getBoundingClientRect().top+window.scrollY-8));}")
            sb.append("window.addEventListener('touchstart',function(){d=true;},{passive:true});")
            sb.append("a();window.addEventListener('load',a);")
            sb.append("if(window.ResizeObserver){var ro=new ResizeObserver(a);ro.observe(document.body);setTimeout(function(){ro.disconnect();},1500);}")
            sb.append("setTimeout(a,20);setTimeout(a,80);setTimeout(a,250);")
            sb.append("})();</script>")
        } else if (autoScroll) {
            sb.append("<script>(function(){")
            sb.append("function b(){if(window.__follow===false)return;window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));}")
            sb.append("b();window.addEventListener('load',b);")
            sb.append("if(window.ResizeObserver){var ro=new ResizeObserver(b);ro.observe(document.body);setTimeout(function(){ro.disconnect();},1500);}")
            sb.append("setTimeout(b,20);setTimeout(b,80);setTimeout(b,250);")
            sb.append("})();</script>")
        } else if (keepScrollY >= 0) {
            sb.append("<script>(function(){")
            sb.append("function r(){window.scrollTo(0,$keepScrollY);}")
            sb.append("r();window.addEventListener('load',r);")
            sb.append("setTimeout(r,20);setTimeout(r,80);")
            sb.append("})();</script>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Parse stored per-reply steps back into render rows. */
    fun stepsOf(json: String): List<OpencodeApi.ProgressStep> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                OpencodeApi.ProgressStep(
                    o.optString("kind", "text"),
                    o.optString("label", ""),
                    o.optString("detail", ""),
                    o.optLong("at", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Step duration text: this step's `at` to the next step's, or blank
     *  when timestamps are absent (legacy rows). Pure, unit-tested. */
    fun stepDur(steps: List<OpencodeApi.ProgressStep>, i: Int): String {
        if (i < 0 || i >= steps.size) return ""
        if (i + 1 >= steps.size) return ""
        val a = steps[i].at
        val b = steps[i + 1].at
        if (a <= 0 || b <= a) return ""
        return fmtDur(b - a)
    }

    /** Total wall time across settled steps, blank when untimed. */
    fun stepsTotal(steps: List<OpencodeApi.ProgressStep>): String {
        if (steps.size < 2) return ""
        val a = steps.first().at
        val b = steps.last().at
        if (a <= 0 || b <= a) return ""
        return fmtDur(b - a)
    }

    private fun fmtDur(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val s = ms / 1000.0
        return if (s < 10) "%.1fs".format(s) else "${ms / 1000}s"
    }

    /** Error-ish step: the server has no error kind, so match the words
     *  providers actually emit. Conservative: highlights failures, never
     *  hides non-failures (class only affects color). */
    fun stepFailed(label: String, detail: String): Boolean {
        val t = "$label $detail"
        return Regex("error|failed|failure|exception|denied|timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    /** Fixed-height scrollable reasoning box. Live turns auto-scroll to the
     *  newest row; settled history renders static under the expander. The
     *  live box is collapsible (open by default) like the settled step
     *  expanders; step patches keep targeting inner #prog either way. */
    /** Settled activity panel: collapsed summary line (count + wall time
     *  when timestamps exist), tap to expand per-command rows with
     *  durations, failure color, and a copy link per step. */
    private fun settledStepsHtml(
        mine: List<OpencodeApi.ProgressStep>,
        chatId: String,
        turnId: String,
        idx: Int,
    ): String {
        val sb = StringBuilder()
        val total = stepsTotal(mine)
        val sum = if (total.isNotBlank()) "Completed · ${mine.size} steps · $total"
            else "view steps (${mine.size})"
        sb.append("<div class='steps' id='steps$idx'><div class='sum' onclick=\"document.getElementById('steps$idx').classList.toggle('open')\">")
            .append(esc(sum)).append(" ▸</div>")
        sb.append("<div class='sbody'><div class='prog'>")
        mine.forEachIndexed { i, s ->
            val failed = stepFailed(s.label, s.detail)
            val cls = if (failed) "prow perr" else "prow"
            sb.append("<div class='$cls'>").append(if (failed) "✕ " else "✓ ").append(esc(s.label.ifBlank { s.kind }))
            val d = stepDur(mine, i)
            if (d.isNotBlank()) sb.append(" <span class='sdur'>").append(d).append("</span>")
            if (chatId.isNotBlank() && turnId.isNotBlank()) {
                sb.append(" <a class='scopy' href=\"terminus://copy-step?chat=").append(escAttr(chatId))
                    .append("&turn=").append(escAttr(turnId)).append("&i=").append(i).append("\">copy</a>")
            }
            sb.append("</div>")
            if (s.detail.isNotBlank()) sb.append("<div class='pdet'>").append(esc(s.detail)).append("</div>")
        }
        sb.append("</div></div></div>")
        return sb.toString()
    }

    /** Fixed-height scrollable reasoning box. Live turns auto-scroll to the
     *  newest row; settled history renders static under the expander. The
     *  live box is collapsible (open by default) like the settled step
     *  expanders; step patches keep targeting inner #prog either way. */
    private fun progressHtml(steps: List<OpencodeApi.ProgressStep>?, live: Boolean, ts: Long = 0L): String {
        val sb = StringBuilder()
        if (live) sb.append("<div class='steps open' id='livebox'" + (if (ts > 0) " data-ts=\"$ts\"" else "") + "><div class='sum' onclick=\"document.getElementById('livebox').classList.toggle('open')\">activity ▸</div><div class='sbody'>")
        if (live) sb.append("<div class='prog' id='prog'>") else sb.append("<div class='prog'>")
        if (steps.isNullOrEmpty()) {
            sb.append("<div class='prow shimmer'>▸ working…</div>")
        } else {
            for (s in steps) {
                sb.append("<div class='prow'>▸ ").append(esc(s.label.ifBlank { s.kind })).append("</div>")
                if (s.detail.isNotBlank()) sb.append("<div class='pdet'>").append(esc(s.detail)).append("</div>")
            }
        }
        sb.append("</div>")
        if (live) sb.append("</div>")
        if (live) sb.append("<script>(function(){var b=document.getElementById('prog');if(b)b.scrollTop=b.scrollHeight;})();</script>")
        return sb.toString()
    }

    /** Inline player plus a filename link beneath (tappable to the
     *  viewer). The href carries the auth token but the visible text is
     *  just the filename, so the token never sits in plain sight. */
    private fun videoHtml(u: String): String {
        val safe = escAttr(u)
        val name = esc(u.substringAfterLast("/").substringBefore("?").substringBefore("#").ifBlank { u })
        return "<video controls preload=\"metadata\" src=\"$safe\"></video>" +
            "<div class='vsrc'><a href=\"$safe\">$name</a></div>"
    }

    private fun attachmentHtml(path: String, durationSec: Int = 0, token: String = ""): String {
        val lower = path.lowercase()
        val http = lower.startsWith("http://") || lower.startsWith("https://")
        if (isImageUrl(lower)) {
            val u = if (http) withToken(path, token) else "file://$path"
            return "<a href=\"$u\"><img class=\"timg\" src=\"$u\"></a>"
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")) {
            val u = if (http) withToken(path, token) else "file://$path"
            return videoHtml(u)
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".aac")
        ) {
            val u = if (http) withToken(path, token) else "file://$path"
            val dur = if (durationSec > 0) "%d:%02d".format(durationSec / 60, durationSec % 60) else "--:--"
            val bars = StringBuilder()
            var h = path.hashCode()
            repeat(24) {
                h = h * 31 + 7
                val bh = 4 + ((h ushr 8) % 25)
                bars.append("<i style='height:").append(bh).append("px'></i>")
            }
            return "<div class='aud'><div class='wave'>$bars</div>" +
                "<audio controls preload=\"metadata\" src=\"$u\"></audio>" +
                "<span class='dur'>$dur</span></div>"
        }
        val name = esc(path.substringAfterLast("/"))
        val u = if (http) withToken(path, token) else "file://$path"
        return "<div>📎 <a href=\"$u\">$name</a></div>"
    }

    fun bodyHtml(src: String, token: String = ""): String {
        // Fenced-block split BEFORE inline parsing: scan fence LINES
        // (```lang … ```) so nested backticks inside code can never
        // prematurely close the block (regex first-close is fragile).
        val fences = mutableListOf<String>()
        val prose = StringBuilder()
        val srcLines = src.split("\n")
        var fi = 0
        while (fi < srcLines.size) {
            val t = srcLines[fi].trimStart()
            if (t.startsWith("```")) {
                val lang = t.removePrefix("```").trim()
                    .takeWhile { it.isLetterOrDigit() || it == '+' || it == '-' || it == '#' }
                val code = StringBuilder()
                fi++
                while (fi < srcLines.size && !srcLines[fi].trimStart().startsWith("```")) {
                    code.append(srcLines[fi]).append("\n")
                    fi++
                }
                if (fi < srcLines.size) fi++ // consume closing fence
                val codeEsc = esc(code.toString().trimEnd('\n'))
                // ```map {places:[{name,lat,lng}], me, zoom}: an interactive map card (thread-ui.js, Leaflet).
                val map = if (lang == "map") MapBridge.normalise(code.toString()) else null
                if (map != null) fences.add("<div class='mapcard' data-map=\"" + escAttr(map) + "\"></div>")
                else fences.add("<pre><code class=\"$lang\">$codeEsc</code></pre>")
                prose.append("\u0000${fences.size - 1}\u0000\n")
            } else {
                prose.append(srcLines[fi]).append("\n")
                fi++
            }
        }
        var s = esc(prose.toString().trimEnd('\n'))
        val out = StringBuilder()
        val lines = s.split("\n")
        var inTable = false
        var inQuote = false
        var inList: String? = null
        fun closeList() {
            if (inList == "ul") out.append("</ul>")
            else if (inList == "ol") out.append("</ol>")
            inList = null
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            // Unpaired closing fence line (no opener): render literally.
            val ulM = Regex("^[-+*] +(.*)").matchEntire(trimmed)
                ?.takeIf { !trimmed.startsWith("**") }
            val olM = Regex("^(\\d+)[.)] +(.*)").matchEntire(trimmed)
            when {
                line.startsWith("\u0000") && line.endsWith("\u0000") -> {
                    closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false
                    closeList()
                    out.append(line)
                }
                ulM != null -> {
                    if (inTable) { out.append("</table>"); inTable = false }
                    if (inQuote) { out.append("</blockquote>"); inQuote = false }
                    if (inList != "ul") { closeList(); out.append("<ul>"); inList = "ul" }
                    out.append("<li>").append(inline(ulM.groupValues[1], token)).append("</li>")
                }
                olM != null -> {
                    if (inTable) { out.append("</table>"); inTable = false }
                    if (inQuote) { out.append("</blockquote>"); inQuote = false }
                    if (inList != "ol") { closeList(); out.append("<ol>"); inList = "ol" }
                    out.append("<li>").append(inline(olM.groupValues[2], token)).append("</li>")
                }
                line.startsWith("### ") -> { closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false
                    closeList()
                    out.append("<h3>").append(inline(line.removePrefix("### "), token)).append("</h3>") }
                line.startsWith("## ") -> { closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false
                    closeList()
                    out.append("<h2>").append(inline(line.removePrefix("## "), token)).append("</h2>") }
                line.startsWith("# ") -> { closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false
                    closeList()
                    out.append("<h2>").append(inline(line.removePrefix("# "), token)).append("</h2>") }
                line.startsWith("&gt;") || line.startsWith(">") -> {
                    closeList()
                    if (!inQuote) { out.append("<blockquote>"); inQuote = true }
                    out.append(inline(line.removePrefix(">").removePrefix("&gt;").trimStart(), token)).append("<br>")
                }
                line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") -> {
                    closeBlocks(out, false, inQuote); inQuote = false
                    if (!line.contains("---")) {
                    closeList()
                    if (!inTable) { closeBlocks(out, false, inQuote); inQuote = false; out.append("<table>"); inTable = true }
                    out.append("<tr>")
                    line.trim().trim('|').split("|").forEach { out.append("<td>").append(inline(it.trim(), token)).append("</td>") }
                    out.append("</tr>")
                    }
                }
                line.isBlank() -> { closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false; closeList(); out.append("<br>") }
                else -> { closeBlocks(out, inTable, inQuote); inTable = false; inQuote = false
                    closeList()
                    out.append("<p style='margin:4px 0'>").append(inline(line, token)).append("</p>") }
            }
        }
        closeBlocks(out, inTable, inQuote)
        closeList()
        var html = out.toString()
        fences.forEachIndexed { i, fence -> html = html.replace("\u0000${i}\u0000", fence) }
        return html
    }

    private fun closeBlocks(out: StringBuilder, inTable: Boolean, inQuote: Boolean, inUl: Boolean = false, inOl: Boolean = false) {
        if (inTable) out.append("</table>")
        if (inQuote) out.append("</blockquote>")
        if (inUl) out.append("</ul>")
        if (inOl) out.append("</ol>")
    }

    private fun inline(s: String, token: String = ""): String {
        // Code spans first, held as placeholders. The link and URL rules below would otherwise take a
        // span's closing backtick into an href, leaving a <code> unclosed: the browser reopens it in the
        // rows that follow, which reads as formatting leaking between messages.
        val spans = mutableListOf<String>()
        var r = s.replace(Regex("`([^`]+)`")) {
            // A span that is only a URL, email or phone number is a contact the agent happened to backtick:
            // render it as the tappable link it was meant to be, not as dead code.
            spans.add(contactLinkFor(it.groupValues[1].trim(), token) ?: "<code>${it.groupValues[1]}</code>")
            "\u0001${spans.size - 1}\u0001"
        }
        // [image:X] → inline image. http(s) gallery URLs load directly
        // (token attached for server-local paths); /abs/path → file://.
        r = r.replace(Regex("\\[image:(.+?)]")) {
            val p = it.groupValues[1].trim()
            if (p.startsWith("http://") || p.startsWith("https://")) {
                val u = withToken(p, token)
                "<a href=\"$u\"><img class=\"timg\" src=\"$u\"></a>"
            } else {
                "<a href=\"file://$p\"><img class=\"timg\" src=\"file://$p\"></a>"
            }
        }
        // ![alt](url) — explicit image intent: embed even without an image
        // extension (trust the author; extension sniffing fails on clean
        // URLs). Same linked-viewer treatment as linkified images. Runs
        // before [text](url) so the inner link never matches first.
        r = r.replace(Regex("!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")) {
            val alt = escAttr(it.groupValues[1])
            val u = withToken(it.groupValues[2], token)
            "<a href=\"$u\"><img class=\"timg\" src=\"$u\" alt=\"$alt\"></a>"
        }
        // [text](url) — server-local links carry the token so taps don't 401.
        // terminus:// actions (retry/restart) pass through untokened for
        // the native URL interceptor. Absolute local paths (/… Deep-link
        // into file:// for the native viewer; angle destinations (<…>,
        // CommonMark for paths with spaces) strip to the bare path. NOTE:
        // the whole input is HTML-escaped before this runs, so angles
        // arrive as &lt;…&gt; — match that form (raw <…> kept defensively).
        r = r.replace(Regex("\\[([^\\]]+)]\\((&lt;.+?&gt;|<[^>]+>|https?://[^)]+|terminus://[^)]+|mailto:[^)\\s]+|tel:[^)\\s]+|/[^)]*)\\)")) {
            val label = it.groupValues[1]
            var d = it.groupValues[2].trim()
            if (d.startsWith("<") && d.endsWith(">")) d = d.substring(1, d.length - 1).trim()
            if (d.startsWith("&lt;") && d.endsWith("&gt;")) d = d.substring(4, d.length - 4).trim()
            when {
                d.startsWith("http://") || d.startsWith("https://") ->
                    if (isVideoUrl(d)) videoHtml(withToken(d, token))
                    else "<a href=\"${withToken(d, token)}\">$label</a>"
                d.startsWith("terminus://") ->
                    if (d.startsWith("terminus://alarm")) alarmCardHtml(d, label)
                    else if (d == "terminus://browser" || d.startsWith("terminus://browser#f=") || d.startsWith("terminus://browser?f=")) browserCardHtml(d, label)
                    else "<a href=\"$d\">$label</a>"
                d.startsWith("mailto:") || d.startsWith("tel:") -> "<a href=\"$d\">$label</a>"
                d.startsWith("/") && d.length > 1 ->
                    "<a href=\"file://$d\">$label</a>"
                else -> it.value
            }
        }
        // bare urls — server-local images render inline (agent→user photo
        // loop); everything else is a token-carrying link. Trailing
        // .,;:!?)]'" punctuation is sentence punctuation, not URL.
        r = r.replace(Regex("(^|\\s)(https?://[^\\s<]+)")) {
            val pre = it.groupValues[1]
            var u = it.groupValues[2]
            var trail = ""
            // Balanced ')' stays (wiki-style …/X_(foo)); unbalanced trailing
            // ')' is sentence punctuation (…(see https://…)).
            fun parenBalanced(s: String): Boolean =
                s.count { c -> c == '(' } >= s.count { c -> c == ')' }
            while (u.isNotEmpty() && u.last() in charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"', '}')) {
                // Keep entity tails (…&amp;) intact: never strip ';' there.
                if (u.last() == ';' && u.endsWith("&amp;")) break
                if (u.last() == ')' && parenBalanced(u)) break
                trail = u.last() + trail
                u = u.dropLast(1)
            }
            // Unbalanced trailing ')' (opened outside the URL, e.g.
            // "(see https://…)") was stripped above as punctuation.
            if (isImageUrl(u)) {
                val w = withToken(u, token)
                "$pre<a href=\"$w\"><img class=\"timg\" src=\"$w\"></a>$trail"
            } else if (isVideoUrl(u)) {
                val w = withToken(u, token)
                "$pre" + videoHtml(w) + trail
            } else {
                linkMetaLookup?.invoke(u.replace("&amp;", "&"))?.let { m -> "$pre${linkChipHtml(u, withToken(u, token), m.title)}$trail" }
                    ?: "$pre<a class=\"lk\" data-u=\"$u\" href=\"${withToken(u, token)}\">$u</a>$trail"
            }
        }
        // bare terminus:// actions — same card treatment as [text](terminus://…);
        // otherwise pasted links sit as dead text. Trailing punctuation rule
        // mirrors the bare-https matcher above (entity tails kept intact).
        r = r.replace(Regex("(^|\\s)(terminus://[^\\s<]+)")) {
            val pre = it.groupValues[1]
            var u = it.groupValues[2]
            var trail = ""
            while (u.isNotEmpty() && u.last() in charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"', '}')) {
                if (u.last() == ';' && u.endsWith("&amp;")) break
                trail = u.last() + trail
                u = u.dropLast(1)
            }
            val card = when {
                u.startsWith("terminus://alarm") -> alarmCardHtml(u, u)
                else -> "<a href=\"$u\">$u</a>"
            }
            "$pre$card$trail"
        }
        // Emails and phone numbers in plain text become mailto:/tel: links. Held as placeholders so the
        // bold/italic rules below never touch an address (john_smith@x.com has underscores).
        r = linkifyContacts(r) { html -> spans.add(html); "\u0001${spans.size - 1}\u0001" }
        // **bold**
        r = r.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        r = r.replace(Regex("(^|[^\\w*])\\*([^\\s*][^*]*?)\\*")) { "${it.groupValues[1]}<i>${it.groupValues[2]}</i>" }
        spans.forEachIndexed { i, html -> r = r.replace("\u0001$i\u0001", html) }
        return r
    }

    private val EMAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    /** International (+44 7700 900123) or UK national (01268 123456, 07700 900123). Digit counts are checked
     *  after matching, so dates, ids and prices that merely look similar stay text. */
    private val PHONE_RE = Regex("(?<![\\w@./:#+-])(\\+\\d{1,3}[ .-]?\\(?\\d{1,4}\\)?(?:[ .-]?\\d{2,4}){2,4}|0\\d{2,4}[ .-]?\\d{3,4}[ .-]?\\d{3,4})(?![\\w-])")
    private val TAG_RE = Regex("<a\\b[^>]*>.*?</a>|<[^>]*>", RegexOption.DOT_MATCHES_ALL)

    /** Link emails and phone numbers in the text parts of `html`, leaving existing tags and links alone.
     *  `hold` stores a finished anchor and returns the placeholder to put in its place. */
    internal fun linkifyContacts(html: String, hold: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in TAG_RE.findAll(html)) {
            out.append(contactsIn(html.substring(last, m.range.first), hold)).append(m.value)
            last = m.range.last + 1
        }
        return out.append(contactsIn(html.substring(last), hold)).toString()
    }

    private fun contactsIn(text: String, hold: (String) -> String): String {
        val withMail = EMAIL_RE.replace(text) { hold("<a href=\"mailto:${it.value}\">${it.value}</a>") }
        return PHONE_RE.replace(withMail) { m -> phoneLink(m.value)?.let(hold) ?: m.value }
    }

    /** The tel: anchor for a matched number, or null when its digit count says it is an id, date or price. */
    private fun phoneLink(value: String): String? {
        val digits = value.count { it.isDigit() }
        val intl = value.startsWith("+")
        if (!(if (intl) digits in 8..15 else digits in 10..11)) return null
        return "<a href=\"tel:${if (intl) "+" else ""}${value.filter { it.isDigit() }}\">$value</a>"
    }

    /** The link for a backticked span that is exactly one URL, email or phone number; null for real code. */
    internal fun contactLinkFor(span: String, token: String = ""): String? = when {
        Regex("https?://[^\\s<>\"]+").matches(span) -> "<a class=\"lk\" data-u=\"$span\" href=\"${withToken(span, token)}\">$span</a>"
        EMAIL_RE.matches(span) -> "<a href=\"mailto:$span\">$span</a>"
        PHONE_RE.matches(span) -> phoneLink(span)
        else -> null
    }

    /** True for video URLs (query/fragment ignored). */
    fun isVideoUrl(u: String): Boolean {
        val l = u.lowercase().substringBefore("?").substringBefore("#")
        return l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".mov")
    }

    /** True for image URLs (query/fragment ignored). */
    fun isImageUrl(u: String): Boolean {
        val l = u.lowercase().substringBefore("?").substringBefore("#")
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") ||
            l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".avif")
    }

    /** True for server-local paths (gallery/files/ops/notes/echo). */
    fun serverLocalPath(url: String): Boolean {
        val path = try { java.net.URI(url).path ?: "" } catch (_: Exception) { "" }
        return path.startsWith("/terminus/") || path == "/terminus" ||
            path.startsWith("/ops") || path.startsWith("/notes") || path.startsWith("/echo")
    }

    /** Append ?token= to server-local http(s) URLs only — external links
     *  never carry the token. No-op when already present or token blank.
     *  The app WebView holds no login cookie, so images need the token;
     *  gallery web uses the cookie and ignores this. */
    fun withToken(url: String, token: String): String {
        if (token.isBlank()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        if (!serverLocalPath(url)) return url
        if (url.contains("token=")) return url
        // The GET-only link token (l1.…) is only for plain files under /terminus/. Anything else (the
        // gallery page itself, /ops, /notes, /echo) uploads or deletes with its URL token, so it gets none
        // here and the app adds the master token when it opens the page.
        if (token.startsWith("l1.")) {
            val path = try { java.net.URI(url).path ?: "" } catch (_: Exception) { "" }
            val last = path.substringAfterLast('/')
            if (!path.startsWith("/terminus/") || !last.contains('.')) return url
        }
        val sep = if (url.contains("?")) "&amp;token=" else "?token="
        return url + sep + token
    }

    private fun escAttr(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Alarm links render as a labeled card, never a bare URL: time, message
     *  and what tapping does. The href is preserved so taps still arm. */
    internal fun alarmCardHtml(url: String, label: String): String {
        val q = url.substringAfter("?", "").replace("&amp;", "&")
        val at = q.split("&").firstOrNull { it.startsWith("at=") }
            ?.substringAfter("=")?.toLongOrNull()
        val when_ = if (at != null && at > 0) {
            java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(at))
        } else ""
        val rawMsg = q.split("&").firstOrNull { it.startsWith("msg=") }?.substringAfter("=") ?: ""
        // Separators arrive HTML-escaped (&amp;); the message itself is URL-
        // encoded by the sender, so decode then escape for display.
        val safeMsg = try {
            esc(java.net.URLDecoder.decode(rawMsg, "UTF-8"))
        } catch (_: Exception) { "" }
        val head = if (label.isNotBlank() && label != url) esc(label) else "Alarm"
        val state = if (at != null && at > 0) alarmStateLookup?.invoke(at) else null
        val badge = when (state) {
            WakeAlarm.State.ARMED -> "Armed \u2713"
            WakeAlarm.State.READY -> "Tap to arm"
            WakeAlarm.State.CANCELLED -> "Cancelled \u00b7 tap to re-arm"
            WakeAlarm.State.PASSED -> "Passed"
            null -> ""
        }
        val sub = listOf(when_, safeMsg).filter { it.isNotBlank() }.joinToString(" · ")
        return "<a class='alarmcard' href=\"${escAttr(url)}\"><span class='alarme'>⏰</span>" +
            "<span><b>$head</b>" +
            (if (sub.isNotBlank()) "<br><span class='alarmsub'>$sub</span>" else "") +
            (if (badge.isNotBlank()) "<br><span class='alarmsub'><b>$badge</b></span>" else "") +
            "</span></a>"
    }

    /** Browser session card: header, numbered steps with screenshots, footer with Open view. Filled live by window.__pb. */
    internal fun browserCardHtml(url: String, label: String): String {
        val head = if (label.isNotBlank() && label != url) esc(label) else "Browser session"
        val hex = url.substringAfter("terminus://browser#f=", "")
        val legacy = url.substringAfter("terminus://browser?f=", "")
        // Legacy format (builds ≤1131): base64url payload after ?f=. Hex it so the
        // same frozen renderer below handles it — no strand of old cards.
        val frozen = when {
            hex.matches(Regex("[0-9a-f]{2,}")) -> hex
            legacy.isNotEmpty() && legacy != url -> try {
                android.util.Base64.decode(legacy, android.util.Base64.URL_SAFE)
                    .joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { "" }
            else -> ""
        }
        // A finished session: collapsed card with its steps (filled by window.__pbh), no "Open view".
        if (frozen.matches(Regex("[0-9a-f]{2,}"))) {
            return "<div class='pbcard pbidle pbc' data-f='$frozen'><div class='pbhead'><span class='pbglobe'>🌐</span>" +
                "<b class='pbtitle'>$head</b><span class='pbstage'>Ended</span><span class='pbchev'>⌃</span></div>" +
                "<div class='pbbody'><div class='pbsteps'></div></div></div>"
        }
        return "<div class='pbcard pbidle'><div class='pbhead'><span class='pbglobe'>🌐</span>" +
            "<b class='pbtitle'>$head</b><span class='pblive'></span><span class='pbstage'></span><span class='pbchev'>⌃</span></div>" +
            "<div class='pbbody'><div class='pbsteps'></div><div class='pbnote'></div>" +
            "<div class='pbfoot'><img class='pbthumb'><span class='pbfm'><b class='pbfootmsg'></b><span class='pburl'></span></span>" +
            "<a class='pbopen' href=\"${escAttr(url)}\">Open view ↗</a></div></div></div>"
    }

    /** First chunk about 200 chars, then about 600, each cut at a sentence end when one is near. */
    fun speechChunks(text: String, hardLimit: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        var cap = 200
        while (rest.isNotEmpty()) {
            val lim = minOf(cap, hardLimit)
            if (rest.length <= lim) { out += rest; break }
            val cut = Regex("[.!?\n]").findAll(rest.substring(0, lim)).lastOrNull()?.range?.last?.plus(1)?.takeIf { it > lim / 3 }
                ?: rest.lastIndexOf(' ', lim).takeIf { it > lim / 3 } ?: lim
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trimStart()
            cap = 600
        }
        return out
    }

    /** Deterministic speech form: retain prose and inline code, omit fenced code. */
    fun speechText(src: String): String {
        var text = src.replace(Regex("(?s)```[^\\n]*\\n.*?```"), " Code block omitted. Use Copy to read it. ")
        text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]+\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*>\\s?"), "")
        text = text.replace(Regex("`([^`]+)`"), "$1")
        text = text.replace(Regex("[*_~]{1,3}"), "")
        text = text.replace(Regex("https?://\\S+"), "")
        return text.replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n").trim()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

