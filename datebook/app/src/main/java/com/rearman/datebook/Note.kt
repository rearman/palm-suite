package com.rearman.datebook

import com.rearman.palmui.*
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The open Note view: the record description for the title tab, the text as typed, and [save],
 * which stores the text in the record. An empty text deletes the note.
 */
internal class NoteEdit(val title: String, val original: String, val save: (String) -> Unit) {
    var text = original
}

/**
 * Opens the Palm Note view for one record, over the current screen. [title] is the record
 * description. Done (or Back) saves the text through [save] and goes back to the screen.
 */
internal fun MainActivity.openNoteView(title: String, text: String, save: (String) -> Unit) {
    // Palm cuts a long description at the end of the title tab.
    val t = title.ifBlank { "(no description)" }.let { if (it.length > 24) it.take(23) + "..." else it }
    noteOpen = NoteEdit(t, text, save)
    render()
    if (text.isEmpty()) showKeyboardSoon()
}

/** Palm Note view: the description in the title tab, a full page of dotted lines, Done and Delete... */
internal fun MainActivity.buildNote(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val n = noteOpen!!
    tab(h, n.title)
    linedPage(content, n.text) { s -> n.text = s }
    cmd(cmds, "Done", gap = 0) { closeNote(n.text) }
    cmd(cmds, "Delete...") {
        ask("Delete Note", "Delete current note?", "Yes" to { closeNote("") }, "No" to {})
    }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** Saves [text] as the note when it changed, and shows the screen under the Note view. */
internal fun MainActivity.closeNote(text: String) {
    val n = noteOpen ?: return
    endEdit()
    noteOpen = null
    if (text != n.original) n.save(text)
    render()
}
