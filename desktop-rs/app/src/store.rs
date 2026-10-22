//! The Palm Desktop record store, as DesktopStore.kt. It keeps the records in memory and saves them to one
//! JSON file (desktop-data.json, the same format as the Swing app) after each write. A synced record that
//! was deleted stays as a tombstone in the sync state until sync removes it; tombstones are not in the
//! record lists.

use palm_core::codec::{self, ShortCuts};
use palm_core::model::{last_day, occurs_on, sort_categories, Address, Category, Event, Memo, Todo};
use palm_core::runner::{Record, SyncRow, SyncStore};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs::File;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "desktop-data.json";
const TABLES: [&str; 5] = ["categories", "events", "todos", "memos", "addresses"];
const CATEGORIES: usize = 0;
const EVENTS: usize = 1;
const TODOS: usize = 2;
const MEMOS: usize = 3;
const ADDRESSES: usize = 4;

fn table_index(table: &str) -> usize {
	TABLES.iter().position(|t| *t == table).unwrap_or(MEMOS)
}

/// Sync state of one record. `deleted` marks a tombstone.
#[derive(Clone, Debug)]
struct Meta {
	uid: String,
	rev: i64,
	dirty: bool,
	deleted: bool,
}

/// A new random uid in the form of Java's UUID.randomUUID().
pub fn new_uid() -> String {
	let mut b = [0u8; 16];
	getrandom::fill(&mut b).expect("the system random source failed");
	b[6] = (b[6] & 0x0f) | 0x40;
	b[8] = (b[8] & 0x3f) | 0x80;
	let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
	format!(
		"{}-{}-{}-{}-{}",
		&h[0..8],
		&h[8..12],
		&h[12..16],
		&h[16..20],
		&h[20..32]
	)
}

/// Takes the lock of the store folder, so a second copy of the app does not overwrite the data file.
/// The lock ends with the returned file, also when the app crashes. The Swing app takes no lock.
pub fn lock(dir: &Path) -> Result<File, String> {
	std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
	let f = File::create(dir.join("desktop.lock")).map_err(|e| e.to_string())?;
	match f.try_lock() {
		Ok(()) => Ok(f),
		Err(std::fs::TryLockError::WouldBlock) => Err("Palm Desktop is already running.".to_string()),
		Err(std::fs::TryLockError::Error(e)) => Err(e.to_string()),
	}
}

pub struct DesktopStore {
	file: PathBuf,
	/// Sync state by table, then by local id.
	meta: [BTreeMap<i64, Meta>; 5],
	pub events: Vec<Event>,
	pub todos: Vec<Todo>,
	pub memos: Vec<Memo>,
	pub addresses: Vec<Address>,
	/// To Do (app 0).
	pub categories: Vec<Category>,
	/// Memo Pad (app 1).
	pub memo_categories: Vec<Category>,
	/// Address Book (app 2).
	pub address_categories: Vec<Category>,
	/// Called after every local write. The SyncStore writes do not call it.
	pub on_change: Option<Box<dyn FnMut()>>,
	/// The error of the last failed save, for the app to show. A later good save clears it.
	pub save_error: Option<String>,
	short_cut_list: ShortCuts,
	since: i64,
	sc_rev: i64,
	sc_dirty: bool,
	/// The sync format that persist writes. A file without the key is format 1.
	sync_format: i64,
}

impl DesktopStore {
	fn empty(file: &Path) -> DesktopStore {
		DesktopStore {
			file: file.to_path_buf(),
			meta: Default::default(),
			events: Vec::new(),
			todos: Vec::new(),
			memos: Vec::new(),
			addresses: Vec::new(),
			categories: Vec::new(),
			memo_categories: Vec::new(),
			address_categories: Vec::new(),
			on_change: None,
			save_error: None,
			short_cut_list: Vec::new(),
			since: 0,
			sc_rev: 0,
			sc_dirty: false,
			sync_format: 2,
		}
	}

