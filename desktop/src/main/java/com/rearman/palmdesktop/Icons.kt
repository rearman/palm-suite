package com.rearman.palmdesktop

import java.awt.Image
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * The toolbar, launcher and marker icons: the Palm Desktop 3.1.1 bitmaps, stored as PNG resources in icons/. See the Palm Desktop artwork note in README.md.
 */
object Icons {
	private val cache = HashMap<String, ImageIcon>()

	fun date(): Icon = load("date")

	fun todo(): Icon = load("todo")

	fun memo(): Icon = load("memo")

	fun save(): Icon = load("save")

	fun print(): Icon = load("print")

	fun cut(): Icon = load("cut")

	fun copy(): Icon = load("copy")

	fun paste(): Icon = load("paste")

	fun undo(): Icon = load("undo")

	fun find(): Icon = load("find")

	fun note(): Icon = load("note")

	fun repeat(): Icon = load("repeat")

	fun alarm(): Icon = load("alarm")

	fun privateRecord(): Icon = load("private")

	fun calendar(): Icon = load("calendar")

	fun clock(): Icon = load("clock")

	fun newItem(): Icon = load("new")

	/** The 16 and 32 pixel frame icons, for JFrame.iconImages. */
	fun appImages(): List<Image> = listOf(load("app16").image, load("app32").image)

	private fun load(name: String): ImageIcon = cache.getOrPut(name) {
		ImageIcon(Icons::class.java.getResource("icons/$name.png") ?: error("missing icon $name"))
	}
}
