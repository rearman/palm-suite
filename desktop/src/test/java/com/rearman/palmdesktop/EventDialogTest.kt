package com.rearman.palmdesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDialogTest {

	@Test
	fun parsesTimesWithSuffix() {
		assertEquals(480, parseTime("8:00a"))
		assertEquals(480, parseTime("8a"))
		assertEquals(0, parseTime("12:00a"))
		assertEquals(720, parseTime("12p"))
		assertEquals(810, parseTime("1:30 PM"))
	}

	@Test
	fun parses24HourAndCompactTimes() {
		assertEquals(810, parseTime("13:30"))
		assertEquals(510, parseTime("830"))
	}

	@Test
	fun rejectsBadTimes() {
		assertNull(parseTime(""))
		assertNull(parseTime("25:00"))
		assertNull(parseTime("8:61"))
	}
}
