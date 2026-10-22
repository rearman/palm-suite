//! iCalendar (RFC 5545) files of Date Book events, as ICal.kt. Date Book has no time zones, so export
//! writes floating local times, and import changes UTC and TZID times to the given time zone.

use crate::days::{dow, epoch_day, month_length, ymd};
use crate::model::{first_day, occurs_on, repeat, week_index, Event};
use jiff::civil::DateTime;
use jiff::tz::TimeZone;
use jiff::Timestamp;
use std::collections::HashMap;

const DAY_CODES: [&str; 7] = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];
const RULE_PARTS: [&str; 8] = [
	"FREQ",
	"INTERVAL",
	"UNTIL",
	"COUNT",
	"BYDAY",
	"BYMONTHDAY",
	"BYMONTH",
	"WKST",
];
const DAY_SECS: i64 = 86_400;

// ---- local date-times, as seconds after 1970-01-01 00:00 with no zone ----

fn day_of(t: i64) -> i64 {
	t.div_euclid(DAY_SECS)
}

fn hour_of(t: i64) -> i32 {
	(t.rem_euclid(DAY_SECS) / 3600) as i32
}

fn minute_of(t: i64) -> i32 {
	(t.rem_euclid(DAY_SECS) % 3600 / 60) as i32
}

fn is_midnight(t: i64) -> bool {
	t.rem_euclid(DAY_SECS) == 0
}

fn ymd_text(day: i64) -> String {
	let d = ymd(day);
	format!("{:04}{:02}{:02}", d.year, d.month, d.day)
}

/// "yyyyMMdd'T'HHmmss" of a local time.
fn time_text(t: i64) -> String {
	let s = t.rem_euclid(DAY_SECS);
	format!(
		"{}T{:02}{:02}{:02}",
		ymd_text(day_of(t)),
		s / 3600,
		s % 3600 / 60,
		s % 60
	)
}

fn digits(s: &str) -> Option<u32> {
	if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
		return None;
	}
	s.parse().ok()
}

/// A "yyyyMMdd" date, as Java's smart resolver reads it: a day 29 to 31 past the month end becomes
/// the last day of the month.
fn parse_date(s: &str) -> Option<i64> {
	if s.len() != 8 || !s.is_ascii() {
		return None;
	}
	let (y, m, d) = (digits(&s[0..4])?, digits(&s[4..6])?, digits(&s[6..8])?);
	if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
		return None;
	}
	Some(epoch_day(y as i64, m, d.min(month_length(y as i64, m))))
}

/// A "yyyyMMdd'T'HHmmss" local time. 24:00:00 is the midnight that ends the day.
fn parse_time(s: &str) -> Option<i64> {
	if s.len() != 15 || !s.is_ascii() || &s[8..9] != "T" {
		return None;
	}
	let day = parse_date(&s[0..8])?;
	let (h, mi, sec) = (digits(&s[9..11])?, digits(&s[11..13])?, digits(&s[13..15])?);
	if mi > 59 || sec > 59 || h > 24 || (h == 24 && (mi > 0 || sec > 0)) {
		return None;
	}
	Some(day * DAY_SECS + (h * 3600 + mi * 60 + sec) as i64)
}

fn to_civil(t: i64) -> Option<DateTime> {
	let d = ymd(day_of(t));
	let s = t.rem_euclid(DAY_SECS);
	DateTime::new(
		i16::try_from(d.year).ok()?,
		d.month as i8,
		d.day as i8,
		(s / 3600) as i8,
		(s % 3600 / 60) as i8,
		(s % 60) as i8,
		0,
	)
	.ok()
}

fn from_civil(dt: DateTime) -> i64 {
	epoch_day(dt.year() as i64, dt.month() as u32, dt.day() as u32) * DAY_SECS
		+ dt.hour() as i64 * 3600
		+ dt.minute() as i64 * 60
		+ dt.second() as i64
}

/// The local time in `to` of local time `t` in `from`. A time in a gap moves forward by the gap; a time
/// in an overlap takes the earlier offset, as Java's atZone.
fn convert(t: i64, from: &TimeZone, to: &TimeZone) -> Option<i64> {
	let z = from.to_zoned(to_civil(t)?).ok()?;
	Some(from_civil(z.with_time_zone(to.clone()).datetime()))
}

// ---- export ----

