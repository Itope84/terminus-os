package com.terminus.rec

import android.webkit.WebView
import java.io.File


internal const val CONTEXT_THROTTLE_MS = 3_000L

internal const val COMMANDS_TTL_MS = 5 * 60_000L

/** Waiting-row sync cadence while a reply runs: about 100 s at most, then the next manual sync. */
internal const val POLL_WAITING_GAP_MS = 2500L

internal const val POLL_WAITING_MAX = 40

/** Send-through: plain sends POST immediately even while a turn
 *  runs; each turn carries its own turnId and settles its own "…" in
 *  place, so ordering never slides. Queue-by-default: a send behind a
 *  running reply waits its turn on the server. "Send now" (steer) folds
 *  a message into the running reply instead. Commands bypass queues. */
internal data class PendingSend(val text: String, val files: List<java.io.File>, val quote: String, val turnId: String = "")

// ---- Built-page cache -------------------------------------------------------------------------------------
// Opening a chat used to rebuild its whole HTML (markdown for ~60 rows) on the UI thread every time. A page
// is now kept per chat, keyed by everything that goes into it, and the most recent chats are built in the
// background while the list is on screen. A hit skips the build; the WebView still parses and lays out.
internal class PageBuild(val html: String, val at: Long)

/** Fork a chat: local history duplicated + server session forked onto the
 *  twin id. Id-bound throughout; the twin opens active. Live placeholders
 *  never copy (their turns settle into the source thread). */
/** Thread overflow: rename, pin, fork, delete for the open chat.
 *  Same actions as the list row menu, without leaving the thread. */
/** Shared select-style sheet: title plus two-line rows with optional
 *  check, same rows as the model/backend selects. Replaces the plain
 *  text popups so every sheet reads as one component. */
internal data class SelectRow(
    val title: String,
    val sub: String = "",
    val checked: Boolean = false,
    val action: () -> Unit = {},
    /** Optional leading logo (drawable id, 0 = none). */
    val icon: Int = 0,
    /** Shown but not pickable (an unavailable backend). */
    val dim: Boolean = false,
)

/** Backend+model seeding the next new chat: the global default backend
 *  and that backend's default model. Never the previous chat's pair. */
internal data class ChatConfig(val backend: String, val model: String)
