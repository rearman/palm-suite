package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AddressTest {
    @Test fun dupKeyIgnoresIdAndUid() {
        val a = Address(id = 1, uid = "x", lastName = "Carter")
        val b = a.copy(id = 9, uid = "y")
        assertEquals(a.dupKey(), b.dupKey())
        assertFalse(a.dupKey() == a.copy(lastName = "Other").dupKey())
    }
}
