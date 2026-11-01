package com.rearman.datebook

import com.rearman.palmui.*
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The Prefs app. As on the Palm, it holds the ShortCuts: the Palm sets them in Prefs, and every
 * app uses them. It has its own icon and task, and it uses the same settings as the other apps.
 */
class PrefsActivity : MainActivity() {
    override val isPrefsApp: Boolean get() = true
}

/**
 * The Palm ShortCuts Preferences screen: the panel pick list at the right of the title, the
 * ShortCuts sorted by name, and New, Edit... and Delete.... A tap selects a ShortCut.
 */
internal fun MainActivity.buildPrefs(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    tab(h, "Prefs")
    // The panels: this app has only ShortCuts.
    h.addView(popupTrigger("ShortCuts") { tv -> popupList(tv, listOf("ShortCuts"), 0) {} }, LP(WRAP, pp(13)).apply { marginEnd = -pp(1) })

    val list = ShortCuts.list.sortedBy { it.name }
    val sel = list.firstOrNull { it.name == shortCutSel }
    val col = vbox()
    for (sc in list) {
        col.addView(label("${sc.name}: ${sc.text}").apply {
            maxLines = 1
            setPadding(pp(3), 0, pp(3), 0)
            if (sc == sel) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
            setOnClickListener { shortCutSel = sc.name; render() }
        }, lp(MATCH))
    }
    withScrollBar(content, ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false })

    cmd(cmds, "New", gap = 0) { editShortCut(null) }
    cmd(cmds, "Edit...") { if (sel != null) editShortCut(sel) else toast("Select a ShortCut first.") }
    cmd(cmds, "Delete...") {
        if (sel == null) toast("Select a ShortCut first.")
        else ask("Delete ShortCut", "Delete the ShortCut \"${sel.name}\"?", "Yes" to {
            saveShortCuts(ShortCuts.list.filter { it != sel })
            shortCutSel = null
            render()
        }, "No" to {})
    }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** ShortCut Entry form. A null [sc] makes a new ShortCut. OK saves it and selects it. */
private fun MainActivity.editShortCut(sc: ShortCut?) {
    val d = PalmDialog(this, "ShortCut Entry", height = 84,
        tips = "ShortCut Name is the text you type, followed by the ShortCut stroke.\n\n" +
            "ShortCut Text is the text that takes its place.")
    val nameField = field(sc?.name ?: "", "ShortCut name")
    val textField = field(sc?.text ?: "", "ShortCut text")
    d.row("ShortCut Name:", nameField, labelEnd = 64)
    d.row("ShortCut Text:", textField, labelEnd = 64)
    d.button("OK", 22) {
        val name = nameField.text.toString().trim()
        when {
            name.isEmpty() -> toast("Enter a ShortCut name.")
            name.any { it.isWhitespace() || it == '^' } -> toast("A ShortCut name has no spaces and no ^.")
            ShortCuts.list.any { it.name == name && it != sc } -> toast("That ShortCut name is in use.")
            else -> {
                d.dismiss()
                saveShortCuts(ShortCuts.list.filter { it != sc } + ShortCut(name, textField.text.toString()))
                shortCutSel = name
                render()
            }
        }
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

private fun MainActivity.saveShortCuts(list: List<ShortCut>) {
    prefs.shortCuts = list
    ShortCuts.list = list
}
