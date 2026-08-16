package com.example.ds04_monash_rideshare_application.Database.User

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAccountTest {

    private fun sample() = UserAccount(
        "user-1", "saml-subject-1", "student@monash.edu", "ACTIVE", 1_700_000_000L
    )

    @Test
    fun `constructor sets all fields`() {
        val account = sample()
        assertEquals("user-1", account.userId)
        assertEquals("saml-subject-1", account.externalSubjectId)
        assertEquals("student@monash.edu", account.emailAccount)
        assertEquals("ACTIVE", account.accountStatus)
        assertEquals(1_700_000_000L, account.createdAt)
    }

    @Test
    fun `setUserId updates userId`() {
        val account = sample()
        account.setUserId("user-2")
        assertEquals("user-2", account.getUserId())
    }

    @Test
    fun `setExternalSubjectId updates externalSubjectId`() {
        val account = sample()
        account.setExternalSubjectId("saml-subject-2")
        assertEquals("saml-subject-2", account.getExternalSubjectId())
    }

    @Test
    fun `setEmailAccount updates emailAccount`() {
        val account = sample()
        account.setEmailAccount("new-email@monash.edu")
        assertEquals("new-email@monash.edu", account.getEmailAccount())
    }

    @Test
    fun `setAccountStatus updates accountStatus`() {
        val account = sample()
        account.setAccountStatus("SUSPENDED")
        assertEquals("SUSPENDED", account.getAccountStatus())
    }

    @Test
    fun `setCreatedAt updates createdAt`() {
        val account = sample()
        account.setCreatedAt(1_800_000_000L)
        assertEquals(1_800_000_000L, account.getCreatedAt())
    }
}