	/// Loads the store from `file`. A missing file gives a new store with the default categories.
	pub fn open(file: &Path) -> Result<DesktopStore, String> {
		let mut store = DesktopStore::empty(file);
		match std::fs::read_to_string(file) {
			Ok(text) => store.load(&text)?,
			Err(e) if e.kind() == std::io::ErrorKind::NotFound => store.add_default_categories(),
			Err(e) => return Err(format!("{}: {e}", file.display())),
		}
		Ok(store)
	}

	// ---- lookups ----

	pub fn categories_for(&self, app: i32) -> &Vec<Category> {
		match app {
			1 => &self.memo_categories,
			2 => &self.address_categories,
			_ => &self.categories,
		}
	}

	fn categories_for_mut(&mut self, app: i32) -> &mut Vec<Category> {
		match app {
			1 => &mut self.memo_categories,
			2 => &mut self.address_categories,
			_ => &mut self.categories,
		}
	}

	fn all_categories(&self) -> impl Iterator<Item = &Category> {
		self.categories
			.iter()
			.chain(&self.memo_categories)
			.chain(&self.address_categories)
	}

	pub fn category_name(&self, id: i32) -> String {
		self.all_categories()
			.find(|c| c.id == id)
			.map_or_else(|| "Unfiled".to_string(), |c| c.name.clone())
	}

	/// Occurrences on one day: untimed first, then by start time, then by end time.
	pub fn day_events(&self, day: i64) -> Vec<&Event> {
		let mut v: Vec<&Event> = self.events.iter().filter(|e| occurs_on(e, day)).collect();
		v.sort_by_key(|e| (!e.untimed, e.start, e.end));
		v
	}

	/// Events, To Dos, Memos and Addresses whose text contains `text`, ignoring case.
	pub fn find(&self, text: &str) -> Vec<Record> {
		let t = text.to_lowercase();
		let has = |s: &str| s.to_lowercase().contains(&t);
		let mut out = Vec::new();
		out.extend(
			self.events
				.iter()
				.filter(|e| has(&e.title) || has(&e.note))
				.cloned()
				.map(Record::Event),
		);
		out.extend(
			self.todos
				.iter()
				.filter(|x| has(&x.title) || has(&x.note))
				.cloned()
				.map(Record::Todo),
		);
		out.extend(self.memos.iter().filter(|m| has(&m.text)).cloned().map(Record::Memo));
		out.extend(
			self.addresses
				.iter()
				.filter(|a| {
					[
						&a.last_name,
						&a.first_name,
						&a.title,
						&a.company,
						&a.address,
						&a.city,
						&a.state,
						&a.zip,
						&a.country,
						&a.note,
					]
					.into_iter()
					.chain(&a.phones)
					.chain(&a.custom)
					.any(|s| has(s))
				})
				.cloned()
				.map(Record::Address),
		);
		out
	}

	// ---- local writes ----

	/// Saves a new or changed event. A new one gets its id and uid here.
	pub fn save_event(&mut self, e: &mut Event) {
		self.put_event(e);
		self.changed();
	}

	pub fn save_todo(&mut self, t: &mut Todo) {
		self.put_todo(t);
		self.changed();
	}

	pub fn save_memo(&mut self, m: &mut Memo) {
		self.put_memo(m);
		self.changed();
	}

	pub fn save_address(&mut self, a: &mut Address) {
		self.put_address(a);
		self.changed();
	}

	pub fn delete_event(&mut self, id: i64) {
		self.remove_local(EVENTS, id);
		self.events.retain(|e| e.id != id);
		self.changed();
	}

	pub fn delete_todo(&mut self, id: i64) {
		self.remove_local(TODOS, id);
		self.todos.retain(|t| t.id != id);
		self.changed();
	}

	pub fn delete_memo(&mut self, id: i64) {
		self.remove_local(MEMOS, id);
		self.memos.retain(|m| m.id != id);
		self.changed();
	}

	pub fn delete_address(&mut self, id: i64) {
		self.remove_local(ADDRESSES, id);
		self.addresses.retain(|a| a.id != id);
		self.changed();
	}

	/// Adds a category for `app` and saves. Returns the new category.
	pub fn add_category(&mut self, name: &str, app: i32) -> Category {
		let cat = self.new_category(name, app);
		self.changed();
		cat
	}

