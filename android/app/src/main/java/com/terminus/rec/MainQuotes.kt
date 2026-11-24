package com.terminus.rec

import android.view.View
import android.widget.TextView

/** Quoting a message into the composer. Extension functions of [MainActivity], moved out of it unchanged. */

/** Stage a quote (selection Quote, swipe, or the row menu), then ask for its optional note. Quotes pile up
 *  in the strip above the composer and all go out with the next send. */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.showQuote(author: String, excerpt: String) {
    val clean = QuoteDraft.clean(excerpt)
    if (clean.isBlank()) return
    val existing = quotes.firstOrNull { it.excerpt == clean }
    if (existing == null) {
        if (quotes.size >= QuoteDraft.MAX_QUOTES) { toast("Up to ${QuoteDraft.MAX_QUOTES} quotes per message"); return }
        quotes.add(QuoteItem(clean))
    }
    openQuoteEditor(existing ?: quotes.last(), isNew = existing == null)
}

internal fun MainActivity.renderQuotes() {
    if (!quoteBarReady) return
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
internal fun MainActivity.openQuoteEditor(q: QuoteItem, isNew: Boolean) {
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

internal fun MainActivity.saveQuoteNote() {
    editingQuote?.comment = quoteNote.text.toString().trim()
    closeQuoteEditor()
}

internal fun MainActivity.closeQuoteEditor() {
    editingQuote = null
    editingQuoteIsNew = false
    if (quoteEditorReady) {
        quoteEditor.visibility = View.GONE
        quoteNote.setText("")
        if (composerInputReady) composerInput.requestFocus()
    }
    renderQuotes()
}

internal fun MainActivity.clearQuote() {
    quotes.clear()
    closeQuoteEditor()
}
