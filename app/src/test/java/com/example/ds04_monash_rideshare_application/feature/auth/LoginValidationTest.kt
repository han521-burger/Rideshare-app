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

    @Test
    fun `sign in only needs a Monash email and a password`() {
        assertEquals(BLANK_PASSWORD_ERROR, validateLoginForm(false, "", "a@student.monash.edu", "", ""))
        assertNull(validateLoginForm(false, "", "a@student.monash.edu", "short", ""))
    }

    @Test
    fun `create account checks name, password length and confirmation`() {
        assertEquals(BLANK_NAME_ERROR, validateLoginForm(true, " ", "a@student.monash.edu", "longenough", "longenough"))
        assertEquals(INVALID_EMAIL_ERROR, validateLoginForm(true, "Jess", "jess@gmail.com", "longenough", "longenough"))
        assertEquals(SHORT_PASSWORD_ERROR, validateLoginForm(true, "Jess", "a@student.monash.edu", "short", "short"))
        assertEquals(PASSWORD_MISMATCH_ERROR, validateLoginForm(true, "Jess", "a@student.monash.edu", "longenough", "different"))
        assertNull(validateLoginForm(true, "Jess", "a@student.monash.edu", "longenough", "longenough"))
    }
}
