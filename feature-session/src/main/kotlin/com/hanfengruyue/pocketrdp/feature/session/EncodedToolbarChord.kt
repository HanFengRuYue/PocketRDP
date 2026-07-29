package com.hanfengruyue.pocketrdp.feature.session

private val toolbarChordIdPattern = Regex("^chord_([1-9][0-9]*)_(key_.+)$")

internal data class EncodedToolbarChord(
    val modifierMask: Int,
    val keyId: String,
)

internal fun encodeToolbarChordId(modifierMask: Int, keyId: String): String =
    "chord_${modifierMask}_$keyId"

internal fun decodeToolbarChordId(id: String): EncodedToolbarChord? {
    val match = toolbarChordIdPattern.matchEntire(id) ?: return null
    val modifierMask = match.groupValues[1].toIntOrNull() ?: return null
    return EncodedToolbarChord(modifierMask = modifierMask, keyId = match.groupValues[2])
}
