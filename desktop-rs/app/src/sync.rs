//! Sync with the sync server, as DesktopSync.kt. Each record is sealed with the sync key before it leaves
//! this computer; the wire format is in sync-server/PROTOCOL.md.
//!
//! Threads: the store and the settings stay on the UI thread. Each network call and the key derivation
//! of a set up run on a thread of their own, and send their result to the event loop as a [Msg].

use crate::msg::Msg;
use crate::settings::Settings;
use crate::store::DesktopStore;
use fltk::app::Sender;
use palm_core::client::{first_key, SyncClient};
use palm_core::crypto::{b64, unb64};
use palm_core::model;
use palm_core::runner::{SyncPlan, SyncStore};
use palm_core::session::{Step, SyncSession};
use std::time::{Duration, Instant};

pub const DEFAULT_SERVER: &str = "https://sync.earman.xyz";
/// The window coming to the front syncs when the last sync ended longer ago than this.
const STALE: Duration = Duration::from_secs(60);

/// Who waits for the end of a run.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Report {
	/// An automatic sync: the status bar only.
	Quiet,
	/// Sync > Sync Now: an alert on an error, else "Sync done." in the status bar.
	Menu,
	/// The Sync Options dialog: the dialog shows the result.
	Dialog,
}

/// What the UI does after a sync step.
#[derive(Debug, PartialEq, Eq)]
pub enum Effect {
	Status(String),
	/// A round changed records: the views read the store again.
	Refresh,
	Alert(String),
	/// The result of a dialog run or set up: None on success, else the error text.
	DialogDone(Option<String>),
}

struct Pending {
	plan: SyncPlan,
	key: Vec<u8>,
}

pub struct DesktopSync {
	session: SyncSession,
	pending: Option<Pending>,
	last_end: Option<Instant>,
	reports: Vec<Report>,
	sender: Sender<Msg>,
	h24: bool,
}

impl DesktopSync {
	pub fn new(sender: Sender<Msg>, h24: bool) -> DesktopSync {
		DesktopSync {
			session: SyncSession::new(),
			pending: None,
			last_end: None,
			reports: Vec::new(),
			sender,
			h24,
		}
	}

	pub fn is_on(settings: &Settings) -> bool {
		settings.get_bool("syncOn", false)
	}

	pub fn server(settings: &Settings) -> String {
		settings.get("server", DEFAULT_SERVER)
	}

	pub fn running(&self) -> bool {
		self.session.running()
	}

	/// Runs one sync. A run that cannot start reports why at once.
	pub fn sync_now(&mut self, store: &mut DesktopStore, settings: &mut Settings, report: Report) -> Vec<Effect> {
		let refuse = |text: &str| match report {
			Report::Quiet => Vec::new(),
			Report::Menu => vec![Effect::Alert(text.to_string())],
			Report::Dialog => vec![Effect::DialogDone(Some(text.to_string()))],
		};
		if self.session.running() {
			return refuse("A sync is already running.");
		}
		if !Self::is_on(settings) {
			return refuse("Sync is off.");
		}
		let (key, token) = (settings.get("key", ""), settings.get("token", ""));
		let Ok(key) = unb64(&key) else {
			return refuse("Set up sync again.");
		};
		if key.is_empty() || token.is_empty() {
			return refuse("Set up sync again.");
		}
		self.reports.push(report);
		let step = self
			.session
			.start(store, &key, &mut || Ok(()))
			.expect("no run is in progress");
		self.step(step, key, store, settings)
	}

	/// Syncs now, or once more when the running sync ends. For the automatic syncs.
	pub fn request(&mut self, store: &mut DesktopStore, settings: &mut Settings) -> Vec<Effect> {
		if self.session.running() {
			self.session.request_again();
			Vec::new()
		} else {
			self.sync_now(store, settings, Report::Quiet)
		}
	}

	/// Syncs when sync is on and the last sync ended more than a minute ago.
	pub fn request_if_stale(&mut self, store: &mut DesktopStore, settings: &mut Settings) -> Vec<Effect> {
		let stale = self.last_end.is_none_or(|t| t.elapsed() > STALE);
		if Self::is_on(settings) && stale {
			self.request(store, settings)
		} else {
			Vec::new()
		}
	}