/// Writes `events` as one VCALENDAR with CRLF line ends. `now` is the DTSTAMP.
pub fn export(events: &[Event], now: Timestamp) -> String {
	let mut out = String::new();
	let stamp = time_text(now.as_second()) + "Z";
	let at = |day: i64, min: i32| time_text(day * DAY_SECS + min as i64 * 60);
	let mut line = |s: &str| fold(s, &mut out);
	line("BEGIN:VCALENDAR");
	line("VERSION:2.0");
	line("PRODID:-//rearman//Date Book//EN");
	for e in events {
		let d = first_day(e);
		line("BEGIN:VEVENT");
		line(&format!("UID:{}@com.rearman.datebook", e.id));
		line(&format!("DTSTAMP:{stamp}"));
		if e.untimed {
			line(&format!("DTSTART;VALUE=DATE:{}", ymd_text(d)));
			line(&format!("DTEND;VALUE=DATE:{}", ymd_text(d + 1)));
		} else {
			line(&format!("DTSTART:{}", at(d, e.start)));
			line(&format!("DTEND:{}", at(d, e.start.max(e.end))));
		}
		line(&format!("SUMMARY:{}", escape(&e.title)));
		if !e.note.is_empty() {
			line(&format!("DESCRIPTION:{}", escape(&e.note)));
		}
		if e.is_private {
			line("CLASS:PRIVATE");
		}
		if let Some(r) = rule(e) {
			line(&format!("RRULE:{r}"));
		}
		if e.repeats() {
			for x in &e.exceptions {
				if e.untimed {
					line(&format!("EXDATE;VALUE=DATE:{}", ymd_text(*x)));
				} else {
					line(&format!("EXDATE:{}", at(*x, e.start)));
				}
			}
		}
		if e.alarm {
			let n = e.alarm_advance;
			line("BEGIN:VALARM");
			line("ACTION:DISPLAY");
			line(&format!("DESCRIPTION:{}", escape(&e.title)));
			line(&match e.alarm_unit {
				0 => format!("TRIGGER:-PT{n}M"),
				1 => format!("TRIGGER:-PT{n}H"),
				_ => format!("TRIGGER:-P{n}D"),
			});
			line("END:VALARM");
		}
		line("END:VEVENT");
	}
	line("END:VCALENDAR");
	out
}

/// BYDAY of a "Nth weekday of the month" repeat: "3MO", or "-1MO" for the last one.
fn week_code(day: i64) -> String {
	let w = week_index(day);
	format!(
		"{}{}",
		if w == 5 { "-1".to_string() } else { w.to_string() },
		DAY_CODES[dow(day) as usize]
	)
}

/// The RRULE value of `e`, or None for a one-time event.
fn rule(e: &Event) -> Option<String> {
	let mut r = String::from("FREQ=");
	match e.repeat {
		repeat::DAY => r.push_str("DAILY"),
		repeat::WEEK => {
			let mask = if e.week_days == 0 {
				1 << dow(e.date)
			} else {
				e.week_days
			};
			let days = (0..7)
				.filter(|i| mask & (1 << i) != 0)
				.map(|i| DAY_CODES[i])
				.collect::<Vec<_>>()
				.join(",");
			r.push_str(&format!("WEEKLY;WKST=SU;BYDAY={days}"));
		}
		repeat::MONTH_DAY => r.push_str(&format!("MONTHLY;BYDAY={}", week_code(e.date))),
		repeat::MONTH_DATE => r.push_str(&format!("MONTHLY;BYMONTHDAY={}", ymd(e.date).day)),
		repeat::YEAR => r.push_str("YEARLY"),
		_ => return None,
	}
	if e.every > 1 {
		r.push_str(&format!(";INTERVAL={}", e.every));
	}
	if e.repeat_end >= 0 {
		r.push_str(";UNTIL=");
		if e.untimed {
			r.push_str(&ymd_text(e.repeat_end));
		} else {
			r.push_str(&time_text(e.repeat_end * DAY_SECS + 23 * 3600 + 59 * 60 + 59));
		}
	}
	Some(r)
}

pub fn escape(s: &str) -> String {
	s.replace('\\', "\\\\")
		.replace(';', "\\;")
		.replace(',', "\\,")
		.replace('\r', "")
		.replace('\n', "\\n")
}

