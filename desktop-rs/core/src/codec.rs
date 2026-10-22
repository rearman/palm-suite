//! Plaintext of a sync blob, as JSON (sync-server/PROTOCOL.md section 6), as SyncCodec.kt.
//!
//! Writers write format 2: short keys, the kind in "k" ("e", "t", "m", "a", "c", "s"), and no field
//! that holds its default. Readers accept format 2 and format 1, which uses the long keys and the
//! kind in "kind". The key order differs from org.json's, which no reader depends on.

use crate::json::*;
use crate::model::{Address, Category, Event, Memo, Todo};
use serde_json::{json, Value};

/// A list of (name, text) ShortCuts.
pub type ShortCuts = Vec<(String, String)>;

fn put_str(o: &mut Obj, key: &str, value: &str) {
	if !value.is_empty() {
		o.insert(key.into(), value.into());
	}
}

fn put_i32(o: &mut Obj, key: &str, value: i32, default: i32) {
	if value != default {
		o.insert(key.into(), value.into());
	}
}

fn put_i64(o: &mut Obj, key: &str, value: i64, default: i64) {
	if value != default {
		o.insert(key.into(), value.into());
	}
}

fn put_bool(o: &mut Obj, key: &str, value: bool) {
	if value {
		o.insert(key.into(), true.into());
	}
}

fn kinded(k: &str) -> Obj {
	let mut o = Obj::new();
	o.insert("k".into(), k.into());
	o
}

fn text(o: Obj) -> String {
	Value::Object(o).to_string()
}

pub fn event(e: &Event) -> String {
	let mut o = kinded("e");
	put_str(&mut o, "t", &e.title);
	put_str(&mut o, "n", &e.note);
	put_i64(&mut o, "d", e.date, 0);
	put_bool(&mut o, "u", e.untimed);
	put_i32(&mut o, "s", e.start, 480);
	put_i32(&mut o, "e", e.end, 540);
	put_bool(&mut o, "a", e.alarm);
	put_i32(&mut o, "aa", e.alarm_advance, 5);
	put_i32(&mut o, "au", e.alarm_unit, 0);
	put_i32(&mut o, "r", e.repeat, 0);
	put_i32(&mut o, "ev", e.every, 1);
	put_i64(&mut o, "re", e.repeat_end, -1);
	put_i32(&mut o, "w", e.week_days, 0);
	if !e.exceptions.is_empty() {
		o.insert("x".into(), json!(e.exceptions.iter().collect::<Vec<_>>()));
	}
	put_bool(&mut o, "p", e.is_private);
	text(o)
}

pub fn todo(t: &Todo, category_uid: &str) -> String {
	let mut o = kinded("t");
	put_str(&mut o, "t", &t.title);
	put_str(&mut o, "n", &t.note);
	put_i32(&mut o, "pr", t.priority, 1);
	put_i64(&mut o, "du", t.due, -1);
	put_bool(&mut o, "dn", t.done);
	put_str(&mut o, "c", category_uid);
	put_bool(&mut o, "p", t.is_private);
	text(o)
}

pub fn memo(m: &Memo, category_uid: &str) -> String {
	let mut o = kinded("m");
	put_str(&mut o, "t", &m.text);
	put_str(&mut o, "c", category_uid);
	put_bool(&mut o, "p", m.is_private);
	put_i32(&mut o, "o", m.pos, 0);
	text(o)
}

pub fn address(a: &Address, category_uid: &str) -> String {
	let mut o = kinded("a");
	put_str(&mut o, "ln", &a.last_name);
	put_str(&mut o, "fn", &a.first_name);
	put_str(&mut o, "ti", &a.title);
	put_str(&mut o, "co", &a.company);
	// A list is written whole when one entry differs from its default.
	if a.phone_labels.iter().enumerate().any(|(i, l)| *l != i as i32) {
		o.insert("pl".into(), json!(a.phone_labels));
	}
	if a.phones.iter().any(|p| !p.is_empty()) {
		o.insert("ph".into(), json!(a.phones));
	}
	put_str(&mut o, "ad", &a.address);
	put_str(&mut o, "ci", &a.city);
	put_str(&mut o, "st", &a.state);
	put_str(&mut o, "z", &a.zip);
	put_str(&mut o, "cn", &a.country);
	if a.custom.iter().any(|c| !c.is_empty()) {
		o.insert("cu".into(), json!(a.custom));
	}
	put_str(&mut o, "n", &a.note);
	put_i32(&mut o, "sp", a.show_phone, 0);
	put_str(&mut o, "c", category_uid);
	put_bool(&mut o, "p", a.is_private);
	text(o)
}

pub fn category(c: &Category) -> String {
	let mut o = kinded("c");
	put_str(&mut o, "n", &c.name);
	put_i32(&mut o, "ap", c.app, 0);
	text(o)
}

pub fn shortcuts(list: &[(String, String)]) -> String {
	let items: Vec<Value> = list
		.iter()
		.map(|(name, t)| {
			let mut item = Obj::new();
			put_str(&mut item, "n", name);
			put_str(&mut item, "t", t);
			Value::Object(item)
		})
		.collect();
	let mut o = kinded("s");
	o.insert("l".into(), Value::Array(items));
	text(o)
}

fn long_kind(k: &str) -> &'static str {
	match k {
		"e" => "event",
		"t" => "todo",
		"m" => "memo",
		"a" => "address",
		"c" => "category",
		"s" => "shortcuts",
		_ => "",
	}
}

/// True when the object is format 2 (it has the short kind key "k").
fn is_v2(o: &Obj) -> bool {
	o.contains_key("k")
}

