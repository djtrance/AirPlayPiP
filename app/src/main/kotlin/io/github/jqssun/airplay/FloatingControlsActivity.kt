package io.github.jqssun.airplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.jqssun.airplay.service.AirPlayService

/** Launcher/Quick Settings shortcut: never opens MainActivity or takes the mirroring surface. */
class FloatingControlsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startService(Intent(this, AirPlayService::class.java).setAction(AirPlayService.ACTION_SELECT_FLOATING))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.floating_controls_unavailable, Toast.LENGTH_LONG).show()
        }
        // Theme.NoDisplay activities must finish before onResume, including error paths.
        finish()
    }
}
