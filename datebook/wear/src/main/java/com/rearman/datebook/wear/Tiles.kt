package com.rearman.datebook.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.material3.PrimaryLayoutMargins
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import com.google.common.util.concurrent.ListenableFuture
import com.rearman.datebook.Fmt
import com.rearman.datebook.Recur
import com.rearman.datebook.WearAgenda
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.min

/**
 * The parts that the Date Book and To Do tiles share. A tile has the whole screen: the watch draws
 * the app icon at the top, the Material 3 primary layout puts the title under it, and the rows of
 * the tile fill the main slot, each entry in a gray bubble of its own, black on light gray as on the
 * Palm. A tile cannot scroll, so it takes the rows that fit (Fit) and the watch screen
 * (AgendaActivity) shows the rest. The text uses the system font.
 */

/** The text sizes of the tiles, in sp: a day header, an entry, and a To Do's check box. */
internal const val HEAD_SP = 13
internal const val ENTRY_SP = 14
internal const val CHECK_SP = 16

private val WHITE = 0xFFFFFFFF.toInt()
private val BLACK = 0xFF000000.toInt()
private val BUBBLE = 0xFFE0E0E0.toInt()

/** The resources version of the tiles, which use no images. */
private const val RESOURCES = "1"

/**
 * The rows that fit the main slot of a tile. Its height is the screen less the header of the
 * primary layout (top margin, icon, spacer, title line, bottom margin, after PrimaryLayout.kt of
 * protolayout-material3 1.4.2) and the bottom margin of [MARGINS]. Each text sets its line height
 * to [LINE] times its size, so a row's height is known before the watch draws it; the line heights
 * scale with the font scale, as the watch scales the text. Tune the values here on the watch.
 */
internal class Fit(params: DeviceParameters) {
    private val scale = params.fontScale
    private val height = params.screenHeightDp
    // The large screens (225 dp or more) take the larger spacer and header margin.
    private val large = height >= 225
    private val header = 3f + 24f + (if (large) 4f else 2f) + TITLE_LINE * scale + (if (large) 6f else 4f)
    // The first row has no gap above it, so the gap of one row comes back.
    private var left = height - header - ceil(BOTTOM * height) + GAP

    fun text(sp: Int): Float = sp * scale * LINE
    fun bubble(sp: Int): Float = text(sp) + 2 * PAD_V

    /** Takes rows of the [heights] in dp if all of them fit, else none. */
    fun take(vararg heights: Float): Boolean {
        val h = heights.sum() + heights.size * GAP
        if (h > left) return false
        left -= h
        return true
    }

    companion object {
        const val LINE = 1.25f
        const val GAP = 4f
        const val PAD_H = 12f
        const val PAD_V = 4f
        const val CORNER = 16f
        // The line height of the Material 3 title (Typography.TITLE_SMALL), in sp.
        const val TITLE_LINE = 16f
        // The main slot's side margins: 7.1% of the screen width, those of a layout with a title
        // and an edge button (192 dp wide on the Pixel Watch 5); the bottom margin of a layout with
        // a title and no bottom slot (16.64% of the screen height).
        const val SIDE = 0.071f
        const val BOTTOM = 0.1664f
        val MARGINS: PrimaryLayoutMargins =
            PrimaryLayoutMargins.customizedPrimaryLayoutMargin(start = SIDE, end = SIDE, bottom = BOTTOM)
    }
}

/** One line of text: [size] sp, one line that ends in an ellipsis, its line height set for Fit. */
internal fun line(text: String, size: Int, color: Int = BLACK, bold: Boolean = false, center: Boolean = false): LayoutElement =
    Text.Builder()
        .setText(text)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(size.toFloat()))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build()
        )
        .setLineHeight(sp(size * Fit.LINE))
        .setMaxLines(1)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setMultilineAlignment(if (center) LayoutElementBuilders.TEXT_ALIGN_CENTER else LayoutElementBuilders.TEXT_ALIGN_START)
        .build()

/** A white centered day header, which opens the watch screen. */
internal fun header(text: String, open: Clickable): LayoutElement =
    Box.Builder()
        .setWidth(expand())
        .setHeight(wrap())
        .setModifiers(Modifiers.Builder().setClickable(open).build())
        .addContent(line(text, HEAD_SP, color = WHITE, bold = true, center = true))
        .build()

/**
 * The gray bubble of one entry, after the Google tiles: the full width of the main slot, round
 * corners, and the content clear of the edge. [height] is its height from Fit, which caps the
 * corner radius at half of it.
 */
