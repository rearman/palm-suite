//! Two devices sync through a real palmsync server. The test runs when PALMSYNC_URL and PALMSYNC_TOKEN
//! name a fresh server (CI builds one from sync-server/ and starts it); without them it passes at once.

mod common;

use common::MemStore;
use palm_core::client::{first_key, SyncClient};
use palm_core::model::{Address, Category, Event, Memo, Todo};
use palm_core::runner::Record;
use palm_core::session::{Step, SyncSession};

fn server() -> Option<SyncClient> {
	// CI sets PALMSYNC_REQUIRED where it starts a server, so a lost server fails the test there.
	if std::env::var("PALMSYNC_REQUIRED").is_ok() && std::env::var("PALMSYNC_URL").is_err() {
		panic!("PALMSYNC_REQUIRED is set but PALMSYNC_URL is not");
	}
	let url = std::env::var("PALMSYNC_URL").ok()?;
	let token = std::env::var("PALMSYNC_TOKEN").ok()?;
	Some(SyncClient::new(&url, &token).expect("a valid server address"))
}

/// One full sync run of `store` against the server; panics on an error.
fn sync(client: &SyncClient, store: &mut MemStore, key: &[u8]) -> bool {
	let mut session = SyncSession::new();
	let mut prep = || Ok(());
	let mut step = session.start(store, key, &mut prep).unwrap();
	loop {
		match step {
			Step::Send(plan) => {
				let net = client.sync(plan.since, &plan.items).map_err(|e| e.message);
				step = session.applied(store, key, &plan, net, &mut prep).1;
			}
			Step::Done { error, changed, .. } => {
				assert_eq!(error, None);
				return changed;
			}
		}
	}
}

fn titles(store: &MemStore) -> Vec<String> {
	let mut t: Vec<String> = store
		.live("events")
		.iter()
		.map(|r| match r {
			Record::Event(e) => e.title.clone(),
			_ => unreachable!(),
		})
		.collect();
	t.sort();
	t
}

#[test]
fn two_devices_sync_through_the_server() {
	let Some(client) = server() else {
		eprintln!("PALMSYNC_URL and PALMSYNC_TOKEN are not set; the server test does not run.");
		return;
	};

	// Setup: the first device stores the salt and the check blob; the second one derives the same key.
	let key_a = first_key(&client, "correct horse")
		.unwrap()
		.expect("the first passphrase sets up the server");
	let key_b = first_key(&client, "correct horse")
		.unwrap()
		.expect("the same passphrase gives the key");
	assert_eq!(key_a, key_b);
	assert_eq!(first_key(&client, "wrong").unwrap(), None);

	// Device A makes one record of each kind, a category and the ShortCuts.
	let mut a = MemStore::new();
	let work = a.create(Record::Category(Category {
		id: 4,
		name: "Work".into(),
		app: 0,
		uid: String::new(),
	}));
	a.create(Record::Todo(Todo {
		title: "Order VFD".into(),
		category: 4,
		priority: 2,
		..Todo::default()
	}));
	let shared = a.create(Record::Event(Event {
		title: "Staff meeting".into(),
		date: 20_750,
		..Event::default()
	}));
	let memo = a.create(Record::Memo(Memo {
		text: "Grüße\nbody".into(),
		..Memo::default()
	}));
	a.create(Record::Address(Address {
		last_name: "Müller".into(),
		..Address::default()
	}));
	a.short_cuts = vec![("br".into(), "Breakfast".into())];
	a.short_cuts_dirty = true;
	sync(&client, &mut a, &key_a);
	assert!(a.rows.iter().all(|e| !e.dirty) && !a.short_cuts_dirty);

	// Device B gets all of it; the To Do keeps its category under B's own category id.
	let mut b = MemStore::new();
	assert!(sync(&client, &mut b, &key_b));
	assert_eq!(b.live("events").len(), 1);
	assert_eq!(b.short_cuts, a.short_cuts);
	let b_work = match &b.entry(&work).unwrap().rec {
		Record::Category(c) => c.id,
		_ => unreachable!(),
	};
	assert!(matches!(b.live("todos")[0], Record::Todo(t) if t.title == "Order VFD" && t.category == b_work));
	assert!(matches!(b.live("memos")[0], Record::Memo(m) if m.text == "Grüße\nbody"));
	assert!(matches!(b.live("addresses")[0], Record::Address(x) if x.last_name == "Müller"));

	// Both devices change the same event: keep-both gives each device both versions.
	a.edit(&shared, |r| {
		if let Record::Event(e) = r {
			e.title = "Staff meeting (A)".into()
		}
	});
	b.edit(&shared, |r| {
		if let Record::Event(e) = r {
			e.title = "Staff meeting (B)".into()
		}
	});
	sync(&client, &mut a, &key_a);
	sync(&client, &mut b, &key_b);
	sync(&client, &mut a, &key_a);
	let both = vec!["Staff meeting (A)".to_string(), "Staff meeting (B)".to_string()];
	assert_eq!(titles(&a), both);
	assert_eq!(titles(&b), both);

	// A delete on one device removes the record on the other.
	a.delete(&memo);
	sync(&client, &mut a, &key_a);
	sync(&client, &mut b, &key_b);
	assert!(b.live("memos").is_empty());
	assert!(
		a.entry(&memo).is_none(),
		"the pushed tombstone is gone once the server takes it"
	);
}
