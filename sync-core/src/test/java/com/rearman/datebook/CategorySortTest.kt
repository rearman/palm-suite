package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySortTest {
    private fun cat(id: Int, name: String) = Category(id = id, name = name)

    @Test fun sortIgnoresCase() {
        val list = mutableListOf(cat(1, "personal"), cat(2, "Business"), cat(3, "QuickList"))
        sortCategories(list)
        assertEquals(listOf("Business", "personal", "QuickList"), list.map { it.name })
    }

    @Test fun equalNamesKeepIdOrder() {
        val list = mutableListOf(cat(9, "Work"), cat(3, "Work"))
        sortCategories(list)
        assertEquals(listOf(3, 9), list.map { it.id })
    }

    @Test fun emptyListStaysEmpty() {
        val list = mutableListOf<Category>()
        sortCategories(list)
        assertEquals(emptyList<Category>(), list)
    }
}
