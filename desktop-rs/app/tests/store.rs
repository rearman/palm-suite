//! The desktop store and settings, as DesktopStoreTest.kt, plus the files of the Swing app.

use palm_core::model::{repeat, Event, Memo, Todo};
use palm_core::runner::{Record, SyncStore};
use palm_desktop::settings::{self, Settings};
use palm_desktop::store::{self, DesktopStore};
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};

/// A new empty folder for one test.
fn temp_dir() -> PathBuf {
	static N: AtomicU32 = AtomicU32::new(0);
	let d = std::env::temp_dir().join(format!(
		"palm-store-{}-{}",
		std::process::id(),
		N.fetch_add(1, Ordering::Relaxed)
	));
	let _ = std::fs::remove_dir_all(&d);
	std::fs::create_dir_all(&d).unwrap();
	d
}

fn file() -> PathBuf {
	temp_dir().join("sub").join(store::FILE_NAME)
}

/// 2026-10-19, a Monday.
const DAY: i64 = 20_745;

fn uids(list: &[palm_core::model::Category]) -> Vec<&str> {
	list.iter().map(|c| c.uid.as_str()).collect()
}

#[test]
fn new_store_has_the_default_categories() {
	let s = DesktopStore::open(&file()).unwrap();
	assert_eq!(uids(&s.categories), ["default-0-business", "default-0-personal"]);
	assert_eq!(uids(&s.memo_categories), ["default-1-business", "default-1-personal"]);
	assert_eq!(
		uids(&s.address_categories),
		["default-2-business", "default-2-personal", "default-2-quicklist"]
	);
	assert_eq!(s.categories.iter().map(|c| c.id).collect::<Vec<_>>(), [1, 2]);
	assert_eq!(s.memo_categories.iter().map(|c| c.id).collect::<Vec<_>>(), [3, 4]);
}

#[test]
fn save_then_reopen_keeps_records() {
	let path = file();
	let mut s = DesktopStore::open(&path).unwrap();
	let work = s.add_category("Work", 0);
	s.save_event(&mut Event {
		title: "Dentist".into(),
		date: DAY,
		start: 600,
		end: 630,
		alarm: true,
		alarm_advance: 10,
		repeat: repeat::WEEK,
		week_days: 2,
		repeat_end: DAY + 60,
		..Event::default()
	});
	s.save_event(&mut Event {
		title: "Lunch".into(),
		date: DAY,
		untimed: true,
		..Event::default()
	});
	s.save_todo(&mut Todo {
		title: "Report".into(),
		priority: 2,
		due: DAY,
		category: work.id,
		..Todo::default()
	});
	s.save_memo(&mut Memo {
		text: "Shopping\nmilk".into(),
		..Memo::default()
	});
	s.save_memo(&mut Memo {
		text: "Ideas".into(),
		..Memo::default()
	});
	assert_eq!(s.save_error, None);

	let r = DesktopStore::open(&path).unwrap();
	assert_eq!(s.events, r.events);
	assert_eq!(s.todos, r.todos);
	assert_eq!(s.memos, r.memos);
	assert_eq!(s.categories, r.categories);
	assert_eq!(s.memo_categories, r.memo_categories);
	assert_eq!(r.memos.iter().map(|m| m.pos).collect::<Vec<_>>(), [0, 1]);
}

#[test]
fn delete_of_never_synced_record_removes_it() {
	let mut s = DesktopStore::open(&file()).unwrap();
	let mut e = Event {
		title: "Gone".into(),
		date: DAY,
		..Event::default()
	};
	s.save_event(&mut e);
	s.delete_event(e.id);
	assert!(s.events.is_empty());
	assert!(s.sync_row(&e.uid).is_none());
}

