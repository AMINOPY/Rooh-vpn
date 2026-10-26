package com.aminopy.roohvpn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Minimal entry point while the networking core is being built.
 * This is intentionally not the final UI.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "RØH VPN\nCore foundation in progress. No VPN engine is enabled yet."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
    }
}
