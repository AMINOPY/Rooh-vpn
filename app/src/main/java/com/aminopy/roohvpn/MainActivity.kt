package com.aminopy.roohvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.aminopy.roohvpn.tor.TorSettings
import com.aminopy.roohvpn.tor.TorSettingsRepository
import com.aminopy.roohvpn.tor.TorProxyService
import com.aminopy.roohvpn.core.EngineFailure
import com.aminopy.roohvpn.core.BoundedProfileReader
import com.aminopy.roohvpn.core.EngineConfigClassifier
import com.aminopy.roohvpn.core.ImportedProfileFormat
import com.aminopy.roohvpn.core.EngineId
import com.aminopy.roohvpn.security.EncryptedProfileStore
import com.aminopy.roohvpn.profiles.ProfileCatalog
import com.aminopy.roohvpn.ui.theme.RoohThemeStore
import com.aminopy.roohvpn.ui.theme.RoohThemeProfiles
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Explicit-consent Xray/WireGuard profile connection screen; no endpoints or credentials are fabricated. */
class MainActivity : Activity() {
    private val themeStore by lazy { RoohThemeStore(applicationContext) }
    private val palette get() = themeStore.colors()
    private lateinit var status: TextView
    private lateinit var contentHost: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var navButtons: Map<String, Button>
    private var currentPage: String = "home"
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
    private lateinit var tlsRecordFragmentationSwitch: Switch
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingConfiguration: ByteArray? = null
    private var pendingEngineId: EngineId? = null
    private var operationInProgress = false
    private var lastStatus = when (BuildConfig.FLAVOR) {
        "singbox" -> "Import a sing-box JSON or WireGuard profile from a trusted provider."
        "multi" -> "Experimental multi-engine build: import Xray, sing-box, or WireGuard profiles."
        else -> "Import an Xray JSON or WireGuard profile from a trusted provider."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyThemeChrome()
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.backgroundBottom, palette.backgroundTop)
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
            setTextColor(palette.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        page.addView(brand, matchWrap())
        page.addView(space(8), matchWrap())

        val title = TextView(this).apply {
            text = "Your private\nconnection."
            textSize = 31f
            setLineSpacing(dp(1).toFloat(), 1.02f)
            setTextColor(palette.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        page.addView(title, matchWrap())
        val subtitle = TextView(this).apply {
            text = "Import a trusted profile, review its engine, then connect."
            textSize = 14f
            setTextColor(palette.secondaryText)
        }
        page.addView(subtitle, marginParams(top = 8, bottom = 20))

        val connectionCard = card()
        val statusHeading = TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(palette.text)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        connectionCard.addView(statusHeading, matchWrap())
        connectionCard.addView(status, marginParams(top = 10))
        page.addView(connectionCard, marginParams(bottom = 14))

        val actionsCard = card()
        actionsCard.addView(sectionHeading("PROFILE WORKSPACE"), matchWrap())
        val flavorLabel = when (BuildConfig.FLAVOR) {
            "singbox" -> "sing-box JSON + WireGuard"
            "multi" -> "Xray + sing-box + WireGuard (experimental)"
            else -> "Xray JSON + WireGuard"
        }
        val profileHint = TextView(this).apply {
            text = "Supported in this build: $flavorLabel. Profiles are not generated automatically."
            textSize = 13f
            setTextColor(palette.secondaryText)
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
            setTextColor(palette.secondaryText)
        }
        optionsCard.addView(optionsHint, marginParams(top = 6, bottom = 8))
        sniInput = themedInput("TLS SNI / REALITY serverName")
        cdnIpInput = themedInput("CDN edge IP (IPv4 / IPv6)")
        hostInput = themedInput("HTTP Host / gRPC authority / transport host")
        optionsCard.addView(sniInput, matchWrap())
        optionsCard.addView(cdnIpInput, marginParams(top = 5))
        optionsCard.addView(hostInput, marginParams(top = 5))
        tlsRecordFragmentationSwitch = Switch(this).apply {
            text = "TLS record fragmentation (sing-box only)"
            textSize = 13f
            setTextColor(palette.text)
            isChecked = false
        }
        optionsCard.addView(tlsRecordFragmentationSwitch, marginParams(top = 12))
        optionsCard.addView(TextView(this).apply {
            text = "Optional. Adds record_fragment to TLS-enabled sing-box outbounds. May reduce performance and may not work with every profile or transport."
            textSize = 11f
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, marginParams(top = 3))

        val engineCard = card()
        engineCard.addView(sectionHeading("ENGINE STATUS"), matchWrap())
        engineStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
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
            setTextColor(palette.secondaryText)
        }
        page.addView(footer, matchWrap())
        homeScroll = scroll
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.backgroundTop)
        }
        contentHost = FrameLayout(this)
        shell.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = glassSurface(palette.card, dp(18), palette.stroke)
        }
        val destinations = listOf(
            "home" to "⌂\nHome",
            "profiles" to "▤\nProfiles",
            "tools" to "⌁\nTools",
            "engines" to "◈\nEngines",
            "settings" to "⚙\nSettings"
        )
        val buttons = linkedMapOf<String, Button>()
        destinations.forEach { (key, label) ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 10f
                minHeight = dp(48)
                minWidth = 0
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setTextColor(palette.secondaryText)
                background = roundedSurface(Color.TRANSPARENT, dp(12), Color.TRANSPARENT)
                setOnClickListener { navigateTo(key) }
            }
            buttons[key] = button
            nav.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        navButtons = buttons
        shell.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })
        setContentView(shell)
        navigateTo("home")
        renderState()
    }

    @Deprecated("Use the navigation bar to move between app sections.")
    override fun onBackPressed() {
        if (currentPage != "home") {
            navigateTo("home")
        } else {
            super.onBackPressed()
        }
    }

    private fun navigateTo(destination: String) {
        currentPage = destination
        contentHost.removeAllViews()
        val content: View = when (destination) {
            "home" -> homeScroll
            "profiles" -> buildProfilesPage()
            "tools" -> buildToolsPage()
            "engines" -> buildEnginesPage()
            "settings" -> buildSettingsPage()
            else -> homeScroll
        }
        contentHost.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        navButtons.forEach { (key, button) ->
            val selected = key == destination
            button.setTextColor(if (selected) palette.accent else palette.secondaryText)
            button.background = roundedSurface(
                if (selected) palette.activeCard else Color.TRANSPARENT,
                dp(12),
                if (selected) palette.stroke else Color.TRANSPARENT
            )
        }
        renderState()
    }

    private fun pageScaffold(titleText: String, subtitleText: String): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.backgroundBottom)
            )
        }
        page.addView(TextView(this).apply {
            text = "R Ø H   /   VPN"
            textSize = 12f
            letterSpacing = 0.15f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        page.addView(TextView(this).apply {
            text = titleText
            textSize = 28f
            setTextColor(palette.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, marginParams(top = 10))
        page.addView(TextView(this).apply {
            text = subtitleText
            textSize = 13f
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, marginParams(top = 6, bottom = 18))
        return page
    }

    private fun pageScroll(page: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun infoCard(titleText: String, bodyText: String): LinearLayout = card().apply {
        addView(sectionHeading(titleText), matchWrap())
        addView(TextView(this@MainActivity).apply {
            text = bodyText
            textSize = 14f
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(4).toFloat(), 1f)
        }, marginParams(top = 9))
    }

    private fun buildProfilesPage(): View {
        val page = pageScaffold("Profiles", "Import, load, store and remove your own trusted VPN profiles.")
        val savedCount = try {
            profileStore.listProfileIds().size
        } catch (_: Exception) {
            null
        }
        val profileSummary = buildString {
            append(if (pendingEngineId != null && pendingConfiguration != null) {
                "Loaded in memory: $pendingEngineId\n$lastStatus"
            } else {
                "No profile loaded. Choose a configuration file supplied by a provider you trust."
            })
            append("\n\nSaved encrypted profiles: ")
            append(savedCount?.toString() ?: "unavailable")
        }
        page.addView(infoCard("CURRENT PROFILE", profileSummary), marginParams(bottom = 12))
        page.addView(actionButton("＋   Import profile", true) { chooseProfile() }, marginParams(bottom = 8))
        page.addView(actionButton("▤   Browse saved profiles", false) { showSavedProfiles() }, marginParams(bottom = 8))
        page.addView(actionButton("▣   Save current profile securely", false) { saveCurrentProfile() }, marginParams(bottom = 8))
        page.addView(actionButton("⌫   Delete a saved profile", false) { chooseSavedProfileToDelete() }, marginParams(bottom = 12))
        page.addView(infoCard(
            "PROFILE SAFETY",
            "Saved profiles are encrypted at rest with Android Keystore. Unsupported formats are not silently passed to another engine. Never import a profile from a source you do not trust."
        ), marginParams(bottom = 12))
        return pageScroll(page)
    }

    private fun buildToolsPage(): View {
        val page = pageScaffold("Tools", "Shortcuts and transparent diagnostics for this build.")
        page.addView(infoCard(
            "QUICK ACTIONS",
            "Use a configuration from your own trusted provider. The app does not invent servers or credentials."
        ), marginParams(bottom = 12))
        page.addView(actionButton("＋   Import a configuration", true) { chooseProfile() }, marginParams(bottom = 8))
        page.addView(actionButton("▤   Open saved configurations", false) { showSavedProfiles() }, marginParams(bottom = 8))
        page.addView(actionButton("◈   View engine readiness", false) { navigateTo("engines") }, marginParams(bottom = 8))
        page.addView(actionButton("✓   Run local profile preflight", true) { runLocalProfilePreflight() }, marginParams(bottom = 12))
        page.addView(infoCard(
            "DIAGNOSTIC HONESTY",
            "TCP reachability is not a VPN handshake or a speed test. This page does not report fake ping, throughput, DNS safety, or IP-leak results. Tunnel verification requires real measurements and a device test."
        ), marginParams(bottom = 12))
        page.addView(infoCard(
            "NOT YET VERIFIED",
            "Per-app routing, tunnel chaining, automated speed tests, MTU discovery and DNS/IP leak checks are not represented as completed features in this screen."
        ), marginParams(bottom = 12))
        return pageScroll(page)
    }

    /**
     * Checks only local profile format and backend registration. This deliberately
     * does not claim that a remote server, tunnel, DNS path, or leak test succeeded.
     */
    private fun runLocalProfilePreflight() {
        val config = pendingConfiguration
        val selectedEngine = pendingEngineId
        val lines = mutableListOf<String>()
        lines += "LOCAL PROFILE PREFLIGHT"
        lines += ""

        if (config == null || selectedEngine == null) {
            lines += "✗ No profile is currently loaded."
            lines += "Import a supported configuration from a provider you trust, then run this check again."
            lines += ""
            lines += "No network traffic was tested."
            AlertDialog.Builder(this)
                .setTitle("Local profile preflight")
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val classification = EngineConfigClassifier.classify(config)
        val coordinator = (application as RoohVpnApplication).connectionCoordinator
        val backendRegistered = coordinator.isEngineAvailable(selectedEngine)
        lines += "Loaded profile: yes (${config.size} bytes)"
        lines += "Selected engine: $selectedEngine"
        lines += "Detected format: ${classification.format}"
        lines += if (classification.engineId == null) {
            "Format-to-engine mapping: unresolved"
        } else if (classification.engineId == selectedEngine) {
            "Format-to-engine mapping: matches"
        } else {
            "Format-to-engine mapping: mismatch (detected ${classification.engineId})"
        }
        lines += if (backendRegistered) {
            "Backend registration: available"
        } else {
            "Backend registration: unavailable"
        }

        // Reuse the bounded structural checks used by the import/connect flow.
        // This cannot prove native-engine compatibility or remote reachability.
        val structuralFailure = when (classification.format) {
            ImportedProfileFormat.XRAY_JSON ->
                if (BuildConfig.FLAVOR == "xray" || BuildConfig.FLAVOR == "multi") com.aminopy.roohvpn.core.XrayProfilePreflight.validate(config)
                else EngineFailure("WRONG_APK_FLAVOR", "Xray profiles require the Xray-flavor APK.", false)
            ImportedProfileFormat.SING_BOX_JSON ->
                if (BuildConfig.FLAVOR == "singbox" || BuildConfig.FLAVOR == "multi") com.aminopy.roohvpn.core.SingBoxProfilePreflight.validate(config)
                else EngineFailure("WRONG_APK_FLAVOR", "sing-box profiles require the sing-box-flavor APK.", false)
            ImportedProfileFormat.WIREGUARD -> null // Native parser validates at connect time.
            else -> null
        }
        lines += when {
            structuralFailure != null -> "Structural preflight: failed (" + structuralFailure.code + ")"
            classification.format == ImportedProfileFormat.XRAY_JSON ||
                classification.format == ImportedProfileFormat.SING_BOX_JSON ->
                "Structural preflight: passed (native validation still required)"
            classification.format == ImportedProfileFormat.WIREGUARD ->
                "Structural preflight: deferred to WireGuard backend"
            else -> "Structural preflight: unavailable for this format"
        }

        when (classification.format) {
            ImportedProfileFormat.SHARE_LINK -> lines += "Action needed: share links are detected but are not converted into a runnable engine configuration here."
            ImportedProfileFormat.UNKNOWN -> lines += "Action needed: the imported format could not be identified; verify it with your provider."
            else -> if (classification.engineId != selectedEngine) {
                lines += "Action needed: re-import a configuration matching the selected engine."
            }
        }
        lines += ""
        lines += "LIMIT: this is a local preflight only. It does not connect to a server or test TCP/UDP, DNS, IPv4/IPv6, speed, or IP leaks."
        AlertDialog.Builder(this)
            .setTitle("Local profile preflight")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildEnginesPage(): View {
        val page = pageScaffold("Engines", "Registered backends are not automatically production-ready tunnels.")
        val coordinator = (application as RoohVpnApplication).connectionCoordinator
        val engines = listOf(
            EngineId.XRAY to "Xray-core",
            EngineId.SING_BOX to "sing-box",
            EngineId.WIREGUARD to "WireGuard",
            EngineId.AETHER to "Aether",
            EngineId.PG_CORE to "PG CORE"
        )
        engines.forEach { (id, label) ->
            val registered = coordinator.isEngineAvailable(id)
            val details = when (id) {
                EngineId.PG_CORE ->
                    "PG CORE Android module and flow-selection primitives are included. " +
                    "A real TUN-to-engine packet-forwarding backend and cooperating path endpoints are not installed, " +
                    "so PG CORE remains disabled for traffic."
                EngineId.AETHER -> if (registered) {
                    "Pinned Aether Rust engine + local SOCKS5 listener + in-process HEV TUN bridge are packaged. " +
                    "Real-device traffic, DNS, IPv6, reconnect and leak verification are still required."
                } else {
                    "Aether native runtime is not packaged for this APK ABI; it is kept unavailable rather than shown as a working engine."
                }
                else ->
                    (if (registered) "Backend registered." else "Backend not integrated in this build.") +
                    "\nReal-device TCP/UDP/DNS forwarding and leak tests still need verification."
            }
            page.addView(infoCard(label, details), marginParams(bottom = 10))
        }
        if (coordinator.isEngineAvailable(EngineId.AETHER)) {
            page.addView(
                actionButton("Connect with Aether", true) { prepareAetherConnection() },
                marginParams(top = 4, bottom = 10)
            )
        }
        page.addView(actionButton("←   Back to Home", false) { navigateTo("home") }, marginParams(top = 4))
        return pageScroll(page)
    }

    private fun buildSettingsPage(): View {
        val page = pageScaffold("Settings", "Configure transport behavior and privacy options.")
        page.addView(infoCard(
            "TRANSPORT OVERRIDES",
            "Keep these blank unless your provider explicitly supplied the values. Incorrect overrides can prevent a connection."
        ), marginParams(bottom = 12))
        page.addView(themeSettingsCard(), marginParams(bottom = 12))
        page.addView(optionsCardForSettings(), marginParams(bottom = 12))
        page.addView(buildTorSettingsCard(), marginParams(bottom = 12))
        page.addView(infoCard(
            "PRIVACY & SECURITY",
            "Saved profiles use Android Keystore encryption. Tor currently exposes a loopback SOCKS proxy only; it does not silently replace the device VPN or route every app through Tor."
        ), marginParams(bottom = 12))
        page.addView(actionButton("←   Back to Home", false) { navigateTo("home") }, marginParams(top = 4))
        return pageScroll(page)
    }

    private fun applyThemeChrome() {
        window.statusBarColor = palette.backgroundTop
        window.navigationBarColor = palette.backgroundTop
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun glassSurface(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(withAlpha(fill, palette.glassAlpha), fill)
        ).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            (Color.alpha(color) * alpha / 255).coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color)
        )

    private fun themeSettingsCard(): LinearLayout {
        val card = card()
        card.addView(sectionHeading("APPEARANCE · LIQUID GLASS"), matchWrap())
        card.addView(TextView(this).apply {
            text = "Android-only appearance controls inspired by the ZX theme system. Changes affect the app UI only; VPN engines and connection behavior are unchanged."
            textSize = 12f
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, marginParams(top = 7, bottom = 12))

        val currentProfile = RoohThemeProfiles.find(themeStore.profileId)
        card.addView(actionButton("Theme profile · ${currentProfile.name}", false) {
            val names = RoohThemeProfiles.all.map { it.name }.toTypedArray()
            val selected = RoohThemeProfiles.all.indexOfFirst { it.id == themeStore.profileId }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Choose a theme")
                .setSingleChoiceItems(names, selected) { dialog, which ->
                    themeStore.profileId = RoohThemeProfiles.all[which].id
                    themeStore.accentOverride = null
                    dialog.dismiss()
                    applyThemeChrome()
                    navigateTo("settings")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, marginParams(bottom = 8))

        card.addView(actionButton("Customize accent color", false) {
            val choices = listOf(
                "Iris" to 0xFF5646D6.toInt(), "Ocean" to 0xFF1E63D0.toInt(),
                "Teal" to 0xFF00897E.toInt(), "Forest" to 0xFF2E7D32.toInt(),
                "Amber" to 0xFFB4690E.toInt(), "Rose" to 0xFFC4326B.toInt(),
                "Cyan" to 0xFF00838F.toInt(), "Grape" to 0xFF8E24AA.toInt()
            )
            AlertDialog.Builder(this)
                .setTitle("Accent color")
                .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                    themeStore.accentOverride = choices[which].second
                    applyThemeChrome()
                    navigateTo("settings")
                }
                .setNeutralButton("Use profile default") { _, _ ->
                    themeStore.accentOverride = null
                    applyThemeChrome()
                    navigateTo("settings")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, marginParams(bottom = 8))

        fun appearanceSwitch(label: String, checked: Boolean, save: (Boolean) -> Unit) {
            val toggle = Switch(this).apply {
                text = label
                textSize = 13f
                setTextColor(palette.text)
                isChecked = checked
                setOnCheckedChangeListener { _, value ->
                    save(value)
                    applyThemeChrome()
                    navigateTo("settings")
                }
            }
            card.addView(toggle, marginParams(top = 4))
        }
        appearanceSwitch("Dark appearance", themeStore.darkMode) { themeStore.darkMode = it }
        appearanceSwitch("AMOLED black background", themeStore.amoled) { themeStore.amoled = it }
        appearanceSwitch("Reduce motion", themeStore.reduceMotion) { themeStore.reduceMotion = it }

        card.addView(TextView(this).apply {
            text = "Glass intensity · ${themeStore.glassLevel}%"
            textSize = 12f
            setTextColor(palette.secondaryText)
        }, marginParams(top = 12))
        val intensity = SeekBar(this).apply {
            max = 80
            progress = (themeStore.glassLevel - 20).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    themeStore.glassLevel = (seekBar?.progress ?: 52) + 20
                    applyThemeChrome()
                    navigateTo("settings")
                }
            })
        }
        card.addView(intensity, marginParams(top = 2))
        return card
    }

    private fun buildTorSettingsCard(): LinearLayout {
        val card = card()
        val repository = TorSettingsRepository(applicationContext)
        var settings = repository.load()
        card.addView(sectionHeading("TOR CORE"), matchWrap())
        card.addView(TextView(this).apply {
            text = "Tor is a separate local SOCKS service. Start it here, then use 127.0.0.1 and the displayed port in a compatible proxy client. Full-device Tor routing is not enabled."
            textSize = 12f
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, marginParams(top = 7, bottom = 12))

        fun spinner(values: List<String>, selected: String): Spinner {
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, values).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(values.indexOf(selected).coerceAtLeast(0))
            }
        }
        fun field(hintText: String, value: String, multiline: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(value)
                textSize = 13f
                setTextColor(palette.text)
                setHintTextColor(palette.secondaryText)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else InputType.TYPE_CLASS_TEXT
                isSingleLine = !multiline
                if (multiline) { minLines = 3; maxLines = 6; gravity = Gravity.TOP }
                background = glassSurface(palette.input, dp(12), palette.stroke)
            }
        }
        fun toggle(label: String, checked: Boolean): Switch = Switch(this).apply {
            text = label
            textSize = 13f
            setTextColor(palette.text)
            isChecked = checked
        }

        val mode = spinner(listOf("none", "default", "own"), settings.bridgeMode)
        val transport = spinner(listOf("obfs4", "obfs3", "scramblesuit", "meek_lite", "snowflake", "conjure", "webtunnel", "vanilla"), settings.bridgeTransport)
        val ownBridges = field("Custom Tor Bridge lines (one per row)", settings.ownBridges, true)
        val enabledBridges = field("Selected default bridge lines (optional; one per row)", settings.enabledBridges, true)
        val newCircuit = field("New circuit period (seconds)", settings.newCircuitPeriodSeconds.toString())
        val maxDirty = field("Maximum circuit dirtiness (seconds)", settings.maxCircuitDirtinessSeconds.toString())
        val socksPort = field("Local SOCKS port (1024–65535)", settings.socksPort.toString())
        val entry = field("Entry node fingerprints / country codes", settings.entryNodes)
        val exit = field("Exit node fingerprints / country codes", settings.exitNodes)
        val excluded = field("Excluded countries / fingerprints", settings.excludeNodes)
        val excludedExit = field("Excluded exit nodes", settings.excludeExitNodes)
        val ipv4 = toggle("Enable IPv4", settings.ipv4)
        val ipv6 = toggle("Enable IPv6", settings.ipv6)
        val padding = toggle("Tor connection padding", settings.connectionPadding)
        val reducedPadding = toggle("Reduced connection padding", settings.reducedConnectionPadding)
        val avoidDisk = toggle("Avoid disk writes", settings.avoidDiskWrites)
        val distinct = toggle("Enforce distinct relay subnets", settings.enforceDistinctSubnets)
        val strict = toggle("Strict node selection", settings.strictNodes)
        val isolateAddress = toggle("Isolate destination address", settings.isolateDestAddr)
        val isolatePort = toggle("Isolate destination port", settings.isolateDestPort)

        card.addView(TextView(this).apply { text = "Bridge mode"; setTextColor(palette.secondaryText); textSize = 12f }, marginParams(top = 4))
        card.addView(mode, marginParams(top = 4))
        card.addView(TextView(this).apply { text = "Bridge transport"; setTextColor(palette.secondaryText); textSize = 12f }, marginParams(top = 10))
        card.addView(transport, marginParams(top = 4))
        card.addView(ownBridges, marginParams(top = 8))
        card.addView(enabledBridges, marginParams(top = 6))
        listOf(newCircuit, maxDirty, socksPort, entry, exit, excluded, excludedExit).forEach { card.addView(it, marginParams(top = 6)) }
        listOf(ipv4, ipv6, padding, reducedPadding, avoidDisk, distinct, strict, isolateAddress, isolatePort).forEach {
            card.addView(it, marginParams(top = 3))
        }

        val save = actionButton("Save Tor settings", true) {
            try {
                settings = settings.copy(
                    bridgeMode = mode.selectedItem.toString(),
                    bridgeTransport = transport.selectedItem.toString(),
                    ownBridges = ownBridges.text.toString(),
                    enabledBridges = enabledBridges.text.toString(),
                    newCircuitPeriodSeconds = newCircuit.text.toString().toIntOrNull() ?: settings.newCircuitPeriodSeconds,
                    maxCircuitDirtinessSeconds = maxDirty.text.toString().toIntOrNull() ?: settings.maxCircuitDirtinessSeconds,
                    socksPort = socksPort.text.toString().toIntOrNull() ?: settings.socksPort,
                    entryNodes = entry.text.toString(),
                    exitNodes = exit.text.toString(),
                    excludeNodes = excluded.text.toString(),
                    excludeExitNodes = excludedExit.text.toString(),
                    ipv4 = ipv4.isChecked,
                    ipv6 = ipv6.isChecked,
                    connectionPadding = padding.isChecked,
                    reducedConnectionPadding = reducedPadding.isChecked,
                    avoidDiskWrites = avoidDisk.isChecked,
                    enforceDistinctSubnets = distinct.isChecked,
                    strictNodes = strict.isChecked,
                    isolateDestAddr = isolateAddress.isChecked,
                    isolateDestPort = isolatePort.isChecked
                )
                repository.save(settings)
                lastStatus = "Tor settings saved. Restart Tor to apply changes."
                renderState()
            } catch (e: Exception) {
                lastStatus = "Tor settings were not saved: ${e.message ?: "invalid value"}"
                renderState()
            }
        }
        card.addView(save, marginParams(top = 12))
        card.addView(actionButton("Start Tor SOCKS", false) {
            val coordinator = (application as RoohVpnApplication).connectionCoordinator
            if (coordinator.activeEngine() != null || coordinator.isOperationInProgress()) {
                lastStatus = "Disconnect the active VPN engine before starting Tor. Tor's native sockets are not protected from that VPN TUN yet."
                renderState()
            } else try {
                settings = settings.copy(
                    bridgeMode = mode.selectedItem.toString(),
                    bridgeTransport = transport.selectedItem.toString(),
                    ownBridges = ownBridges.text.toString(),
                    enabledBridges = enabledBridges.text.toString(),
                    newCircuitPeriodSeconds = newCircuit.text.toString().toIntOrNull() ?: settings.newCircuitPeriodSeconds,
                    maxCircuitDirtinessSeconds = maxDirty.text.toString().toIntOrNull() ?: settings.maxCircuitDirtinessSeconds,
                    socksPort = socksPort.text.toString().toIntOrNull() ?: settings.socksPort,
                    entryNodes = entry.text.toString(),
                    exitNodes = exit.text.toString(),
                    excludeNodes = excluded.text.toString(),
                    excludeExitNodes = excludedExit.text.toString(),
                    ipv4 = ipv4.isChecked,
                    ipv6 = ipv6.isChecked,
                    connectionPadding = padding.isChecked,
                    reducedConnectionPadding = reducedPadding.isChecked,
                    avoidDiskWrites = avoidDisk.isChecked,
                    enforceDistinctSubnets = distinct.isChecked,
                    strictNodes = strict.isChecked,
                    isolateDestAddr = isolateAddress.isChecked,
                    isolateDestPort = isolatePort.isChecked
                )
                repository.save(settings)
                lastStatus = "Starting Tor…"
                renderState()
                worker.execute {
                    val started = TorProxyService.start(applicationContext)
                    runOnUiThread {
                        lastStatus = if (started) "Tor SOCKS is ready at 127.0.0.1:${settings.socksPort}. Bootstrap may still be in progress."
                            else "Tor did not open its local SOCKS listener. Check native runtime and bridge settings."
                        renderState()
                    }
                }
            } catch (e: Exception) {
                lastStatus = "Tor could not start: ${e.message ?: "invalid settings"}"
                renderState()
            }
        }, marginParams(top = 8))
        card.addView(actionButton("Stop Tor", false) {
            worker.execute {
                val stopped = TorProxyService.stop(applicationContext)
                runOnUiThread {
                    lastStatus = if (stopped) "Tor stopped." else "Tor shutdown could not be confirmed."
                    renderState()
                }
            }
        }, marginParams(top = 8))
        return card
    }

    private fun optionsCardForSettings(): LinearLayout {
        val options = card()
        listOf(sniInput, cdnIpInput, hostInput).forEach { field ->
            (field.parent as? android.view.ViewGroup)?.removeView(field)
        }
        (tlsRecordFragmentationSwitch.parent as? android.view.ViewGroup)?.removeView(tlsRecordFragmentationSwitch)
        options.addView(sectionHeading("OPTIONAL VALUES"), matchWrap())
        options.addView(sniInput, marginParams(top = 8))
        options.addView(cdnIpInput, marginParams(top = 6))
        options.addView(hostInput, marginParams(top = 6))
        options.addView(tlsRecordFragmentationSwitch, marginParams(top = 12))
        options.addView(actionButton("Clear optional values", false) {
            sniInput.text.clear()
            cdnIpInput.text.clear()
            hostInput.text.clear()
            lastStatus = "Optional transport overrides cleared."
            renderState()
        }, marginParams(top = 10))
        return options
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
        background = glassSurface(palette.card, dp(22), palette.stroke)
        elevation = dp(2).toFloat()
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
        setTextColor(palette.secondaryText)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (primary) palette.onAccent else palette.text)
            background = roundedSurface(
                if (primary) palette.accent else palette.activeCard,
                dp(14),
                if (primary) palette.accent else palette.stroke
            )
            minHeight = dp(48)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onClick() }
        }

    private fun themedInput(label: String) = EditText(this).apply {
        hint = label
        maxLines = 1
        textSize = 14f
        setTextColor(palette.text)
        setHintTextColor(palette.secondaryText)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        background = glassSurface(palette.input, dp(14), palette.stroke)
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

        // Keep stateful section summaries in sync after imports, saves, loads, and connection changes.
        // Rebuild only pages that display coordinator/profile state; Settings owns live EditText views.
        if (::contentHost.isInitialized && contentHost.childCount > 0 &&
            (currentPage == "profiles" || currentPage == "engines")) {
            val refreshedPage = if (currentPage == "profiles") buildProfilesPage() else buildEnginesPage()
            contentHost.removeAllViews()
            contentHost.addView(refreshedPage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
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
                    if ((BuildConfig.FLAVOR != "xray" && BuildConfig.FLAVOR != "multi") ||
                        com.aminopy.roohvpn.core.XrayProfilePreflight.validate(bytes) != null) {
                        null
                    } else EngineId.XRAY
                }
                ImportedProfileFormat.SING_BOX_JSON -> {
                    if ((BuildConfig.FLAVOR != "singbox" && BuildConfig.FLAVOR != "multi") ||
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
                    if (BuildConfig.FLAVOR != "xray" && BuildConfig.FLAVOR != "multi") {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "This APK packages sing-box; use the Xray or experimental multi-engine APK for Xray profiles."
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
                    if (BuildConfig.FLAVOR != "singbox" && BuildConfig.FLAVOR != "multi") {
                        importedBytes!!.fill(0)
                        importedBytes = null
                        lastStatus = "This APK packages Xray; use the sing-box or experimental multi-engine APK for sing-box profiles."
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

    private fun prepareAetherConnection() {
        if (operationInProgress || !(application as RoohVpnApplication).connectionCoordinator
                .isEngineAvailable(EngineId.AETHER)) return
        pendingConfiguration?.fill(0)
        pendingConfiguration = ByteArray(0)
        pendingEngineId = EngineId.AETHER
        lastStatus = "Starting Aether with its built-in WARP profile…"
        requestVpnConsentAndConnect()
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
        if (TorProxyService.isActive()) {
            source.fill(0)
            lastStatus = "Stop the Tor local proxy before starting a VPN engine. Tor sockets are not yet protected from the VPN TUN."
            renderState()
            return
        }

        val sni = sniInput.text.toString().trim().ifEmpty { null }
        val cdnIp = cdnIpInput.text.toString().trim().ifEmpty { null }
        val host = hostInput.text.toString().trim().ifEmpty { null }
        val hasTransportOverrides = sni != null || cdnIp != null || host != null
        if (engine != EngineId.XRAY && engine != EngineId.SING_BOX && hasTransportOverrides) {
            source.fill(0)
            lastStatus = "SNI/CDN overrides are supported only for Xray and sing-box profiles."
            renderState()
            return
        }
        val options = when (engine) {
            EngineId.XRAY -> com.aminopy.roohvpn.core.EngineLaunchOptions(
                xraySni = sni,
                xrayCdnEdgeIp = cdnIp,
                xrayHostHeader = host
            )
            EngineId.SING_BOX -> com.aminopy.roohvpn.core.EngineLaunchOptions(
                singBoxSni = sni,
                singBoxCdnEdgeIp = cdnIp,
                singBoxHostHeader = host
            )
            else -> com.aminopy.roohvpn.core.EngineLaunchOptions()
        }

        var configurationToConnect = source
        if (tlsRecordFragmentationSwitch.isChecked) {
            if (engine != EngineId.SING_BOX) {
                source.fill(0)
                lastStatus = "TLS record fragmentation is currently available only for sing-box profiles."
                renderState()
                return
            }
            val fragmented = com.aminopy.roohvpn.core.SingBoxTlsFragmentation
                .enableRecordFragmentation(source)
            if (fragmented == null) {
                source.fill(0)
                lastStatus = "No enabled TLS outbound was found. Check the profile or turn fragmentation off."
                renderState()
                return
            }
            source.fill(0)
            configurationToConnect = fragmented.configuration
            lastStatus = "TLS record fragmentation enabled for ${fragmented.modifiedOutbounds} TLS outbound(s)."
        }

        operationInProgress = true
        if (!tlsRecordFragmentationSwitch.isChecked) lastStatus = "Starting $engine…"
        renderState()
        worker.execute {
            val failure = try {
                (application as RoohVpnApplication).connectionCoordinator.connect(engine, configurationToConnect, options)
            } catch (_: Exception) {
                EngineFailure("CONNECTION_FAILED", "Could not start the VPN connection.", true)
            } finally {
                configurationToConnect.fill(0)
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
