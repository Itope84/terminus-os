package com.terminus.rec

import android.net.Uri
import android.view.View
import android.webkit.WebView

/** The web tabs. Extension functions of [MainActivity], moved out of it unchanged. */

internal fun MainActivity.pullFor(w: WebView) = when (w) {
    galleryWeb -> galleryPull
    opsWeb -> opsPull
    else -> notesPull
}

internal fun MainActivity.showWeb(w: WebView, path: String) {
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
internal fun MainActivity.resetWebTab(w: WebView, path: String) {
    val base = OpencodeApi.effectiveBase(prefs.serverUrl())
    val tok = prefs.token()
    val urlTok = tok   // same: panes need the master token
    w.loadUrl("$base$path?mobile=1" + if (urlTok.isNotBlank()) "&token=" + android.net.Uri.encode(urlTok) else "")
    w.setTag(R.id.tagAuthedUrl, base + "|" + tok)
}