	/// Adds a dirty category and does not save.
	fn new_category(&mut self, name: &str, app: i32) -> Category {
		let id = self.next_id(CATEGORIES) as i32;
		let cat = Category {
			id,
			name: name.to_string(),
			app,
			uid: new_uid(),
		};
		self.mark_dirty(CATEGORIES, id as i64, &cat.uid);
		let list = self.categories_for_mut(app);
		list.push(cat.clone());
		sort_categories(list);
		cat
	}

	pub fn rename_category(&mut self, app: i32, id: i32, name: &str) {
		let list = self.categories_for_mut(app);
		let Some(c) = list.iter_mut().find(|c| c.id == id) else {
			return;
		};
		c.name = name.to_string();
		let uid = c.uid.clone();
		sort_categories(list);
		self.mark_dirty(CATEGORIES, id as i64, &uid);
		self.changed();
	}

	/// Deletes a category. Its items move to Unfiled, and they are marked dirty.
	pub fn delete_category(&mut self, app: i32, id: i32) {
		self.remove_local(CATEGORIES, id as i64);
		self.categories_for_mut(app).retain(|c| c.id != id);
		self.move_to_unfiled(id, true);
		self.changed();
	}

	/// Deletes the events that ended before `before`. Returns the count.
	pub fn purge_events(&mut self, before: i64) -> usize {
		let old: Vec<i64> = self
			.events
			.iter()
			.filter(|e| last_day(e) < before)
			.map(|e| e.id)
			.collect();
		for id in &old {
			self.remove_local(EVENTS, *id);
		}
		self.events.retain(|e| last_day(e) >= before);
		if !old.is_empty() {
			self.changed();
		}
		old.len()
	}

	/// Deletes the completed To Do items. Returns the count.
	pub fn purge_todos(&mut self) -> usize {
		let done: Vec<i64> = self.todos.iter().filter(|t| t.done).map(|t| t.id).collect();
		for id in &done {
			self.remove_local(TODOS, *id);
		}
		self.todos.retain(|t| !t.done);
		if !done.is_empty() {
			self.changed();
		}
		done.len()
	}

	/// Replaces the ShortCuts list after a local edit. The list syncs on the next run.
	pub fn set_short_cuts(&mut self, list: ShortCuts) {
		self.short_cut_list = list;
		self.sc_dirty = true;
		self.changed();
	}

	// ---- the file ----

	/// The JSON text of the whole store, as DesktopStore.persist writes it.
	fn to_json(&self) -> Result<String, String> {
		let mut rows = Vec::new();
		for (t, table) in TABLES.iter().enumerate() {
			for (id, m) in &self.meta[t] {
				let mut row = json!({
					"table": table, "id": id, "uid": m.uid, "rev": m.rev, "dirty": m.dirty, "deleted": m.deleted,
				});
				if !m.deleted {
					let plain = self
						.plain_of(t, *id)
						.ok_or_else(|| format!("No live record for {table} {id}"))?;
					row["plain"] = Value::String(plain);
				}
				rows.push(row);
			}
		}
		let root = json!({
			"version": 1,
			"since": self.since,
			"scRev": self.sc_rev,
			"scDirty": self.sc_dirty,
			"shortcuts": self.short_cut_list.iter().map(|(n, t)| json!({"name": n, "text": t})).collect::<Vec<_>>(),
			"rows": rows,
			"addressSync": true,
			"syncFormat": self.sync_format,
		});
		Ok(root.to_string())
	}

	/// Writes the whole store: a temp file, then a move over the old file. A failure keeps the old file
	/// and goes to [save_error](Self::save_error).
	pub fn persist(&mut self) {
		let result = self.to_json().and_then(|text| {
			if let Some(dir) = self.file.parent().filter(|d| !d.as_os_str().is_empty()) {
				std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
			}
			let mut tmp = self.file.clone().into_os_string();
			tmp.push(".tmp");
			std::fs::write(&tmp, text).map_err(|e| e.to_string())?;
			std::fs::rename(&tmp, &self.file).map_err(|e| e.to_string())
		});
		self.save_error = result.err();
	}

