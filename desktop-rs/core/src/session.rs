//! The round loop of one sync run, as SyncSession.kt, as a state machine that the caller drives.
//!
//! A run has one or more rounds. Each round collects the dirty rows on the UI thread ([SyncSession::start]
//! or the [Step::Send] of the round before), sends the plan on a worker thread, and applies the server
//! answer on the UI thread ([SyncSession::applied]). A run ends when the server reports no conflicts, or
//! after [MAX_ROUNDS] rounds.

use crate::client::SyncResult;
use crate::runner::{self, SyncOutcome, SyncPlan, SyncStore, MAX_ROUNDS};

const FAILED: &str = "Sync failed.";

/// What the caller does next.
#[derive(Debug)]
pub enum Step {
	/// Send these push items on the worker thread, then pass the answer to [SyncSession::applied].
	Send(SyncPlan),
	/// The run ended: the error text, or None on success; whether any round changed records; and whether
	/// [SyncSession::request_again] asked for one more run.
	Done {
		error: Option<String>,
		changed: bool,
		again: bool,
	},
}

#[derive(Default)]
pub struct SyncSession {
	running: bool,
	rounds: u32,
	changed: bool,
	again_requested: bool,
}

impl SyncSession {
	pub fn new() -> SyncSession {
		SyncSession::default()
	}

	/// True from [start](Self::start) until the run ends.
	pub fn running(&self) -> bool {
		self.running
	}

	/// Starts a run and collects its first round. None, and no change, when a run is in progress.
	/// `before_collect` runs before each collect; an error there ends the run with its text.
	pub fn start(
		&mut self,
		store: &dyn SyncStore,
		key: &[u8],
		before_collect: &mut dyn FnMut() -> Result<(), String>,
	) -> Option<Step> {
		if self.running {
			return None;
		}
		self.running = true;
		self.rounds = 0;
		self.changed = false;
		Some(self.next_round(store, key, before_collect))
	}

	/// Asks for one more run when the run in progress ends. Does nothing when no run is in progress.
	pub fn request_again(&mut self) {
		if self.running {
			self.again_requested = true;
		}
	}

	fn next_round(
		&mut self,
		store: &dyn SyncStore,
		key: &[u8],
		before_collect: &mut dyn FnMut() -> Result<(), String>,
	) -> Step {
		self.rounds += 1;
		match before_collect().and_then(|_| runner::collect(store, key)) {
			Ok(plan) => Step::Send(plan),
			Err(e) => self.finish(Some(e)),
		}
	}

	/// Applies the answer to the plan of the last [Step::Send] (or the send error) and returns the next
	/// step, with the outcome of the round when it applied.
	pub fn applied(
		&mut self,
		store: &mut dyn SyncStore,
		key: &[u8],
		plan: &SyncPlan,
		net: Result<SyncResult, String>,
		before_collect: &mut dyn FnMut() -> Result<(), String>,
	) -> (Option<SyncOutcome>, Step) {
		let result = match net {
			Ok(r) => r,
			Err(e) => return (None, self.finish(Some(if e.is_empty() { FAILED.into() } else { e }))),
		};
		let outcome = match runner::apply(store, key, plan, &result) {
			Ok(o) => o,
			Err(e) => return (None, self.finish(Some(e))),
		};
		if outcome.changed {
			self.changed = true;
		}
		let step = if outcome.no_conflicts || self.rounds >= MAX_ROUNDS {
			self.finish(None)
		} else {
			self.next_round(store, key, before_collect)
		};
		(Some(outcome), step)
	}

	fn finish(&mut self, error: Option<String>) -> Step {
		self.running = false;
		let again = std::mem::take(&mut self.again_requested);
		Step::Done {
			error,
			changed: self.changed,
			again,
		}
	}
}