#[test]
fn delete_of_synced_record_leaves_tombstone() {
	let mut s = DesktopStore::open(&file()).unwrap();
	let mut e = Event {
		title: "Kept".into(),
		date: DAY,
		..Event::default()
	};
	s.save_event(&mut e);
	s.mark_synced("events", &e.uid, 5, true);
	s.delete_event(e.id);
	assert!(s.events.is_empty());
	let rows: Vec<_> = s.dirty_rows().into_iter().filter(|r| r.table == "events").collect();
	assert_eq!(rows.len(), 1);
	assert!(rows[0].deleted && rows[0].dirty);
	assert_eq!((rows[0].uid.as_str(), rows[0].rev), (e.uid.as_str(), 5));
	// Once the server takes the delete, the tombstone goes.
	s.mark_synced("events", &e.uid, 6, true);
	assert!(s.sync_row(&e.uid).is_none());
}

#[test]
fn put_remote_keeps_the_local_id() {
	let mut s = DesktopStore::open(&file()).unwrap();
	let mut e = Event {
		title: "Local".into(),
		date: DAY,
		..Event::default()
	};
	s.save_event(&mut e);
	s.put_remote(
		Record::Event(Event {
			title: "Remote".into(),
			date: DAY,
			uid: e.uid.clone(),
			..Event::default()
		}),
		7,
	);
	assert_eq!(s.events.len(), 1);
	assert_eq!((s.events[0].id, s.events[0].title.as_str()), (e.id, "Remote"));
	let row = s.sync_row(&e.uid).unwrap();
	assert_eq!(row.rev, 7);
	assert!(!row.dirty && !row.deleted);
}

#[test]
fn drop_remote_category_moves_todos_to_unfiled() {
	let mut s = DesktopStore::open(&file()).unwrap();
	let work = s.add_category("Work", 0);
	let mut t = Todo {
		title: "Task".into(),
		category: work.id,
		..Todo::default()
	};
	s.save_todo(&mut t);
	s.mark_synced("todos", &t.uid, 3, true);
	s.drop_remote("categories", &work.uid);
	assert_eq!(s.todos[0].category, 0);
	assert!(s.categories.iter().all(|c| c.uid != work.uid));
	assert!(!s.sync_row(&t.uid).unwrap().dirty);
}

#[test]
fn delete_category_moves_items_to_unfiled_and_marks_them() {
	let mut s = DesktopStore::open(&file()).unwrap();
	let work = s.add_category("Work", 0);
	let mut t = Todo {
		title: "Task".into(),
		category: work.id,
		..Todo::default()
	};
	s.save_todo(&mut t);
	s.mark_synced("todos", &t.uid, 3, true);
	s.delete_category(0, work.id);
	assert_eq!(s.todos[0].category, 0);
	assert!(s.sync_row(&t.uid).unwrap().dirty);
}

#[test]
fn purge_todos_deletes_done_items() {
	let mut s = DesktopStore::open(&file()).unwrap();
	s.save_todo(&mut Todo {
		title: "Done".into(),
		done: true,
		..Todo::default()
	});
	s.save_todo(&mut Todo {
		title: "Open".into(),
		..Todo::default()
	});
	assert_eq!(s.purge_todos(), 1);
	assert_eq!(s.todos.iter().map(|t| t.title.as_str()).collect::<Vec<_>>(), ["Open"]);
}

#[test]
fn weekly_event_occurs_on_its_days_only() {
	let mut s = DesktopStore::open(&file()).unwrap();
	s.save_event(&mut Event {
		title: "Standup".into(),
		date: DAY,
		repeat: repeat::WEEK,
		week_days: 2,
		..Event::default()
	});
	assert_eq!(s.day_events(DAY + 7).len(), 1);
	assert!(s.day_events(DAY + 1).is_empty());
	assert_eq!(s.day_events(DAY + 14)[0].title, "Standup");
}

#[test]
fn find_ignores_case() {
	let mut s = DesktopStore::open(&file()).unwrap();
	s.save_event(&mut Event {
		title: "Dentist".into(),
		date: DAY,
		..Event::default()
	});
	s.save_todo(&mut Todo {
		title: "Buy MILK".into(),
		..Todo::default()
	});
	s.save_memo(&mut Memo {
		text: "Call the DENTIST".into(),
		..Memo::default()
	});
	assert_eq!(s.find("dentist").len(), 2);
	assert_eq!(s.find("milk").len(), 1);
}