	fn load(&mut self, text: &str) -> Result<(), String> {
		let root = match serde_json::from_str::<Value>(text) {
			Ok(Value::Object(o)) => o,
			Ok(_) => return Err("The data file is not a JSON object.".to_string()),
			Err(e) => return Err(format!("The data file does not read: {e}")),
		};
		use palm_core::json::{get_i64, get_str, opt_bool, opt_i64};
		self.since = opt_i64(&root, "since", 0);
		self.sc_rev = opt_i64(&root, "scRev", 0);
		self.sc_dirty = opt_bool(&root, "scDirty");
		self.sync_format = opt_i64(&root, "syncFormat", 1);
		self.short_cut_list = Vec::new();
		if let Some(Value::Array(sc)) = root.get("shortcuts") {
			for s in sc {
				let s = s.as_object().ok_or("A ShortCut is not an object.")?;
				self.short_cut_list.push((get_str(s, "name")?, get_str(s, "text")?));
			}
		}
		let rows: Vec<&serde_json::Map<String, Value>> = match root.get("rows") {
			Some(Value::Array(a)) => a
				.iter()
				.map(|r| r.as_object().ok_or("A row is not an object."))
				.collect::<Result<_, _>>()?,
			_ => return Err("The data file has no rows.".to_string()),
		};
		// Categories load first, so that the other rows can map category uids to ids.
		let (cats, others): (Vec<_>, Vec<_>) = rows
			.into_iter()
			.partition(|r| r.get("table").and_then(Value::as_str) == Some("categories"));
		for o in cats.into_iter().chain(others) {
			let table = get_str(o, "table")?;
			let t = table_index(&table);
			let id = get_i64(o, "id")?;
			let uid = get_str(o, "uid")?;
			let deleted = o
				.get("deleted")
				.and_then(Value::as_bool)
				.ok_or("A row has no deleted flag.")?;
			let dirty = o
				.get("dirty")
				.and_then(Value::as_bool)
				.ok_or("A row has no dirty flag.")?;
			self.meta[t].insert(
				id,
				Meta {
					uid: uid.clone(),
					rev: get_i64(o, "rev")?,
					dirty,
					deleted,
				},
			);
			if deleted {
				continue;
			}
			let text = get_str(o, "plain")?;
			match t {
				CATEGORIES => {
					let c = codec::to_category(&text, &uid, id as i32)?;
					self.categories_for_mut(c.app).push(c);
				}
				EVENTS => self.events.push(Event {
					id,
					..codec::to_event(&text, &uid)?
				}),
				TODOS => {
					let x = codec::to_todo(&text, &uid, |u| self.category_id_for_uid(u, 0))?;
					self.todos.push(Todo { id, ..x });
				}
				ADDRESSES => {
					let x = codec::to_address(&text, &uid, |u| self.category_id_for_uid(u, 2))?;
					self.addresses.push(Address { id, ..x });
				}
				_ => {
					let x = codec::to_memo(&text, &uid, |u| self.category_id_for_uid(u, 1))?;
					self.memos.push(Memo { id, ..x });
				}
			}
		}
		sort_categories(&mut self.categories);
		sort_categories(&mut self.memo_categories);
		sort_categories(&mut self.address_categories);
		self.memos.sort_by_key(|m| m.pos);
		// Desktop 0.10 skipped the address rows but still moved the sync position past them. A file
		// without the addressSync key came from 0.10, so the next sync reads all rows again.
		if !root.contains_key("addressSync") {
			self.since = 0;
		}
		// A format 1 store has uploaded its rows in format 1. Upload every live row once more in format 2.
		// The ShortCuts list goes up too, but only when it synced before, so it never replaces another
		// list with defaults.
		if self.sync_format < 2 {
			for map in &mut self.meta {
				for m in map.values_mut().filter(|m| !m.deleted) {
					m.dirty = true;
				}
			}
			if self.sc_rev > 0 {
				self.sc_dirty = true;
			}
			self.sync_format = 2;
		}
		Ok(())
	}

