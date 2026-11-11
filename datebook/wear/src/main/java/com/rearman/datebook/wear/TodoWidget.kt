package com.rearman.datebook.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import com.rearman.datebook.AgendaTodo
import com.rearman.datebook.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * The To Do widget. The large card lists the first open To Dos in the order of the phone's To Do
 * List, the small card the first one. A tap on a check box completes the To Do (TodoCheck); a tap
 * on its text opens the watch screen at the To Dos, where the whole text shows.
 */
class TodoWidget : GlanceWearWidget() {
    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val agenda = AgendaStore.load(context)
        Fmt.h24 = DateFormat.is24HourFormat(context)
        val today = LocalDate.now().toEpochDay()
        val todos = AgendaStore.openTodos(context, agenda)
        // The late mark of a due date changes at midnight.
        AgendaTick.schedule(context, agenda, today, LocalTime.now().let { it.hour * 60 + it.minute })
        val small = params.containerType == ContainerInfo.CONTAINER_TYPE_SMALL
        return WearWidgetDocument(background = WearWidgetBrush.color(Color.White.rc)) {
            RemoteColumn(
                modifier = RemoteModifier.fillMaxSize(),
                verticalArrangement = if (small) RemoteArrangement.Center else RemoteArrangement.spacedBy(2.rdp),
            ) {
                val open = RemoteModifier.fillMaxWidth().clickable(openScreen(todos = true))
                if (!small) Line("To Do", 14, bold = true, modifier = open)
                if (todos.isEmpty()) Line("No To Do items", 13, modifier = open)
                // Three bubbles fit the large card of the 41 mm watch below the title.
                for (t in todos.take(if (small) 1 else ROWS)) TodoRow(t, today)
            }
        }
    }

    companion object {
        const val ROWS = 3
    }
}

@AssociateWithGlanceWearWidget(TodoWidget::class)
class TodoWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = TodoWidget()
}

/** The text of a To Do in the Palm order: priority, description, due date ("!" when late). */
internal fun todoText(t: AgendaTodo, today: Long): String {
    val due = if (t.due < 0) "" else Fmt.ld(t.due).let { "  ${it.monthValue}/${it.dayOfMonth}" + if (t.due < today) "!" else "" }
    return "${t.priority} ${t.title}$due"
}

@Composable
@RemoteComposable
private fun TodoRow(t: AgendaTodo, today: Long) {
    RemoteRow(modifier = RemoteModifier.bubble(), verticalAlignment = RemoteAlignment.CenterVertically) {
        // The empty check box of the system font, with a space that widens the tap target. A
        // masked To Do (no uid) has none.
        if (t.uid.isNotEmpty()) {
            val check = pendingIntentAction { ctx ->
                PendingIntent.getBroadcast(
                    ctx, 0, TodoCheck.intent(ctx, t.uid),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            Line("☐ ", 18, modifier = RemoteModifier.clickable(check))
        }
        Line(todoText(t, today), 13, bold = t.due in 0 until today,
            modifier = RemoteModifier.fillMaxWidth().clickable(openScreen(todos = true)))
    }
}

/** Completes the To Do whose check box was tapped on the widget. */
class TodoCheck : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uid = intent.data?.schemeSpecificPart ?: return
        // goAsync keeps the process until the put and the update end (the system allows about 10 s).
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AgendaStore.markDone(ctx, uid)
                Widgets.updateAll(ctx)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        /** The uid goes in the data, so each To Do has a PendingIntent of its own. */
        fun intent(ctx: Context, uid: String): Intent =
            Intent(ctx, TodoCheck::class.java).setData(Uri.fromParts("todo", uid, null))
    }
}
