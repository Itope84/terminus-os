package com.terminus.rec

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle

/** Asks for location the first time something needs it (a notification from [PhoneLocation], or the map card's
 *  "Show my location"). Step one is Android's own prompt (while using the app / only this time / don't allow).
 *  When the request came from a background job, a second step explains and opens the "Allow all the time"
 *  choice, which Android only offers in Settings. Invisible otherwise; finishes when done. */
class LocationPermissionActivity : Activity() {
    private val wantBackground get() = intent.getBooleanExtra(EXTRA_BACKGROUND, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when {
            !PhoneLocation.hasForeground(this) -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_FOREGROUND)
            wantBackground && !PhoneLocation.hasBackground(this) -> explainBackground()
            else -> finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == REQ_FOREGROUND && granted && wantBackground && !PhoneLocation.hasBackground(this)) explainBackground()
        else finish()
    }

    private fun explainBackground() {
        AlertDialog.Builder(this)
            .setTitle("Location while the app is closed")
            .setMessage("Jobs that watch where you are (\"tell me when I'm near…\") need location while Terminus is in the background. " +
                "On the next screen choose \"Allow all the time\". You can turn it off there any time.")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_BACKGROUND = "background"
        private const val REQ_FOREGROUND = 41
        private const val REQ_BACKGROUND = 42
    }
}