	fn plain_of(&self, t: usize, id: i64) -> Option<String> {
		Some(match self.live_record(t, id)? {
			Record::Event(e) => codec::event(&e),
			Record::Todo(x) => codec::todo(&x, &self.category_uid(x.category)),
			Record::Memo(m) => codec::memo(&m, &self.category_uid(m.category)),
			Record::Address(a) => codec::address(&a, &self.category_uid(a.category)),
			Record::Category(c) => codec::category(&c),
		})
	}

	// ---- helpers ----

	/// Saves after a local write: the file, then on_change.
	fn changed(&mut self) {
		self.persist();
		if let Some(f) = self.on_change.as_mut() {
			f();
		}
	}

	fn add_default_categories(&mut self) {
		let defaults = [
			(1, "Business", 0, "default-0-business"),
			(2, "Personal", 0, "default-0-personal"),
			(3, "Business", 1, "default-1-business"),
			(4, "Personal", 1, "default-1-personal"),
			(5, "Business", 2, "default-2-business"),
			(6, "Personal", 2, "default-2-personal"),
			(7, "QuickList", 2, "default-2-quicklist"),
		];
		for (id, name, app, uid) in defaults {
			self.categories_for_mut(app).push(Category {
				id,
				name: name.into(),
				app,
				uid: uid.into(),
			});
			self.mark_dirty(CATEGORIES, id as i64, uid);
		}
		sort_categories(&mut self.categories);
		sort_categories(&mut self.memo_categories);
		sort_categories(&mut self.address_categories);
	}

	fn put_event(&mut self, e: &mut Event) {
		if e.uid.is_empty() {
			e.uid = new_uid();
		}
		if e.id == 0 {
			e.id = self.next_id(EVENTS);
		}
		put_in_list(&mut self.events, e.clone(), |x| x.id == e.id);
		self.mark_dirty(EVENTS, e.id, &e.uid);
	}

	fn put_todo(&mut self, t: &mut Todo) {
		if t.uid.is_empty() {
			t.uid = new_uid();
		}
		if t.id == 0 {
			t.id = self.next_id(TODOS);
		}
		put_in_list(&mut self.todos, t.clone(), |x| x.id == t.id);
		self.mark_dirty(TODOS, t.id, &t.uid);
	}

	fn put_memo(&mut self, m: &mut Memo) {
		if m.uid.is_empty() {
			m.uid = new_uid();
		}
		if m.id == 0 {
			m.pos = self.memos.iter().map(|x| x.pos + 1).max().unwrap_or(0);
			m.id = self.next_id(MEMOS);
		}
		put_in_list(&mut self.memos, m.clone(), |x| x.id == m.id);
		self.mark_dirty(MEMOS, m.id, &m.uid);
	}

	fn put_address(&mut self, a: &mut Address) {
		if a.uid.is_empty() {
			a.uid = new_uid();
		}
		if a.id == 0 {
			a.id = self.next_id(ADDRESSES);
		}
		put_in_list(&mut self.addresses, a.clone(), |x| x.id == a.id);
		self.mark_dirty(ADDRESSES, a.id, &a.uid);
	}

	/// Marks a local write: the row is dirty and live.
	fn mark_dirty(&mut self, t: usize, id: i64, uid: &str) {
		let m = self.meta[t].entry(id).or_insert_with(|| Meta {
			uid: uid.to_string(),
			rev: 0,
			dirty: true,
			deleted: false,
		});
		m.dirty = true;
		m.deleted = false;
	}

	/// Deletes a row that never reached the server. A synced row becomes a tombstone.
	fn remove_local(&mut self, t: usize, id: i64) {
		let Some(m) = self.meta[t].get_mut(&id) else { return };
		if m.rev == 0 {
			self.meta[t].remove(&id);
		} else {
			m.deleted = true;
			m.dirty = true;
		}
	}

	/// Stores the sync state of a server copy: clean, live, at `rev`.
	fn set_server(&mut self, t: usize, id: i64, uid: &str, rev: i64) {
		self.meta[t].insert(
			id,
			Meta {
				uid: uid.to_string(),
				rev,
				dirty: false,
				deleted: false,
			},
		);
	}