/// Appends `s` as content lines of at most 75 octets, each with a CRLF end.
pub fn fold(s: &str, out: &mut String) {
	let mut n = 0;
	for c in s.chars() {
		let bytes = c.len_utf8();
		if n + bytes > 75 {
			out.push_str("\r\n ");
			n = 1;
		}
		out.push(c);
		n += bytes;
	}
	out.push_str("\r\n");
}

pub fn unescape(s: &str) -> String {
	let mut b = String::with_capacity(s.len());
	let mut it = s.chars().peekable();
	while let Some(c) = it.next() {
		if c == '\\' {
			if let Some(n) = it.next() {
				b.push(if n == 'n' || n == 'N' { '\n' } else { n });
				continue;
			}
		}
		b.push(c);
	}
	b
}

// ---- import ----

/// The events of a file, and the number of file events that Date Book could not show in full.
#[derive(Clone, Debug, Default)]
pub struct Parsed {
	pub events: Vec<Event>,
	pub simplified: usize,
}

struct Prop {
	name: String,
	params: HashMap<String, String>,
	value: String,
}

/// One VEVENT, as one Date Book event for each day it covers. An item with a recurrence day changes
/// one occurrence of the series with the same uid.
struct Item {
	uid: String,
	recurrence_day: Option<i64>,
	events: Vec<Event>,
	simplified: bool,
}

/// Reads every VEVENT of `text`; UTC and TZID times change to local times in `zone`.
pub fn parse(text: &str, zone: &TimeZone) -> Parsed {
	let mut items: Vec<Item> = Vec::new();
	let mut stack: Vec<String> = Vec::new();
	let mut props: Vec<Prop> = Vec::new();
	let mut trigger: Option<Prop> = None;
	for l in unfold(text) {
		let Some(p) = prop(&l) else { continue };
		match p.name.as_str() {
			"BEGIN" => {
				stack.push(p.value.trim().to_uppercase());
				if stack.last().map(String::as_str) == Some("VEVENT") {
					props = Vec::new();
					trigger = None;
				}
			}
			"END" => {
				if stack.pop().as_deref() == Some("VEVENT") {
					if let Some(it) = item(&props, trigger.as_ref(), zone) {
						items.push(it);
					}
				}
			}
			_ => {
				let n = stack.len();
				if stack.last().map(String::as_str) == Some("VEVENT") {
					props.push(p);
				} else if n >= 2
					&& stack[n - 1] == "VALARM"
					&& stack[n - 2] == "VEVENT"
					&& p.name == "TRIGGER"
					&& trigger.is_none()
				{
					trigger = Some(p);
				}
			}
		}
	}
	// A changed or cancelled occurrence becomes an exception of its series, in each day part.
	let mut marks = Vec::new();
	for o in &items {
		let Some(day) = o.recurrence_day else { continue };
		if o.uid.is_empty() {
			continue;
		}
		if let Some(s) = items
			.iter()
			.position(|it| it.recurrence_day.is_none() && it.uid == o.uid)
		{
			marks.push((s, day));
		}
	}
	for (s, day) in marks {
		for (k, ev) in items[s].events.iter_mut().enumerate() {
			if ev.repeats() {
				ev.exceptions.insert(day + k as i64);
			}
		}
	}
	let simplified = items.iter().filter(|it| it.simplified).count();
	Parsed {
		events: items.into_iter().flat_map(|it| it.events).collect(),
		simplified,
	}
}

