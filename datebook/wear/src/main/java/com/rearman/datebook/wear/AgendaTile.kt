package com.rearman.datebook.wear

import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import com.rearman.datebook.AgendaEvent
import com.rearman.datebook.Fmt
import com.rearman.datebook.WearAgenda

/**
 * The Date Book tile: the appointments still to come that fit, each in a bubble of its own under
 * the centered header of its day, today's first. A day takes its header only with its first
 * bubble. With no appointments it shows today's header and "No appointments". A tap opens the
 * watch screen (AgendaActivity).
 */
class AgendaTileService : DateBookTileService() {
    override suspend fun build(request: RequestBuilders.TileRequest, agenda: WearAgenda, today: Long, now: Int): Tile {
        val events = agenda.upcoming(today, now)
        val open = openScreen(this)
        val fit = Fit(request.deviceConfiguration)
        val entry = fit.bubble(ENTRY_SP)
        val items = mutableListOf<LayoutElement>()
        if (events.isEmpty()) {
            items += header(Fmt.dowDate(today), open)
            items += bubble(line("No appointments", ENTRY_SP), entry, open)
        }
        var shown = -1L
        for ((day, e) in events) {
            // A new day takes its header row with its first bubble; stop at the first row that does not fit.
            val newDay = day != shown
            val fits = if (newDay) fit.take(fit.text(HEAD_SP), entry) else fit.take(entry)
            if (!fits) break
            if (newDay) {
                items += header(Fmt.dowDate(day), open)
                shown = day
            }
            items += bubble(line(eventText(e), ENTRY_SP), entry, open)
        }
        return page(this, request, getString(R.string.agenda_tile_label), items, open)
    }
}

/** An appointment's line: its start time, then its description; an untimed one has no time. */
internal fun eventText(e: AgendaEvent): String = if (e.untimed) e.title else "${Fmt.time(e.start)} ${e.title}"
