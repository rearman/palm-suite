package com.rearman.datebook.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * The Date Book widget. The large card lists today's appointments from now on and the count of
 * the To Dos due; the small card shows the next appointment. Black on white, as on the Palm. The
 * text uses the system font: Remote Compose resolves a font by name on the watch, so the Palm OS
 * font of the app cannot reach it.
 */
class AgendaWidget : GlanceWearWidget() {
    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val agenda = AgendaStore.load(context)
        Fmt.h24 = DateFormat.is24HourFormat(context)
        val today = LocalDate.now().toEpochDay()
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        // Untimed events stay all day; a timed one leaves at its end.
        val events = agenda.events(today).filter { it.untimed || it.end > now }
        val due = agenda.todosDue(today)
        AgendaTick.schedule(context, agenda, today, now)
        val small = params.containerType == ContainerInfo.CONTAINER_TYPE_SMALL
        return WearWidgetDocument(background = WearWidgetBrush.color(Color.White.rc)) {
            if (small) SmallCard(events.firstOrNull { !it.untimed } ?: events.firstOrNull())
            else LargeCard(today, events, due)
        }
    }

    companion object {
        /**
         * Asks the host for new content of every Date Book widget. The caller holds its process
         * until this returns (a service thread, or a receiver's goAsync).
         */
        suspend fun updateAll(ctx: Context) {
            val app = ctx.applicationContext
            try {
                withContext(Dispatchers.Main) { AgendaWidget().triggerUpdateAll(app) }
            } catch (_: Exception) {
            }
        }
    }
}

class AgendaWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = AgendaWidget()
}

private val Black = Color.Black.rc

private fun line(e: AgendaEvent): String =
    if (e.untimed) e.title else "${Fmt.time(e.start)} ${e.title}"

@Composable
@RemoteComposable
private fun Line(text: String, size: Int, bold: Boolean = false) {
    RemoteText(
        text = text.rs,
        modifier = RemoteModifier.fillMaxWidth(),
        color = Black,
        fontSize = size.rsp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
    )
}

@Composable
@RemoteComposable
private fun LargeCard(today: Long, events: List<AgendaEvent>, due: Int) {
    RemoteColumn(modifier = RemoteModifier.fillMaxSize(), verticalArrangement = RemoteArrangement.spacedBy(2.rdp)) {
        Line(Fmt.dowDate(today), 14, bold = true)
        if (events.isEmpty()) Line("No appointments", 13)
        // Four lines fit the large card of the 41 mm watch.
        for (e in events.take(4)) Line(line(e), 13)
        if (due > 0) Line(if (due == 1) "1 To Do due" else "$due To Dos due", 12, bold = true)
    }
}

@Composable
@RemoteComposable
private fun SmallCard(next: AgendaEvent?) {
    RemoteColumn(modifier = RemoteModifier.fillMaxSize(), verticalArrangement = RemoteArrangement.Center) {
        if (next == null) {
            Line("No appointments", 13)
        } else {
            if (!next.untimed) Line(Fmt.time(next.start), 14, bold = true)
            Line(next.title, 13)
        }
    }
}

/**
 * Updates the widget at the next end of a timed appointment of today, or at midnight, so the
 * cards move on with no push from the phone. Inexact: the widget may lag a few minutes.
 */
class AgendaTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // goAsync keeps the process until the update ends (the system allows about 10 s).
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AgendaWidget.updateAll(ctx)
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
