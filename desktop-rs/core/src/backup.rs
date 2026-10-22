//! The Date Book backup file (JSON), as Backup.kt. Android and the desktop apps write and read it.
//!
//! Top-level keys: "format", "categories" (To Do names), "memoCategories", "addressCategories",
//! "events", "todos", "memos" and "addresses". A record names its category, never its id.

use crate::json::*;
use crate::model::{Address, Event, Memo, Todo, LABELS};
use serde_json::{json, Value};

/// The format name that the file carries in its "format" key.
pub const FORMAT: &str = "datebook-backup-1";

/// The category name of category 0. It is never stored as a category of its own.
const UNFILED: &str = "Unfiled";

/// The records of one backup file, and the category names of each app, including empty categories.
/// Records read from a file have id 0 and uid "".
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Content {
	pub events: Vec<Event>,
	pub todos: Vec<Todo>,
	pub memos: Vec<Memo>,
	pub addresses: Vec<Address>,
	pub todo_categories: Vec<String>,
	pub memo_categories: Vec<String>,
	pub address_categories: Vec<String>,
}

/// Writes `c` as a backup file. `category_name` gives the name of a category id, and 0 gives "Unfiled".
/// The category lists are the ones in `c`, in order, plus any name that a record uses and the list lacks.
pub fn write(c: &Content, category_name: impl Fn(i32) -> String) -> String {
	let names = |given: &[String], ids: Vec<i32>| with_names(given, &ids, &category_name);
	let root = json!({
		"format": FORMAT,
		"categories": names(&c.todo_categories, c.todos.iter().map(|t| t.category).collect()),
		"memoCategories": names(&c.memo_categories, c.memos.iter().map(|m| m.category).collect()),
		"addressCategories": names(&c.address_categories, c.addresses.iter().map(|a| a.category).collect()),
		"events": c.events.iter().map(event_json).collect::<Vec<_>>(),
		"todos": c.todos.iter().map(|t| json!({
			"title": t.title, "note": t.note, "priority": t.priority, "due": t.due, "done": t.done,
			"category": category_name(t.category), "private": t.is_private,
		})).collect::<Vec<_>>(),
		"memos": c.memos.iter().map(|m| json!({
			"text": m.text, "category": category_name(m.category), "private": m.is_private,
		})).collect::<Vec<_>>(),
		"addresses": c.addresses.iter().map(|a| json!({
			"lastName": a.last_name, "firstName": a.first_name, "title": a.title, "company": a.company,
			"phoneLabels": a.phone_labels, "phones": a.phones, "address": a.address, "city": a.city,
			"state": a.state, "zip": a.zip, "country": a.country, "custom": a.custom, "note": a.note,
			"showPhone": a.show_phone, "category": category_name(a.category), "private": a.is_private,
		})).collect::<Vec<_>>(),
	});
	// org.json's toString(1): one space for each level.
	let mut out = Vec::new();
	let fmt = serde_json::ser::PrettyFormatter::with_indent(b" ");
	let mut ser = serde_json::Serializer::with_formatter(&mut out, fmt);
	serde::Serialize::serialize(&root, &mut ser).expect("JSON values always serialize");
	String::from_utf8(out).expect("serde_json writes UTF-8")
}

/// `given`, then the names of the categories `ids` use that `given` lacks. Unfiled and blank names are left out.
fn with_names(given: &[String], ids: &[i32], category_name: &impl Fn(i32) -> String) -> Vec<String> {
	let mut out = given.to_vec();
	let used = ids.iter().filter(|id| **id != 0).map(|id| category_name(*id));
	for u in used.filter(|u| !crate::model::is_blank(u) && !u.eq_ignore_ascii_case(UNFILED)) {
		if !given.iter().any(|g| g.to_lowercase() == u.to_lowercase()) && !out[given.len()..].contains(&u) {
			out.push(u);
		}
	}
	out
}

fn event_json(e: &Event) -> Value {
	json!({
		"title": e.title, "note": e.note, "date": e.date, "untimed": e.untimed, "start": e.start, "end": e.end,
		"alarm": e.alarm, "alarmAdvance": e.alarm_advance, "alarmUnit": e.alarm_unit, "repeat": e.repeat,
		"every": e.every, "repeatEnd": e.repeat_end, "weekDays": e.week_days,
		"exceptions": e.exceptions.iter().collect::<Vec<_>>(), "private": e.is_private,
	})
}

