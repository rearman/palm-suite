package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateSharedTest {

	@Test
	fun timeLabelTwelveHour() {
		val saved = Fmt.h24
		Fmt.h24 = false
		try {
			assertEquals("12:00a", timeLabel(0))
			assertEquals("8:00a", timeLabel(480))
			assertEquals("12:00p", timeLabel(720))
			assertEquals("1:30p", timeLabel(810))
		} finally {
			Fmt.h24 = saved
		}
	}

	@Test
	fun timeLabelTwentyFourHour() {
		val saved = Fmt.h24
		Fmt.h24 = true
		try {
			assertEquals("8:00", timeLabel(480))
			assertEquals("13:30", timeLabel(810))
		} finally {
			Fmt.h24 = saved
		}
	}

	@Test
	fun weekStartOfUsesGivenFirstDay() {
		val monday = LocalDate.of(2026, 10, 19).toEpochDay()
		val sunday = LocalDate.of(2026, 10, 18).toEpochDay()
		assertEquals(sunday, weekStartOf(monday, 0))
		assertEquals(monday, weekStartOf(monday, 1))
		assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), weekStartOf(sunday, 1))
	}
}
