package com.rearman.datebook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTest {
    @Test
    fun rightPasswordMatches() {
        assertTrue(Password.check(Password.make("palm"), "palm"))
    }

    @Test
    fun wrongPasswordFails() {
        val stored = Password.make("palm")
        assertFalse(Password.check(stored, "Palm"))
        assertFalse(Password.check(stored, ""))
    }

    @Test
    fun saltMakesEachHashDifferent() {
        assertNotEquals(Password.make("palm"), Password.make("palm"))
    }

    @Test
    fun badStoredValueFails() {
        assertFalse(Password.check("", "palm"))
        assertFalse(Password.check("not base64!:x", "palm"))
    }
}
