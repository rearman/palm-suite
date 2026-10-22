package com.rearman.datebook

import com.rearman.palmsync.PushItem
import com.rearman.palmsync.SyncClient
import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.b64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Test vectors for the Rust port of sync-core in desktop-rs/. Each test runs the Kotlin code on fixed
 * inputs and compares the inputs and outputs with sync-core/testdata/<name>.json, as JSON values.
 * A missing file is written, so a new vector set starts from the Kotlin output. A changed output fails
 * the test and is written to build/vectors/<name>.json: check it, copy it to testdata, and fix the
 * Rust port to match.
 *
 * Records appear in the model form of [ev], [todo], [memo], [addr] and [cat]: every field, long keys.
 */
class VectorsTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private fun check(name: String, actual: JSONObject) {
        val file = File("testdata/$name.json")
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText(actual.toString(1) + "\n")
            println("VectorsTest: wrote ${file.path}")
            return
        }
        if (!JSONObject(file.readText()).similar(actual)) {
            val out = File("build/vectors/$name.json")
            out.parentFile.mkdirs()
            out.writeText(actual.toString(1) + "\n")
            fail("testdata/$name.json no longer matches the Kotlin output. The new output is in ${out.path}.")
        }
    }

    // ---- the model form ----

    private fun ev(e: Event) = JSONObject()
        .put("id", e.id).put("title", e.title).put("note", e.note).put("date", e.date)
        .put("untimed", e.untimed).put("start", e.start).put("end", e.end).put("alarm", e.alarm)
        .put("alarmAdvance", e.alarmAdvance).put("alarmUnit", e.alarmUnit).put("repeat", e.repeat)
        .put("every", e.every).put("repeatEnd", e.repeatEnd).put("weekDays", e.weekDays)
        .put("exceptions", JSONArray(e.exceptions.sorted())).put("private", e.isPrivate).put("uid", e.uid)

    private fun todo(t: Todo) = JSONObject()
        .put("id", t.id).put("title", t.title).put("note", t.note).put("priority", t.priority)
        .put("due", t.due).put("done", t.done).put("category", t.category).put("private", t.isPrivate)
        .put("uid", t.uid)

    private fun memo(m: Memo) = JSONObject()
        .put("id", m.id).put("text", m.text).put("category", m.category).put("private", m.isPrivate)
        .put("pos", m.pos).put("uid", m.uid)

    private fun addr(a: Address) = JSONObject()
        .put("id", a.id).put("lastName", a.lastName).put("firstName", a.firstName).put("title", a.title)
        .put("company", a.company).put("phoneLabels", JSONArray(a.phoneLabels)).put("phones", JSONArray(a.phones))
        .put("address", a.address).put("city", a.city).put("state", a.state).put("zip", a.zip)
        .put("country", a.country).put("custom", JSONArray(a.custom)).put("note", a.note)
        .put("showPhone", a.showPhone).put("category", a.category).put("private", a.isPrivate).put("uid", a.uid)

    private fun cat(c: Category) = JSONObject().put("id", c.id).put("name", c.name).put("app", c.app).put("uid", c.uid)

    private fun events(list: List<Event>) = JSONArray(list.map { ev(it) })

    // ---- shared records ----

    /** Category ids and their sync uids. */
    private val catUids = mapOf(3 to "cat-1", 7 to "cat-7")
    private fun catUid(id: Int) = catUids[id].orEmpty()
    private fun catId(uid: String) = catUids.entries.firstOrNull { it.value == uid }?.key ?: 0

    private val fullAddress = Address(
        lastName = "Müller", firstName = "Jörg", title = "Engineer, Controls", company = "R717; Inc.",
        phoneLabels = listOf(Address.WORK, Address.MOBILE, Address.EMAIL, Address.MAIN, Address.PAGER),
        phones = listOf("555-0100", "555-0101", "jorg@example.com", "555-0103", ""),
        address = "1 Main St\nSuite 2", city = "Springfield", state = "IL", zip = "62701", country = "USA",
        custom = listOf("Birthday 5/1", "", "", "Ham call W9XYZ"), note = "Met at the expo. " + "ö".repeat(50),
        showPhone = 1, category = 3, isPrivate = true
    )

    // ---- vectors ----

    @Test fun crypto() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val kdf = JSONArray()
        for ((pass, rounds) in listOf(
            "correct horse battery staple" to 1,
            "correct horse battery staple" to 1000,
            "pässwörd ✓ 鍵" to 1000,
            "palm" to SyncCrypto.ROUNDS
        )) {
            kdf.put(
                JSONObject().put("passphrase", pass).put("salt", salt.b64()).put("rounds", rounds)
                    .put("key", SyncCrypto.deriveKey(pass.toCharArray(), salt, rounds).b64())
            )
        }
        val key = ByteArray(32) { (it + 7).toByte() }
        val seal = JSONArray()
        val plains = listOf(SyncCrypto.CHECK_TEXT, "{\"k\":\"m\",\"t\":\"Grüße\"}", "x".repeat(1000))
        plains.forEachIndexed { i, p ->
            val nonce = ByteArray(12) { (it + 40 + i).toByte() }
            val id = if (i == 0) SyncCrypto.CHECK_ID else "rec-$i"
            val plain = p.toByteArray(Charsets.UTF_8)
            seal.put(
                JSONObject().put("key", key.b64()).put("id", id).put("plain", plain.b64()).put("nonce", nonce.b64())
                    .put("blob", SyncCrypto.seal(key, id, plain, nonce))
            )
        }
        check("crypto", JSONObject().put("kdf", kdf).put("seal", seal))
    }

    @Test fun pack() {
        val texts = listOf(
            "{\"k\":\"m\",\"t\":\"hi\"}",
            "{\"k\":\"m\",\"t\":\"" + "Palm Desktop ".repeat(40) + "\"}",
            "Grüße " + "ö".repeat(200)
        )
        val arr = JSONArray()
        for (t in texts) arr.put(JSONObject().put("text", t).put("packed", PlainBytes.pack(t).b64()))
        check("pack", JSONObject().put("pack", arr))
    }

    @Test fun codec() {
        val evs = listOf(
            Event(
                title = "Dentist \"Dr. Who\"", note = "first\nsecond", date = 19000, start = 600, end = 660,
                alarm = true, alarmAdvance = 2, alarmUnit = 1, repeat = Repeat.WEEK, every = 2,
                repeatEnd = 19100, weekDays = 0x2A, exceptions = mutableSetOf(19014L, 19007L), isPrivate = true
            ),
            Event(title = "Defaults", date = 0),
            Event(date = 20000, untimed = true, start = 0, end = 0, repeat = Repeat.YEAR),
            Event(title = "Ünïcode ✓   tab\there", date = 20001, start = 1439, end = 1439, alarmAdvance = 99, alarmUnit = 2)
        )
        val events = JSONArray(evs.map {
            JSONObject().put("uid", "u1").put("record", ev(it.copy(uid = "u1"))).put("text", SyncCodec.event(it))
        })
        val tds = listOf(
            Todo(title = "Call", note = "later", priority = 2, due = 19000, done = true, category = 7, isPrivate = true),
            Todo(title = "Plain"),
            Todo(title = "Filed", priority = 5, category = 3)
        )
        val todos = JSONArray(tds.map {
            JSONObject().put("uid", "t1").put("categoryUid", catUid(it.category))
                .put("record", todo(it.copy(uid = "t1"))).put("text", SyncCodec.todo(it, catUid(it.category)))
        })
        val mms = listOf(
            Memo(text = "Title\nbody", category = 3, isPrivate = true, pos = 3),
            Memo(text = "")
        )
        val memos = JSONArray(mms.map {
            JSONObject().put("uid", "m1").put("categoryUid", catUid(it.category))
                .put("record", memo(it.copy(uid = "m1"))).put("text", SyncCodec.memo(it, catUid(it.category)))
        })
        val ads = listOf(fullAddress, Address(lastName = "Only"), Address(company = "Acme", category = 7))
        val addresses = JSONArray(ads.map {
            JSONObject().put("uid", "a1").put("categoryUid", catUid(it.category))
                .put("record", addr(it.copy(uid = "a1"))).put("text", SyncCodec.address(it, catUid(it.category)))
        })
        val cts = listOf(Category(id = 3, name = "Business", app = 0), Category(id = 9, name = "Ideas", app = 1))
        val categories = JSONArray(cts.map {
            JSONObject().put("uid", "c1").put("id", it.id)
                .put("record", cat(it.copy(uid = "c1"))).put("text", SyncCodec.category(it))
        })
        val sc = listOf("br" to "Breakfast", "dts" to "", "" to "x")
        val shortcuts = JSONObject()
            .put("list", JSONArray(sc.map { JSONArray(listOf(it.first, it.second)) }))
            .put("text", SyncCodec.shortcuts(sc))

        // Format 1: the long keys of the first sync format, as an old phone still sends them.
        val v1 = JSONArray()
        val v1Event = "{\"kind\":\"event\",\"title\":\"Old\",\"date\":19000,\"start\":600,\"end\":660,\"exceptions\":[19007],\"private\":true}"
        v1.put(JSONObject().put("kind", "event").put("text", v1Event).put("record", ev(SyncCodec.toEvent(v1Event, "v1"))))
        val v1Todo = "{\"kind\":\"todo\",\"title\":\"Old todo\",\"priority\":3,\"due\":19001,\"done\":true,\"category\":\"cat-7\"}"
        v1.put(JSONObject().put("kind", "todo").put("text", v1Todo).put("record", todo(SyncCodec.toTodo(v1Todo, "v1", ::catId))))
        val v1Memo = "{\"kind\":\"memo\",\"text\":\"Old memo\",\"category\":\"cat-1\",\"pos\":2}"
        v1.put(JSONObject().put("kind", "memo").put("text", v1Memo).put("record", memo(SyncCodec.toMemo(v1Memo, "v1", ::catId))))
        val v1Addr = "{\"kind\":\"address\",\"lastName\":\"Doe\",\"phones\":[\"1\",\"2\"],\"phoneLabels\":[7,9],\"showPhone\":9,\"category\":\"cat-7\"}"
        v1.put(JSONObject().put("kind", "address").put("text", v1Addr).put("record", addr(SyncCodec.toAddress(v1Addr, "v1", ::catId))))
        val v1Cat = "{\"kind\":\"category\",\"name\":\"Business\",\"app\":1}"
        v1.put(JSONObject().put("kind", "category").put("text", v1Cat).put("record", cat(SyncCodec.toCategory(v1Cat, "v1", 4))))
        val v1Sc = "{\"kind\":\"shortcuts\",\"list\":[{\"name\":\"br\",\"text\":\"Breakfast\"}]}"
        v1.put(
            JSONObject().put("kind", "shortcuts").put("text", v1Sc)
                .put("list", JSONArray(SyncCodec.toShortcuts(v1Sc).map { JSONArray(listOf(it.first, it.second)) }))
        )

        val kinds = JSONArray()
        for (t in listOf("{\"k\":\"e\"}", "{\"k\":\"s\",\"l\":[]}", "{\"k\":\"zz\"}", "{\"kind\":\"memo\"}", "{}", "not json", "")) {
            kinds.put(JSONObject().put("text", t).put("kind", SyncCodec.kind(t)))
        }

        check(
            "codec", JSONObject().put("categoryUids", JSONObject(catUids.mapKeys { it.key.toString() }))
                .put("events", events).put("todos", todos).put("memos", memos).put("addresses", addresses)
                .put("categories", categories).put("shortcuts", shortcuts).put("v1", v1).put("kinds", kinds)
        )
    }

    @Test fun recur() {
        val d0 = day(2026, 1, 1)
        val evs = listOf(
            Event(title = "once", date = day(2026, 3, 8)),
            Event(title = "daily", date = d0, repeat = Repeat.DAY),
            Event(title = "every 3 days to May", date = d0, repeat = Repeat.DAY, every = 3, repeatEnd = day(2026, 5, 1), exceptions = mutableSetOf(d0 + 9)),
            Event(title = "weekly on its own day", date = day(2026, 1, 7), repeat = Repeat.WEEK),
            Event(title = "Mon Wed Fri every 2 weeks", date = day(2026, 1, 5), repeat = Repeat.WEEK, every = 2, weekDays = 0x2A),
            Event(title = "starts off its days", date = day(2026, 10, 21), repeat = Repeat.WEEK, every = 2, weekDays = 1 shl 1),
            Event(title = "every day by weekdays", date = d0, repeat = Repeat.WEEK, weekDays = 0x7F),
            Event(title = "2nd Tuesday", date = day(2026, 1, 13), repeat = Repeat.MONTH_DAY),
            Event(title = "last Friday", date = day(2026, 1, 30), repeat = Repeat.MONTH_DAY),
            Event(title = "4th Thursday every 2 months", date = day(2026, 1, 22), repeat = Repeat.MONTH_DAY, every = 2),
            Event(title = "the 31st", date = day(2026, 1, 31), repeat = Repeat.MONTH_DATE),
            Event(title = "the 15th every 3 months", date = day(2026, 2, 15), repeat = Repeat.MONTH_DATE, every = 3),
            Event(title = "leap day", date = day(2024, 2, 29), repeat = Repeat.YEAR),
            Event(title = "every 2 years", date = day(2026, 7, 4), repeat = Repeat.YEAR, every = 2, repeatEnd = day(2032, 12, 31))
        )
        val out = JSONArray()
        for (e in evs) {
            val from = e.date - 3
            val to = e.date + 1500
            val days = (from..to).filter { Recur.occursOn(e, it) }
            out.put(
                JSONObject().put("event", ev(e)).put("firstDay", Recur.firstDay(e)).put("lastDay", Recur.lastDay(e))
                    .put("isDaily", Recur.isDaily(e)).put("describe", Recur.describe(e)).put("shortName", Recur.shortName(e))
                    .put("from", from).put("to", to).put("days", JSONArray(days))
            )
        }
        check("recur", JSONObject().put("events", out))
    }

    @Test fun fmt() {
        val times = JSONArray()
        try {
            for (h24 in listOf(false, true)) {
                Fmt.h24 = h24
                for (min in listOf(0, 59, 60, 719, 720, 779, 1439, 1440)) {
                    times.put(
                        JSONObject().put("h24", h24).put("min", min).put("time", Fmt.time(min))
                            .put("timeShort", Fmt.timeShort(min))
                    )
                }
            }
        } finally {
            Fmt.h24 = false
        }
        val days = JSONArray()
        for (d in listOf(day(2026, 10, 5), day(2000, 1, 1), day(1999, 12, 31), day(2009, 7, 4))) {
            days.put(
                JSONObject().put("day", d).put("dayTitle", Fmt.dayTitle(d)).put("shortDate", Fmt.shortDate(d))
                    .put("dowDate", Fmt.dowDate(d))
            )
        }
        val ordinals = JSONArray()
        for (n in (1..31) + listOf(101, 111, 112, 113, 121)) ordinals.put(JSONObject().put("n", n).put("ordinal", Fmt.ordinal(n)))
        check("fmt", JSONObject().put("times", times).put("days", days).put("ordinals", ordinals))
    }

    private val icalEvents = listOf(
        Event(
            id = 7, title = "Staff, meeting; room 2", note = "line 1\nline 2\\end " + "ö".repeat(60),
            date = day(2026, 10, 19), start = 9 * 60, end = 10 * 60 + 30,
            alarm = true, alarmAdvance = 15, repeat = Repeat.WEEK, every = 2,
            weekDays = (1 shl 1) or (1 shl 3), repeatEnd = day(2026, 12, 31),
            exceptions = mutableSetOf(day(2026, 11, 2)), isPrivate = true
        ),
        Event(id = 8, title = "Birthday", date = day(2027, 3, 5), untimed = true, repeat = Repeat.YEAR, alarm = true, alarmAdvance = 2, alarmUnit = 2),
        Event(id = 9, title = "Review", date = day(2026, 10, 29), start = 14 * 60, end = 15 * 60, repeat = Repeat.MONTH_DAY),
        Event(id = 10, title = "Gym", date = day(2026, 10, 21), start = 8 * 60, repeat = Repeat.WEEK, every = 2, weekDays = 1 shl 1),
        Event(id = 11, title = "Rent", date = day(2026, 1, 31), untimed = true, repeat = Repeat.MONTH_DATE, every = 3, repeatEnd = day(2027, 1, 31), exceptions = mutableSetOf(day(2026, 7, 31))),
        Event(id = 12, title = "Standup", date = day(2026, 3, 2), start = 9 * 60, end = 9 * 60 + 15, repeat = Repeat.DAY, every = 1, alarm = true, alarmAdvance = 1, alarmUnit = 1),
        Event(id = 13, title = "Backwards", date = day(2026, 3, 3), start = 10 * 60, end = 9 * 60),
        Event(id = 14, title = "", date = day(2026, 3, 4), untimed = true)
    )

    private fun cal(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")

    @Test fun ical() {
        val now = Instant.parse("2026-10-22T12:34:56Z")
        val text = ICal.export(icalEvents, now)
        val export = JSONObject().put("now", now.toString()).put("events", events(icalEvents)).put("text", text)

        val files = linkedMapOf(
            "export round trip" to text,
            "google style" to cal(
                "BEGIN:VTIMEZONE", "TZID:America/New_York", "END:VTIMEZONE",
                "BEGIN:VEVENT", "DTSTART:20261020T140000Z", "DTEND:20261020T150000Z", "RRULE:FREQ=DAILY;COUNT=3",
                "UID:abc@google.com", "SUMMARY:Stand", "  up", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-P1D", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;TZID=America/New_York:20261021T100000", "DURATION:PT45M",
                "RECURRENCE-ID:20261021T140000Z", "UID:abc@google.com", "SUMMARY:Stand up (moved)", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;VALUE=DATE:20261101", "DTEND;VALUE=DATE:20261104", "SUMMARY:Trip", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261105T080000", "RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR", "SUMMARY:Report", "END:VEVENT"
            ),
            "triggers" to cal(
                "BEGIN:VEVENT", "DTSTART:20261106T100000", "DTEND:20261106T110000", "SUMMARY:Minutes",
                "BEGIN:VALARM", "TRIGGER:-PT150M", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261107T100000", "SUMMARY:Hours", "BEGIN:VALARM", "TRIGGER;RELATED=START:-PT2H", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261108T100000", "SUMMARY:Mixed", "BEGIN:VALARM", "TRIGGER:-P1DT2H", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261109T100000", "SUMMARY:After", "BEGIN:VALARM", "TRIGGER:PT5M", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T100000", "SUMMARY:Two alarms",
                "BEGIN:VALARM", "TRIGGER:-PT30M", "END:VALARM", "BEGIN:VALARM", "TRIGGER:-PT5M", "END:VALARM", "END:VEVENT"
            ),
            "rules" to cal(
                "BEGIN:VEVENT", "DTSTART:20261106T100000", "DTEND:20261106T110000", "SUMMARY:Water", "RRULE:FREQ=DAILY;INTERVAL=150", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261102T090000", "SUMMARY:Weekdays", "RRULE:FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261102T090000", "SUMMARY:Count weekly", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH;COUNT=5", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;VALUE=DATE:20261110", "SUMMARY:Until date", "RRULE:FREQ=WEEKLY;UNTIL=20261201", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Second Tuesday", "RRULE:FREQ=MONTHLY;BYDAY=2TU", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Wrong weekday", "RRULE:FREQ=MONTHLY;BYDAY=3TU", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Month day", "RRULE:FREQ=MONTHLY;BYMONTHDAY=10;INTERVAL=2", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Other month day", "RRULE:FREQ=MONTHLY;BYMONTHDAY=11", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;VALUE=DATE:20261110", "SUMMARY:Yearly by month", "RRULE:FREQ=YEARLY;BYMONTH=11", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Exdates", "RRULE:FREQ=DAILY", "EXDATE:20261111T090000,20261113T090000", "EXDATE:20261115T090000", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T090000", "SUMMARY:Rdate", "RDATE:20261120T090000", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;VALUE=DATE:20261201", "DTEND;VALUE=DATE:20261204", "SUMMARY:Long trip weekly", "RRULE:FREQ=WEEKLY", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:No start", "END:VEVENT"
            ),
            "zones" to cal(
                "BEGIN:VEVENT", "DTSTART:20260308T073000Z", "DTEND:20260308T083000Z", "SUMMARY:Across the spring change", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261101T063000Z", "DTEND:20261101T073000Z", "SUMMARY:Across the fall change", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;TZID=Europe/Berlin:20261110T180000", "DTEND;TZID=Europe/Berlin:20261110T190000", "SUMMARY:Berlin", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART;TZID=\"W. Europe Standard Time\":20261110T180000", "SUMMARY:Windows zone name", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110", "SUMMARY:Bare date", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261110T230000Z", "RRULE:FREQ=DAILY;UNTIL=20261115T045959Z", "SUMMARY:UTC until", "END:VEVENT"
            ),
            "overnight" to cal(
                "BEGIN:VEVENT", "DTSTART:20261106T220000", "DTEND:20261108T060000", "SUMMARY:Retreat",
                "BEGIN:VALARM", "TRIGGER:-PT10M", "END:VALARM", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261109T230000", "DTEND:20261110T000000", "SUMMARY:Late", "END:VEVENT",
                "BEGIN:VEVENT", "UID:shift", "DTSTART:20261023T220000", "DTEND:20261024T060000",
                "RRULE:FREQ=WEEKLY;BYDAY=FR,SA", "EXDATE:20261030T220000", "SUMMARY:Shift", "END:VEVENT",
                "BEGIN:VEVENT", "UID:shift", "RECURRENCE-ID:20261106T220000", "STATUS:CANCELLED", "DTSTART:20261106T220000", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261023T220000", "DTEND:20261024T060000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SA", "SUMMARY:Biweekly shift", "END:VEVENT",
                "BEGIN:VEVENT", "DTSTART:20261201T080000", "DTEND:20270115T080000", "SUMMARY:Too long", "END:VEVENT"
            ),
            "text forms" to ("﻿BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20261110T090000\nSUMMARY:Escaped \\, \\; \\\\ \\N new\n\tline\n" +
                "DESCRIPTION:Note\nCLASS:CONFIDENTIAL\nX-ITEM.SUMMARY:ignored\nEND:VEVENT\nEND:VCALENDAR\n")
        )
        val zone = ZoneId.of("America/Chicago")
        val parse = JSONArray()
        for ((name, f) in files) {
            val p = ICal.parse(f, zone)
            parse.put(
                JSONObject().put("name", name).put("zone", zone.id).put("text", f).put("events", events(p.events))
                    .put("simplified", p.simplified)
            )
        }
        check("ical", JSONObject().put("export", export).put("parse", parse))
    }

    @Test fun vcard() {
        val names = mapOf(3 to "Business", 7 to "Friends")
        val list = listOf(
            fullAddress,
            Address(company = "Acme Tools", phones = listOf("", "555-0200", "", "", ""), showPhone = 0, category = 7),
            Address(firstName = "Ann", phoneLabels = listOf(Address.FAX, Address.HOME, Address.OTHER, Address.WORK, Address.EMAIL), phones = listOf("1", "2", "3", "4", "5"), showPhone = 4)
        )
        val text = VCard.export(list) { names[it] }
        val export = JSONObject().put("categoryNames", JSONObject(names.mapKeys { it.key.toString() }))
            .put("addresses", JSONArray(list.map { addr(it) })).put("text", text)

        val files = linkedMapOf(
            "export round trip" to text,
            "vcard 2.1" to listOf(
                "BEGIN:VCARD", "VERSION:2.1",
                "N;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:M=FCller;J=F6rg",
                "TEL;CELL:555-1", "TEL;WORK;FAX:555-2", "TEL;PREF;HOME:555-3",
                "NOTE;ENCODING=QUOTED-PRINTABLE:line one=0D=0Aline=", "two",
                "END:VCARD"
            ).joinToString("\r\n"),
            "vcard 4.0" to listOf(
                "BEGIN:VCARD", "VERSION:4.0", "FN:Jean Luc Picard",
                "TEL;VALUE=uri;TYPE=\"cell,voice\":tel:+1-555-3", "EMAIL;PREF=1:jl@example.com", "item1.TEL:555-4",
                "TEL;TYPE=pager:555-5", "TEL;TYPE=main-number:555-6", "TEL;TYPE=work:555-7",
                "ADR;TYPE=work:;;1 Starfleet Way;San Francisco;CA;94016;USA",
                "ADR;TYPE=home:;;La Barre;;;;France",
                "CATEGORIES:Work,Home", "CLASS:private", "END:VCARD",
                "BEGIN:VCARD", "VERSION:3.0", "FN:Acme Tools", "ORG:Acme Tools;Sales", "END:VCARD",
                "BEGIN:VCARD", "VERSION:3.0", "END:VCARD"
            ).joinToString("\n")
        )
        val parse = JSONArray()
        for ((name, f) in files) {
            val cards = JSONArray(VCard.parse(f).map {
                JSONObject().put("address", addr(it.address)).put("category", it.categoryName ?: JSONObject.NULL)
            })
            parse.put(JSONObject().put("name", name).put("text", f).put("cards", cards))
        }
        check("vcard", JSONObject().put("export", export).put("parse", parse))
    }

    private fun content(c: Backup.Content) = JSONObject()
        .put("events", events(c.events)).put("todos", JSONArray(c.todos.map { todo(it) }))
        .put("memos", JSONArray(c.memos.map { memo(it) })).put("addresses", JSONArray(c.addresses.map { addr(it) }))
        .put("todoCategories", JSONArray(c.todoCategories)).put("memoCategories", JSONArray(c.memoCategories))
        .put("addressCategories", JSONArray(c.addressCategories))

    @Test fun backup() {
        val names = mapOf(3 to "Business", 7 to "Friends", 9 to "Personal")
        val c = Backup.Content(
            events = icalEvents.take(3),
            todos = listOf(
                Todo(title = "Call", note = "later", priority = 2, due = 19000, done = true, category = 7, isPrivate = true),
                Todo(title = "Plain")
            ),
            memos = listOf(Memo(text = "Title\nbody", category = 9), Memo(text = "Loose")),
            addresses = listOf(fullAddress, Address(lastName = "Only")),
            todoCategories = listOf("Business", "Empty"),
            memoCategories = emptyList(),
            addressCategories = listOf("business")
        )
        val text = Backup.write(c) { names[it] ?: "" }
        val write = JSONObject().put("categoryNames", JSONObject(names.mapKeys { it.key.toString() }))
            .put("content", content(c)).put("text", text)

        val files = linkedMapOf(
            "write round trip" to text,
            "desktop file" to "{\"format\":\"datebook-backup-1\",\"categories\":[\"Business\"],\"memoCategories\":[\"Notes\"]," +
                "\"events\":[{\"title\":\"Min\",\"date\":20000}],\"todos\":[{\"title\":\"T\",\"category\":\"Unfiled\"},{\"title\":\"U\",\"category\":\"\"}," +
                "{\"title\":\"V\",\"category\":\"Business\"}],\"memos\":[{\"text\":\"M\",\"category\":\"Notes\"},{\"text\":\"N\"}]}",
            "short address lists" to "{\"format\":\"datebook-backup-1\",\"addresses\":[{\"lastName\":\"L\",\"phoneLabels\":[9,-1],\"phones\":[\"1\"]," +
                "\"custom\":[\"a\",\"b\",\"c\",\"d\",\"e\"],\"showPhone\":7,\"category\":\"Clients\"}]}",
            "wrong format" to "{\"format\":\"something-else\",\"events\":[]}",
            "not json" to "Palm Desktop"
        )
        val read = JSONArray()
        for ((name, f) in files) {
            val ids = LinkedHashMap<String, Int>()
            val calls = JSONArray()
            val entry = JSONObject().put("name", name).put("text", f)
            try {
                val got = Backup.read(f) { n, app ->
                    calls.put(JSONArray(listOf(n, app)))
                    ids.getOrPut("$app/$n") { 100 + ids.size }
                }
                entry.put("content", content(got)).put("calls", calls)
            } catch (e: Exception) {
                entry.put("error", true)
            }
            read.put(entry)
        }
        check("backup", JSONObject().put("write", write).put("read", read))
    }

    @Test fun client() {
        val push = listOf(PushItem("a", 3, false, "blobA"), PushItem("b", 0, true, "ignored"))
        val body = JSONObject().put("since", 42).put("text", SyncClient.pushBody(42, push))
            .put("push", JSONArray(push.map {
                JSONObject().put("id", it.id).put("baseRev", it.baseRev).put("deleted", it.deleted).put("blob", it.blob)
            }))
        val results = JSONArray()
        for (t in listOf(
            "{\"rev\":10,\"accepted\":[{\"id\":\"a\",\"rev\":9}],\"conflicts\":[\"b\",\"a\"]," +
                "\"changes\":[{\"id\":\"c\",\"rev\":10,\"deleted\":false,\"blob\":\"xyz\"},{\"id\":\"d\",\"rev\":8,\"deleted\":true}]}",
            "{\"rev\":0}"
        )) {
            val r = SyncClient.parseResult(t)
            results.put(
                JSONObject().put("text", t).put("rev", r.rev).put("accepted", JSONObject(r.accepted))
                    .put("conflicts", JSONArray(r.conflicts.sorted()))
                    .put("changes", JSONArray(r.changes.map {
                        JSONObject().put("id", it.id).put("rev", it.rev).put("deleted", it.deleted).put("blob", it.blob)
                    }))
            )
        }
        val metas = JSONArray()
        for (t in listOf("{\"salt\":\"s\",\"check\":\"c\"}", "{}")) {
            val m = SyncClient.parseMeta(t)
            metas.put(JSONObject().put("text", t).put("salt", m.salt).put("check", m.check))
        }
        check("client", JSONObject().put("pushBody", body).put("parseResult", results).put("parseMeta", metas))
    }

    @Test fun logic() {
        val decisions = JSONArray()
        for (local in LocalState.values()) for (deleted in listOf(false, true)) for (same in listOf(false, true))
            for (lastWins in listOf(false, true)) {
                decisions.put(
                    JSONObject().put("local", local.name).put("remoteDeleted", deleted).put("same", same)
                        .put("lastWins", lastWins).put("action", decide(local, deleted, same, lastWins).name)
                )
            }

        val d = day(2026, 10, 20)
        fun occ(start: Int, end: Int) = Occ(Event(date = d, start = start, end = end), d)
        val free = JSONArray()
        for ((timed, ends) in listOf(
            emptyList<Occ>() to false,
            listOf(occ(9 * 60, 10 * 60 + 30), occ(13 * 60, 13 * 60)) to false,
            listOf(occ(9 * 60, 10 * 60 + 30), occ(13 * 60, 13 * 60)) to true,
            listOf(occ(7 * 60 + 15, 8 * 60 + 45), occ(16 * 60 + 50, 24 * 60 - 1)) to true
        )) {
            free.put(
                JSONObject().put("timed", JSONArray(timed.map { JSONArray(listOf(it.event.start, it.event.end)) }))
                    .put("dayStart", 8).put("dayEnd", 18).put("ends", ends)
                    .put("free", JSONArray(freeTimes(timed, 8, 18, ends)))
            )
        }

        val orig = Event(id = 5, title = "Class", date = day(2026, 10, 5), start = 600, end = 660, repeat = Repeat.WEEK,
            exceptions = mutableSetOf(day(2026, 10, 12), day(2026, 10, 26), day(2026, 11, 9)), uid = "s1")
        val split = JSONArray()
        for ((occDay, workDate) in listOf(day(2026, 10, 19) to day(2026, 10, 20), day(2026, 10, 5) to day(2026, 10, 5))) {
            val work = orig.deepCopy().apply { date = workDate; title = "Class moved" }
            val (shortened, series) = splitFuture(orig, occDay, work)
            split.put(
                JSONObject().put("orig", ev(orig)).put("occDay", occDay).put("work", ev(work))
                    .put("shortened", if (shortened == null) JSONObject.NULL else ev(shortened)).put("series", ev(series))
            )
        }

        val dup = JSONArray()
        for (e in listOf(
            Event(id = 3, uid = "x", title = "U", date = d, untimed = true, start = 100, end = 200, alarmAdvance = 9, alarmUnit = 1, every = 4, repeatEnd = d + 9, weekDays = 3, exceptions = mutableSetOf(d + 1)),
            Event(title = "W", date = day(2026, 10, 21), repeat = Repeat.WEEK, every = 2, weekDays = 1 shl 1, alarm = true, alarmAdvance = 9),
            Event(title = "W0", date = day(2026, 10, 21), repeat = Repeat.WEEK)
        )) dup.put(JSONObject().put("event", ev(e)).put("key", ev(e.dupKey())))

        val names = JSONArray()
        for (a in listOf(
            fullAddress, Address(), Address(company = "Acme"), Address(firstName = "Ann", company = "Acme"),
            Address(lastName = "Lee", phones = listOf("", "", "x@y.z", "", ""), phoneLabels = listOf(0, 1, 4, 3, 2)),
            Address(lastName = "Kim", phones = listOf("1", "", "", "", ""), showPhone = 3)
        )) {
            val phone = a.listPhone()
            names.put(
                JSONObject().put("address", addr(a)).put("byName", a.listName(false)).put("byCompany", a.listName(true))
                    .put("phone", if (phone == null) JSONObject.NULL else JSONArray(listOf(phone.first, phone.second.toString())))
                    .put("isEmpty", a.isEmpty)
            )
        }

        val memoList = listOf(
            Memo(id = 1, text = "beta\nx", pos = 2), Memo(id = 2, text = "Alpha", pos = 0),
            Memo(id = 3, text = "  alpha  \ny", pos = 1), Memo(id = 4, text = "", pos = 3)
        )
        val memoOrders = JSONObject().put("memos", JSONArray(memoList.map { memo(it) }))
            .put("alphabetic", JSONArray(memoOrder(memoList, true).map { it.id }))
            .put("manual", JSONArray(memoOrder(memoList, false).map { it.id }))
        val moved = memoOrder(memoList.map { it.copy() }, false)
        val changed = moveMemo(moved, 0, 3)
        memoOrders.put("move", JSONObject().put("from", 0).put("to", 3)
            .put("order", JSONArray(moved.sortedBy { it.pos }.map { it.id })).put("changed", JSONArray(changed.map { it.id })))

        val addrList = listOf(
            Address(id = 1, lastName = "smith", company = "Zeta"), Address(id = 2, lastName = "Smith", firstName = "Al"),
            Address(id = 3, company = "Acme"), Address(id = 4)
        )
        val addrOrders = JSONObject().put("addresses", JSONArray(addrList.map { addr(it) }))
            .put("byName", JSONArray(addressOrder(addrList, false).map { it.id }))
            .put("byCompany", JSONArray(addressOrder(addrList, true).map { it.id }))

        val cats = mutableListOf(Category(4, "work"), Category(2, "Personal"), Category(3, "Work"), Category(1, "ideas"))
        val catsIn = JSONArray(cats.map { cat(it) })
        sortCategories(cats)
        val catSort = JSONObject().put("categories", catsIn).put("sorted", JSONArray(cats.map { it.id }))

        check(
            "logic", JSONObject().put("decide", decisions).put("freeTimes", free).put("splitFuture", split)
                .put("dupKey", dup).put("addressNames", names).put("memoOrder", memoOrders)
                .put("addressOrder", addrOrders).put("sortCategories", catSort)
        )
    }
}