#[test]
fn new_uids_are_version_4() {
	let u = store::new_uid();
	assert_eq!(u.len(), 36);
	assert_eq!(&u[14..15], "4");
	assert!("89ab".contains(&u[19..20]));
	assert_ne!(u, store::new_uid());
}

fn fixture_store() -> (PathBuf, DesktopStore) {
	let path = temp_dir().join(store::FILE_NAME);
	std::fs::copy(
		concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/desktop-data-v1.json"),
		&path,
	)
	.unwrap();
	let s = DesktopStore::open(&path).unwrap();
	(path, s)
}

#[test]
fn format_1_file_loads_and_uploads_again() {
	let (path, mut s) = fixture_store();
	// The categories load first, so the To Do and the memo find theirs although their rows come earlier.
	let work = s.categories.iter().find(|c| c.name == "Work").unwrap().id;
	assert_eq!(
		(s.todos[0].title.as_str(), s.todos[0].category, s.todos[0].id),
		("Order VFD", work, 2)
	);
	assert_eq!(
		s.memos.iter().map(|m| m.text.as_str()).collect::<Vec<_>>(),
		["First\nbody", "Second"]
	);
	assert_eq!(s.memos[1].category, 9);
	assert_eq!(s.events[0].exceptions.iter().copied().collect::<Vec<_>>(), [20_757]);
	// A file without addressSync reads all rows again; a format 1 file uploads every live row again.
	assert_eq!(s.sync_since(), 0);
	let dirty = s.dirty_rows();
	assert_eq!(dirty.len(), 7);
	assert!(dirty.iter().any(|r| r.uid == "e-3" && r.deleted));
	assert!(s.short_cuts_dirty());
	assert_eq!(s.short_cuts()[1], ("dts".to_string(), "{date} {time}".to_string()));

	s.persist();
	assert_eq!(s.save_error, None);
	let root: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
	assert_eq!(
		(root["syncFormat"].as_i64(), root["addressSync"].as_bool()),
		(Some(2), Some(true))
	);
	let r = DesktopStore::open(&path).unwrap();
	assert_eq!(
		(r.todos.clone(), r.memos.clone(), r.events.clone()),
		(s.todos.clone(), s.memos.clone(), s.events.clone())
	);
	assert_eq!(r.dirty_rows(), s.dirty_rows());
	assert!(!path.with_extension("json.tmp").exists());
}

#[test]
fn a_bad_file_does_not_open() {
	let path = temp_dir().join(store::FILE_NAME);
	std::fs::write(&path, "{\"rows\": 5}").unwrap();
	assert!(DesktopStore::open(&path).is_err());
	std::fs::write(&path, "Palm Desktop").unwrap();
	assert!(DesktopStore::open(&path).is_err());
}

#[test]
fn second_lock_fails() {
	let dir = temp_dir();
	let first = store::lock(&dir).unwrap();
	assert_eq!(store::lock(&dir).unwrap_err(), "Palm Desktop is already running.");
	drop(first);
	assert!(store::lock(&dir).is_ok());
}

// ---- settings ----

#[test]
fn settings_read_what_java_writes() {
	let text = "#comment\n! another\n#Thu Oct 22 15:49:22 EDT 2026\nserver=https\\://sync.example.com\n\
		name=M\\u00FCller \\u2713\nspaced\\ key = value with  spaces  \nempty=\ncolon:yes\n  indented=1\n\
		multi=first \\\n    second\nescapes=a\\tb\\\\c\\=d\\#e\nemoji=\\uD83D\\uDE00\nbare\n";
	let p = settings::parse(text);
	let want: BTreeMap<String, String> = [
		("server", "https://sync.example.com"),
		("name", "Müller \u{2713}"),
		("spaced key", "value with  spaces  "),
		("empty", ""),
		("colon", "yes"),
		("indented", "1"),
		("multi", "first second"),
		("escapes", "a\tb\\c=d#e"),
		("emoji", "\u{1F600}"),
		("bare", ""),
	]
	.into_iter()
	.map(|(k, v)| (k.to_string(), v.to_string()))
	.collect();
	assert_eq!(p, want);
	// What store writes reads back the same, and is pure ASCII.
	let out = settings::store(&p, "c", "date");
	assert!(out.is_ascii());
	assert_eq!(settings::parse(&out), want);
	assert!(out.contains("\nname=M\\u00FCller \\u2713\n") && out.contains("\nspaced\\ key=value with  spaces  \n"));
}

