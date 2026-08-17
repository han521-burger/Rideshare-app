package com.example.ds04_monash_rideshare_application.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginValidationTest {
    @Test
    fun `blank email shows the required field message`() {
        assertEquals(BLANK_EMAIL_ERROR, validateMonashEmail("  "))
    }

    @Test
    fun `non Monash email is rejected`() {
        assertEquals(INVALID_EMAIL_ERROR, validateMonashEmail("student@example.com"))
    }

    @Test
    fun `student and staff Monash emails are accepted`() {
        assertNull(validateMonashEmail("student@student.monash.edu"))
        assertNull(validateMonashEmail("staff@monash.edu"))
    }
}