fn item(props: &[Prop], trigger: Option<&Prop>, zone: &TimeZone) -> Option<Item> {
	let first = |n: &str| props.iter().find(|p| p.name == n);
	let ds = first("DTSTART")?;
	let start = time(&ds.params, &ds.value, zone)?;
	let untimed = is_date(&ds.params, &ds.value);
	let uid = first("UID").map(|p| p.value.trim().to_string()).unwrap_or_default();
	let rid = first("RECURRENCE-ID")
		.and_then(|p| time(&p.params, &p.value, zone))
		.map(day_of);
	if first("STATUS").is_some_and(|p| p.value.trim().eq_ignore_ascii_case("CANCELLED")) {
		return Some(Item {
			uid,
			recurrence_day: rid,
			events: Vec::new(),
			simplified: false,
		});
	}
	let end = first("DTEND")
		.and_then(|p| time(&p.params, &p.value, zone))
		.or_else(|| {
			first("DURATION")
				.and_then(|d| duration(d.value.trim()))
				.map(|d| start + minutes(&d) * 60)
		})
		.unwrap_or(if untimed { start + DAY_SECS } else { start });
	let mut e = Event {
		title: first("SUMMARY").map(|p| unescape(&p.value)).unwrap_or_default(),
		note: first("DESCRIPTION").map(|p| unescape(&p.value)).unwrap_or_default(),
		date: day_of(start),
		untimed,
		is_private: first("CLASS")
			.is_some_and(|p| matches!(p.value.trim().to_uppercase().as_str(), "PRIVATE" | "CONFIDENTIAL")),
		..Event::default()
	};
	let mut simplified = false;
	// Days that a timed event covers after its first day, and its end time on the last one. An end at
	// midnight covers no part of that day.
	let mut span = 0i64;
	let mut last_end = 0;
	if !untimed {
		let midnight = end > start && is_midnight(end);
		span = (day_of(end) - if midnight { 1 } else { 0 } - e.date).max(0);
		last_end = if midnight {
			24 * 60 - 1
		} else {
			hour_of(end) * 60 + minute_of(end)
		};
		e.start = hour_of(start) * 60 + minute_of(start);
		e.end = if span > 0 { 24 * 60 - 1 } else { e.start.max(last_end) };
	}
	if let Some(t) = trigger {
		let (n, unit) = advance(&t.value);
		e.alarm = true;
		e.alarm_advance = n;
		e.alarm_unit = unit;
	}
	let rule = first("RRULE");
	if rid.is_none() {
		if let Some(r) = rule {
			if !set_rule(&mut e, &r.value, zone) {
				simplified = true;
			}
		}
	}
	let days = day_of(end) - e.date;
	if untimed && days > 1 {
		// A multi-day untimed event becomes a daily repeat over its days.
		if rule.is_none() && rid.is_none() {
			e.repeat = repeat::DAY;
			e.repeat_end = e.date + days - 1;
		} else {
			simplified = true;
		}
	}
	if e.repeats() {
		for x in props.iter().filter(|p| p.name == "EXDATE") {
			for v in x.value.split(',') {
				if let Some(t) = time(&x.params, v, zone) {
					e.exceptions.insert(day_of(t));
				}
			}
		}
	}
	if first("RDATE").is_some() {
		simplified = true;
	}
	let mut events = vec![e.clone()];
	if span > 0 {
		// A timed event that goes past midnight becomes one event for each day it covers.
		// Shortcut: at most 31 days. Upgrade path: an end date in Event.
		let mask = if e.week_days == 0 {
			1 << dow(day_of(start))
		} else {
			e.week_days
		};
		for k in 1..=span.min(30) {
			// Date Book counts weeks from Sunday, so a part that moves past Saturday is off by one week.
			if e.repeat == repeat::WEEK && e.every > 1 && (0..7).any(|i| mask & (1 << i) != 0 && i + k > 6) {
				simplified = true;
			}
			events.push(Event {
				date: e.date + k,
				start: 0,
				end: if k == span { last_end } else { 24 * 60 - 1 },
				alarm: false,
				repeat_end: if e.repeat_end >= 0 { e.repeat_end + k } else { -1 },
				week_days: if e.repeat == repeat::WEEK {
					rotate(mask, k)
				} else {
					e.week_days
				},
				exceptions: e.exceptions.iter().map(|x| x + k).collect(),
				..e.clone()
			});
		}
		if span > 30 || (e.repeats() && e.repeat != repeat::DAY && e.repeat != repeat::WEEK) {
			simplified = true;
		}
	}
	Some(Item {
		uid,
		recurrence_day: rid,
		events,
		simplified,
	})
}

/// The weekday bits of `mask`, moved `k` days later.
fn rotate(mask: i32, k: i64) -> i32 {
	let s = (k % 7) as u32;
	(((mask as u32) << s | (mask as u32) >> (7 - s)) & 0x7F) as i32
}

