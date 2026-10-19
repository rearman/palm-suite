package com.rearman.palmdesktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecordsTest {
	private val day = LocalDate.of(2026, 10, 19).toEpochDay()

	@Test fun rowsDescribeEachKindAndSort() {
		val list = rows(listOf(
			"t1" to """{"kind":"todo","title":"Call Ann","due":$day,"priority":2,"done":true,"category":"c1"}""",
			"c1" to """{"kind":"category","name":"Work","app":0}""",
			"e1" to """{"kind":"event","title":"Staff","date":$day,"start":540,"end":630,"repeat":2}""",
			"e2" to """{"kind":"event","title":"Holiday","date":$day,"untimed":true}""",
			"m1" to """{"kind":"memo","text":"  Groceries \nmilk","category":"gone"}""",
			"s" to """{"kind":"shortcuts","list":[{"name":"br","text":"Breakfast"}]}""",
			"x" to """{"kind":"future"}""",
			"bad" to "not json"
		))
		assertEquals(listOf(
			Row("Category", "Work", "To Do List"),
			Row("Event", "Holiday", "2026-10-19 no time"),
			Row("Event", "Staff", "2026-10-19 9:00 to 10:30, weekly"),
			Row("Memo", "Groceries", "Unfiled"),
			Row("ShortCuts", "1 ShortCuts", ""),
			Row("To Do", "Call Ann", "Due 2026-10-19, Priority 2, Work, Done")
		), list)
	}

	@Test fun todoWithoutDueDate() {
		val list = rows(listOf("t" to """{"kind":"todo","title":"Later","due":-1}"""))
		assertEquals(Row("To Do", "Later", "No due date, Priority 1, Unfiled"), list.single())
	}
}
