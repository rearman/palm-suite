package com.rearman.palmdesktop

import com.rearman.datebook.Address
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTitleTest {

	@Test
	fun addressTitleIsNameAndListNumber() {
		val a = Address(lastName = "Carter", firstName = "Melanie", phones = listOf("111", "222", "", "", ""), showPhone = 1)
		assertEquals("Carter, Melanie 222", eventTitle(a, false))
	}

	@Test
	fun addressTitleWithoutNumberIsName() {
		assertEquals("Acme", eventTitle(Address(company = "Acme"), false))
		assertEquals("Acme, Carter", eventTitle(Address(company = "Acme", lastName = "Carter"), true))
	}
}