/// Sets the repeat of `e` from an RRULE value. False when Date Book cannot show the rule: `e` then stays
/// a one-time event.
fn set_rule(e: &mut Event, value: &str, zone: &TimeZone) -> bool {
	let mut r: HashMap<String, String> = HashMap::new();
	for part in value.trim().split(';') {
		if let Some(i) = part.find('=').filter(|i| *i > 0) {
			r.insert(part[..i].to_uppercase(), part[i + 1..].to_uppercase());
		}
	}
	if r.keys().any(|k| !RULE_PARTS.contains(&k.as_str())) {
		return false;
	}
	let s = ymd(e.date);
	let freq = r.get("FREQ").map(String::as_str);
	let every = match r.get("INTERVAL") {
		Some(v) => v.parse::<i32>().unwrap_or(1),
		None => 1,
	};
	if every < 1 {
		return false;
	}
	let days: Option<Vec<String>> = r.get("BYDAY").map(|v| {
		v.split(',')
			.map(|d| d.strip_prefix('+').unwrap_or(d).to_string())
			.collect()
	});
	// Bit 8 marks a day with a number before it, such as "2MO".
	let mask = days.as_ref().map(|ds| {
		ds.iter().fold(0, |m, d| {
			m | DAY_CODES.iter().position(|c| c == d).map_or(0x100, |i| 1 << i)
		})
	});
	let month_day = r.get("BYMONTHDAY");
	if let Some(md) = month_day {
		if (freq != Some("MONTHLY") && freq != Some("YEARLY")) || *md != s.day.to_string() {
			return false;
		}
	}
	if let Some(m) = r.get("BYMONTH") {
		if freq != Some("YEARLY") || *m != s.month.to_string() {
			return false;
		}
	}
	let mut week_days = 0;
	let rep = match freq {
		Some("DAILY") => match mask {
			None => repeat::DAY,
			Some(m) if m < 0x100 && every == 1 => {
				week_days = m;
				repeat::WEEK
			}
			_ => return false,
		},
		Some("WEEKLY") => match mask {
			None => repeat::WEEK,
			Some(m) if m < 0x100 => {
				week_days = m;
				repeat::WEEK
			}
			_ => return false,
		},
		Some("MONTHLY") => match &days {
			None => repeat::MONTH_DATE,
			Some(ds) if month_day.is_none() && ds.len() == 1 && ds[0] == week_code(e.date) => repeat::MONTH_DAY,
			_ => return false,
		},
		Some("YEARLY") if days.is_none() => repeat::YEAR,
		_ => return false,
	};
	let mut t = Event {
		repeat: rep,
		every,
		week_days,
		repeat_end: -1,
		..e.clone()
	};
	if let Some(u) = r.get("UNTIL") {
		match time(&HashMap::new(), u, zone) {
			Some(x) => t.repeat_end = day_of(x),
			None => return false,
		}
	}
	if let Some(c) = r.get("COUNT") {
		match c.parse::<i32>().ok().filter(|c| *c > 0) {
			Some(c) => t.repeat_end = count_end(&t, c),
			None => return false,
		}
	}
	e.repeat = t.repeat;
	e.every = t.every;
	e.week_days = t.week_days;
	e.repeat_end = t.repeat_end;
	true
}

/// The day of occurrence number `count` of `e`. Shortcut: a scan day by day, which stops after 100
/// years. Upgrade path: step by the repeat unit.
fn count_end(e: &Event, count: i32) -> i64 {
	let mut left = count;
	let stop = e.date + 36_525;
	let mut d = e.date;
	while d < stop {
		if occurs_on(e, d) {
			left -= 1;
			if left == 0 {
				return d;
			}
		}
		d += 1;
	}
	stop
}

fn is_date(params: &HashMap<String, String>, v: &str) -> bool {
	params.get("VALUE").is_some_and(|x| x.eq_ignore_ascii_case("DATE")) || v.trim().chars().count() == 8
}

/// A DATE or DATE-TIME value as a local time in `zone`, or None when it does not parse.
fn time(params: &HashMap<String, String>, value: &str, zone: &TimeZone) -> Option<i64> {
	let v = value.trim();
	if is_date(params, v) {
		let head: String = v.chars().take(8).collect();
		return parse_date(&head).map(|d| d * DAY_SECS);
	}
	if let Some(utc) = v.strip_suffix('Z') {
		return convert(parse_time(utc)?, &TimeZone::UTC, zone);
	}
	let t = parse_time(v)?;
	// Shortcut: a TZID that is not an IANA zone name (Outlook writes Windows names) reads as local time.
	// Upgrade path: read the offsets from the VTIMEZONE of the file.
	match params.get("TZID").and_then(|id| TimeZone::get(id).ok()) {
		Some(tz) => convert(t, &tz, zone),
		None => Some(t),
	}
}

