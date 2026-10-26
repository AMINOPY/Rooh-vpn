package com.aminopy.roohvpn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Minimal status screen while the server API and consent-driven connection
 * flow are being completed. This is intentionally not the final UI.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "RØH VPN\nWireGuard engine adapter integrated.\nAutomatic connection is not ready: an authorized server/profile service and VPN consent flow are still required."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
    }
}
