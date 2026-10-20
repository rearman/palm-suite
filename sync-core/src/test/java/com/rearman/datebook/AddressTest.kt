package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
    @Test fun listNameByLastName() {
        assertEquals("Carter, Melanie", Address(lastName = "Carter", firstName = "Melanie").listName(false))
        assertEquals("Melanie", Address(firstName = "Melanie").listName(false))
        assertEquals("Acme", Address(company = "Acme").listName(false))
        assertEquals("-Unnamed-", Address().listName(false))
    }

    @Test fun listNameByCompany() {
        assertEquals("Acme, Carter", Address(company = "Acme", lastName = "Carter").listName(true))
    }

    @Test fun listPhoneUsesShowPhoneField() {
        val a = Address(phones = listOf("111", "222", "", "", ""), showPhone = 1)
        assertEquals("222" to 'H', a.listPhone())
    }

    @Test fun listPhoneFallsBackToFirstNonEmpty() {
        val a = Address(phones = listOf("", "", "555-3", "", ""), showPhone = 0)
        assertEquals("555-3" to 'F', a.listPhone())
    }

    @Test fun listPhoneNullWhenAllEmpty() {
        assertNull(Address().listPhone())
    }

    @Test fun listPhoneMobileGivesM() {
        val a = Address(phoneLabels = listOf(Address.MOBILE, 1, 2, 3, 4), phones = listOf("555-7", "", "", "", ""))
        assertEquals("555-7" to 'M', a.listPhone())
    }

    @Test fun dupKeyIgnoresIdAndUid() {
        val a = Address(id = 1, uid = "x", lastName = "Carter")
        val b = a.copy(id = 9, uid = "y")
        assertEquals(a.dupKey(), b.dupKey())
        assertFalse(a.dupKey() == a.copy(lastName = "Other").dupKey())
    }

    @Test fun addressOrderIgnoresCase() {
        val list = listOf(
            Address(id = 1, lastName = "beta"),
            Address(id = 2, lastName = "Alpha"),
            Address(id = 3, lastName = "charlie")
        )
        assertEquals(listOf(2L, 1L, 3L), addressOrder(list, false).map { it.id })
    }

    @Test fun isEmptyOnlyWithNoText() {
        assertTrue(Address().isEmpty)
        assertFalse(Address(phones = listOf("555", "", "", "", "")).isEmpty)
    }
}