/// Reads a backup file written by either app. `category_id` gets a category name and its app (0 To Do,
/// 1 Memo Pad, 2 Address Book) and returns the id; the caller finds or creates the category. "Unfiled"
/// and a blank name give 0 without a call. The category lists of the file also go through `category_id`,
/// so a category with no records is created. Fails when the text is not JSON or its "format" is not
/// [FORMAT], before `category_id` runs, so a bad file creates no category.
pub fn read(text: &str, mut category_id: impl FnMut(&str, i32) -> i32) -> Result<Content, String> {
	let root = parse_obj(text)?;
	if opt_str(&root, "format") != FORMAT {
		return Err("Not a Date Book backup.".to_string());
	}
	// Parse every record first, so that a bad record creates no category.
	let mut top_names = Vec::new();
	for (key, app) in [("categories", 0), ("memoCategories", 1), ("addressCategories", 2)] {
		for v in opt_array(&root, key).map(Vec::as_slice).unwrap_or(&[]) {
			let name = v
				.as_str()
				.ok_or_else(|| format!("{key} holds a value that is not a string"))?;
			top_names.push((name.to_string(), app));
		}
	}
	let objects = |key: &str| -> Result<Vec<&Obj>, String> {
		opt_array(&root, key)
			.map(Vec::as_slice)
			.unwrap_or(&[])
			.iter()
			.map(|v| {
				v.as_object()
					.ok_or_else(|| format!("{key} holds a value that is not an object"))
			})
			.collect()
	};
	let events = objects("events")?
		.into_iter()
		.map(event_from)
		.collect::<Result<Vec<_>, _>>()?;
	let todos: Vec<(Todo, String)> = objects("todos")?
		.into_iter()
		.map(|o| {
			let t = Todo {
				title: opt_str(o, "title"),
				note: opt_str(o, "note"),
				priority: opt_i32(o, "priority", 1),
				due: opt_i64(o, "due", -1),
				done: opt_bool(o, "done"),
				is_private: opt_bool(o, "private"),
				..Todo::default()
			};
			(t, opt_str_or(o, "category", UNFILED))
		})
		.collect();
	let memos: Vec<(Memo, String)> = objects("memos")?
		.into_iter()
		.map(|o| {
			let m = Memo {
				text: opt_str(o, "text"),
				is_private: opt_bool(o, "private"),
				..Memo::default()
			};
			(m, opt_str_or(o, "category", UNFILED))
		})
		.collect();
	let addresses: Vec<(Address, String)> = objects("addresses")?
		.into_iter()
		.map(|o| {
			let strings = |key: &str, n: usize| -> Vec<String> {
				(0..n)
					.map(|i| opt_array(o, key).map_or(String::new(), |a| at_str(a, i, "")))
					.collect()
			};
			let a = Address {
				last_name: opt_str(o, "lastName"),
				first_name: opt_str(o, "firstName"),
				title: opt_str(o, "title"),
				company: opt_str(o, "company"),
				phone_labels: (0..5)
					.map(|i| {
						opt_array(o, "phoneLabels")
							.map_or(i, |a| at_i32(a, i as usize, i))
							.clamp(0, LABELS.len() as i32 - 1)
					})
					.collect(),
				phones: strings("phones", 5),
				address: opt_str(o, "address"),
				city: opt_str(o, "city"),
				state: opt_str(o, "state"),
				zip: opt_str(o, "zip"),
				country: opt_str(o, "country"),
				custom: strings("custom", 4),
				note: opt_str(o, "note"),
				show_phone: opt_i32(o, "showPhone", 0).clamp(0, 4),
				is_private: opt_bool(o, "private"),
				..Address::default()
			};
			(a, opt_str_or(o, "category", UNFILED))
		})
		.collect();

	let mut id_of = |name: &str, app: i32| -> i32 {
		if crate::model::is_blank(name) || name.eq_ignore_ascii_case(UNFILED) {
			0
		} else {
			category_id(name, app)
		}
	};
	for (name, app) in &top_names {
		id_of(name, *app);
	}
	let list = |app: i32| {
		top_names
			.iter()
			.filter(|(_, a)| *a == app)
			.map(|(n, _)| n.clone())
			.collect::<Vec<_>>()
	};
	Ok(Content {
		events,
		todos: todos
			.into_iter()
			.map(|(t, n)| Todo {
				category: id_of(&n, 0),
				..t
			})
			.collect(),
		memos: memos
			.into_iter()
			.map(|(m, n)| Memo {
				category: id_of(&n, 1),
				..m
			})
			.collect(),
		addresses: addresses
			.into_iter()
			.map(|(a, n)| Address {
				category: id_of(&n, 2),
				..a
			})
			.collect(),
		todo_categories: list(0),
		memo_categories: list(1),
		address_categories: list(2),
	})
}

fn event_from(o: &Obj) -> Result<Event, String> {
	let exceptions = match o.get("exceptions") {
		Some(Value::Array(a)) => a
			.iter()
			.map(|v| v.as_i64().ok_or("exceptions holds a value that is not a number"))
			.collect::<Result<_, _>>()?,
		_ => Default::default(),
	};
	Ok(Event {
		title: opt_str(o, "title"),
		note: opt_str(o, "note"),
		date: get_i64(o, "date")?,
		untimed: opt_bool(o, "untimed"),
		start: opt_i32(o, "start", 480),
		end: opt_i32(o, "end", 540),
		alarm: opt_bool(o, "alarm"),
		alarm_advance: opt_i32(o, "alarmAdvance", 5),
		alarm_unit: opt_i32(o, "alarmUnit", 0),
		repeat: opt_i32(o, "repeat", 0),
		every: opt_i32(o, "every", 1),
		repeat_end: opt_i64(o, "repeatEnd", -1),
		week_days: opt_i32(o, "weekDays", 0),
		exceptions,
		is_private: opt_bool(o, "private"),
		..Event::default()
	})
}
