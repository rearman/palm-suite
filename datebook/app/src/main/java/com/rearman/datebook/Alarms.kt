package com.rearman.datebook

import com.rearman.palmui.*
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZoneId

/**
 * Palm-style alarms: one system alarm is set for the next reminder.
 * When it rings, every event due at that minute shows a notification.
 * "Remind" and "Play every" from Preferences repeat the alert until you tap OK.
 */
object Alarms {
    const val CHANNEL = "datebook_alarms"
    const val ACTION_FIRE = "com.rearman.datebook.FIRE"
    const val ACTION_REMIND = "com.rearman.datebook.REMIND"
    const val ACTION_SNOOZE = "com.rearman.datebook.SNOOZE"
    const val ACTION_SNOOZED = "com.rearman.datebook.SNOOZED"
    const val ACTION_OK = "com.rearman.datebook.OK"
    // The next start or end of an alarmed timed event: the Live Update shows or goes.
    const val ACTION_LIVE = "com.rearman.datebook.LIVE"
    // The Live Update channel, and the tag that keeps its notification ids apart from the alarms.
    const val CHANNEL_NOW = "datebook_now"
    private const val LIVE_TAG = "live"
    const val SNOOZE_MINUTES = 5

    // The Live Update and the watch agenda move on at midnight.
    const val ACTION_DAY = "com.rearman.datebook.DAY"

