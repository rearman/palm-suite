package com.rearman.datebook.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import com.rearman.datebook.AgendaEvent
import com.rearman.datebook.Fmt
import com.rearman.datebook.Recur
import com.rearman.datebook.WearAgenda
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * The Date Book widget. The large card lists the count of the To Dos due and the appointments
 * still to come that fit, each in a bubble of its own under the header of its day, today's
 * first; the card itself is clear, after the Google widgets. The small card is one white
 * bubble with the next appointment. A tap opens the watch screen (AgendaActivity). Black on white
 * in the bubbles, as on the Palm. The text uses the system font: Remote Compose resolves a font by
 * name on the watch, so the Palm OS font of the app cannot reach it.
 */
class AgendaWidget : GlanceWearWidget() {
    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val agenda = AgendaStore.load(context)
        Fmt.h24 = DateFormat.is24HourFormat(context)
        val today = LocalDate.now().toEpochDay()
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        val events = agenda.upcoming(today, now)
        val due = agenda.copy(todos = AgendaStore.openTodos(context, agenda)).todosDue(today)
        AgendaTick.schedule(context, agenda, today, now)
        val small = params.containerType == ContainerInfo.CONTAINER_TYPE_SMALL
        return WearWidgetDocument(background = cardBrush(small)) {
            if (small) SmallCard(today, events.firstOrNull { !it.second.untimed } ?: events.firstOrNull())
            else LargeCard(today, events, due)
        }
    }
}

// The annotation lets Glance Wear find the widget class without starting the service.
@AssociateWithGlanceWearWidget(AgendaWidget::class)
class AgendaWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = AgendaWidget()
}

/** The widgets of the watch app. */
object Widgets {
    /**
     * Asks the host for new content of every Date Book and To Do widget. The caller holds its
     * process until this returns (a service thread, or a receiver's goAsync).
     */
    suspend fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        for (w in listOf(AgendaWidget(), TodoWidget())) {
            try {
                withContext(Dispatchers.Main) { w.triggerUpdateAll(app) }
            } catch (_: Exception) {
            }
        }
    }
}

internal val Black = Color.Black.rc
internal val White = Color.White.rc

/**
 * The background of a card: white for the small card, which is one bubble; clear for the large
 * card, whose entries are bubbles of their own on the black of the watch. A transparent brush is
 * not the empty brush, so the host draws it clear in place of its default surface.
 */
internal fun cardBrush(small: Boolean): WearWidgetBrush =
    WearWidgetBrush.color(if (small) White else Color.Transparent.rc)

/**
 * The most rows (headers and bubbles) of a large card: the rows that fit the large card of the
 * 41 mm watch. A widget cannot scroll (Glance Wear allows no scroll operation; a document with
 * one shows black), so the watch screen shows the rest. Tune it on a larger watch.
 */
internal const val MAX_ROWS = 5

/**
 * The bubble of one entry of a large card, after the Google widgets: the full width, a light gray
 * fill with round corners, and the text clear of the edge.
 */
internal fun RemoteModifier.bubble(): RemoteModifier =
    fillMaxWidth().clip(RemoteRoundedCornerShape(10.rdp)).background(Color(0xFFE0E0E0).rc)
        .padding(horizontal = 8.rdp, vertical = 3.rdp)

/** The column of a large card: the full card, its rows with a gap between them. */
@Composable
@RemoteComposable
internal fun CardList(content: @RemoteComposable @Composable () -> Unit) {
    RemoteColumn(
        modifier = RemoteModifier.fillMaxSize(),
        verticalArrangement = RemoteArrangement.spacedBy(3.rdp),
    ) { content() }
}

/** The day of an appointment not of [today], before its time: "Thu ". */
internal fun dayPrefix(today: Long, day: Long): String =
    if (day == today) "" else "${Fmt.dowShort[Recur.dow(Fmt.ld(day))]} "

private fun line(e: AgendaEvent): String = if (e.untimed) e.title else "${Fmt.time(e.start)} ${e.title}"

/** Opens the watch screen at its appointments, or at its To Dos with [todos]. */
@Composable
@RemoteComposable
internal fun openScreen(todos: Boolean = false) = pendingIntentAction { ctx ->
    PendingIntent.getActivity(
        ctx, if (todos) 1 else 0, AgendaActivity.intent(ctx, todos),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

@Composable
@RemoteComposable
internal fun Line(
    text: String, size: Int, bold: Boolean = false,
    modifier: RemoteModifier = RemoteModifier.fillMaxWidth(), color: RemoteColor = Black,
) {
    RemoteText(
        text = text.rs,
        modifier = modifier,
        color = color,
        fontSize = size.rsp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
    )
}

@Composable
@RemoteComposable
private fun LargeCard(today: Long, events: List<Pair<Long, AgendaEvent>>, due: Int) {
    // Each bubble and header opens the screen; the clear gaps between them take no tap.
    val open = openScreen()
    val header = RemoteModifier.fillMaxWidth().padding(horizontal = 8.rdp).clickable(open)
    val bubble = RemoteModifier.bubble().clickable(open)
    CardList {
        // Today's header shows even with no appointment, as the date of the card.
        Line(Fmt.dowDate(today), 14, bold = true, modifier = header, color = White)
        var rows = 1
        if (events.isEmpty()) {
            Line("No appointments", 13, modifier = bubble)
            rows++
        }
        if (due > 0) {
            Line(if (due == 1) "1 To Do due" else "$due To Dos due", 13, bold = true, modifier = bubble)
            rows++
        }
        var shown = today
        for ((day, e) in events) {
            // A later day takes a header row and a bubble row; stop at the rows that fit.
            val need = if (day != shown) 2 else 1
            if (rows + need > MAX_ROWS) break
            if (day != shown) {
                Line(Fmt.dowDate(day), 14, bold = true, modifier = header, color = White)
                shown = day
            }
            Line(line(e), 13, modifier = bubble)
            rows += need
        }
    }
}

@Composable
@RemoteComposable
private fun SmallCard(today: Long, next: Pair<Long, AgendaEvent>?) {
    RemoteColumn(
        modifier = RemoteModifier.fillMaxSize().clickable(openScreen()),
        verticalArrangement = RemoteArrangement.Center,
    ) {
        if (next == null) {
            Line("No appointments", 13)
        } else {
            val (day, e) = next
            val time = (dayPrefix(today, day) + if (e.untimed) "" else Fmt.time(e.start)).trim()
            // The white card is the bubble.
            if (time.isNotEmpty()) Line(time, 14, bold = true)
            Line(e.title, 13)
        }
    }
}

/**
 * Updates the widgets at the next end of a timed appointment of today, or at midnight, so the
 * cards move on with no push from the phone. Inexact: the widget may lag a few minutes.
 */
class AgendaTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // goAsync keeps the process until the update ends (the system allows about 10 s).
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Widgets.updateAll(ctx)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        fun schedule(ctx: Context, agenda: WearAgenda, today: Long, now: Int) {
            val nextEnd = agenda.events(today).filter { !it.untimed && it.end > now }.minOfOrNull { it.end }
            // The next end of today, else the midnight that starts tomorrow.
            val at = if (nextEnd != null) Recur.millis(today, nextEnd.toLong()) else Recur.millis(today + 1, 0)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, AgendaTick::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, pi)
        }
    }
}
