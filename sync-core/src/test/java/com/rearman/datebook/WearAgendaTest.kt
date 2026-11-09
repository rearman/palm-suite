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
        todos = listOf(AgendaTodo("Pay rent", 20399, 1), AgendaTodo("Call", 20402, 2)),
        generated = 1234,
    )

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
