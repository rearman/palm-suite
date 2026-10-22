//! An in-memory SyncStore for the round and server tests, as FakeStore in SyncRunnerTest.kt. Rows keep
//! their insertion order; a category from the server gets the next free local id.

#![allow(dead_code)]

use palm_core::codec::ShortCuts;
use palm_core::runner::{Record, SyncRow, SyncStore};
use std::sync::atomic::{AtomicU32, Ordering};

pub struct Entry {
	pub table: &'static str,
	pub uid: String,
	pub rev: i64,
	pub dirty: bool,
	pub deleted: bool,
	pub rec: Record,
}

#[derive(Default)]
pub struct MemStore {
	pub rows: Vec<Entry>,
	pub copies: Vec<Record>,
	pub short_cuts: ShortCuts,
	pub short_cuts_dirty: bool,
	pub short_cuts_rev: i64,
	pub sync_since: i64,
}

/// New uids for every store of the test process.
static NEXT_UID: AtomicU32 = AtomicU32::new(1);

const TABLES: [&str; 5] = ["categories", "events", "todos", "memos", "addresses"];

pub fn table_of(rec: &Record) -> &'static str {
	match rec {
		Record::Category(_) => "categories",
		Record::Event(_) => "events",
		Record::Todo(_) => "todos",
		Record::Memo(_) => "memos",
		Record::Address(_) => "addresses",
	}
}

fn with_uid(rec: Record, uid: &str) -> Record {
	match rec {
		Record::Event(mut x) => {
			x.uid = uid.into();
			Record::Event(x)
		}
		Record::Todo(mut x) => {
			x.uid = uid.into();
			Record::Todo(x)
		}
		Record::Memo(mut x) => {
			x.uid = uid.into();
			Record::Memo(x)
		}
		Record::Address(mut x) => {
			x.uid = uid.into();
			Record::Address(x)
		}
		Record::Category(mut x) => {
			x.uid = uid.into();
			Record::Category(x)
		}
	}
}

impl MemStore {
	pub fn new() -> MemStore {
		MemStore::default()
	}

	pub fn entry(&self, uid: &str) -> Option<&Entry> {
		self.rows.iter().find(|e| e.uid == uid)
	}

	pub fn entry_mut(&mut self, uid: &str) -> Option<&mut Entry> {
		self.rows.iter_mut().find(|e| e.uid == uid)
	}

	/// Adds a row with this uid, as a local edit unless `dirty` is false.
	pub fn add(&mut self, uid: &str, rec: Record, rev: i64, dirty: bool, deleted: bool) {
		let rec = with_uid(rec, uid);
		self.rows.retain(|e| e.uid != uid);
		self.rows.push(Entry {
			table: table_of(&rec),
			uid: uid.into(),
			rev,
			dirty,
			deleted,
			rec,
		});
	}

	/// A new local record with a new uid.
	pub fn create(&mut self, rec: Record) -> String {
		let uid = format!("local-{}", NEXT_UID.fetch_add(1, Ordering::Relaxed));
		self.add(&uid, rec, 0, true, false);
		uid
	}

	/// Changes a record locally, as an edit in the app.
	pub fn edit(&mut self, uid: &str, f: impl FnOnce(&mut Record)) {
		let e = self.entry_mut(uid).expect("no such row");
		f(&mut e.rec);
		e.dirty = true;
	}

	/// Deletes a record locally: it stays as a dirty tombstone until sync pushes it.
	pub fn delete(&mut self, uid: &str) {
		let e = self.entry_mut(uid).expect("no such row");
		e.deleted = true;
		e.dirty = true;
	}

	pub fn live(&self, table: &str) -> Vec<&Record> {
		self.rows
			.iter()
			.filter(|e| e.table == table && !e.deleted)
			.map(|e| &e.rec)
			.collect()
	}

	fn next_category_id(&self) -> i32 {
		1 + self
			.rows
			.iter()
			.filter_map(|e| match &e.rec {
				Record::Category(c) => Some(c.id),
				_ => None,
			})
			.max()
			.unwrap_or(0)
	}
}

