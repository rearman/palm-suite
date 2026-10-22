//! One sync round and the round loop, as SyncRunnerTest.kt and SyncSessionTest.kt.

mod common;

use common::MemStore;
use palm_core::client::{Change, SyncResult};
use palm_core::codec;
use palm_core::crypto::{self, plain_bytes};
use palm_core::model::{Address, Category, Event};
use palm_core::runner::{self, Record, SyncStore, DECRYPT_MESSAGE, MAX_ROUNDS};
use palm_core::session::{Step, SyncSession};
use std::collections::{BTreeSet, HashMap};

fn key() -> Vec<u8> {
	(0..32).map(|i| i as u8 * 3 + 1).collect()
}

fn event(title: &str) -> Record {
	Record::Event(Event {
		title: title.into(),
		date: 19_000,
		start: 540,
		end: 600,
		..Event::default()
	})
}

fn title(store: &MemStore, uid: &str) -> String {
	match &store.entry(uid).unwrap().rec {
		Record::Event(e) => e.title.clone(),
		other => panic!("not an event: {other:?}"),
	}
}

/// A live change when `text` is Some, otherwise a delete.
fn change(uid: &str, rev: i64, text: Option<&str>, k: &[u8]) -> Change {
	match text {
		None => Change {
			id: uid.into(),
			rev,
			deleted: true,
			blob: String::new(),
		},
		Some(t) => Change {
			id: uid.into(),
			rev,
			deleted: false,
			blob: crypto::seal(k, uid, t.as_bytes()).unwrap(),
		},
	}
}

fn result(rev: i64, accepted: &[(&str, i64)], changes: Vec<Change>) -> SyncResult {
	SyncResult {
		rev,
		accepted: accepted
			.iter()
			.map(|(u, r)| (u.to_string(), *r))
			.collect::<HashMap<_, _>>(),
		conflicts: BTreeSet::new(),
		changes,
	}
}

fn remote_event(t: &str) -> String {
	codec::event(&Event {
		title: t.into(),
		date: 19_000,
		start: 540,
		end: 600,
		..Event::default()
	})
}

#[test]
fn collect_seals_dirty_rows_and_sends_tombstones() {
	let mut store = MemStore::new();
	store.add("e1", event("Dentist"), 0, true, false);
	store.add("e2", event("Gone"), 3, true, true);
	let plan = runner::collect(&store, &key()).unwrap();
	assert_eq!(plan.items.len(), 2);
	let live = &plan.items[0];
	assert_eq!((live.id.as_str(), live.deleted), ("e1", false));
	let plain = plain_bytes::unpack(&crypto::open(&key(), "e1", &live.blob).unwrap()).unwrap();
	assert_eq!(codec::kind(&plain), "event");
	assert_eq!(codec::to_event(&plain, "e1").unwrap().title, "Dentist");
	assert_eq!(plan.pushed["e1"], Some(plain));
	let tomb = &plan.items[1];
	assert_eq!(
		(tomb.id.as_str(), tomb.deleted, tomb.blob.as_str(), tomb.base_rev),
		("e2", true, "", 3)
	);
	assert_eq!(plan.pushed["e2"], None);
	assert_eq!(plan.since, store.sync_since());
}

#[test]
fn accepted_unchanged_row_becomes_clean() {
	let mut store = MemStore::new();
	store.add("e1", event("Dentist"), 0, true, false);
	let plan = runner::collect(&store, &key()).unwrap();
	let outcome = runner::apply(&mut store, &key(), &plan, &result(5, &[("e1", 5)], vec![])).unwrap();
	let e = store.entry("e1").unwrap();
	assert_eq!((e.rev, e.dirty), (5, false));
	assert!(outcome.no_conflicts && !outcome.changed);
}

#[test]
fn accepted_row_changed_after_collect_stays_dirty() {
	let mut store = MemStore::new();
	store.add("e1", event("Dentist"), 0, true, false);
	let plan = runner::collect(&store, &key()).unwrap();
	store.entry_mut("e1").unwrap().rec = event("Dentist, moved");
	runner::apply(&mut store, &key(), &plan, &result(5, &[("e1", 5)], vec![])).unwrap();
	let e = store.entry("e1").unwrap();
	assert_eq!((e.rev, e.dirty), (5, true));
}

