//! One sync round over a [SyncStore], as SyncRunner.kt. The wire format is in sync-server/PROTOCOL.md.
//!
//! Keep-both rule: a record that changed here and on the server is kept twice. The local copy becomes
//! a new record with its own uid, and the server copy takes the place of the original. ShortCuts do
//! not follow this rule: the last writer wins.

use crate::client::{Change, PushItem, SyncResult};
use crate::codec::{self, ShortCuts};
use crate::crypto::{self, plain_bytes};
use crate::logic::{decide, Action, LocalState};
use crate::model::{Address, Category, Event, Memo, Todo};
use std::collections::HashMap;

/// One row of a sync table. A tombstone has deleted = true and no live record.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncRow {
	pub table: String,
	pub id: i64,
	pub uid: String,
	pub rev: i64,
	pub dirty: bool,
	pub deleted: bool,
}

/// The live record of a row. A round moves few records, so the Address size does not need a Box.
#[allow(clippy::large_enum_variant)]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Record {
	Event(Event),
	Todo(Todo),
	Memo(Memo),
	Address(Address),
	Category(Category),
}

/// The local data that one sync round reads and writes. Each app implements it over its own store.
pub trait SyncStore {
	/// Dirty rows of "categories", "events", "todos", "memos", "addresses", categories first.
	fn dirty_rows(&self) -> Vec<SyncRow>;
	/// Any table, live or tombstone.
	fn sync_row(&self, uid: &str) -> Option<SyncRow>;
	/// The live record of the row, or None.
	fn record(&self, row: &SyncRow) -> Option<Record>;
	/// "" for 0 or unknown.
	fn category_uid(&self, id: i32) -> String;
	/// 0 when blank or unknown.
	fn category_id_for_uid(&self, uid: &str, app: i32) -> i32;
	fn mark_synced(&mut self, table: &str, uid: &str, rev: i64, clean: bool);
	/// Saves the server copy of a record; its uid is in the record.
	fn put_remote(&mut self, record: Record, rev: i64);
	fn drop_remote(&mut self, table: &str, uid: &str);
	/// Keep-both: saves the record as a new dirty record with a new uid.
	fn save_copy(&mut self, record: Record);
	fn short_cuts(&self) -> ShortCuts;
	fn short_cuts_dirty(&self) -> bool;
	fn short_cuts_rev(&self) -> i64;
	fn set_short_cuts_rev(&mut self, rev: i64);
	fn set_short_cuts_from_sync(&mut self, list: ShortCuts, rev: i64);
	fn sync_since(&self) -> i64;
	fn set_sync_since(&mut self, rev: i64);
}

/// The push items of one round, with the plaintext of each pushed row (None for a delete).
#[derive(Clone, Debug)]
pub struct SyncPlan {
	pub items: Vec<PushItem>,
	pub pushed: HashMap<String, Option<String>>,
	pub since: i64,
}

/// What applying one server answer changed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SyncOutcome {
	pub no_conflicts: bool,
	pub changed: bool,
	pub short_cuts_changed: bool,
}

/// Each run stops after this many rounds. A later run finishes what is left.
pub const MAX_ROUNDS: u32 = 5;

const SHORTCUTS_ID: &str = "shortcuts";
pub const DECRYPT_MESSAGE: &str = "A record could not be decrypted. Check the passphrase.";

fn encode(store: &dyn SyncStore, rec: &Record) -> String {
	match rec {
		Record::Event(e) => codec::event(e),
		Record::Todo(t) => codec::todo(t, &store.category_uid(t.category)),
		Record::Memo(m) => codec::memo(m, &store.category_uid(m.category)),
		Record::Address(a) => codec::address(a, &store.category_uid(a.category)),
		Record::Category(c) => codec::category(c),
	}
}

fn seal(key: &[u8], id: &str, plain: &str) -> Result<String, String> {
	crypto::seal(key, id, &plain_bytes::pack(plain))
}

