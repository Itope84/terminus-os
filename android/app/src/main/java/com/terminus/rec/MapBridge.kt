package com.terminus.rec

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** The chat page's side of map cards (window.TerminusMap). Tiles come through the Mac's cache
 *  (/terminus/_tiles, see server map-tiles.ts); the blue dot comes from the phone's own GPS and never leaves
 *  the phone. */
class MapBridge(private val ctx: Context, private val prefs: Prefs, private val web: () -> WebView?) {

    /** Leaflet tile URL template on the Mac, with a link token. */
    @JavascriptInterface
    fun tileUrl(): String {
        val base = prefs.serverUrl().trimEnd('/')
        if (base.isBlank()) return ""
        return "$base/terminus/_tiles/{z}/{x}/{y}.png?token=" + android.net.Uri.encode(LinkToken.forUrl(ctx, prefs))
    }

    @JavascriptInterface
    fun canLocate(): Boolean = PhoneLocation.hasForeground(ctx)

    /** Asynchronous: the answer arrives as window.__mapMe(json|null). */
    @JavascriptInterface
    fun requestMyLocation() {
        Thread {
            val fix = try { PhoneLocation.currentFix(ctx, 8_000) } catch (_: Exception) { null }
            val js = "window.__mapMe&&window.__mapMe(" + (fix?.let { PhoneLocation.fixJson(it).toString() } ?: "null") + ")"
            val w = web() ?: return@Thread
            w.post { try { w.evaluateJavascript(js, null) } catch (_: Exception) {} }
        }.start()
    }

    /** First use: Android's location prompt. */
    @JavascriptInterface
    fun askLocation() {
        try { ctx.startActivity(Intent(ctx, LocationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    /** A pin tapped: open it in the maps app (Google Maps when installed). */
    @JavascriptInterface
    fun openPlace(lat: Double, lng: Double, name: String) {
        if (!lat.isFinite() || !lng.isFinite()) return
        val label = android.net.Uri.encode(name.take(80))
        val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    companion object {
        /** Pure (unit-tested): a ```map block's JSON → the card's normalised JSON, or null when it isn't a
         *  usable map (the block then renders as plain code). Places need finite lat/lng in range; at most 25. */
        fun normalise(raw: String): String? {
            val o = try { JSONObject(raw.trim()) } catch (_: Exception) { return null }
            val out = org.json.JSONArray()
            val arr = o.optJSONArray("places") ?: org.json.JSONArray()
            for (i in 0 until minOf(arr.length(), 25)) {
                val p = arr.optJSONObject(i) ?: continue
                val lat = p.optDouble("lat", Double.NaN)
                val lng = p.optDouble("lng", p.optDouble("lon", Double.NaN))
                if (!lat.isFinite() || !lng.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lng) > 180) continue
                out.put(JSONObject().put("name", p.optString("name").take(80)).put("lat", lat).put("lng", lng))
            }
            val me = o.optBoolean("me", false)
            // A drawn route: [[lat,lng],...] (from a routing service), up to 3000 points, plus an optional label.
            val path = org.json.JSONArray()
            o.optJSONArray("path")?.let { a ->
                for (i in 0 until minOf(a.length(), 3000)) {
                    val pt = a.optJSONArray(i) ?: continue
                    val la = pt.optDouble(0, Double.NaN); val ln = pt.optDouble(1, Double.NaN)
                    if (la.isFinite() && ln.isFinite() && kotlin.math.abs(la) <= 90 && kotlin.math.abs(ln) <= 180) path.put(org.json.JSONArray().put(la).put(ln))
                }
            }
            if (out.length() == 0 && !me && path.length() < 2) return null
            val r = JSONObject().put("places", out).put("me", me)
            // line: dashed straight lines from the owner's dot to each place, labelled with the distance.
            if (me && o.optBoolean("line", false)) r.put("line", true)
            if (path.length() >= 2) {
                r.put("path", path)
                o.optString("pathLabel").take(60).takeIf { it.isNotBlank() }?.let { r.put("pathLabel", it) }
            }
            val z = o.optInt("zoom", 0)
            if (z in 3..19) r.put("zoom", z)
            return r.toString()
        }
    }
}
