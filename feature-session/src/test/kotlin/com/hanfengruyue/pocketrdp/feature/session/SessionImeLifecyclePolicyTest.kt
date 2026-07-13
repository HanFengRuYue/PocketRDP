package com.hanfengruyue.pocketrdp.feature.session

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionImeLifecyclePolicyTest {
    @Test
    fun pausingSessionDismissesImeBeforeAppEntersBackground() {
        assertTrue(shouldDismissIme(Lifecycle.Event.ON_PAUSE))
        assertFalse(shouldDismissIme(Lifecycle.Event.ON_RESUME))
        assertFalse(shouldDismissIme(Lifecycle.Event.ON_STOP))
    }
}
