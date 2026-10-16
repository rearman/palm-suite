package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuTest {
    private val menus = listOf(
        "Record" to listOf(MenuItem("New Event", 'N') {}, MenuItem("Export Backup...") {}),
        "Options" to listOf(MenuItem("Font...", 'F') {}, MenuItem("Other", 'N') {}),
    )

    @Test
    fun commandFindsLetterInAnyMenuAndCase() {
        assertEquals("Font...", menus.command('f')?.name)
        assertEquals("New Event", menus.command('N')?.name)
    }

    @Test
    fun commandWithoutMatchIsNull() {
        assertNull(menus.command('Q'))
    }
}
