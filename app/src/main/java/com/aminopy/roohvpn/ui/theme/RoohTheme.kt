package com.aminopy.roohvpn.ui.theme

import android.content.Context
import android.graphics.Color

data class RoohThemeProfile(
    val id: String,
    val name: String,
    val accent: Int,
    val connected: Int,
    val download: Int,
    val upload: Int,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val card: Int,
    val activeCard: Int,
)

object RoohThemeProfiles {
    val all = listOf(
        RoohThemeProfile("aurora", "Aurora", 0xFF12A594.toInt(), 0xFF7C5CFF.toInt(), 0xFF4FE3D8.toInt(), 0xFFB49BFF.toInt(), 0xFF071A20.toInt(), 0xFF102B32.toInt(), 0xFF143238.toInt(), 0xFF174B49.toInt()),
        RoohThemeProfile("ember", "Ember", 0xFFE07A1F.toInt(), 0xFFC0392B.toInt(), 0xFFFFB870.toInt(), 0xFFFF8A65.toInt(), 0xFF1D100A.toInt(), 0xFF351B12.toInt(), 0xFF382217.toInt(), 0xFF59301D.toInt()),
        RoohThemeProfile("midnight", "Midnight", 0xFF2563EB.toInt(), 0xFF1E3A8A.toInt(), 0xFF7FB2FF.toInt(), 0xFF9BC4FF.toInt(), 0xFF030712.toInt(), 0xFF0B1730.toInt(), 0xFF101D33.toInt(), 0xFF172D4D.toInt()),
        RoohThemeProfile("sakura", "Sakura", 0xFFD9407A.toInt(), 0xFF8E2751.toInt(), 0xFFFFA8C5.toInt(), 0xFFFFC2D6.toInt(), 0xFF1C0A14.toInt(), 0xFF351023.toInt(), 0xFF3B182B.toInt(), 0xFF57223C.toInt()),
        RoohThemeProfile("citrus", "Citrus", 0xFF9BC91F.toInt(), 0xFF3F6212.toInt(), 0xFFC7F24E.toInt(), 0xFF8FD14F.toInt(), 0xFF101505.toInt(), 0xFF202A0A.toInt(), 0xFF29340F.toInt(), 0xFF394A12.toInt()),
        RoohThemeProfile("mono", "Mono", 0xFF8A8A93.toInt(), 0xFF55555D.toInt(), 0xFFE4E4E7.toInt(), 0xFFB4B4BB.toInt(), 0xFF09090B.toInt(), 0xFF1A1A1C.toInt(), 0xFF232326.toInt(), 0xFF34343A.toInt()),
        RoohThemeProfile("neonlime", "Neon Lime", 0xFFC7F24E.toInt(), 0xFF4A7C0F.toInt(), 0xFFD4FF3F.toInt(), 0xFF9EE84A.toInt(), 0xFF050800.toInt(), 0xFF111900.toInt(), 0xFF171F08.toInt(), 0xFF27350A.toInt()),
        RoohThemeProfile("tokyo", "Tokyo", 0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF080A13.toInt(), 0xFF171A2B.toInt(), 0xFF1B2035.toInt(), 0xFF29304D.toInt()),
        RoohThemeProfile("solar", "Solar", 0xFFFFC107.toInt(), 0xFF8D6E00.toInt(), 0xFFFFD54F.toInt(), 0xFFFFB300.toInt(), 0xFF1B1504.toInt(), 0xFF332707.toInt(), 0xFF3B2E0A.toInt(), 0xFF55410C.toInt()),
        RoohThemeProfile("moss", "Moss", 0xFF10B981.toInt(), 0xFF065F46.toInt(), 0xFF6EE7A8.toInt(), 0xFF34D399.toInt(), 0xFF04130E.toInt(), 0xFF09241A.toInt(), 0xFF0E2D21.toInt(), 0xFF164431.toInt()),
        RoohThemeProfile("clay", "Clay", 0xFFD97757.toInt(), 0xFF8C4A34.toInt(), 0xFFE0A08A.toInt(), 0xFFD97757.toInt(), 0xFF180F0C.toInt(), 0xFF302019.toInt(), 0xFF39251D.toInt(), 0xFF523528.toInt()),
    )
    fun find(id: String?): RoohThemeProfile = all.firstOrNull { it.id == id } ?: all.first()
}

data class RoohThemeColors(
    val accent: Int,
    val onAccent: Int,
    val connected: Int,
    val download: Int,
    val upload: Int,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val card: Int,
    val activeCard: Int,
    val text: Int,
    val secondaryText: Int,
    val stroke: Int,
    val input: Int,
    val dark: Boolean,
    val glassAlpha: Int,
    val amoled: Boolean,
)

class RoohThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("rooh_android_appearance", Context.MODE_PRIVATE)

    var profileId: String
        get() = prefs.getString("profile", "aurora") ?: "aurora"
        set(value) { prefs.edit().putString("profile", value).apply() }

    var darkMode: Boolean
        get() = prefs.getBoolean("dark", true)
        set(value) { prefs.edit().putBoolean("dark", value).apply() }

    var amoled: Boolean
        get() = prefs.getBoolean("amoled", false)
        set(value) { prefs.edit().putBoolean("amoled", value).apply() }

    var accentOverride: Int?
        get() = prefs.getInt("accent_override", -1).takeIf { it != -1 }
        set(value) { prefs.edit().putInt("accent_override", value ?: -1).apply() }

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(value) { prefs.edit().putBoolean("reduce_motion", value).apply() }

    var glassLevel: Int
        get() = prefs.getInt("glass_level", 72).coerceIn(20, 100)
        set(value) { prefs.edit().putInt("glass_level", value.coerceIn(20, 100)).apply() }

    fun colors(): RoohThemeColors {
        val p = RoohThemeProfiles.find(profileId)
        val dark = darkMode
        val black = amoled && dark
        val alpha = (glassLevel * 255 / 100).coerceIn(40, 255)
        return RoohThemeColors(
            accent = accentOverride ?: p.accent,
            onAccent = if (Color.luminance(p.accent) > 0.55f) 0xFF10131A.toInt() else Color.WHITE,
            connected = p.connected,
            download = p.download,
            upload = p.upload,
            backgroundTop = if (black) Color.BLACK else if (dark) p.backgroundTop else 0xFFF2F5FA.toInt(),
            backgroundBottom = if (black) Color.BLACK else if (dark) p.backgroundBottom else 0xFFE6ECF5.toInt(),
            card = if (dark) p.card else 0xDFFFFFFF.toInt(),
            activeCard = if (dark) p.activeCard else 0xFFE1EAF5.toInt(),
            text = if (dark) 0xFFF1F5FC.toInt() else 0xFF172033.toInt(),
            secondaryText = if (dark) 0xFFB3C1D4.toInt() else 0xFF59677B.toInt(),
            stroke = if (dark) 0x66FFFFFF else 0x553B526D,
            input = if (dark) 0xFF0B1727.toInt() else 0xFFF8FAFD.toInt(),
            dark = dark,
            glassAlpha = alpha,
            amoled = black,
        )
    }
}
