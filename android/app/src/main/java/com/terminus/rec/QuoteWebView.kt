package com.terminus.rec

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/** Thread WebView with Quote added to the native text-selection menu.
 *  WebView has no setCustomSelectionActionModeCallback (that is TextView),
 *  so the callback is wrapped here: the system items (Copy/Share/Select all)
 *  pass through untouched, one Quote item is added, and its tap hands the
 *  selected text to [onQuote]. Everything else delegates to WebView. */
class QuoteWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    /** Receives the selected text. It is read BEFORE the menu closes: finishing the action mode clears the
     *  page selection, so reading it afterwards raced and came back empty on the first try. */
    var onQuote: ((String) -> Unit)? = null

    companion object {
        private const val QUOTE_ID = 0x5107
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(orig: ActionMode.Callback): ActionMode.Callback =
        object : ActionMode.Callback by orig {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val ok = orig.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, QUOTE_ID, 0, "Quote")?.apply {
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                return ok
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == QUOTE_ID) {
                    evaluateJavascript("(function(){var s=window.getSelection();return s?s.toString():''})()") { result ->
                        val text = try {
                            org.json.JSONTokener(result ?: "\"\"").nextValue() as? String ?: ""
                        } catch (_: Exception) { "" }
                        if (text.isNotBlank()) onQuote?.invoke(text)
                        mode.finish()
                    }
                    return true
                }
                return orig.onActionItemClicked(mode, item)
            }
        }
}