impl SyncStore for MemStore {
	fn dirty_rows(&self) -> Vec<SyncRow> {
		let mut rows: Vec<&Entry> = self.rows.iter().filter(|e| e.dirty).collect();
		rows.sort_by_key(|e| TABLES.iter().position(|t| *t == e.table));
		rows.iter().map(|e| row(e)).collect()
	}

	fn sync_row(&self, uid: &str) -> Option<SyncRow> {
		self.entry(uid).map(row)
	}

	fn record(&self, r: &SyncRow) -> Option<Record> {
		self.entry(&r.uid).filter(|e| !e.deleted).map(|e| e.rec.clone())
	}

	fn category_uid(&self, id: i32) -> String {
		if id == 0 {
			return String::new();
		}
		self.rows
			.iter()
			.find(|e| !e.deleted && matches!(&e.rec, Record::Category(c) if c.id == id))
			.map(|e| e.uid.clone())
			.unwrap_or_default()
	}

	fn category_id_for_uid(&self, uid: &str, app: i32) -> i32 {
		match self.entry(uid).filter(|e| !e.deleted).map(|e| &e.rec) {
			Some(Record::Category(c)) if c.app == app => c.id,
			_ => 0,
		}
	}

	/// A tombstone that the server took goes away, as in the app stores.
	fn mark_synced(&mut self, _table: &str, uid: &str, rev: i64, clean: bool) {
		if let Some(e) = self.entry_mut(uid) {
			e.rev = rev;
			e.dirty = !clean;
		}
		self.rows.retain(|e| !(e.uid == uid && e.deleted && clean));
	}

	fn put_remote(&mut self, rec: Record, rev: i64) {
		let uid = match &rec {
			Record::Event(x) => x.uid.clone(),
			Record::Todo(x) => x.uid.clone(),
			Record::Memo(x) => x.uid.clone(),
			Record::Address(x) => x.uid.clone(),
			Record::Category(x) => x.uid.clone(),
		};
		let rec = match rec {
			Record::Category(mut c) => {
				c.id = match self.entry(&uid).map(|e| &e.rec) {
					Some(Record::Category(old)) => old.id,
					_ => self.next_category_id(),
				};
				Record::Category(c)
			}
			other => other,
		};
		self.add(&uid, rec, rev, false, false);
	}

	fn drop_remote(&mut self, _table: &str, uid: &str) {
		self.rows.retain(|e| e.uid != uid);
	}

	fn save_copy(&mut self, rec: Record) {
		let uid = format!("copy-{}", self.copies.len() + 1);
		let rec = match rec {
			Record::Category(mut c) => {
				c.id = self.next_category_id();
				Record::Category(c)
			}
			other => other,
		};
		self.copies.push(rec.clone());
		self.add(&uid, rec, 0, true, false);
	}

	fn short_cuts(&self) -> ShortCuts {
		self.short_cuts.clone()
	}

	fn short_cuts_dirty(&self) -> bool {
		self.short_cuts_dirty
	}

	fn short_cuts_rev(&self) -> i64 {
		self.short_cuts_rev
	}

	fn set_short_cuts_rev(&mut self, rev: i64) {
		self.short_cuts_rev = rev;
	}

	fn set_short_cuts_from_sync(&mut self, list: ShortCuts, rev: i64) {
		self.short_cuts = list;
		self.short_cuts_rev = rev;
		self.short_cuts_dirty = false;
	}

	fn sync_since(&self) -> i64 {
		self.sync_since
	}

	fn set_sync_since(&mut self, rev: i64) {
		self.sync_since = rev;
	}
}

fn row(e: &Entry) -> SyncRow {
	SyncRow {
		table: e.table.into(),
		id: 0,
		uid: e.uid.clone(),
		rev: e.rev,
		dirty: e.dirty,
		deleted: e.deleted,
	}
}
