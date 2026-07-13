package com.hanfengruyue.pocketrdp.feature.session.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionImeVisibilityTest {
    @Test
    fun hiddenBridgeReleasesFocusBeforeItCanBeShownAgain() {
        val actions = mutableListOf<String>()

        applyImeVisibility(
            visible = false,
            requestFocus = { actions += "request-focus" },
            clearFocus = { actions += "clear-focus" },
            showKeyboard = { actions += "show-keyboard" },
            hideKeyboard = { actions += "hide-keyboard" },
        )
        applyImeVisibility(
            visible = true,
            requestFocus = { actions += "request-focus" },
            clearFocus = { actions += "clear-focus" },
            showKeyboard = { actions += "show-keyboard" },
            hideKeyboard = { actions += "hide-keyboard" },
        )

        assertEquals(
            listOf("clear-focus", "hide-keyboard", "request-focus", "show-keyboard"),
            actions,
        )
    }
}