#[test]
fn remote_new_event_is_inserted() {
	let mut store = MemStore::new();
	let plan = runner::collect(&store, &key()).unwrap();
	let changes = vec![change("e9", 4, Some(&remote_event("Remote")), &key())];
	let outcome = runner::apply(&mut store, &key(), &plan, &result(4, &[], changes)).unwrap();
	assert_eq!(title(&store, "e9"), "Remote");
	let e = store.entry("e9").unwrap();
	assert_eq!((e.rev, e.dirty), (4, false));
	assert!(outcome.changed);
	assert_eq!(store.sync_since(), 4);
}

#[test]
fn remote_new_address_is_inserted_with_address_category() {
	let mut store = MemStore::new();
	store.add(
		"cat-a",
		Record::Category(Category {
			id: 6,
			name: "Work".into(),
			app: 2,
			uid: String::new(),
		}),
		0,
		false,
		false,
	);
	let plan = runner::collect(&store, &key()).unwrap();
	let remote = codec::address(
		&Address {
			last_name: "Earman".into(),
			first_name: "Ross".into(),
			..Address::default()
		},
		"cat-a",
	);
	let outcome = runner::apply(
		&mut store,
		&key(),
		&plan,
		&result(4, &[], vec![change("a9", 4, Some(&remote), &key())]),
	)
	.unwrap();
	match &store.entry("a9").unwrap().rec {
		Record::Address(a) => assert_eq!((a.last_name.as_str(), a.category), ("Earman", 6)),
		other => panic!("{other:?}"),
	}
	assert!(outcome.changed);
}

#[test]
fn dirty_local_event_with_different_remote_keeps_both() {
	let mut store = MemStore::new();
	store.add("e1", event("Local"), 0, true, false);
	let plan = runner::collect(&store, &key()).unwrap();
	let changes = vec![change("e1", 7, Some(&remote_event("Remote")), &key())];
	let outcome = runner::apply(&mut store, &key(), &plan, &result(7, &[], changes)).unwrap();
	assert_eq!(store.copies.len(), 1);
	assert!(matches!(&store.copies[0], Record::Event(e) if e.title == "Local"));
	assert_eq!(title(&store, "e1"), "Remote");
	let e = store.entry("e1").unwrap();
	assert_eq!((e.rev, e.dirty), (7, false));
	assert!(outcome.changed);
}

#[test]
fn dirty_local_event_with_same_content_takes_remote_without_copy() {
	let mut store = MemStore::new();
	store.add("e1", event("Same"), 0, true, false);
	let plan = runner::collect(&store, &key()).unwrap();
	let changes = vec![change("e1", 7, Some(&remote_event("Same")), &key())];
	runner::apply(&mut store, &key(), &plan, &result(7, &[], changes)).unwrap();
	assert!(store.copies.is_empty());
	let e = store.entry("e1").unwrap();
	assert_eq!((e.rev, e.dirty), (7, false));
}

#[test]
fn remote_delete_of_clean_row_drops_it() {
	let mut store = MemStore::new();
	store.add("e1", event("Old"), 2, false, false);
	let plan = runner::collect(&store, &key()).unwrap();
	let outcome = runner::apply(
		&mut store,
		&key(),
		&plan,
		&result(8, &[], vec![change("e1", 8, None, &key())]),
	)
	.unwrap();
	assert!(store.sync_row("e1").is_none());
	assert!(outcome.changed);
}

#[test]
fn short_cuts_dirty_with_remote_change_rebases() {
	let mut store = MemStore::new();
	store.short_cuts = vec![("a".into(), "1".into())];
	store.short_cuts_dirty = true;
	store.short_cuts_rev = 3;
	let plan = runner::collect(&store, &key()).unwrap();
	assert_eq!(plan.items.iter().find(|i| i.id == "shortcuts").unwrap().base_rev, 3);
	let remote = codec::shortcuts(&[("b".into(), "2".into())]);
	let outcome = runner::apply(
		&mut store,
		&key(),
		&plan,
		&result(9, &[], vec![change("shortcuts", 9, Some(&remote), &key())]),
	)
	.unwrap();
	assert_eq!(store.short_cuts_rev, 9);
	assert!(store.short_cuts_dirty);
	assert_eq!(store.short_cuts, vec![("a".to_string(), "1".to_string())]);
	assert!(!outcome.short_cuts_changed && !outcome.changed);
}

