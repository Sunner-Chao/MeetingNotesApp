package com.oa.automation.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceAccountIsolationTest {
    @Test
    fun unknownWorkspaceOwnerIsResetBeforeFirstAccount() {
        assertTrue(shouldResetLocalWorkspace(null, "user-a"))
        assertTrue(shouldResetLocalWorkspace("", "user-a"))
    }

    @Test
    fun legacyWorkspaceIsKeptOnlyForTheAlreadySignedInAccount() {
        assertFalse(shouldResetLocalWorkspace(null, "user-a", "user-a"))
        assertTrue(shouldResetLocalWorkspace(null, "user-b", "user-a"))
    }

    @Test
    fun switchingAccountsResetsWorkspaceButSameAccountDoesNot() {
        assertTrue(shouldResetLocalWorkspace("user-a", "user-b"))
        assertFalse(shouldResetLocalWorkspace("user-a", "user-a"))
    }

    @Test
    fun blankAccountIdNeverResetsWorkspace() {
        assertFalse(shouldResetLocalWorkspace("user-a", " "))
    }
}