/// The format 2 key when `v2`, else the format 1 key.
fn key<'a>(v2: bool, f2: &'a str, f1: &'a str) -> &'a str {
	if v2 {
		f2
	} else {
		f1
	}
}

/// The "kind" of a blob, or "" when the text is not a JSON object or has no kind.
pub fn kind(text: &str) -> String {
	match parse_obj(text) {
		Ok(o) if is_v2(&o) => long_kind(&opt_str(&o, "k")).to_string(),
		Ok(o) => opt_str(&o, "kind"),
		Err(_) => String::new(),
	}
}

pub fn to_event(text: &str, uid: &str) -> Result<Event, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	let exceptions = opt_array(&o, key(v2, "x", "exceptions"))
		.map(|a| (0..a.len()).map(|i| at_i64(a, i)).collect())
		.unwrap_or_default();
	Ok(Event {
		id: 0,
		title: opt_str(&o, key(v2, "t", "title")),
		note: opt_str(&o, key(v2, "n", "note")),
		date: opt_i64(&o, key(v2, "d", "date"), 0),
		untimed: opt_bool(&o, key(v2, "u", "untimed")),
		start: opt_i32(&o, key(v2, "s", "start"), 480),
		end: opt_i32(&o, key(v2, "e", "end"), 540),
		alarm: opt_bool(&o, key(v2, "a", "alarm")),
		alarm_advance: opt_i32(&o, key(v2, "aa", "alarmAdvance"), 5),
		alarm_unit: opt_i32(&o, key(v2, "au", "alarmUnit"), 0),
		repeat: opt_i32(&o, key(v2, "r", "repeat"), 0),
		every: opt_i32(&o, key(v2, "ev", "every"), 1),
		repeat_end: opt_i64(&o, key(v2, "re", "repeatEnd"), -1),
		week_days: opt_i32(&o, key(v2, "w", "weekDays"), 0),
		exceptions,
		is_private: opt_bool(&o, key(v2, "p", "private")),
		uid: uid.to_string(),
	})
}

pub fn to_todo(text: &str, uid: &str, category_id: impl Fn(&str) -> i32) -> Result<Todo, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	Ok(Todo {
		id: 0,
		title: opt_str(&o, key(v2, "t", "title")),
		note: opt_str(&o, key(v2, "n", "note")),
		priority: opt_i32(&o, key(v2, "pr", "priority"), 1),
		due: opt_i64(&o, key(v2, "du", "due"), -1),
		done: opt_bool(&o, key(v2, "dn", "done")),
		category: category_id(&opt_str(&o, key(v2, "c", "category"))),
		is_private: opt_bool(&o, key(v2, "p", "private")),
		uid: uid.to_string(),
	})
}

pub fn to_memo(text: &str, uid: &str, category_id: impl Fn(&str) -> i32) -> Result<Memo, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	Ok(Memo {
		id: 0,
		text: opt_str(&o, key(v2, "t", "text")),
		category: category_id(&opt_str(&o, key(v2, "c", "category"))),
		is_private: opt_bool(&o, key(v2, "p", "private")),
		pos: opt_i32(&o, key(v2, "o", "pos"), 0),
		uid: uid.to_string(),
	})
}

pub fn to_address(text: &str, uid: &str, category_id: impl Fn(&str) -> i32) -> Result<Address, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	let labels = opt_array(&o, key(v2, "pl", "phoneLabels"));
	let phones = opt_array(&o, key(v2, "ph", "phones"));
	let custom = opt_array(&o, key(v2, "cu", "custom"));
	Ok(Address {
		id: 0,
		last_name: opt_str(&o, key(v2, "ln", "lastName")),
		first_name: opt_str(&o, key(v2, "fn", "firstName")),
		title: opt_str(&o, key(v2, "ti", "title")),
		company: opt_str(&o, key(v2, "co", "company")),
		phone_labels: (0..5)
			.map(|i| labels.map_or(i, |a| at_i32(a, i as usize, i)).clamp(0, 7))
			.collect(),
		phones: (0..5)
			.map(|i| phones.map_or(String::new(), |a| at_str(a, i, "")))
			.collect(),
		address: opt_str(&o, key(v2, "ad", "address")),
		city: opt_str(&o, key(v2, "ci", "city")),
		state: opt_str(&o, key(v2, "st", "state")),
		zip: opt_str(&o, key(v2, "z", "zip")),
		country: opt_str(&o, key(v2, "cn", "country")),
		custom: (0..4)
			.map(|i| custom.map_or(String::new(), |a| at_str(a, i, "")))
			.collect(),
		note: opt_str(&o, key(v2, "n", "note")),
		show_phone: opt_i32(&o, key(v2, "sp", "showPhone"), 0).clamp(0, 4),
		category: category_id(&opt_str(&o, key(v2, "c", "category"))),
		is_private: opt_bool(&o, key(v2, "p", "private")),
		uid: uid.to_string(),
	})
}

pub fn to_category(text: &str, uid: &str, id: i32) -> Result<Category, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	Ok(Category {
		id,
		name: opt_str(&o, key(v2, "n", "name")),
		app: opt_i32(&o, key(v2, "ap", "app"), 0),
		uid: uid.to_string(),
	})
}

pub fn to_shortcuts(text: &str) -> Result<ShortCuts, String> {
	let o = parse_obj(text)?;
	let v2 = is_v2(&o);
	let Some(arr) = opt_array(&o, key(v2, "l", "list")) else {
		return Ok(Vec::new());
	};
	arr.iter()
		.map(|s| {
			let s = s.as_object().ok_or("JSONArray item is not a JSONObject.")?;
			Ok((opt_str(s, key(v2, "n", "name")), opt_str(s, key(v2, "t", "text"))))
		})
		.collect()
}
