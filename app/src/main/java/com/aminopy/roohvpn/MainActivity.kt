package com.aminopy.roohvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.BoundedProfileReader
import com.aminopy.roohvpn.core.EngineConfigClassifier
import com.aminopy.roohvpn.core.ImportedProfileFormat
import com.aminopy.roohvpn.core.EngineId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Explicit-consent Xray/WireGuard profile connection screen; no endpoints or credentials are fabricated. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var sniInput: EditText
    private lateinit var cdnIpInput: EditText
    private lateinit var hostInput: EditText
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingConfiguration: ByteArray? = null
    private var pendingEngineId: EngineId? = null
    private var operationInProgress = false
    private var lastStatus = "Import an Xray JSON or WireGuard profile from a trusted provider."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply { textSize = 17f }
        engineStatus = TextView(this).apply {
            textSize = 14f
            text = buildString {
                append("Engine availability (priority order)\n")
                val coordinator = (application as RoohVpnApplication).connectionCoordinator
                val engines = listOf(
                    EngineId.XRAY to "Xray-core",
                    EngineId.SING_BOX to "sing-box",
                    EngineId.AETHER to "Aether",
                    EngineId.WIREGUARD to "WireGuard",
                    EngineId.PG_CORE to "PG CORE"
                )
                engines.forEachIndexed { index, (id, label) ->
                    val availability = if (coordinator.isEngineAvailable(id)) "backend registered" else "not integrated"
                    append(index + 1)
                    append(". ")
                    append(label)
                    append(" — ")
                    append(availability)
                    if ((id == EngineId.WIREGUARD || id == EngineId.XRAY) &&
                        coordinator.isEngineAvailable(id)) {
                        append("; end-to-end traffic not device-verified")
                    }
                    if (index != engines.lastIndex) append("\n")
                }
            }
        }
        importButton = Button(this).apply {
            text = "Import Xray / WireGuard profile"
            setOnClickListener { chooseProfile() }
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestVpnConsentAndConnect() }
        }
        sniInput = EditText(this).apply {
            hint = "Optional TLS SNI / REALITY serverName"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        cdnIpInput = EditText(this).apply {
            hint = "Optional CDN edge IP (literal IPv4 / IPv6)"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        hostInput = EditText(this).apply {
            hint = "Optional HTTP Host / gRPC authority / XHTTP host"
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnect() }
        }
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(engineStatus, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(importButton)
        root.addView(sniInput)
        root.addView(cdnIpInput)
        root.addView(hostInput)
        root.addView(connectButton)
        root.addView(disconnectButton)
        setContentView(root)
        renderState()
    }

    private fun renderState() {
        val active = (application as RoohVpnApplication).connectionCoordinator.activeEngine()
        status.text = when {
            operationInProgress -> lastStatus
            active != null -> "Engine active: $active\n$lastStatus"
            pendingConfiguration != null -> "${pendingEngineId ?: "Unknown"} profile is loaded in memory.\n$lastStatus"
            else -> lastStatus
        }
        connectButton.isEnabled = !operationInProgress && pendingConfiguration != null && pendingEngineId != null && active == null
        disconnectButton.isEnabled = !operationInProgress && active != null
        importButton.isEnabled = !operationInProgress && active == null
    }

    private fun chooseProfile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PROFILE)
    }

    @Deprecated("Legacy result API retained to avoid adding a UI dependency solely for this screen.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROFILE -> if (resultCode == RESULT_OK) loadSelectedProfile(data)
            REQUEST_VPN_CONSENT -> if (resultCode == RESULT_OK) {
                connectImportedProfile()
            } else {
                lastStatus = "VPN permission was not granted."
                renderState()
            }
        }
    }

    private fun loadSelectedProfile(data: Intent?) {
        // A failed replacement import must never leave an older profile connectable.
        pendingConfiguration?.fill(0)
        pendingConfiguration = null
        pendingEngineId = null
        val uri = data?.data
        if (uri == null) {
            lastStatus = "No profile was selected."
            renderState()
            return
        }
        var importedBytes: ByteArray? = null
        try {
            importedBytes = contentResolver.openInputStream(uri)?.use { input ->
                BoundedProfileReader.read(input, MAX_CONFIG_BYTES)
            } ?: throw IllegalArgumentException("Could not read profile")
            require(importedBytes!!.isNotEmpty()) { "Profile is empty" }
            val classification = EngineConfigClassifier.classify(importedBytes!!)
            when (classification.format) {
                ImportedProfileFormat.WIREGUARD -> {
                    pendingEngineId = EngineId.WIREGUARD
                    lastStatus = "WireGuard profile loaded in memory only; the private key is not saved."
                }
                ImportedProfileFormat.XRAY_JSON -> {
                    val failure = com.aminopy.roohvpn.core.XrayProfilePreflight.validate(importedBytes!!)
                    if (failure != null) {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "The Xray JSON profile failed structural validation."
                        renderState()
                        return
                    }
                    pendingEngineId = EngineId.XRAY
                    lastStatus = "Xray profile loaded in memory only; connection requires a trusted server profile."
                }
                ImportedProfileFormat.SING_BOX_JSON -> {
                    importedBytes!!.fill(0)
                    importedBytes = null
                    lastStatus = "Detected sing-box JSON, but its runtime is not enabled in this build."
                    renderState()
                    return
                }
                ImportedProfileFormat.SHARE_LINK -> {
                    importedBytes!!.fill(0)
                    importedBytes = null
                    lastStatus = "Detected a proxy share link. Share-link conversion is not available yet."
                    renderState()
                    return
                }
                else -> {
                    importedBytes!!.fill(0)
                    importedBytes = null
                    lastStatus = "Unsupported profile. Select Xray JSON or a valid WireGuard profile."
                    renderState()
                    return
                }
            }
            pendingConfiguration = importedBytes
            importedBytes = null // ownership transferred to pendingConfiguration
        } catch (_: Exception) {
            importedBytes?.fill(0)
            lastStatus = "Profile could not be read or exceeds the 1 MiB import limit."
        }
        renderState()
    }

    private fun requestVpnConsentAndConnect() {
        if (operationInProgress || pendingConfiguration == null || pendingEngineId == null) return
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) startActivityForResult(consentIntent, REQUEST_VPN_CONSENT)
        else connectImportedProfile()
    }

    private fun connectImportedProfile() {
        val source = pendingConfiguration?.copyOf() ?: return
        if (operationInProgress) {
            source.fill(0)
            return
        }
        val engine = pendingEngineId ?: run {
            source.fill(0)
            renderState()
            return
        }

        // The SNI/CDN controls intentionally remain simple until the UI pass.
        // Transform only Xray profiles and fail closed on invalid combinations.
        val configurationToConnect = if (engine == EngineId.XRAY) {
            val sni = sniInput.text.toString().trim().ifEmpty { null }
            val cdnIp = cdnIpInput.text.toString().trim().ifEmpty { null }
            val host = hostInput.text.toString().trim().ifEmpty { null }
            if (sni == null && cdnIp == null && host == null) {
                source
            } else {
                val transformed = com.aminopy.roohvpn.core.XrayCdnProfileAdapter.apply(
                    source,
                    com.aminopy.roohvpn.core.XrayCdnProfileAdapter.Overrides(
                        sni = sni,
                        cdnEdgeIp = cdnIp,
                        hostHeader = host
                    )
                )
                if (!transformed.isSuccess) {
                    source.fill(0)
                    lastStatus = "SNI/CDN settings rejected: ${transformed.errorCode ?: "invalid profile"}."
                    renderState()
                    return
                }
                source.fill(0)
                transformed.configuration!!.toByteArray(Charsets.UTF_8)
            }
        } else {
            source
        }

        operationInProgress = true
        lastStatus = "Starting $engine…"
        renderState()
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator.connect(engine, configurationToConnect)
            } catch (_: Exception) {
                configurationToConnect.fill(0)
                EngineFailure("CONNECTION_FAILED", "Could not start the VPN connection.", true)
            }
            runOnUiThread {
                operationInProgress = false
                lastStatus = if (failure == null) {
                    "$engine reports RUNNING. End-to-end traffic/DNS still requires device testing."
                } else {
                    "Connection failed: ${failure.safeMessage}"
                }
                renderState()
            }
        }
    }

    private fun disconnect() {
        if (operationInProgress) return
        operationInProgress = true
        val activeEngine = (application as RoohVpnApplication).connectionCoordinator.activeEngine()
        lastStatus = "Stopping ${activeEngine ?: "VPN"}…"
        renderState()
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator.disconnect()
            } catch (_: Exception) {
                EngineFailure("DISCONNECT_FAILED", "Could not confirm a clean disconnect.", true)
            }
            runOnUiThread {
                operationInProgress = false
                if (failure == null) {
                    pendingConfiguration?.fill(0)
                    pendingConfiguration = null
                    pendingEngineId = null
                    lastStatus = "Disconnected."
                } else {
                    lastStatus = "Disconnect not confirmed: ${failure.safeMessage}"
                }
                renderState()
            }
        }
    }

    override fun onDestroy() {
        pendingConfiguration?.fill(0)
        pendingConfiguration = null
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PROFILE = 101
        const val REQUEST_VPN_CONSENT = 102
        const val MAX_CONFIG_BYTES = 1024 * 1024
    }
}