#[test]
fn settings_save_and_reopen() {
	let dir = temp_dir();
	let mut s = Settings::open(&dir);
	assert_eq!((s.get_int("todoSort", 3), s.get_bool("syncOn", false)), (3, false));
	s.set("server", "https://sync.example.com").unwrap();
	s.set_int("todoSort", 2).unwrap();
	s.set_bool("syncOn", true).unwrap();
	s.set("token", "a=b c").unwrap();
	let r = Settings::open(&dir);
	assert_eq!(r.get("server", ""), "https://sync.example.com");
	assert_eq!(
		(
			r.get_int("todoSort", 0),
			r.get_bool("syncOn", false),
			r.get("token", "")
		),
		(2, true, "a=b c".to_string())
	);
	#[cfg(unix)]
	{
		use std::os::unix::fs::PermissionsExt;
		let mode = std::fs::metadata(dir.join(settings::FILE_NAME))
			.unwrap()
			.permissions()
			.mode();
		assert_eq!(mode & 0o777, 0o600);
	}
}

// ---- the real files of this machine ----

/// The rows of a data file by table and id, each with its plain text read as JSON.
fn rows_of(text: &str) -> (Value, BTreeMap<(String, i64), Value>) {
	let mut root: Value = serde_json::from_str(text).unwrap();
	let rows = root.as_object_mut().unwrap().remove("rows").unwrap();
	let mut out = BTreeMap::new();
	for mut r in rows.as_array().unwrap().clone() {
		if let Some(Value::String(p)) = r.get("plain").cloned() {
			r["plain"] = serde_json::from_str(&p).unwrap();
		}
		out.insert((r["table"].as_str().unwrap().to_string(), r["id"].as_i64().unwrap()), r);
	}
	(root, out)
}

/// Opens copies of the real desktop-data.json and desktop.properties in PALM_REAL_DATA (a folder) and
/// checks that this port writes them back as the same values. Without the variable it passes at once;
/// the files are private and stay out of the repository.
#[test]
fn real_files_round_trip() {
	let Some(dir) = std::env::var_os("PALM_REAL_DATA").map(PathBuf::from) else {
		return;
	};
	let work = temp_dir();
	let path = work.join(store::FILE_NAME);
	std::fs::copy(dir.join(store::FILE_NAME), &path).unwrap();
	let original = std::fs::read_to_string(&path).unwrap();
	let mut s = DesktopStore::open(&path).unwrap();
	s.persist();
	assert_eq!(s.save_error, None);
	let written = std::fs::read_to_string(&path).unwrap();
	let (root_a, rows_a) = rows_of(&original);
	let (root_b, rows_b) = rows_of(&written);
	assert_eq!(root_a, root_b);
	assert_eq!(rows_a.len(), rows_b.len());
	for (k, a) in &rows_a {
		assert_eq!(Some(a), rows_b.get(k), "{k:?}");
	}
	eprintln!("real data: {} rows written back as the same values", rows_a.len());

	let props = Path::new(&dir).join(settings::FILE_NAME);
	let bytes = std::fs::read(props).unwrap();
	let p = settings::parse(&bytes.iter().map(|b| *b as char).collect::<String>());
	assert_eq!(settings::parse(&settings::store(&p, "c", "d")), p);
	eprintln!("real settings: {} keys written back as the same values", p.len());
}
