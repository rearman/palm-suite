package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Repeat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RepeatTextTest {

	// 2026-10-20 is a Tuesday, and its day of month is 20.
	private val oct20 = LocalDate.of(2026, 10, 20).toEpochDay()

	@Test
	fun noRepeatIsEmpty() {
		assertEquals("", describeDesktop(Event(date = oct20, repeat = Repeat.NONE)))
	}

	@Test
	fun daily() {
		assertEquals("Every day", describeDesktop(Event(date = oct20, repeat = Repeat.DAY, every = 1)))
		assertEquals("Every other day", describeDesktop(Event(date = oct20, repeat = Repeat.DAY, every = 2)))
		assertEquals("Every 3rd day", describeDesktop(Event(date = oct20, repeat = Repeat.DAY, every = 3)))
	}

	@Test
	fun weeklyOneDay() {
		val tue = 1 shl 2
		assertEquals("Every Tuesday", describeDesktop(Event(date = oct20, repeat = Repeat.WEEK, every = 1, weekDays = tue)))
		assertEquals("Every other Tuesday", describeDesktop(Event(date = oct20, repeat = Repeat.WEEK, every = 2, weekDays = tue)))
	}

	@Test
	fun weeklyTwoDays() {
		val monTue = (1 shl 1) or (1 shl 2)
		assertEquals("Every 3rd week on Mon, and Tue", describeDesktop(Event(date = oct20, repeat = Repeat.WEEK, every = 3, weekDays = monTue)))
	}

	@Test
	fun weeklyThreeDays() {
		val monWedFri = (1 shl 1) or (1 shl 3) or (1 shl 5)
		assertEquals("Every week on Mon, Wed, and Fri", describeDesktop(Event(date = oct20, repeat = Repeat.WEEK, every = 1, weekDays = monWedFri)))
	}

	@Test
	fun monthlyByDate() {
		assertEquals("The 20th of every month", describeDesktop(Event(date = oct20, repeat = Repeat.MONTH_DATE, every = 1)))
		assertEquals("The 20th of every 3rd month", describeDesktop(Event(date = oct20, repeat = Repeat.MONTH_DATE, every = 3)))
	}

	@Test
	fun yearly() {
		assertEquals("October 20 every year", describeDesktop(Event(date = oct20, repeat = Repeat.YEAR, every = 1)))
	}

	@Test
	fun ordinalsOfEveryDay() {
		val expected = mapOf(
			11 to "Every 11th day",
			12 to "Every 12th day",
			13 to "Every 13th day",
			21 to "Every 21st day",
			22 to "Every 22nd day",
			23 to "Every 23rd day",
		)
		for ((n, text) in expected) {
			assertEquals(text, describeDesktop(Event(date = oct20, repeat = Repeat.DAY, every = n)))
		}
	}
}