	/// Applies the answer of the round on the wire.
	pub fn answer(
		&mut self,
		store: &mut DesktopStore,
		settings: &mut Settings,
		net: Result<palm_core::client::SyncResult, String>,
	) -> Vec<Effect> {
		let Some(p) = self.pending.take() else {
			return Vec::new();
		};
		let (outcome, step) = self.session.applied(store, &p.key, &p.plan, net, &mut || Ok(()));
		let mut effects = Vec::new();
		if outcome.is_some_and(|o| o.changed) {
			effects.push(Effect::Refresh);
		}
		effects.extend(self.step(step, p.key, store, settings));
		effects
	}

	fn step(&mut self, step: Step, key: Vec<u8>, store: &mut DesktopStore, settings: &mut Settings) -> Vec<Effect> {
		match step {
			Step::Send(plan) => {
				let (server, token) = (Self::server(settings), settings.get("token", ""));
				let (since, items) = (plan.since, plan.items.clone());
				let sender = self.sender;
				std::thread::spawn(move || {
					let net = SyncClient::new(&server, &token)
						.map_err(Into::into)
						.and_then(|c| c.sync(since, &items))
						.map_err(|e| e.message);
					sender.send(Msg::SyncAnswer(net));
				});
				self.pending = Some(Pending { plan, key });
				Vec::new()
			}
			Step::Done { error, again, .. } => {
				let text = match &error {
					None => format!("Last sync: {}", now_text(self.h24)),
					Some(e) => format!("Sync failed: {e}"),
				};
				self.last_end = Some(Instant::now());
				let mut effects = vec![Effect::Status(text.clone())];
				if let Err(e) = settings.set("lastSync", &text) {
					effects.push(Effect::Status(format!("The settings were not saved: {e}")));
				}
				for report in std::mem::take(&mut self.reports) {
					match (report, &error) {
						(Report::Quiet, _) => {}
						(Report::Menu, Some(e)) => effects.push(Effect::Alert(e.clone())),
						(Report::Menu, None) => effects.push(Effect::Status("Sync done.".into())),
						(Report::Dialog, e) => effects.push(Effect::DialogDone(e.clone())),
					}
				}
				if again {
					effects.extend(self.sync_now(store, settings, Report::Quiet));
				}
				effects
			}
		}
	}

	/// Sets up sync on the server: joins its passphrase, or creates one when the server has none. The key
	/// derivation runs on a thread; [finish_set_up](Self::finish_set_up) takes the result.
	pub fn set_up(&self, server: String, token: String, passphrase: String) {
		let sender = self.sender;
		std::thread::spawn(move || {
			let result = SyncClient::new(&server, &token)
				.map_err(Into::into)
				.and_then(|c| first_key(&c, &passphrase))
				.map_err(|e| e.message);
			sender.send(Msg::SetUpDone { result, server, token });
		});
	}

	/// Saves the settings of a good set up, then runs the first sync.
	pub fn finish_set_up(
		&mut self,
		store: &mut DesktopStore,
		settings: &mut Settings,
		result: Result<Option<Vec<u8>>, String>,
		server: &str,
		token: &str,
	) -> Vec<Effect> {
		let key = match result {
			Err(e) => return vec![Effect::DialogDone(Some(e))],
			Ok(None) => return vec![Effect::DialogDone(Some("Wrong passphrase.".into()))],
			Ok(Some(k)) => k,
		};
		let saved = settings
			.set("server", server)
			.and_then(|_| settings.set("token", token))
			.and_then(|_| settings.set("key", &b64(&key)))
			.and_then(|_| settings.set_bool("syncOn", true));
		if let Err(e) = saved {
			return vec![Effect::DialogDone(Some(format!("The settings were not saved: {e}")))];
		}
		store.set_sync_since(0);
		self.sync_now(store, settings, Report::Dialog)
	}

	/// Turns sync off on this computer. The token and the key are removed.
	pub fn turn_off(settings: &mut Settings) -> std::io::Result<()> {
		settings.set_bool("syncOn", false)?;
		settings.remove("token")?;
		settings.remove("key")
	}
}

/// "10/22/26 3:49 pm" for the status bar.
fn now_text(h24: bool) -> String {
	let now = jiff::Zoned::now();
	let d = now.date();
	let day = palm_core::days::epoch_day(d.year() as i64, d.month() as u32, d.day() as u32);
	format!(
		"{} {}",
		model::short_date(day),
		model::time(now.hour() as i32 * 60 + now.minute() as i32, h24)
	)
}