	/// Moves the items of category `id` to Unfiled. `dirty` is true for a local delete only.
	fn move_to_unfiled(&mut self, id: i32, dirty: bool) {
		let mut marks = Vec::new();
		for t in self.todos.iter_mut().filter(|t| t.category == id) {
			t.category = 0;
			marks.push((TODOS, t.id, t.uid.clone()));
		}
		for m in self.memos.iter_mut().filter(|m| m.category == id) {
			m.category = 0;
			marks.push((MEMOS, m.id, m.uid.clone()));
		}
		for a in self.addresses.iter_mut().filter(|a| a.category == id) {
			a.category = 0;
			marks.push((ADDRESSES, a.id, a.uid.clone()));
		}
		if dirty {
			for (t, rid, uid) in marks {
				self.mark_dirty(t, rid, &uid);
			}
		}
	}

	fn live_record(&self, t: usize, id: i64) -> Option<Record> {
		match t {
			CATEGORIES => self
				.all_categories()
				.find(|c| c.id as i64 == id)
				.cloned()
				.map(Record::Category),
			EVENTS => self.events.iter().find(|e| e.id == id).cloned().map(Record::Event),
			TODOS => self.todos.iter().find(|x| x.id == id).cloned().map(Record::Todo),
			ADDRESSES => self.addresses.iter().find(|a| a.id == id).cloned().map(Record::Address),
			_ => self.memos.iter().find(|m| m.id == id).cloned().map(Record::Memo),
		}
	}

	fn id_of(&self, t: usize, uid: &str) -> Option<i64> {
		self.meta[t].iter().find(|(_, m)| m.uid == uid).map(|(id, _)| *id)
	}

	/// The local id of `uid`, or a new id when this device has not seen the uid.
	fn id_for(&self, t: usize, uid: &str) -> i64 {
		self.id_of(t, uid).unwrap_or_else(|| self.next_id(t))
	}

	/// One more than the highest id of the table, tombstones included.
	fn next_id(&self, t: usize) -> i64 {
		self.meta[t].keys().next_back().copied().unwrap_or(0) + 1
	}
}

/// Replaces the item that `same` finds, or adds `item` when none is found.
fn put_in_list<T>(list: &mut Vec<T>, item: T, same: impl Fn(&T) -> bool) {
	match list.iter().position(same) {
		Some(i) => list[i] = item,
		None => list.push(item),
	}
}

fn row_of(t: usize, id: i64, m: &Meta) -> SyncRow {
	SyncRow {
		table: TABLES[t].to_string(),
		id,
		uid: m.uid.clone(),
		rev: m.rev,
		dirty: m.dirty,
		deleted: m.deleted,
	}
}

impl SyncStore for DesktopStore {
	fn dirty_rows(&self) -> Vec<SyncRow> {
		(0..TABLES.len())
			.flat_map(|t| {
				self.meta[t]
					.iter()
					.filter(|(_, m)| m.dirty)
					.map(move |(id, m)| row_of(t, *id, m))
			})
			.collect()
	}

	fn sync_row(&self, uid: &str) -> Option<SyncRow> {
		(0..TABLES.len()).find_map(|t| self.id_of(t, uid).map(|id| row_of(t, id, &self.meta[t][&id])))
	}

	fn record(&self, row: &SyncRow) -> Option<Record> {
		if row.deleted {
			None
		} else {
			self.live_record(table_index(&row.table), row.id)
		}
	}

	fn category_uid(&self, id: i32) -> String {
		if id == 0 {
			return String::new();
		}
		self.all_categories()
			.find(|c| c.id == id)
			.map(|c| c.uid.clone())
			.unwrap_or_default()
	}

	fn category_id_for_uid(&self, uid: &str, app: i32) -> i32 {
		if palm_core::model::is_blank(uid) {
			return 0;
		}
		self.categories_for(app)
			.iter()
			.find(|c| c.uid == uid)
			.map_or(0, |c| c.id)
	}

	fn mark_synced(&mut self, table: &str, uid: &str, rev: i64, clean: bool) {
		let t = table_index(table);
		let Some(id) = self.id_of(t, uid) else { return };
		let m = self.meta[t].get_mut(&id).expect("id_of found it");
		if clean && m.deleted {
			self.meta[t].remove(&id);
		} else {
			m.rev = rev;
			if clean {
				m.dirty = false;
			}
		}
		self.persist();
	}