/// Step A: builds the push items from the dirty rows and the ShortCuts list.
pub fn collect(store: &dyn SyncStore, key: &[u8]) -> Result<SyncPlan, String> {
	let mut items = Vec::new();
	let mut pushed = HashMap::new();
	for row in store.dirty_rows() {
		if row.deleted {
			items.push(PushItem {
				id: row.uid.clone(),
				base_rev: row.rev,
				deleted: true,
				blob: String::new(),
			});
			pushed.insert(row.uid, None);
			continue;
		}
		let Some(rec) = store.record(&row) else { continue };
		let plain = encode(store, &rec);
		items.push(PushItem {
			id: row.uid.clone(),
			base_rev: row.rev,
			deleted: false,
			blob: seal(key, &row.uid, &plain)?,
		});
		pushed.insert(row.uid, Some(plain));
	}
	if store.short_cuts_dirty() {
		let plain = codec::shortcuts(&store.short_cuts());
		items.push(PushItem {
			id: SHORTCUTS_ID.into(),
			base_rev: store.short_cuts_rev(),
			deleted: false,
			blob: seal(key, SHORTCUTS_ID, &plain)?,
		});
		pushed.insert(SHORTCUTS_ID.into(), Some(plain));
	}
	Ok(SyncPlan {
		items,
		pushed,
		since: store.sync_since(),
	})
}

/// Steps C.1 to C.4. Fails with a message for the user when a live change cannot be decrypted. A bad
/// key changes nothing, because every live change is decrypted before any change is applied.
pub fn apply(
	store: &mut dyn SyncStore,
	key: &[u8],
	plan: &SyncPlan,
	result: &SyncResult,
) -> Result<SyncOutcome, String> {
	let changes: Vec<&Change> = result
		.changes
		.iter()
		.filter(|c| result.accepted.get(&c.id) != Some(&c.rev))
		.collect();
	let mut plains: HashMap<String, String> = HashMap::new();
	for c in &changes {
		if !c.deleted {
			let plain = crypto::open(key, &c.id, &c.blob)
				.and_then(|raw| plain_bytes::unpack(&raw))
				.map_err(|_| DECRYPT_MESSAGE.to_string())?;
			plains.insert(c.id.clone(), plain);
		}
	}
	let kind_of: HashMap<&str, String> = plains.iter().map(|(id, p)| (id.as_str(), codec::kind(p))).collect();

	// C.1: the accepted pushes. A row that did not change since the push becomes clean.
	let mut accepted: Vec<(&String, &i64)> = result.accepted.iter().collect();
	accepted.sort();
	for (uid, rev) in accepted {
		if uid == SHORTCUTS_ID {
			let list = store.short_cuts();
			if Some(codec::shortcuts(&list)) == plan.pushed.get(uid).cloned().flatten() {
				store.set_short_cuts_from_sync(list, *rev);
			} else {
				store.set_short_cuts_rev(*rev);
			}
			continue;
		}
		let Some(row) = store.sync_row(uid) else { continue };
		let pushed = plan.pushed.get(uid).cloned().flatten();
		let same = if row.deleted {
			pushed.is_none()
		} else {
			let now = store.record(&row).map(|r| encode(store, &r));
			now.is_some() && now == pushed
		};
		store.mark_synced(&row.table, uid, *rev, same);
	}

	// C.3: live categories first, then other live changes, then deleted ones.
	let is_cat = |c: &&Change| kind_of.get(c.id.as_str()).map(String::as_str) == Some("category");
	let live: Vec<&Change> = changes.iter().copied().filter(|c| !c.deleted).collect();
	let order: Vec<&Change> = live
		.iter()
		.copied()
		.filter(is_cat)
		.chain(live.iter().copied().filter(|c| !is_cat(c)))
		.chain(changes.iter().copied().filter(|c| c.deleted))
		.collect();
	let mut changed = false;
	let mut short_cuts_changed = false;
	for c in order {
		let text = plains.get(&c.id).map(String::as_str).unwrap_or("");
		if c.id == SHORTCUTS_ID {
			if apply_short_cuts(store, c, text) {
				short_cuts_changed = true;
			}
		} else if apply_change(store, c, text) {
			changed = true;
		}
	}

	// C.4: the server revision is saved only when every change above applied.
	store.set_sync_since(result.rev);
	Ok(SyncOutcome {
		no_conflicts: result.conflicts.is_empty(),
		changed,
		short_cuts_changed,
	})
}

