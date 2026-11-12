package com.rearman.datebook.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import com.rearman.datebook.AgendaTodo
import com.rearman.datebook.Fmt
import com.rearman.datebook.WearAgenda

/**
 * The To Do tile: the first open To Dos that fit, in the order of the phone's To Do List, each in
 * a bubble of its own. A tap on a check box completes the To Do: the tile asks for itself again
 * with the check box's id (a LoadAction), and that request marks it done. Any other tap opens the
 * watch screen at the To Dos, where the whole text shows.
 */
class TodoTileService : DateBookTileService() {
    override suspend fun build(request: RequestBuilders.TileRequest, agenda: WearAgenda, today: Long, now: Int): Tile {
        val clicked = request.currentState.lastClickableId
        // A later request may carry the last clicked id again; takeClick lets each id act once, and
        // only a To Do still open is marked done.
        if (clicked.startsWith(DONE) && AgendaStore.takeClick(this, clicked)) {
            val uid = clicked.removePrefix(DONE).substringBeforeLast('/')
            if (AgendaStore.openTodos(this, agenda).any { it.uid == uid }) AgendaStore.markDone(this, uid)
        }
        val todos = AgendaStore.openTodos(this, agenda)
        val open = openScreen(this, todos = true)
        val fit = Fit(request.deviceConfiguration)
        // The check box is the tallest text of a row.
        val row = fit.bubble(maxOf(CHECK_SP, ENTRY_SP))
        // The nonce of this tile's check box ids.
        val nonce = System.currentTimeMillis()
        val items = mutableListOf<LayoutElement>()
        if (todos.isEmpty()) items += bubble(line("No To Do items", ENTRY_SP), fit.bubble(ENTRY_SP), open)
        for (t in todos) {
            // Stop at the first row that does not fit.
            if (!fit.take(row)) break
            items += bubble(todoRow(t, today, open, "$DONE${t.uid}/$nonce", fit.text(CHECK_SP), request.deviceConfiguration.fontScale), row)
        }
        return page(this, request, getString(R.string.todo_tile_label), items, open)
    }

    private companion object {
        // The id prefix of a check box's Clickable: then the uid, a slash, and the tile's nonce.
        const val DONE = "done/"

        /**
         * One To Do: its check box at the start, with the click [id], which completes it, and its
         * text, which opens the watch screen. The text sits clear of the box by padding, so it
         * takes the rest of the width and ends in an ellipsis. A masked To Do (no uid) has no check
         * box. The box's touch area is its own line, [boxHeight] dp: the default minimum of 48 dp
         * would reach the rows above and below.
         */
        fun todoRow(t: AgendaTodo, today: Long, open: Clickable, id: String, boxHeight: Float, scale: Float): LayoutElement {
            val text = line(todoText(t, today), ENTRY_SP, bold = t.due in 0 until today)
            if (t.uid.isEmpty()) return startBox(Modifiers.Builder().setClickable(open).build(), text)
            // The width of the box glyph and the space after it: an estimate, one em of the check size.
            val boxWidth = CHECK_SP * scale + 4f
            val check = Clickable.Builder()
                .setId(id)
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .setMinimumClickableWidth(dp(boxWidth))
                .setMinimumClickableHeight(dp(boxHeight))
                .build()
            val textArea = startBox(
                Modifiers.Builder()
                    .setClickable(open)
                    .setPadding(Padding.Builder().setStart(dp(boxWidth)).build())
                    .build(),
                text,
            )
            val box = Box.Builder()
                .setWidth(dp(boxWidth))
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .setModifiers(Modifiers.Builder().setClickable(check).build())
                .addContent(line("☐", CHECK_SP))
                .build()
            // The box comes last, so it lies over the start of the text area and takes its taps.
            return startBox(null, textArea, box)
        }
    }
}

/** The text of a To Do in the Palm order: priority, description, due date ("!" when late). */
internal fun todoText(t: AgendaTodo, today: Long): String {
    val due = if (t.due < 0) "" else Fmt.ld(t.due).let { "  ${it.monthValue}/${it.dayOfMonth}" + if (t.due < today) "!" else "" }
    return "${t.priority} ${t.title}$due"
}
