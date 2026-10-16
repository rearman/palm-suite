package com.rearman.datebook

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
import java.time.LocalDate
import java.time.LocalDateTime
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
    const val SNOOZE_MINUTES = 5

    fun alarmTime(e: Event, day: Long): Long {
        val startMin = if (e.untimed) 0 else e.start
        val t = LocalDateTime.of(LocalDate.ofEpochDay(day), java.time.LocalTime.MIDNIGHT)
            .plusMinutes(startMin.toLong())
            .minusMinutes(e.advanceMinutes())
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

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

    /** Finds the next alarm time after now and sets the system alarm for it. */
    fun scheduleNext(ctx: Context) {
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toEpochDay()
        var best = Long.MAX_VALUE
        for (e in store.events) {
            if (!e.alarm) continue
            val advDays = e.advanceMinutes() / 1440 + 1
            val last = minOf(Recur.lastDay(e), today + 400 + advDays)
            var d = maxOf(today, e.date)
            while (d <= last) {
                if (Recur.occursOn(e, d)) {
                    val t = alarmTime(e, d)
                    if (t > now) {
                        if (t < best) best = t
                        break
                    }
                }
                if (!e.repeats) break
                d++
            }
        }
        val fire = pi(ctx, ACTION_FIRE, "fire")
        am(ctx).cancel(fire)
        if (best != Long.MAX_VALUE) {
            setExact(ctx, best, pi(ctx, ACTION_FIRE, "fire") { it.putExtra("time", best) })
        }
    }

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
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            ctx, notifId(e, day),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_DAY, day)
                .putExtra(MainActivity.EXTRA_EVENT, e.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
        val publicVersion = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Date Book reminder")
            .setContentText(Fmt.dowDate(day))
            .build()
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
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.scheduleNext(ctx)
    }
}