internal fun bubble(content: LayoutElement, height: Float, click: Clickable? = null): LayoutElement {
    val mods = Modifiers.Builder()
        .setBackground(
            Background.Builder()
                .setColor(argb(BUBBLE))
                .setCorner(Corner.Builder().setRadius(dp(min(Fit.CORNER, height / 2))).build())
                .build()
        )
        .setPadding(
            Padding.Builder()
                .setStart(dp(Fit.PAD_H)).setEnd(dp(Fit.PAD_H))
                .setTop(dp(Fit.PAD_V)).setBottom(dp(Fit.PAD_V))
                .build()
        )
    click?.let { mods.setClickable(it) }
    return startBox(mods.build(), content)
}

/** A box of the full width and the height of its [content], which lies at its start, in order. */
internal fun startBox(mods: Modifiers?, vararg content: LayoutElement): LayoutElement =
    Box.Builder()
        .setWidth(expand())
        .setHeight(wrap())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .apply { mods?.let { setModifiers(it) } }
        .apply { content.forEach { addContent(it) } }
        .build()

/** The rows of a tile, top first, with a gap between them. */
internal fun column(items: List<LayoutElement>): LayoutElement =
    Column.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .apply {
            items.forEachIndexed { i, e ->
                if (i > 0) addContent(Spacer.Builder().setHeight(dp(Fit.GAP)).build())
                addContent(e)
            }
        }
        .build()

/** Opens the watch screen at its appointments, or at its To Dos with [todos]. */
internal fun openScreen(ctx: Context, todos: Boolean = false): Clickable =
    Clickable.Builder()
        .setId(if (todos) "open/todos" else "open/events")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(ctx.packageName)
                        .setClassName(AgendaActivity::class.java.name)
                        .addKeyToExtraMapping(AgendaActivity.EXTRA_TODOS, ActionBuilders.booleanExtra(todos))
                        .build()
                )
                .build()
        )
        .build()

/**
 * The tile of [request]: [title] under the icon and the [items] of the main slot, top first. A tap
 * outside the rows' own taps does [open].
 */
internal fun page(ctx: Context, request: RequestBuilders.TileRequest, title: String, items: List<LayoutElement>, open: Clickable): Tile {
    val layout = materialScope(ctx, request.deviceConfiguration) {
        primaryLayout(
            titleSlot = { text(title.layoutString) },
            mainSlot = { column(items) },
            onClick = open,
            margins = Fit.MARGINS,
        )
    }
    return tile(timeline = timeline(timelineEntry(layout)), resourcesVersion = RESOURCES)
}

/**
 * The base of the two tile services: each request loads the agenda, sets the clock format, moves
 * AgendaTick to the next change, and builds the tile with [build]. The tiles use no images.
 */
abstract class DateBookTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> =
        SuspendToFutureAdapter.launchFuture {
            val agenda = AgendaStore.load(this@DateBookTileService)
            Fmt.h24 = DateFormat.is24HourFormat(this@DateBookTileService)
            val today = LocalDate.now().toEpochDay()
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            // The late mark of a To Do's due date also changes at midnight.
            AgendaTick.schedule(this@DateBookTileService, agenda, today, now)
            build(requestParams, agenda, today, now)
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<Resources> =
        SuspendToFutureAdapter.launchFuture { Resources.Builder().setVersion(RESOURCES).build() }

    /** The tile of [request] for [agenda], on the epoch day [today] at [now] minutes. */
    protected abstract suspend fun build(request: RequestBuilders.TileRequest, agenda: WearAgenda, today: Long, now: Int): Tile
}

/** The tiles of the watch app. */
object Tiles {
    /** Asks the watch for new content of the Date Book and To Do tiles. */
    fun updateAll(ctx: Context) {
        val updater = TileService.getUpdater(ctx.applicationContext)
        updater.requestUpdate(AgendaTileService::class.java)
        updater.requestUpdate(TodoTileService::class.java)
    }
}

/**
 * Updates the tiles at the next end of a timed appointment of today, or at midnight, so they move
 * on with no push from the phone. Inexact: a tile may lag a few minutes.
 */
class AgendaTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Tiles.updateAll(ctx)
        // requestUpdate binds to the watch's tile service and returns at once, so the receiver
        // holds its process a while for the bind. A shortcut with a fixed hold of HOLD_MS: the
        // upgrade is to finish when the requester reports its bind done, if the library gains a way.
        val done = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({ done.finish() }, HOLD_MS)
    }

    companion object {
        // The hold for the update's bind, in ms; the system allows a receiver about 10 s.
        private const val HOLD_MS = 3000L

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