/// The parts of a DURATION or TRIGGER value: `[+-]P[nW][nD][T[nH][nM][nS]]`. A part that is there holds
/// its number, or 0 when the number does not fit.
struct Duration {
	negative: bool,
	weeks: Option<i64>,
	days: Option<i64>,
	hours: Option<i64>,
	minutes: Option<i64>,
	seconds: Option<i64>,
}

fn duration(s: &str) -> Option<Duration> {
	let mut d = Duration {
		negative: false,
		weeks: None,
		days: None,
		hours: None,
		minutes: None,
		seconds: None,
	};
	let mut rest = s;
	if let Some(r) = rest.strip_prefix('+') {
		rest = r;
	} else if let Some(r) = rest.strip_prefix('-') {
		rest = r;
		d.negative = true;
	}
	rest = rest.strip_prefix('P')?;
	// Each part may come once and in order: W, D, then after T: H, M, S.
	let mut stage = 0;
	let mut timed = false;
	while !rest.is_empty() {
		if !timed && rest.starts_with('T') {
			timed = true;
			stage = 2;
			rest = &rest[1..];
			continue;
		}
		let n = rest.bytes().take_while(u8::is_ascii_digit).count();
		if n == 0 || n == rest.len() {
			return None;
		}
		let value = Some(rest[..n].parse::<i64>().unwrap_or(0));
		let unit = rest.as_bytes()[n];
		let slot = match (timed, unit) {
			(false, b'W') => 1,
			(false, b'D') => 2,
			(true, b'H') => 3,
			(true, b'M') => 4,
			(true, b'S') => 5,
			_ => return None,
		};
		if slot <= stage {
			return None;
		}
		stage = slot;
		match slot {
			1 => d.weeks = value,
			2 => d.days = value,
			3 => d.hours = value,
			4 => d.minutes = value,
			_ => d.seconds = value,
		}
		rest = &rest[n + 1..];
	}
	Some(d)
}

/// Signed minutes of a duration.
fn minutes(d: &Duration) -> i64 {
	let v = d.weeks.unwrap_or(0) * 10_080
		+ d.days.unwrap_or(0) * 1440
		+ d.hours.unwrap_or(0) * 60
		+ d.minutes.unwrap_or(0)
		+ d.seconds.unwrap_or(0) / 60;
	if d.negative {
		-v
	} else {
		v
	}
}

/// A TRIGGER value as Date Book (advance, unit), in the unit of the file.
fn advance(value: &str) -> (i32, i32) {
	let Some(d) = duration(value.trim()) else { return (0, 0) };
	let before = -minutes(&d);
	if before <= 0 {
		return (0, 0);
	}
	let unit = if d.hours.is_none() && d.minutes.is_none() && d.seconds.is_none() {
		2
	} else if d.minutes.is_none() && d.seconds.is_none() {
		1
	} else {
		0
	};
	((before / [1, 60, 1440][unit]) as i32, unit as i32)
}

fn unfold(text: &str) -> Vec<String> {
	let mut out: Vec<String> = Vec::new();
	for raw in text.strip_prefix('\u{FEFF}').unwrap_or(text).split('\n') {
		let l = raw.strip_suffix('\r').unwrap_or(raw);
		if (l.starts_with(' ') || l.starts_with('\t')) && !out.is_empty() {
			out.last_mut().unwrap().push_str(&l[1..]);
		} else if !crate::model::is_blank(l) {
			out.push(l.to_string());
		}
	}
	out
}

fn prop(line: &str) -> Option<Prop> {
	let mut quoted = false;
	let mut colon = None;
	for (i, c) in line.char_indices() {
		if c == '"' {
			quoted = !quoted;
		} else if c == ':' && !quoted {
			colon = Some(i);
			break;
		}
	}
	let colon = colon?;
	let head: Vec<&str> = line[..colon].split(';').collect();
	let mut params = HashMap::new();
	for p in &head[1..] {
		if let Some(i) = p.find('=').filter(|i| *i > 0) {
			params.insert(p[..i].to_uppercase(), p[i + 1..].trim_matches('"').to_string());
		}
	}
	let name = head[0].rsplit('.').next().unwrap_or("").to_uppercase();
	Some(Prop {
		name,
		params,
		value: line[colon + 1..].to_string(),
	})
}
