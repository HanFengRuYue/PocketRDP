package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpDriveRedirectionTest {
    @Test
    fun disabledWhenConnectionDoesNotRedirectFiles() {
        val plan = plan(redirectFiles = false)

        assertEquals(RdpDriveRedirectionPlan.Disabled, plan)
    }

    @Test
    fun waitsForAllFilesAccessBeforeConnecting() {
        val plan = plan(allFilesAccessGranted = false)

        assertEquals(RdpDriveRedirectionPlan.NeedsAllFilesAccess, plan)
    }

    @Test
    fun rejectsUnreadableStoragePath() {
        val plan = plan(pathCanRead = false)

        assertTrue(plan is RdpDriveRedirectionPlan.Unavailable)
        assertTrue((plan as RdpDriveRedirectionPlan.Unavailable).reason.contains("not readable"))
    }

    @Test
    fun acceptsReadableFilesystemDirectory() {
        val plan = plan(externalStoragePath = " /storage/emulated/0 ")

        assertEquals(RdpDriveRedirectionPlan.Ready("/storage/emulated/0"), plan)
    }

    private fun plan(
        redirectFiles: Boolean = true,
        allFilesAccessGranted: Boolean = true,
        storageMounted: Boolean = true,
        externalStoragePath: String? = "/storage/emulated/0",
        pathExists: Boolean = true,
        pathIsDirectory: Boolean = true,
        pathCanRead: Boolean = true,
    ): RdpDriveRedirectionPlan = planRdpDriveRedirection(
        redirectFiles = redirectFiles,
        allFilesAccessGranted = allFilesAccessGranted,
        storageMounted = storageMounted,
        externalStoragePath = externalStoragePath,
        pathExists = { pathExists },
        pathIsDirectory = { pathIsDirectory },
        pathCanRead = { pathCanRead },
    )
}