/// Step C.3 for the ShortCuts list. True when the list was applied.
fn apply_short_cuts(store: &mut dyn SyncStore, c: &Change, text: &str) -> bool {
	let local = if store.short_cuts_dirty() {
		LocalState::Dirty
	} else {
		LocalState::Clean
	};
	match decide(local, c.deleted, false, true) {
		Action::Apply => match codec::to_shortcuts(text) {
			Ok(list) => {
				store.set_short_cuts_from_sync(list, c.rev);
				true
			}
			Err(_) => false,
		},
		Action::Rebase => {
			store.set_short_cuts_rev(c.rev);
			false
		}
		_ => false,
	}
}

/// The server copy of a record, with the local ids of its categories.
fn remote_record(store: &dyn SyncStore, kind: &str, text: &str, uid: &str) -> Option<Record> {
	match kind {
		"event" => codec::to_event(text, uid).ok().map(Record::Event),
		"todo" => codec::to_todo(text, uid, |u| store.category_id_for_uid(u, 0))
			.ok()
			.map(Record::Todo),
		"memo" => codec::to_memo(text, uid, |u| store.category_id_for_uid(u, 1))
			.ok()
			.map(Record::Memo),
		"address" => codec::to_address(text, uid, |u| store.category_id_for_uid(u, 2))
			.ok()
			.map(Record::Address),
		"category" => codec::to_category(text, uid, 0).ok().map(Record::Category),
		_ => None,
	}
}

/// Step C.2 check: true when the local record has the same content as the server copy.
fn same_content(local: &Record, remote: &Record) -> bool {
	match (local, remote) {
		(Record::Event(a), Record::Event(b)) => a.dup_key() == b.dup_key(),
		(Record::Todo(a), Record::Todo(b)) => a.dup_key() == b.dup_key(),
		(Record::Memo(a), Record::Memo(b)) => a.dup_key() == b.dup_key(),
		(Record::Address(a), Record::Address(b)) => a.dup_key() == b.dup_key(),
		(Record::Category(a), Record::Category(b)) => a.name == b.name && a.app == b.app,
		_ => false,
	}
}

/// Step C.3 for one record change. True when a record changed.
fn apply_change(store: &mut dyn SyncStore, c: &Change, text: &str) -> bool {
	let row = store.sync_row(&c.id);
	let local = match &row {
		None => LocalState::None,
		Some(r) if r.deleted => LocalState::Tomb,
		Some(r) if r.dirty => LocalState::Dirty,
		Some(_) => LocalState::Clean,
	};
	let kind = if c.deleted { String::new() } else { codec::kind(text) };
	if !c.deleted && kind.is_empty() {
		return false; // unknown kind: skip
	}
	let mine = row.as_ref().and_then(|r| store.record(r));
	let remote = if c.deleted {
		None
	} else {
		remote_record(store, &kind, text, &c.id)
	};
	let same = local == LocalState::Dirty
		&& !c.deleted
		&& matches!((&mine, &remote), (Some(m), Some(r)) if same_content(m, r));

	let put = |store: &mut dyn SyncStore| match remote.clone() {
		Some(r) => {
			store.put_remote(r, c.rev);
			true
		}
		None => false,
	};
	let drop = |store: &mut dyn SyncStore| match &row {
		Some(r) => {
			store.drop_remote(&r.table, &c.id);
			true
		}
		None => false,
	};
	let copy = |store: &mut dyn SyncStore| match mine.clone() {
		Some(m) => {
			store.save_copy(m);
			true
		}
		None => false,
	};
	match decide(local, c.deleted, same, false) {
		Action::Skip | Action::Rebase => false,
		Action::Apply => put(store),
		Action::CopyThenApply => {
			let copied = copy(store);
			put(store) || copied
		}
		Action::CopyThenDrop => {
			let copied = copy(store);
			drop(store) || copied
		}
		Action::Drop => drop(store),
	}
}
