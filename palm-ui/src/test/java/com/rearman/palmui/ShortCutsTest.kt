package com.rearman.palmui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortCutsTest {
	private val me = ShortCut("me", "Meeting")
	private val dts = ShortCut("dts", "{date} {time}")
	private val ds = ShortCut("ds", "{date}")

	@Test
	fun findAtStartOfText() {
		assertEquals(0 to me, ShortCuts.find("^me", 3, ShortCuts.defaults))
	}

	@Test
	fun findAfterOtherText() {
		assertEquals(2 to ds, ShortCuts.find("x ^ds", 5, ShortCuts.defaults))
	}

	@Test
	fun unknownNameIsNull() {
		assertNull(ShortCuts.find("^m", 2, ShortCuts.defaults))
	}

	@Test
	fun textWithoutTriggerIsNull() {
		assertNull(ShortCuts.find("me", 2, ShortCuts.defaults))
	}

	@Test
	fun whitespaceBreaksName() {
		assertNull(ShortCuts.find("^ me", 4, ShortCuts.defaults))
	}

	@Test
	fun findLongName() {
		assertEquals(0 to dts, ShortCuts.find("^dts", 4, ShortCuts.defaults))
	}

	@Test
	fun cursorBeyondLengthIsNull() {
		assertNull(ShortCuts.find("^dts", 5, ShortCuts.defaults))
	}

	@Test
	fun expandReplacesDateAndTime() {
		ShortCuts.date = { "1/2/26" }
		ShortCuts.time = { "3:04 pm" }
		assertEquals("1/2/26 3:04 pm", ShortCuts.expand("{date} {time}"))
		assertEquals("Lunch", ShortCuts.expand("Lunch"))
	}
}
