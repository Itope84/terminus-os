package com.terminus.rec

/** Thread scrolling and history growth. Extension functions of [MainActivity], moved out of it unchanged. */

/** Scroll policy per render. New replies go to the bottom via instant
 *  in-engine JS (also injected in the HTML itself); progress-only
 *  re-renders restore the saved Y. Queuing cancels the previous render's
 *  posts first: stacked renders (open + title sync + inbox) used to pile
 *  animated pageDowns that landed at different heights — the open-chat
 *  shake. Latest render wins; delayed reposts survive image-driven
 *  height shifts. A real touch-drag on threadWeb cancels the pending post. */
internal fun MainActivity.scrollThreadToBottom() {
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
internal fun MainActivity.anchorThreadScroll(idx: Int) {
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

/** Grow rendered history keeping the first visible row anchored, so
 *  loading earlier (tap or scroll-up auto-fetch) never jumps to a
 *  random message. Falls back to plain grow when no row is visible. */
internal fun MainActivity.growHistory(id: String) {
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
internal fun MainActivity.restoreThreadScroll(cssY: Int) {
    threadTouchScroll = false
    pendingThreadScroll?.let { threadScrollHandler.removeCallbacks(it) }
    val r = Runnable {
        threadWeb.evaluateJavascript("window.scrollTo(0, $cssY);", null)
    }
    pendingThreadScroll = r
    threadScrollHandler.post(r)
    threadScrollHandler.postDelayed(r, 60)
}