	fn put_remote(&mut self, record: Record, rev: i64) {
		match record {
			Record::Event(mut e) => {
				e.id = self.id_for(EVENTS, &e.uid);
				self.set_server(EVENTS, e.id, &e.uid, rev);
				let id = e.id;
				put_in_list(&mut self.events, e, |x| x.id == id);
			}
			Record::Todo(mut x) => {
				x.id = self.id_for(TODOS, &x.uid);
				self.set_server(TODOS, x.id, &x.uid, rev);
				let id = x.id;
				put_in_list(&mut self.todos, x, |y| y.id == id);
			}
			Record::Memo(mut m) => {
				m.id = self.id_for(MEMOS, &m.uid);
				self.set_server(MEMOS, m.id, &m.uid, rev);
				let id = m.id;
				put_in_list(&mut self.memos, m, |y| y.id == id);
			}
			Record::Address(mut a) => {
				a.id = self.id_for(ADDRESSES, &a.uid);
				self.set_server(ADDRESSES, a.id, &a.uid, rev);
				let id = a.id;
				put_in_list(&mut self.addresses, a, |y| y.id == id);
			}
			Record::Category(c) => {
				let id = self.id_for(CATEGORIES, &c.uid) as i32;
				self.categories.retain(|x| x.uid != c.uid);
				self.memo_categories.retain(|x| x.uid != c.uid);
				self.address_categories.retain(|x| x.uid != c.uid);
				self.set_server(CATEGORIES, id as i64, &c.uid, rev);
				let list = self.categories_for_mut(c.app);
				list.push(Category { id, ..c });
				sort_categories(list);
			}
		}
		self.persist();
	}

	/// Removes a row that another device deleted. A category also moves its items to Unfiled, without
	/// marking them dirty.
	fn drop_remote(&mut self, table: &str, uid: &str) {
		let t = table_index(table);
		let Some(id) = self.id_of(t, uid) else { return };
		self.meta[t].remove(&id);
		match t {
			CATEGORIES => {
				let cid = id as i32;
				self.categories.retain(|c| c.id != cid);
				self.memo_categories.retain(|c| c.id != cid);
				self.address_categories.retain(|c| c.id != cid);
				self.move_to_unfiled(cid, false);
			}
			EVENTS => self.events.retain(|e| e.id != id),
			TODOS => self.todos.retain(|x| x.id != id),
			ADDRESSES => self.addresses.retain(|a| a.id != id),
			_ => self.memos.retain(|m| m.id != id),
		}
		self.persist();
	}

	/// Keeps `record` as a new dirty record with a new uid.
	fn save_copy(&mut self, record: Record) {
		match record {
			Record::Event(mut e) => {
				e.id = 0;
				e.uid = String::new();
				self.put_event(&mut e);
			}
			Record::Todo(mut x) => {
				x.id = 0;
				x.uid = String::new();
				self.put_todo(&mut x);
			}
			Record::Memo(mut m) => {
				m.id = 0;
				m.uid = String::new();
				self.put_memo(&mut m);
			}
			Record::Address(mut a) => {
				a.id = 0;
				a.uid = String::new();
				self.put_address(&mut a);
			}
			Record::Category(c) => {
				self.new_category(&c.name, c.app);
			}
		}
		self.persist();
	}

	fn short_cuts(&self) -> ShortCuts {
		self.short_cut_list.clone()
	}

	fn short_cuts_dirty(&self) -> bool {
		self.sc_dirty
	}

	fn short_cuts_rev(&self) -> i64 {
		self.sc_rev
	}

	fn set_short_cuts_rev(&mut self, rev: i64) {
		self.sc_rev = rev;
		self.persist();
	}

	fn set_short_cuts_from_sync(&mut self, list: ShortCuts, rev: i64) {
		self.short_cut_list = list;
		self.sc_rev = rev;
		self.sc_dirty = false;
		self.persist();
	}

	fn sync_since(&self) -> i64 {
		self.since
	}

	fn set_sync_since(&mut self, rev: i64) {
		self.since = rev;
		self.persist();
	}
}
