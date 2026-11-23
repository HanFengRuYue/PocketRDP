package com.hanfengruyue.pocketrdp.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesValidationTest {
    @Test
    fun autoReconnectDefaultsToEnabled() {
        assertTrue(DEFAULT_AUTO_RECONNECT)
        assertTrue(AppPreferences().autoReconnect)
    }

    @Test
    fun simulatedCursorDefaultsToOneHundredFiftyPercent() {
        assertEquals(1.5f, DEFAULT_SIMULATED_CURSOR_SCALE)
    }

    @Test
    fun nonFiniteValuesFallBackToDefault() {
        assertEquals(0.7f, sanitizeFinitePreference(Float.NaN, 0.7f, 0f, 1f))
        assertEquals(0.7f, sanitizeFinitePreference(Float.POSITIVE_INFINITY, 0.7f, 0f, 1f))
        assertEquals(0.7f, sanitizeFinitePreference(Float.NEGATIVE_INFINITY, 0.7f, 0f, 1f))
        assertEquals(0.7f, sanitizeFinitePreference(null, 0.7f, 0f, 1f))
    }

    @Test
    fun finiteValuesAreClamped() {
        assertEquals(0f, sanitizeFinitePreference(-1f, 0.7f, 0f, 1f))
        assertEquals(0.4f, sanitizeFinitePreference(0.4f, 0.7f, 0f, 1f))
        assertEquals(1f, sanitizeFinitePreference(2f, 0.7f, 0f, 1f))
    }

    @Test
    fun languageTagSanitizationAcceptsSupportedTagsAndFallsBackToSystem() {
        assertEquals("zh-CN", sanitizeLanguageTag(" zh-CN "))
        assertEquals("en", sanitizeLanguageTag("en"))
        assertEquals("pt-BR", sanitizeLanguageTag("pt-BR"))
        assertEquals(LANGUAGE_SYSTEM, sanitizeLanguageTag(LANGUAGE_SYSTEM))
        assertEquals(LANGUAGE_SYSTEM, sanitizeLanguageTag(""))
        assertEquals(LANGUAGE_SYSTEM, sanitizeLanguageTag("   "))
        assertEquals(LANGUAGE_SYSTEM, sanitizeLanguageTag("zz-ZZ"))
        assertEquals(LANGUAGE_SYSTEM, sanitizeLanguageTag(null))
    }

    @Test
    fun functionToolbarQuickIdsDeduplicateAndRespectCap() {
        val oversized = (1..40).map { "key_$it" }
        val sanitized = sanitizeFunctionToolbarQuickIds(listOf(" mod_ctrl ", "mod_ctrl", "") + oversized)

        assertEquals(MAX_FUNCTION_TOOLBAR_QUICK_IDS, sanitized.size)
        assertEquals("mod_ctrl", sanitized.first())
        assertEquals(DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS, sanitizeFunctionToolbarQuickIds(emptyList()))
    }
}
