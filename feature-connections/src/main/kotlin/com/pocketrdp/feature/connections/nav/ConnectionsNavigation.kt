package com.pocketrdp.feature.connections.nav

object ConnectionsRoutes {
    const val LIST = "connections/list"
    const val EDIT_BASE = "connections/edit"
    const val EDIT_PATTERN = "$EDIT_BASE?id={id}"

    fun edit(id: Long? = null): String =
        if (id == null) EDIT_BASE else "$EDIT_BASE?id=$id"
}
