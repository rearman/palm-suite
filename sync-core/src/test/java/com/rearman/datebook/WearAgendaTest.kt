package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test

// The watch agenda codec: what the phone writes, the watch reads back.
class WearAgendaTest {
    private val agenda = WearAgenda(
        today = 20400,
        days = mapOf(
            20400L to listOf(AgendaEvent("Holiday", 480, 540, true), AgendaEvent("Private", 600, 660, false, true)),
            20401L to emptyList(),
        ),
        todos = listOf(
            AgendaTodo("Pay rent", 20399, 1, uid = "u1"), AgendaTodo("Call", 20402, 2), AgendaTodo("Someday", -1, 3),
        ),
        generated = 1234,
    )

    @Test
    fun upcomingDropsTheEndedOfTodayAndKeepsLaterDays() {
        val a = WearAgenda(
            20400,
            mapOf(
                20399L to listOf(AgendaEvent("Past", 480, 540, false)),
                20400L to listOf(AgendaEvent("Holiday", 0, 0, true), AgendaEvent("Ended", 480, 540, false),
                    AgendaEvent("Now", 600, 660, false)),
                20401L to listOf(AgendaEvent("Early", 60, 120, false)),
            ),
            emptyList(),
        )
        assertEquals(
            listOf(20400L to "Holiday", 20400L to "Now", 20401L to "Early"),
            a.upcoming(20400, 630).map { it.first to it.second.title },
        )
    }

    @Test
    fun todoWithNoUidParses() {
        val json = """{"v":1, "today":0, "generated":0, "days":[],
            "todos":[{"title":"Old", "due":5, "priority":1, "private":false}]}"""
        assertEquals(listOf(AgendaTodo("Old", 5)), WearAgenda.parse(json).todos)
    }

    @Test
    fun roundTripsAndCountsTodosDue() {
        val back = WearAgenda.parse(agenda.toJson())
        assertEquals(agenda, back)
        assertEquals(emptyList<AgendaEvent>(), back.events(20405))
        assertEquals(1, back.todosDue(20400))
        assertEquals(2, back.todosDue(20402))
    }

    @Test
    fun badOrOtherVersionGivesEmpty() {
        assertEquals(WearAgenda.EMPTY, WearAgenda.parse("{not json"))
        assertEquals(WearAgenda.EMPTY, WearAgenda.parse("""{"v":2, "today":0, "generated":0, "days":[], "todos":[]}"""))
        assertEquals(WearAgenda.EMPTY, WearAgenda.parse(null))
    }
}
