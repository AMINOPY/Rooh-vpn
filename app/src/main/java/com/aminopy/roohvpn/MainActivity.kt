package com.aminopy.roohvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal, explicit-consent WireGuard flow. The user imports a profile from a
 * source they trust; no credentials or server endpoints are fabricated.
 * This is functional scaffolding, not the final product UI.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingConfiguration: ByteArray? = null
    private var operationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildControls()
        renderInitialState()
    }

    private fun buildControls() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply {
            textSize = 17f
            text = "RØH VPN"
        }
        importButton = Button(this).apply {
            text = "Import WireGuard profile"
            setOnClickListener { chooseProfile() }
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestVpnConsentAndConnect() }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnect() }
        }
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(importButton)
        root.addView(connectButton)
        root.addView(disconnectButton)
        setContentView(root)
    }

    private fun renderInitialState() {
        val active = (application as RoohVpnApplication).connectionCoordinator.activeEngine()
        status.text = when {
            active != null -> "Engine active: $active"
            pendingConfiguration != null -> "WireGuard profile imported. Ready to request VPN permission."
            else -> "Import a WireGuard profile from a trusted provider. No server directory is configured yet."
        }
        connectButton.isEnabled = !operationInProgress && pendingConfiguration != null && active == null
        disconnectButton.isEnabled = !operationInProgress && active != null
    }

    private fun chooseProfile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PROFILE)
    }

    @Deprecated("Use Activity Result APIs when the app adds the AndroidX Activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CONFIG_BYTES) throw IllegalArgumentException("Profile exceeds 64 KiB.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("Could not read the selected profile.")
            if (bytes.isEmpty()) throw IllegalArgumentException("The selected profile is empty.")
            pendingConfiguration?.fill(0)
            pendingConfiguration = bytes
            status.text = "Profile loaded in memory. The private key is not saved by this screen."
        } catch (_: Exception) {
            status.text = "Profile could not be read. Check the file and try again."
        }
        renderInitialState()
    }

    private fun requestVpnConsentAndConnect() {
        if (operationInProgress) return
        if (pendingConfiguration == null) {
            status.text = "Import a WireGuard profile first."
            return
        }
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            startActivityForResult(consentIntent, REQUEST_VPN_CONSENT)
        } else {
            connectImportedProfile()
        }
    }

    @Deprecated("Use Activity Result APIs when the app adds the AndroidX Activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROFILE -> if (resultCode == RESULT_OK) loadSelectedProfile(data)
            REQUEST_VPN_CONSENT -> if (resultCode == RESULT_OK) connectImportedProfile() else {
                status.text = "VPN permission was not granted."
                renderInitialState()
            }
        }
    }

    private fun loadSelectedProfile(data: Intent?) {
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CONFIG_BYTES) { "Profile exceeds size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("Could not read profile")
            require(bytes.isNotEmpty())
            pendingConfiguration?.fill(0)
            pendingConfiguration = bytes
            status.text = "WireGuard profile loaded in memory."
        } catch (_: Exception) {
            status.text = "Profile could not be read. Check the file and try again."
        }
        renderInitialState()
    }

    private fun connectImportedProfile() {
        val source = pendingConfiguration?.copyOf()
        if (source == null || operationInProgress) return
        operationInProgress = true
        renderInitialState()
        status.text = "Starting WireGuard…"
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator
                    .connect(com.aminopy.roohvpn.core.EngineId.WIREGUARD, source)
            } catch (_: Exception) {
                source.fill(0)
                com.aminopy.roohvpn.core.EngineFailure(
                    "CONNECTION_FAILED", "Could not start the VPN connection.", true
                )
            }
            runOnUiThread {
                operationInProgress = false
                status.text = if (failure == null) {
                    "WireGuard reports the engine is running. Verify traffic/DNS on a device before treating this as production-ready."
                } else {
                    "Connection failed: ${failure.safeMessage}"
                }
                renderInitialState()
            }
        }
    }

    private fun disconnect() {
        if (operationInProgress) return
        operationInProgress = true
        renderInitialState()
        status.text = "Stopping WireGuard…"
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator.disconnect()
            } catch (_: Exception) {
                com.aminopy.roohvpn.core.EngineFailure(
                    "DISCONNECT_FAILED", "Could not confirm a clean disconnect.", true
                )
            }
            runOnUiThread {
                operationInProgress = false
                if (failure == null) {
                    pendingConfiguration?.fill(0)
                    pendingConfiguration = null
                    status.text = "Disconnected."
                } else {
                    status.text = "Disconnect not confirmed: ${failure.safeMessage}"
                }
                renderInitialState()
            }
        }
    }

    override fun onDestroy() {
        // The active backend owns the live tunnel; only clear the screen's copy.
        pendingConfiguration?.fill(0)
        pendingConfiguration = null
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PROFILE = 101
        const val REQUEST_VPN_CONSENT = 102
        const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