    fun alarmTime(e: Event, day: Long): Long =
        Recur.millis(day, (if (e.untimed) 0 else e.start) - e.advanceMinutes())

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(ctx: Context, action: String, data: String, extras: (Intent) -> Unit = {}): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action).setData(Uri.parse("datebook://$data"))
        extras(i)
        return PendingIntent.getBroadcast(
            ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setExact(ctx: Context, time: Long, p: PendingIntent) {
        val a = am(ctx)
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || a.canScheduleExactAlarms()
        if (exactOk) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p)
        else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p)
    }

    private fun at(day: Long, minutes: Int): Long = Recur.millis(day, minutes.toLong())

    // A receiver can run in a new process, where only MainActivity would have set the 12 or 24 hour clock.
    private fun clock(ctx: Context) {
        Fmt.h24 = DateFormat.is24HourFormat(ctx)
    }

    /** The first day, from today on, of an occurrence of [e] whose [time] is after [now], or null. */
    private fun nextDay(e: Event, today: Long, now: Long, time: (Long) -> Long): Long? {
        val advDays = e.advanceMinutes() / 1440 + 1
        val last = minOf(Recur.lastDay(e), today + 400 + advDays)
        var d = maxOf(today, e.date)
        while (d <= last) {
            if (Recur.occursOn(e, d) && time(d) > now) return d
            if (!e.repeats) return null
            d++
        }
        return null
    }

    /** A timed event with Alarm on gets a Live Update from its start to its end. */
    private fun hasLive(e: Event) = e.alarm && !e.untimed && e.end > e.start

    private fun liveOn() = Build.VERSION.SDK_INT >= 36

    // Promotion (the status bar chip) came in Android 16 QPR2, API 36.1: on 36.0 the Builder has no
    // setRequestPromotedOngoing or setShortCriticalText, and calling them throws NoSuchMethodError.
    private fun promoteOn() = Build.VERSION.SDK_INT > 36 ||
        (Build.VERSION.SDK_INT == 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1)

    /**
     * Finds the next alarm time after now and sets the system alarm for it. On Android 16 and
     * later it also sets the alarm for the next start or end of a Live Update, and brings the
     * Live Updates up to date, so an edit or delete removes a stale one. A third alarm, at
     * midnight, moves the Live Updates and the watch agenda on to the new day.
     */
    fun scheduleNext(ctx: Context) {
        clock(ctx)
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toEpochDay()
        var best = Long.MAX_VALUE
        var live = Long.MAX_VALUE
        for (e in store.events) {
            if (!e.alarm) continue
            nextDay(e, today, now) { alarmTime(e, it) }?.let { best = minOf(best, alarmTime(e, it)) }
            if (liveOn() && hasLive(e)) {
                // The next occurrence that has not ended: its start, or its end when it is in progress.
                nextDay(e, today, now) { at(it, e.end) }?.let { d ->
                    val start = at(d, e.start)
                    live = minOf(live, if (start > now) start else at(d, e.end))
                }
            }
        }
        // Inexact: a few minutes late at midnight does no harm.
        am(ctx).set(AlarmManager.RTC, Recur.millis(today + 1, 0), pi(ctx, ACTION_DAY, "day"))
        val fire = pi(ctx, ACTION_FIRE, "fire")
        am(ctx).cancel(fire)
        if (best != Long.MAX_VALUE) {
            setExact(ctx, best, pi(ctx, ACTION_FIRE, "fire") { it.putExtra("time", best) })
        }
        am(ctx).cancel(pi(ctx, ACTION_LIVE, "live"))
        if (live != Long.MAX_VALUE) setExact(ctx, live, pi(ctx, ACTION_LIVE, "live"))
        if (liveOn()) refreshLive(ctx, store, now)
    }

    /**
     * Shows a Live Update for each alarmed timed event in progress now, and removes the others.
     * The chronometer counts down to the end, so the notification needs no update while it shows.
     * Before API 36.1, or when the system refuses promotion, it shows as an ordinary one. A
     * notification the system rejects is left out: this runs in onResume and at boot, so a
     * throw here would stop the app at every start.
     */
    private fun refreshLive(ctx: Context, store: Store, now: Long) {
        if (Build.VERSION.SDK_INT < 36) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val today = LocalDate.now().toEpochDay()
        val active = store.events.filter {
            hasLive(it) && Recur.occursOn(it, today) && at(today, it.start) <= now && now < at(today, it.end)
        }
        val keep = active.map { notifId(it, today) }.toSet()
        for (sbn in nm.activeNotifications) {
            if (sbn.tag == LIVE_TAG && sbn.id !in keep) nm.cancel(LIVE_TAG, sbn.id)
        }
        if (active.isEmpty()) return
        ensureNowChannel(ctx)
        for (e in active) {
            val end = at(today, e.end)
            val until = "until ${Fmt.time(e.end)}"
            val b = Notification.Builder(ctx, CHANNEL_NOW)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(e.title.ifBlank { "(no description)" })
                .setContentText(until)
                .setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(openIntent(ctx, e, today))
                .setWhen(end)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // A backup for the end alarm: the system removes it at the end.
                .setTimeoutAfter(end - now)
                .setVisibility(if (e.isPrivate) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
                .setPublicVersion(publicVersion(ctx, CHANNEL_NOW, "Date Book event", until))
            if (promoteOn()) {
                // The status bar chip: the end time with am or pm ("3:00 pm"), to fit its few letters.
                b.setShortCriticalText(Fmt.time(e.end))
                b.setRequestPromotedOngoing(nm.canPostPromotedNotifications())
            }
            try {
                nm.notify(LIVE_TAG, notifId(e, today), b.build())
            } catch (_: RuntimeException) {
                // SecurityException (no permission) or a rejected notification: no Live Update.
            }
        }
    }

    private fun ensureNowChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_NOW) != null) return
        val ch = NotificationChannel(CHANNEL_NOW, "Event in progress", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "The event in progress, until its end"
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        nm.createNotificationChannel(ch)
    }

    /** Opens the Date Book on the event. */
    private fun openIntent(ctx: Context, e: Event, day: Long): PendingIntent = PendingIntent.getActivity(
        ctx, notifId(e, day),
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_DAY, day)
            .putExtra(MainActivity.EXTRA_EVENT, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The lock screen form of a private record: no title. */
    private fun publicVersion(ctx: Context, channel: String, title: String, text: String): Notification =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .build()

    /** All occurrences whose alarm rings at this time (to the minute). */
    fun dueAt(ctx: Context, time: Long): List<Occ> {
        val store = Store.get(ctx)
        val zone = ZoneId.systemDefault()
        val fireDay = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay()
        val out = mutableListOf<Occ>()
        for (e in store.events) {
            if (!e.alarm) continue
            val advDays = e.advanceMinutes() / 1440 + 1
            for (d in fireDay..fireDay + advDays) {
                if (Recur.occursOn(e, d) && Math.abs(alarmTime(e, d) - time) < 30_000) {
                    out.add(Occ(e, d))
                }
            }
        }
        return out
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Date Book alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Event reminders"
        ch.enableVibration(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        nm.createNotificationChannel(ch)
    }

    fun notifId(e: Event, day: Long): Int = (e.id * 1009 + day).toInt()

    private fun key(e: Event, day: Long) = "${e.id}/$day"

    fun show(ctx: Context, e: Event, day: Long) {
        clock(ctx)
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = openIntent(ctx, e, day)
        val ok = pi(ctx, ACTION_OK, "ok/${key(e, day)}") {
            it.putExtra("id", e.id); it.putExtra("day", day)
        }
        val snooze = pi(ctx, ACTION_SNOOZE, "snooze/${key(e, day)}") {
            it.putExtra("id", e.id); it.putExtra("day", day)
        }
        val title = e.title.ifBlank { "(no description)" }
        val text = (if (e.untimed) "" else "${Fmt.time(e.start)} - ${Fmt.time(e.end)}, ") +
            Fmt.dowDate(day)
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat)
        val publicVersion = publicVersion(ctx, CHANNEL, "Date Book reminder", Fmt.dowDate(day))
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setDeleteIntent(ok)
            .setAutoCancel(true)
            .setVisibility(if (e.isPrivate) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .addAction(Notification.Action.Builder(icon, "OK", ok).build())
            .addAction(Notification.Action.Builder(icon, "Snooze", snooze).build())
            // One id on both devices: OK or a dismiss on the phone or the watch clears the other.
            .extend(Notification.WearableExtender().setDismissalId("alarm/${key(e, day)}"))
            .build()
        try {
            nm.notify(notifId(e, day), n)
        } catch (_: SecurityException) {
        }
    }

    fun scheduleRemind(ctx: Context, e: Event, day: Long, remaining: Int, everyMin: Int) {
        val p = pi(ctx, ACTION_REMIND, "remind/${key(e, day)}") {
            it.putExtra("id", e.id); it.putExtra("day", day); it.putExtra("left", remaining)
        }
        setExact(ctx, System.currentTimeMillis() + everyMin * 60_000L, p)
    }

    fun cancelRemind(ctx: Context, id: Long, day: Long) {
        am(ctx).cancel(pi(ctx, ACTION_REMIND, "remind/$id/$day"))
    }

    fun snooze(ctx: Context, id: Long, day: Long) {
        val p = pi(ctx, ACTION_SNOOZED, "snoozed/$id/$day") {
            it.putExtra("id", id); it.putExtra("day", day)
        }
        setExact(ctx, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, p)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store.get(ctx)
        val prefs = Prefs(ctx)
        val id = intent.getLongExtra("id", 0)
        val day = intent.getLongExtra("day", 0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            Alarms.ACTION_FIRE -> {
                val time = intent.getLongExtra("time", 0)
                if (time != prefs.lastFired) {
                    prefs.lastFired = time
                    for (o in Alarms.dueAt(ctx, time)) {
                        Alarms.show(ctx, o.event, o.day)
                        if (prefs.remindTimes > 1) {
                            Alarms.scheduleRemind(ctx, o.event, o.day, prefs.remindTimes - 1, prefs.remindEvery)
                        }
                    }
                }
                Alarms.scheduleNext(ctx)
            }
            Alarms.ACTION_REMIND -> {
                val e = store.event(id) ?: return
                val left = intent.getIntExtra("left", 0)
                Alarms.show(ctx, e, day)
                if (left > 1) Alarms.scheduleRemind(ctx, e, day, left - 1, prefs.remindEvery)
            }
            Alarms.ACTION_OK -> {
                Alarms.cancelRemind(ctx, id, day)
                store.event(id)?.let { nm.cancel(Alarms.notifId(it, day)) }
            }
            Alarms.ACTION_SNOOZE -> {
                Alarms.cancelRemind(ctx, id, day)
                store.event(id)?.let { nm.cancel(Alarms.notifId(it, day)) }
                Alarms.snooze(ctx, id, day)
            }
            Alarms.ACTION_SNOOZED -> {
                val e = store.event(id) ?: return
                Alarms.show(ctx, e, day)
            }
            Alarms.ACTION_LIVE -> Alarms.scheduleNext(ctx)
            Alarms.ACTION_DAY -> {
                Alarms.scheduleNext(ctx)
                WearBridge.push(ctx)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.scheduleNext(ctx)
        WearBridge.push(ctx)
    }
}
