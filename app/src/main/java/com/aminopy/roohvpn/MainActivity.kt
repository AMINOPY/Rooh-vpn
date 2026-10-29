package com.aminopy.roohvpn

import android.app.Activity
import android.app.AlertDialog
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
import com.aminopy.roohvpn.security.EncryptedProfileStore
import com.aminopy.roohvpn.profiles.ProfileCatalog
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Explicit-consent Xray/WireGuard profile connection screen; no endpoints or credentials are fabricated. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var importButton: Button
    private lateinit var saveProfileButton: Button
    private lateinit var savedProfilesButton: Button
    private lateinit var removeProfileButton: Button
    private val profileStore by lazy { EncryptedProfileStore(applicationContext) }
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var sniInput: EditText
    private lateinit var cdnIpInput: EditText
    private lateinit var hostInput: EditText
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingConfiguration: ByteArray? = null
    private var pendingEngineId: EngineId? = null
    private var operationInProgress = false
    private var lastStatus = if (BuildConfig.FLAVOR == "singbox") {
        "Import a sing-box JSON or WireGuard profile from a trusted provider."
    } else {
        "Import an Xray JSON or WireGuard profile from a trusted provider."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 15, 29)
        window.navigationBarColor = Color.rgb(8, 15, 29)
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(8, 15, 29), Color.rgb(13, 25, 43), Color.rgb(8, 15, 29))
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val brand = TextView(this).apply {
            text = "R Ø H   /   VPN"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(103, 232, 249))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        page.addView(brand, matchWrap())
        page.addView(space(8), matchWrap())

        val title = TextView(this).apply {
            text = "Your private\nconnection."
            textSize = 31f
            setLineSpacing(dp(1).toFloat(), 1.02f)
            setTextColor(Color.rgb(241, 247, 255))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        page.addView(title, matchWrap())
        val subtitle = TextView(this).apply {
            text = "Import a trusted profile, review its engine, then connect."
            textSize = 14f
            setTextColor(Color.rgb(157, 174, 197))
        }
        page.addView(subtitle, marginParams(top = 8, bottom = 20))

        val connectionCard = card()
        val statusHeading = TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(103, 232, 249))
            typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(231, 239, 250))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        connectionCard.addView(statusHeading, matchWrap())
        connectionCard.addView(status, marginParams(top = 10))
        page.addView(connectionCard, marginParams(bottom = 14))

        val actionsCard = card()
        actionsCard.addView(sectionHeading("PROFILE WORKSPACE"), matchWrap())
        val flavorLabel = if (BuildConfig.FLAVOR == "singbox") "sing-box JSON + WireGuard" else "Xray JSON + WireGuard"
        val profileHint = TextView(this).apply {
            text = "Supported in this build: $flavorLabel. Profiles are not generated automatically."
            textSize = 13f
            setTextColor(Color.rgb(157, 174, 197))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        actionsCard.addView(profileHint, marginParams(top = 7, bottom = 14))

        importButton = actionButton("＋   Import profile", true) { chooseProfile() }
        saveProfileButton = actionButton("▣   Save securely", false) { saveCurrentProfile() }
        savedProfilesButton = actionButton("▤   Saved profiles", false) { showSavedProfiles() }
        removeProfileButton = actionButton("⌫   Delete saved profile", false) { chooseSavedProfileToDelete() }
        actionsCard.addView(importButton, matchWrap())
        actionsCard.addView(saveProfileButton, marginParams(top = 8))
        actionsCard.addView(savedProfilesButton, marginParams(top = 8))
        actionsCard.addView(removeProfileButton, marginParams(top = 8))
        page.addView(actionsCard, marginParams(bottom = 14))

        val optionsCard = card()
        optionsCard.addView(sectionHeading("OPTIONAL TRANSPORT OVERRIDES"), matchWrap())
        val optionsHint = TextView(this).apply {
            text = "Leave these empty unless your provider gave you exact values."
            textSize = 12f
            setTextColor(Color.rgb(157, 174, 197))
        }
        optionsCard.addView(optionsHint, marginParams(top = 6, bottom = 8))
        sniInput = themedInput("TLS SNI / REALITY serverName")
        cdnIpInput = themedInput("CDN edge IP (IPv4 / IPv6)")
        hostInput = themedInput("HTTP Host / gRPC authority / transport host")
        optionsCard.addView(sniInput, matchWrap())
        optionsCard.addView(cdnIpInput, marginParams(top = 5))
        optionsCard.addView(hostInput, marginParams(top = 5))
        page.addView(optionsCard, marginParams(bottom = 14))

        val engineCard = card()
        engineCard.addView(sectionHeading("ENGINE STATUS"), matchWrap())
        engineStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(197, 211, 230))
            setLineSpacing(dp(5).toFloat(), 1f)
            text = buildString {
                append("Availability is not proof of a working tunnel.\n\n")
                val coordinator = (application as RoohVpnApplication).connectionCoordinator
                val engines = listOf(
                    EngineId.XRAY to "Xray-core",
                    EngineId.SING_BOX to "sing-box",
                    EngineId.AETHER to "Aether",
                    EngineId.WIREGUARD to "WireGuard",
                    EngineId.PG_CORE to "PG CORE"
                )
                engines.forEachIndexed { index, (id, label) ->
                    val available = coordinator.isEngineAvailable(id)
                    append(if (available) "●  " else "○  ")
                    append(label)
                    append("  ·  ")
                    append(if (available) "backend registered" else "not integrated")
                    if ((id == EngineId.WIREGUARD || id == EngineId.XRAY) && available) {
                        append("\n    Device traffic still needs real-device verification")
                    }
                    if (index != engines.lastIndex) append("\n")
                }
            }
        }
        engineCard.addView(engineStatus, marginParams(top = 10))
        page.addView(engineCard, marginParams(bottom = 14))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        connectButton = actionButton("Connect", true) { requestVpnConsentAndConnect() }.apply {
            textSize = 15f
        }
        disconnectButton = actionButton("Disconnect", false) { disconnect() }.apply {
            textSize = 15f
        }
        controls.addView(connectButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(6)
        })
        controls.addView(disconnectButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginStart = dp(6)
        })
        page.addView(controls, marginParams(bottom = 18))

        val footer = TextView(this).apply {
            text = "RØH VPN  •  Private by design  •  No fabricated endpoints"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(112, 132, 158))
        }
        page.addView(footer, matchWrap())
        setContentView(scroll)
        renderState()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun space(height: Int) = TextView(this).apply { this.height = dp(height) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedSurface(Color.rgb(17, 30, 49), dp(20), Color.rgb(39, 57, 81))
    }

    private fun roundedSurface(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun sectionHeading(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(Color.rgb(170, 190, 216))
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (primary) Color.rgb(5, 25, 40) else Color.rgb(225, 237, 251))
            background = roundedSurface(
                if (primary) Color.rgb(103, 232, 249) else Color.rgb(25, 43, 66),
                dp(14),
                if (primary) Color.rgb(103, 232, 249) else Color.rgb(53, 75, 103)
            )
            minHeight = dp(48)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onClick() }
        }

    private fun themedInput(label: String) = EditText(this).apply {
        hint = label
        maxLines = 1
        textSize = 14f
        setTextColor(Color.rgb(235, 243, 255))
        setHintTextColor(Color.rgb(119, 140, 167))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        background = roundedSurface(Color.rgb(10, 21, 36), dp(12), Color.rgb(45, 65, 91))
        setPadding(dp(12), dp(10), dp(12), dp(10))
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
        saveProfileButton.isEnabled = !operationInProgress && active == null && pendingConfiguration != null
        savedProfilesButton.isEnabled = !operationInProgress && active == null
        removeProfileButton.isEnabled = !operationInProgress && active == null
    }

    private fun saveCurrentProfile() {
        if (pendingConfiguration == null || operationInProgress) return
        val nameInput = EditText(this).apply {
            hint = "Profile name"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Save profile securely")
            .setMessage("The profile will be encrypted using Android Keystore.")
            .setView(nameInput)
            .setPositiveButton("Save") { _, _ -> saveProfileNamed(nameInput.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveProfileNamed(rawName: String) {
        val current = pendingConfiguration ?: return
        if (operationInProgress) return
        val name = rawName.trim().take(60)
        if (name.isBlank()) {
            lastStatus = "Enter a name before saving the profile."
            renderState()
            return
        }
        val safeName = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "saved" }
        val copy = current.copyOf()
        try {
            val fingerprint = ProfileCatalog.fingerprint(copy)
            val duplicateId = profileStore.listProfileIds().firstNotNullOfOrNull { id ->
                val stored = profileStore.load(id) ?: return@firstNotNullOfOrNull null
                val storedBytes = stored.json.toByteArray(Charsets.UTF_8)
                try {
                    if (ProfileCatalog.fingerprint(storedBytes) == fingerprint) id else null
                } finally {
                    storedBytes.fill(0)
                }
            }
            if (duplicateId != null) {
                lastStatus = "This exact configuration is already saved as $duplicateId."
            } else {
                val id = "profile-$safeName-${System.currentTimeMillis()}"
                profileStore.save(id, copy.toString(Charsets.UTF_8))
                lastStatus = "Saved encrypted profile: $name"
            }
        } catch (_: Exception) {
            lastStatus = "Could not save this profile securely."
        } finally {
            copy.fill(0)
        }
        renderState()
    }

    private fun showSavedProfiles() {
        val ids = try {
            profileStore.listProfileIds()
        } catch (_: Exception) {
            lastStatus = "Could not read saved profile index."
            renderState()
            return
        }
        if (ids.isEmpty()) {
            lastStatus = "No saved profiles yet. Import a profile, then choose Save profile securely."
            renderState()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Saved encrypted profiles")
            .setItems(ids.toTypedArray()) { _, which -> loadSavedProfile(ids[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadSavedProfile(id: String) {
        val stored = try {
            profileStore.load(id)
        } catch (_: Exception) {
            null
        }
        if (stored == null) {
            lastStatus = "Saved profile could not be decrypted; it may be corrupted or the Keystore key is unavailable."
            renderState()
            return
        }
        var candidate: ByteArray? = stored.json.toByteArray(Charsets.UTF_8)
        try {
            val bytes = candidate ?: return
            val classification = EngineConfigClassifier.classify(bytes)
            val engine = when (classification.format) {
                ImportedProfileFormat.WIREGUARD -> EngineId.WIREGUARD
                ImportedProfileFormat.XRAY_JSON -> {
                    if (BuildConfig.FLAVOR != "xray" ||
                        com.aminopy.roohvpn.core.XrayProfilePreflight.validate(bytes) != null) {
                        null
                    } else EngineId.XRAY
                }
                ImportedProfileFormat.SING_BOX_JSON -> {
                    if (BuildConfig.FLAVOR != "singbox" ||
                        com.aminopy.roohvpn.core.SingBoxProfilePreflight.validate(bytes) != null) {
                        null
                    } else EngineId.SING_BOX
                }
                else -> null
            }
            if (engine == null) {
                lastStatus = "Saved profile is unsupported or failed validation in this APK flavor."
            } else {
                pendingConfiguration?.fill(0)
                pendingConfiguration = bytes
                candidate = null
                pendingEngineId = engine
                lastStatus = "Loaded saved $engine profile into memory. It remains encrypted at rest."
            }
        } catch (_: Exception) {
            lastStatus = "Saved profile could not be parsed safely."
        } finally {
            candidate?.fill(0)
        }
        renderState()
    }

    private fun chooseSavedProfileToDelete() {
        val ids = try {
            profileStore.listProfileIds()
        } catch (_: Exception) {
            lastStatus = "Could not read saved profile index."
            renderState()
            return
        }
        if (ids.isEmpty()) {
            lastStatus = "There are no saved profiles to delete."
            renderState()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete saved profile")
            .setItems(ids.toTypedArray()) { _, which ->
                val selected = ids[which]
                AlertDialog.Builder(this)
                    .setMessage("Permanently delete $selected?")
                    .setPositiveButton("Delete") { _, _ ->
                        try {
                            profileStore.delete(selected)
                            lastStatus = "Deleted saved profile: $selected"
                        } catch (_: Exception) {
                            lastStatus = "Could not delete the saved profile."
                        }
                        renderState()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
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
                    if (BuildConfig.FLAVOR != "xray") {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "This APK packages sing-box; use the Xray-flavor APK for Xray profiles."
                        renderState()
                        return
                    }
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
                    if (BuildConfig.FLAVOR != "singbox") {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "This APK packages Xray; use the singbox-flavor APK for sing-box profiles."
                        renderState()
                        return
                    }
                    val failure = com.aminopy.roohvpn.core.SingBoxProfilePreflight.validate(importedBytes!!)
                    if (failure != null) {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "The sing-box JSON profile failed structural validation."
                        renderState()
                        return
                    }
                    pendingEngineId = EngineId.SING_BOX
                    lastStatus = "sing-box profile loaded in memory only; secrets are not saved."
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
                    lastStatus = if (BuildConfig.FLAVOR == "singbox") "Unsupported profile. Select sing-box JSON or a valid WireGuard profile." else "Unsupported profile. Select Xray JSON or a valid WireGuard profile."
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

        val sni = sniInput.text.toString().trim().ifEmpty { null }
        val cdnIp = cdnIpInput.text.toString().trim().ifEmpty { null }
        val host = hostInput.text.toString().trim().ifEmpty { null }
        val hasTransportOverrides = sni != null || cdnIp != null || host != null
        if (engine != EngineId.XRAY && hasTransportOverrides) {
            source.fill(0)
            lastStatus = "SNI/CDN overrides apply only to Xray profiles. Clear these fields for WireGuard."
            renderState()
            return
        }
        val options = if (engine == EngineId.XRAY) {
            com.aminopy.roohvpn.core.EngineLaunchOptions(
                xraySni = sni,
                xrayCdnEdgeIp = cdnIp,
                xrayHostHeader = host
            )
        } else {
            com.aminopy.roohvpn.core.EngineLaunchOptions()
        }

        operationInProgress = true
        lastStatus = "Starting $engine…"
        renderState()
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator.connect(engine, source, options)
            } catch (_: Exception) {
                source.fill(0)
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
