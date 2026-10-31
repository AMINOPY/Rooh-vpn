package com.aminopy.roohvpn.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoohThemeProfilesTest {
    @Test
    fun profileIdsAreUniqueAndStable() {
        val ids = RoohThemeProfiles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(11, ids.size)
        assertEquals("aurora", ids.first())
    }

    @Test
    fun allProfilesExposeRequiredVisualTokens() {
        RoohThemeProfiles.all.forEach { profile ->
            assertTrue(profile.name.isNotBlank())
            assertTrue(profile.accent != 0)
            assertTrue(profile.backgroundTop != 0)
            assertTrue(profile.backgroundBottom != 0)
            assertTrue(profile.card != 0)
        }
    }

    @Test
    fun unknownProfileFallsBackToAurora() {
        assertNotNull(RoohThemeProfiles.find("not-a-real-theme"))
        assertEquals("aurora", RoohThemeProfiles.find("not-a-real-theme").id)
    }
}
