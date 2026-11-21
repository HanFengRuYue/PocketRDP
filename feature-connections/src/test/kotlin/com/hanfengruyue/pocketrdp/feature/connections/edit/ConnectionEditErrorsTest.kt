package com.hanfengruyue.pocketrdp.feature.connections.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionEditErrorsTest {
    @Test
    fun clearEditErrorsRemovesMatchingFieldErrorAndSaveFailure() {
        val initial = ConnectionEditUiState(
            isLoading = false,
            errors = listOf(
                ConnectionEditError.HOST_REQUIRED,
                ConnectionEditError.PORT_INVALID,
            ),
            failure = ConnectionEditFailure.SAVE_FAILED,
        )

        val updated = initial.clearEditErrors(ConnectionEditError.HOST_REQUIRED)

        assertEquals(listOf(ConnectionEditError.PORT_INVALID), updated.errors)
        assertNull(updated.failure)
    }

    @Test
    fun clearEditErrorsPreservesFatalLoadFailures() {
        val loadFailed = ConnectionEditUiState(
            isLoading = false,
            failure = ConnectionEditFailure.LOAD_FAILED,
        )
        val notFound = ConnectionEditUiState(
            isLoading = false,
            failure = ConnectionEditFailure.NOT_FOUND,
        )

        assertEquals(ConnectionEditFailure.LOAD_FAILED, loadFailed.clearEditErrors().failure)
        assertEquals(ConnectionEditFailure.NOT_FOUND, notFound.clearEditErrors().failure)
    }

    @Test
    fun consumeEditErrorsClearsValidationListAndSaveFailureOnly() {
        val initial = ConnectionEditUiState(
            isLoading = false,
            errors = listOf(
                ConnectionEditError.NAME_REQUIRED,
                ConnectionEditError.PASSWORD_REQUIRED,
            ),
            failure = ConnectionEditFailure.SAVE_FAILED,
        )

        val consumed = initial.consumeEditErrors()

        assertEquals(emptyList<ConnectionEditError>(), consumed.errors)
        assertNull(consumed.failure)

        val fatalConsumed = ConnectionEditUiState(
            isLoading = false,
            errors = listOf(ConnectionEditError.NAME_REQUIRED),
            failure = ConnectionEditFailure.LOAD_FAILED,
        ).consumeEditErrors()

        assertEquals(emptyList<ConnectionEditError>(), fatalConsumed.errors)
        assertEquals(ConnectionEditFailure.LOAD_FAILED, fatalConsumed.failure)
    }
}
