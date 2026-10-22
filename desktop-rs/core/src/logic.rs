//! The decision table of one remote change, as SyncLogic.kt.

/// The sync state of a local row.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LocalState {
	/// No local row for the uid.
	None,
	/// A live row with no local change since the last sync.
	Clean,
	/// A live row with a local change not yet pushed.
	Dirty,
	/// A tombstone, deleted locally and not yet pushed.
	Tomb,
}

/// What the sync engine does with one remote change.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
	/// Nothing to do.
	Skip,
	/// Write the remote version to the local row.
	Apply,
	/// Delete the local row.
	Drop,
	/// Save the local record as a new record, then Apply.
	CopyThenApply,
	/// Save the local record as a new record, then Drop.
	CopyThenDrop,
	/// Keep the local version dirty and take the remote rev as its base.
	Rebase,
}

/// Decides what to do with one remote change for a uid. A conflict (a Dirty row with a remote change)
/// keeps both copies, as Palm HotSync did; with `last_wins` (the ShortCuts list) the local version wins.
pub fn decide(local: LocalState, remote_deleted: bool, same: bool, last_wins: bool) -> Action {
	match local {
		LocalState::None => {
			if remote_deleted {
				Action::Skip
			} else {
				Action::Apply
			}
		}
		LocalState::Clean | LocalState::Tomb => {
			if remote_deleted {
				Action::Drop
			} else {
				Action::Apply
			}
		}
		LocalState::Dirty => {
			if last_wins {
				Action::Rebase
			} else if remote_deleted {
				Action::CopyThenDrop
			} else if same {
				Action::Apply
			} else {
				Action::CopyThenApply
			}
		}
	}
}
