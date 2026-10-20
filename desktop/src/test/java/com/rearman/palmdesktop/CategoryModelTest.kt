package com.rearman.palmdesktop

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.SwingUtilities

class CategoryModelTest {

	@Test
	fun editEntryKeepsSelectionAndCallsEdit() {
		var edits = 0
		val model = CategoryModel(listOf("All", "Business"), EDIT_CATEGORIES) { edits++ }
		assertEquals(3, model.size)
		assertEquals(EDIT_CATEGORIES, model.getElementAt(2))
		model.selectedItem = "Business"
		model.selectedItem = model.getElementAt(2)
		SwingUtilities.invokeAndWait {}   // onEdit runs on the event thread
		assertEquals("Business", model.selectedItem)
		assertEquals(1, edits)
	}
}