#[test]
fn blob_sealed_with_another_key_fails_and_changes_nothing() {
	let mut store = MemStore::new();
	store.add("e1", event("Local"), 0, true, false);
	let plan = runner::collect(&store, &key()).unwrap();
	let other = vec![9u8; 32];
	let changes = vec![change("e1", 7, Some(&remote_event("Remote")), &other)];
	let err = runner::apply(&mut store, &key(), &plan, &result(7, &[("e1", 5)], changes)).unwrap_err();
	assert_eq!(err, DECRYPT_MESSAGE);
	let e = store.entry("e1").unwrap();
	assert_eq!((e.rev, e.dirty), (0, true));
	assert_eq!(title(&store, "e1"), "Local");
	assert!(store.copies.is_empty());
	assert_eq!(store.sync_since(), 0);
}

// ---- the round loop ----

fn no_prep() -> impl FnMut() -> Result<(), String> {
	|| Ok(())
}

fn empty_result(conflicts: &[&str]) -> SyncResult {
	SyncResult {
		conflicts: conflicts.iter().map(|c| c.to_string()).collect(),
		..SyncResult::default()
	}
}

/// Drives a run to its end with `send` answering each round; returns the Done step and the round count.
fn drive(
	session: &mut SyncSession,
	store: &mut MemStore,
	mut send: impl FnMut(&mut SyncSession) -> Result<SyncResult, String>,
) -> (Step, u32) {
	let mut prep = no_prep();
	let mut step = session.start(store, &key(), &mut prep).expect("a run was in progress");
	let mut rounds = 0;
	while let Step::Send(plan) = step {
		rounds += 1;
		let net = send(session);
		step = session.applied(store, &key(), &plan, net, &mut prep).1;
	}
	(step, rounds)
}

fn done(step: &Step) -> (Option<String>, bool, bool) {
	match step {
		Step::Done { error, changed, again } => (error.clone(), *changed, *again),
		Step::Send(_) => panic!("the run did not end"),
	}
}

#[test]
fn clean_run_finishes_once_with_no_error_and_no_change() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let (step, rounds) = drive(&mut session, &mut store, |_| Ok(empty_result(&[])));
	assert_eq!(done(&step), (None, false, false));
	assert_eq!(rounds, 1);
	assert!(!session.running());
}

#[test]
fn start_while_running_gives_none() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let mut inner = None;
	let (step, _) = drive(&mut session, &mut store, |s| {
		inner = Some(s.start(&MemStore::new(), &key(), &mut no_prep()).is_none());
		Ok(empty_result(&[]))
	});
	assert_eq!(inner, Some(true));
	assert_eq!(done(&step), (None, false, false));
	assert!(!session.running());
}

#[test]
fn failed_send_finishes_with_its_message() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let (step, _) = drive(&mut session, &mut store, |_| Err("No network.".into()));
	assert_eq!(done(&step), (Some("No network.".into()), false, false));
	assert!(!session.running());
}

#[test]
fn failed_send_without_message_uses_default_text() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let (step, _) = drive(&mut session, &mut store, |_| Err(String::new()));
	assert_eq!(done(&step).0, Some("Sync failed.".into()));
}

#[test]
fn request_again_during_run_asks_once_after_finish() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let (step, _) = drive(&mut session, &mut store, |s| {
		s.request_again();
		Ok(empty_result(&[]))
	});
	assert_eq!(done(&step), (None, false, true));
	let (step, _) = drive(&mut session, &mut store, |_| Ok(empty_result(&[])));
	assert!(!done(&step).2);
}

#[test]
fn request_again_without_run_does_nothing() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	session.request_again();
	let (step, _) = drive(&mut session, &mut store, |_| Ok(empty_result(&[])));
	assert!(!done(&step).2);
}

#[test]
fn conflicts_run_up_to_max_rounds() {
	let (mut session, mut store) = (SyncSession::new(), MemStore::new());
	let (step, rounds) = drive(&mut session, &mut store, |_| Ok(empty_result(&["e1"])));
	assert_eq!(rounds, MAX_ROUNDS);
	assert_eq!(done(&step), (None, false, false));
}

#[test]
fn before_collect_error_ends_the_run() {
	let (mut session, store) = (SyncSession::new(), MemStore::new());
	let step = session
		.start(&store, &key(), &mut || Err("Store busy.".to_string()))
		.unwrap();
	assert_eq!(done(&step), (Some("Store busy.".into()), false, false));
	assert!(!session.running());
}
