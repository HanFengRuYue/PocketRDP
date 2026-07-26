package com.hanfengruyue.pocketrdp.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesValidationTest {
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
}
