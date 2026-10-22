//! The Rust port against the Kotlin output in sync-core/testdata (see VectorsTest.kt). Records appear in
//! the model form of VectorsTest: every field, long keys. JSON texts and packed plaintexts compare as
//! values; iCalendar and vCard texts compare byte for byte.

use palm_core::client::{self, PushItem};
use palm_core::crypto::{self, plain_bytes};
use palm_core::logic::{decide, LocalState};
use palm_core::model::{self, Address, Category, Event, Memo, Todo};
use palm_core::{backup, codec, ical, vcard};
use serde_json::{json, Value};
use std::collections::HashMap;

fn load(name: &str) -> Value {
	let path = format!("{}/../../sync-core/testdata/{name}.json", env!("CARGO_MANIFEST_DIR"));
	serde_json::from_str(&std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{path}: {e}"))).unwrap()
}

fn s(v: &Value) -> String {
	v.as_str().unwrap_or_else(|| panic!("not a string: {v}")).to_string()
}

fn i(v: &Value) -> i64 {
	v.as_i64().unwrap_or_else(|| panic!("not a number: {v}"))
}

fn b(v: &Value) -> bool {
	v.as_bool().unwrap_or_else(|| panic!("not a boolean: {v}"))
}

fn arr(v: &Value) -> &Vec<Value> {
	v.as_array().unwrap_or_else(|| panic!("not an array: {v}"))
}

fn json_of(text: &str) -> Value {
	serde_json::from_str(text).unwrap_or_else(|e| panic!("{e}: {text}"))
}

// ---- the model form ----

fn ev_from(v: &Value) -> Event {
	Event {
		id: i(&v["id"]),
		title: s(&v["title"]),
		note: s(&v["note"]),
		date: i(&v["date"]),
		untimed: b(&v["untimed"]),
		start: i(&v["start"]) as i32,
		end: i(&v["end"]) as i32,
		alarm: b(&v["alarm"]),
		alarm_advance: i(&v["alarmAdvance"]) as i32,
		alarm_unit: i(&v["alarmUnit"]) as i32,
		repeat: i(&v["repeat"]) as i32,
		every: i(&v["every"]) as i32,
		repeat_end: i(&v["repeatEnd"]),
		week_days: i(&v["weekDays"]) as i32,
		exceptions: arr(&v["exceptions"]).iter().map(i).collect(),
		is_private: b(&v["private"]),
		uid: s(&v["uid"]),
	}
}

fn ev_to(e: &Event) -> Value {
	json!({
		"id": e.id, "title": e.title, "note": e.note, "date": e.date, "untimed": e.untimed, "start": e.start,
		"end": e.end, "alarm": e.alarm, "alarmAdvance": e.alarm_advance, "alarmUnit": e.alarm_unit,
		"repeat": e.repeat, "every": e.every, "repeatEnd": e.repeat_end, "weekDays": e.week_days,
		"exceptions": e.exceptions.iter().collect::<Vec<_>>(), "private": e.is_private, "uid": e.uid,
	})
}

fn events_to(list: &[Event]) -> Value {
	Value::Array(list.iter().map(ev_to).collect())
}

fn todo_from(v: &Value) -> Todo {
	Todo {
		id: i(&v["id"]),
		title: s(&v["title"]),
		note: s(&v["note"]),
		priority: i(&v["priority"]) as i32,
		due: i(&v["due"]),
		done: b(&v["done"]),
		category: i(&v["category"]) as i32,
		is_private: b(&v["private"]),
		uid: s(&v["uid"]),
	}
}

fn todo_to(t: &Todo) -> Value {
	json!({
		"id": t.id, "title": t.title, "note": t.note, "priority": t.priority, "due": t.due, "done": t.done,
		"category": t.category, "private": t.is_private, "uid": t.uid,
	})
}

fn memo_from(v: &Value) -> Memo {
	Memo {
		id: i(&v["id"]),
		text: s(&v["text"]),
		category: i(&v["category"]) as i32,
		is_private: b(&v["private"]),
		pos: i(&v["pos"]) as i32,
		uid: s(&v["uid"]),
	}
}

fn memo_to(m: &Memo) -> Value {
	json!({"id": m.id, "text": m.text, "category": m.category, "private": m.is_private, "pos": m.pos, "uid": m.uid})
}

fn addr_from(v: &Value) -> Address {
	let strs = |k: &str| arr(&v[k]).iter().map(s).collect();
	Address {
		id: i(&v["id"]),
		last_name: s(&v["lastName"]),
		first_name: s(&v["firstName"]),
		title: s(&v["title"]),
		company: s(&v["company"]),
		phone_labels: arr(&v["phoneLabels"]).iter().map(|x| i(x) as i32).collect(),
		phones: strs("phones"),
		address: s(&v["address"]),
		city: s(&v["city"]),
		state: s(&v["state"]),
		zip: s(&v["zip"]),
		country: s(&v["country"]),
		custom: strs("custom"),
		note: s(&v["note"]),
		show_phone: i(&v["showPhone"]) as i32,
		category: i(&v["category"]) as i32,
		is_private: b(&v["private"]),
		uid: s(&v["uid"]),
	}
}

fn addr_to(a: &Address) -> Value {
	json!({
		"id": a.id, "lastName": a.last_name, "firstName": a.first_name, "title": a.title, "company": a.company,
		"phoneLabels": a.phone_labels, "phones": a.phones, "address": a.address, "city": a.city, "state": a.state,
		"zip": a.zip, "country": a.country, "custom": a.custom, "note": a.note, "showPhone": a.show_phone,
		"category": a.category, "private": a.is_private, "uid": a.uid,
	})
}

fn cat_from(v: &Value) -> Category {
	Category {
		id: i(&v["id"]) as i32,
		name: s(&v["name"]),
		app: i(&v["app"]) as i32,
		uid: s(&v["uid"]),
	}
}

fn cat_to(c: &Category) -> Value {
	json!({"id": c.id, "name": c.name, "app": c.app, "uid": c.uid})
}

fn pairs(v: &Value) -> Vec<(String, String)> {
	arr(v).iter().map(|p| (s(&p[0]), s(&p[1]))).collect()
}

/// The names of a {"3": "Business"} object, by category id.
fn names_by_id(v: &Value) -> HashMap<i32, String> {
	v.as_object()
		.unwrap()
		.iter()
		.map(|(k, n)| (k.parse().unwrap(), s(n)))
		.collect()
}

// ---- vectors ----

#[test]
fn crypto_vectors() {
	let v = load("crypto");
	for k in arr(&v["kdf"]) {
		let salt = crypto::unb64(&s(&k["salt"])).unwrap();
		let key = crypto::derive_key(&s(&k["passphrase"]), &salt, i(&k["rounds"]) as u32);
		assert_eq!(crypto::b64(&key), s(&k["key"]), "{k}");
	}
	for x in arr(&v["seal"]) {
		let key = crypto::unb64(&s(&x["key"])).unwrap();
		let plain = crypto::unb64(&s(&x["plain"])).unwrap();
		let nonce = crypto::unb64(&s(&x["nonce"])).unwrap();
		let id = s(&x["id"]);
		assert_eq!(
			crypto::seal_with_nonce(&key, &id, &plain, &nonce).unwrap(),
			s(&x["blob"])
		);
		assert_eq!(crypto::open(&key, &id, &s(&x["blob"])).unwrap(), plain);
		assert!(crypto::open(&key, "other-id", &s(&x["blob"])).is_err());
		if id == crypto::CHECK_ID {
			assert!(crypto::check_ok(&key, &s(&x["blob"])));
		}
	}
}

#[test]
fn pack_vectors() {
	for x in arr(&load("pack")["pack"]) {
		let text = s(&x["text"]);
		let packed = crypto::unb64(&s(&x["packed"])).unwrap();
		assert_eq!(plain_bytes::unpack(&packed).unwrap(), text);
		assert_eq!(plain_bytes::unpack(&plain_bytes::pack(&text)).unwrap(), text);
		// Both sides deflate the same texts, whatever their deflate bytes.
		assert_eq!(plain_bytes::pack(&text)[0] == 1, packed[0] == 1, "{text}");
	}
}

#[test]
fn codec_vectors() {
	let v = load("codec");
	let uids = names_by_id(&v["categoryUids"]);
	let cat_id = |uid: &str| uids.iter().find(|(_, u)| *u == uid).map_or(0, |(id, _)| *id);
	for x in arr(&v["events"]) {
		let rec = ev_from(&x["record"]);
		assert_eq!(json_of(&codec::event(&rec)), json_of(&s(&x["text"])));
		assert_eq!(
			ev_to(&codec::to_event(&s(&x["text"]), &s(&x["uid"])).unwrap()),
			x["record"]
		);
	}
	for x in arr(&v["todos"]) {
		let rec = todo_from(&x["record"]);
		assert_eq!(
			json_of(&codec::todo(&rec, &s(&x["categoryUid"]))),
			json_of(&s(&x["text"]))
		);
		assert_eq!(
			todo_to(&codec::to_todo(&s(&x["text"]), &s(&x["uid"]), cat_id).unwrap()),
			x["record"]
		);
	}
	for x in arr(&v["memos"]) {
		let rec = memo_from(&x["record"]);
		assert_eq!(
			json_of(&codec::memo(&rec, &s(&x["categoryUid"]))),
			json_of(&s(&x["text"]))
		);
		assert_eq!(
			memo_to(&codec::to_memo(&s(&x["text"]), &s(&x["uid"]), cat_id).unwrap()),
			x["record"]
		);
	}
	for x in arr(&v["addresses"]) {
		let rec = addr_from(&x["record"]);
		assert_eq!(
			json_of(&codec::address(&rec, &s(&x["categoryUid"]))),
			json_of(&s(&x["text"]))
		);
		assert_eq!(
			addr_to(&codec::to_address(&s(&x["text"]), &s(&x["uid"]), cat_id).unwrap()),
			x["record"]
		);
	}
	for x in arr(&v["categories"]) {
		let rec = cat_from(&x["record"]);
		assert_eq!(json_of(&codec::category(&rec)), json_of(&s(&x["text"])));
		assert_eq!(
			cat_to(&codec::to_category(&s(&x["text"]), &s(&x["uid"]), i(&x["id"]) as i32).unwrap()),
			x["record"]
		);
	}
	let sc = &v["shortcuts"];
	assert_eq!(
		json_of(&codec::shortcuts(&pairs(&sc["list"]))),
		json_of(&s(&sc["text"]))
	);
	assert_eq!(codec::to_shortcuts(&s(&sc["text"])).unwrap(), pairs(&sc["list"]));
	for x in arr(&v["v1"]) {
		let text = s(&x["text"]);
		let got = match s(&x["kind"]).as_str() {
			"event" => ev_to(&codec::to_event(&text, "v1").unwrap()),
			"todo" => todo_to(&codec::to_todo(&text, "v1", cat_id).unwrap()),
			"memo" => memo_to(&codec::to_memo(&text, "v1", cat_id).unwrap()),
			"address" => addr_to(&codec::to_address(&text, "v1", cat_id).unwrap()),
			"category" => cat_to(&codec::to_category(&text, "v1", 4).unwrap()),
			_ => {
				assert_eq!(codec::to_shortcuts(&text).unwrap(), pairs(&x["list"]));
				continue;
			}
		};
		assert_eq!(got, x["record"], "{text}");
		assert_eq!(codec::kind(&text), s(&x["kind"]));
	}
	for x in arr(&v["kinds"]) {
		assert_eq!(codec::kind(&s(&x["text"])), s(&x["kind"]), "{x}");
	}
}

#[test]
fn recur_vectors() {
	for x in arr(&load("recur")["events"]) {
		let e = ev_from(&x["event"]);
		assert_eq!(model::first_day(&e), i(&x["firstDay"]), "{}", e.title);
		assert_eq!(model::last_day(&e), x["lastDay"].as_i64().unwrap(), "{}", e.title);
		assert_eq!(model::is_daily(&e), b(&x["isDaily"]), "{}", e.title);
		assert_eq!(model::describe(&e), s(&x["describe"]));
		assert_eq!(model::short_name(&e), s(&x["shortName"]));
		let days: Vec<i64> = (i(&x["from"])..=i(&x["to"]))
			.filter(|d| model::occurs_on(&e, *d))
			.collect();
		assert_eq!(days, arr(&x["days"]).iter().map(i).collect::<Vec<_>>(), "{}", e.title);
	}
}

#[test]
fn fmt_vectors() {
	let v = load("fmt");
	for x in arr(&v["times"]) {
		let (h24, min) = (b(&x["h24"]), i(&x["min"]) as i32);
		assert_eq!(model::time(min, h24), s(&x["time"]));
		assert_eq!(model::time_short(min, h24), s(&x["timeShort"]));
	}
	for x in arr(&v["days"]) {
		let d = i(&x["day"]);
		assert_eq!(model::day_title(d), s(&x["dayTitle"]));
		assert_eq!(model::short_date(d), s(&x["shortDate"]));
		assert_eq!(model::dow_date(d), s(&x["dowDate"]));
	}
	for x in arr(&v["ordinals"]) {
		assert_eq!(model::ordinal(i(&x["n"]) as i32), s(&x["ordinal"]));
	}
}

#[test]
fn ical_vectors() {
	let v = load("ical");
	let ex = &v["export"];
	let events: Vec<Event> = arr(&ex["events"]).iter().map(ev_from).collect();
	let now: jiff::Timestamp = s(&ex["now"]).parse().unwrap();
	assert_eq!(ical::export(&events, now), s(&ex["text"]));
	for x in arr(&v["parse"]) {
		let zone = jiff::tz::TimeZone::get(&s(&x["zone"])).unwrap();
		let p = ical::parse(&s(&x["text"]), &zone);
		assert_eq!(events_to(&p.events), x["events"], "{}", s(&x["name"]));
		assert_eq!(p.simplified as i64, i(&x["simplified"]), "{}", s(&x["name"]));
	}
}

#[test]
fn vcard_vectors() {
	let v = load("vcard");
	let ex = &v["export"];
	let names = names_by_id(&ex["categoryNames"]);
	let list: Vec<Address> = arr(&ex["addresses"]).iter().map(addr_from).collect();
	assert_eq!(vcard::export(&list, |id| names.get(&id).cloned()), s(&ex["text"]));
	for x in arr(&v["parse"]) {
		let cards: Vec<Value> = vcard::parse(&s(&x["text"]))
			.iter()
			.map(|c| json!({"address": addr_to(&c.address), "category": c.category_name}))
			.collect();
		assert_eq!(Value::Array(cards), x["cards"], "{}", s(&x["name"]));
	}
}

fn content_to(c: &backup::Content) -> Value {
	json!({
		"events": events_to(&c.events),
		"todos": c.todos.iter().map(todo_to).collect::<Vec<_>>(),
		"memos": c.memos.iter().map(memo_to).collect::<Vec<_>>(),
		"addresses": c.addresses.iter().map(addr_to).collect::<Vec<_>>(),
		"todoCategories": c.todo_categories, "memoCategories": c.memo_categories,
		"addressCategories": c.address_categories,
	})
}

#[test]
fn backup_vectors() {
	let v = load("backup");
	let w = &v["write"];
	let names = names_by_id(&w["categoryNames"]);
	let c = &w["content"];
	let content = backup::Content {
		events: arr(&c["events"]).iter().map(ev_from).collect(),
		todos: arr(&c["todos"]).iter().map(todo_from).collect(),
		memos: arr(&c["memos"]).iter().map(memo_from).collect(),
		addresses: arr(&c["addresses"]).iter().map(addr_from).collect(),
		todo_categories: arr(&c["todoCategories"]).iter().map(s).collect(),
		memo_categories: arr(&c["memoCategories"]).iter().map(s).collect(),
		address_categories: arr(&c["addressCategories"]).iter().map(s).collect(),
	};
	let text = backup::write(&content, |id| names.get(&id).cloned().unwrap_or_default());
	assert_eq!(json_of(&text), json_of(&s(&w["text"])));
	for x in arr(&v["read"]) {
		let mut ids: Vec<String> = Vec::new();
		let mut calls = Vec::new();
		let got = backup::read(&s(&x["text"]), |n, app| {
			calls.push(json!([n, app]));
			let k = format!("{app}/{n}");
			match ids.iter().position(|x| *x == k) {
				Some(p) => 100 + p as i32,
				None => {
					ids.push(k);
					99 + ids.len() as i32
				}
			}
		});
		let name = s(&x["name"]);
		match got {
			Ok(content) => {
				assert_eq!(content_to(&content), x["content"], "{name}");
				assert_eq!(Value::Array(calls), x["calls"], "{name}");
			}
			Err(_) => assert_eq!(x["error"], json!(true), "{name}"),
		}
	}
}

#[test]
fn client_vectors() {
	let v = load("client");
	let pb = &v["pushBody"];
	let push: Vec<PushItem> = arr(&pb["push"])
		.iter()
		.map(|p| PushItem {
			id: s(&p["id"]),
			base_rev: i(&p["baseRev"]),
			deleted: b(&p["deleted"]),
			blob: s(&p["blob"]),
		})
		.collect();
	assert_eq!(
		json_of(&client::push_body(i(&pb["since"]), &push)),
		json_of(&s(&pb["text"]))
	);
	for x in arr(&v["parseResult"]) {
		let r = client::parse_result(&s(&x["text"])).unwrap();
		assert_eq!(r.rev, i(&x["rev"]));
		let accepted: HashMap<String, i64> = x["accepted"]
			.as_object()
			.unwrap()
			.iter()
			.map(|(k, v)| (k.clone(), i(v)))
			.collect();
		assert_eq!(r.accepted, accepted);
		assert_eq!(
			r.conflicts.iter().cloned().collect::<Vec<_>>(),
			arr(&x["conflicts"]).iter().map(s).collect::<Vec<_>>()
		);
		let changes: Vec<Value> = r
			.changes
			.iter()
			.map(|c| json!({"id": c.id, "rev": c.rev, "deleted": c.deleted, "blob": c.blob}))
			.collect();
		assert_eq!(Value::Array(changes), x["changes"]);
	}
	for x in arr(&v["parseMeta"]) {
		let m = client::parse_meta(&s(&x["text"])).unwrap();
		assert_eq!((m.salt, m.check), (s(&x["salt"]), s(&x["check"])));
	}
}

#[test]
fn logic_vectors() {
	let v = load("logic");
	for x in arr(&v["decide"]) {
		let local = match s(&x["local"]).as_str() {
			"NONE" => LocalState::None,
			"CLEAN" => LocalState::Clean,
			"DIRTY" => LocalState::Dirty,
			_ => LocalState::Tomb,
		};
		let action = decide(local, b(&x["remoteDeleted"]), b(&x["same"]), b(&x["lastWins"]));
		let name = format!("{action:?}");
		let kotlin: String = s(&x["action"])
			.split('_')
			.map(|w| w[..1].to_string() + &w[1..].to_lowercase())
			.collect();
		assert_eq!(name, kotlin, "{x}");
	}
	for x in arr(&v["freeTimes"]) {
		let timed: Vec<(i32, i32)> = arr(&x["timed"])
			.iter()
			.map(|p| (i(&p[0]) as i32, i(&p[1]) as i32))
			.collect();
		let got = model::free_times(&timed, i(&x["dayStart"]) as i32, i(&x["dayEnd"]) as i32, b(&x["ends"]));
		assert_eq!(got, arr(&x["free"]).iter().map(|f| i(f) as i32).collect::<Vec<_>>());
	}
	for x in arr(&v["splitFuture"]) {
		let (shortened, series) = model::split_future(&ev_from(&x["orig"]), i(&x["occDay"]), &ev_from(&x["work"]));
		assert_eq!(shortened.as_ref().map_or(Value::Null, ev_to), x["shortened"]);
		assert_eq!(ev_to(&series), x["series"]);
	}
	for x in arr(&v["dupKey"]) {
		assert_eq!(ev_to(&ev_from(&x["event"]).dup_key()), x["key"]);
	}
	for x in arr(&v["addressNames"]) {
		let a = addr_from(&x["address"]);
		assert_eq!(a.list_name(false), s(&x["byName"]));
		assert_eq!(a.list_name(true), s(&x["byCompany"]));
		assert_eq!(
			a.list_phone().map_or(Value::Null, |(n, l)| json!([n, l.to_string()])),
			x["phone"]
		);
		assert_eq!(a.is_empty(), b(&x["isEmpty"]));
	}
	let mo = &v["memoOrder"];
	let memos: Vec<Memo> = arr(&mo["memos"]).iter().map(memo_from).collect();
	let ids = |l: &[Memo]| l.iter().map(|m| json!(m.id)).collect::<Vec<_>>();
	assert_eq!(Value::Array(ids(&model::memo_order(&memos, true))), mo["alphabetic"]);
	assert_eq!(Value::Array(ids(&model::memo_order(&memos, false))), mo["manual"]);
	let mv = &mo["move"];
	let mut moved = model::memo_order(&memos, false);
	let changed = model::move_memo(&mut moved, i(&mv["from"]) as usize, i(&mv["to"]) as usize);
	assert_eq!(Value::Array(ids(&moved)), mv["order"]);
	assert_eq!(
		changed.iter().map(|k| json!(moved[*k].id)).collect::<Vec<_>>(),
		*arr(&mv["changed"])
	);
	let ao = &v["addressOrder"];
	let addrs: Vec<Address> = arr(&ao["addresses"]).iter().map(addr_from).collect();
	let aids = |l: &[Address]| Value::Array(l.iter().map(|a| json!(a.id)).collect());
	assert_eq!(aids(&model::address_order(&addrs, false)), ao["byName"]);
	assert_eq!(aids(&model::address_order(&addrs, true)), ao["byCompany"]);
	let sc = &v["sortCategories"];
	let mut cats: Vec<Category> = arr(&sc["categories"]).iter().map(cat_from).collect();
	model::sort_categories(&mut cats);
	assert_eq!(Value::Array(cats.iter().map(|c| json!(c.id)).collect()), sc["sorted"]);
}
