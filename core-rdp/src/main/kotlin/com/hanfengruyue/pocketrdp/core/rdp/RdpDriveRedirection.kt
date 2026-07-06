package com.hanfengruyue.pocketrdp.core.rdp

sealed interface RdpDriveRedirectionPlan {
    data object Disabled : RdpDriveRedirectionPlan
    data object NeedsAllFilesAccess : RdpDriveRedirectionPlan
    data class Ready(val path: String) : RdpDriveRedirectionPlan
    data class Unavailable(val reason: String) : RdpDriveRedirectionPlan
}

fun planRdpDriveRedirection(
    redirectFiles: Boolean,
    allFilesAccessGranted: Boolean,
    storageMounted: Boolean,
    externalStoragePath: String?,
    pathExists: (String) -> Boolean,
    pathIsDirectory: (String) -> Boolean,
    pathCanRead: (String) -> Boolean,
): RdpDriveRedirectionPlan {
    if (!redirectFiles) return RdpDriveRedirectionPlan.Disabled
    if (!allFilesAccessGranted) return RdpDriveRedirectionPlan.NeedsAllFilesAccess
    if (!storageMounted) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection requested but shared storage is not mounted",
        )
    }

    val path = externalStoragePath?.trim().orEmpty()
    if (path.isEmpty()) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection requested but shared storage path is unavailable",
        )
    }
    if (path.startsWith("content:", ignoreCase = true)) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection needs a filesystem path, not a content URI: $path",
        )
    }
    if (!pathExists(path)) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection path does not exist: $path",
        )
    }
    if (!pathIsDirectory(path)) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection path is not a directory: $path",
        )
    }
    if (!pathCanRead(path)) {
        return RdpDriveRedirectionPlan.Unavailable(
            "folder redirection path is not readable by PocketRDP: $path",
        )
    }

    return RdpDriveRedirectionPlan.Ready(path)
}
