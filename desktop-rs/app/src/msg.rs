//! The messages of the event loop. Every widget callback, timer and worker thread sends one; the main
//! loop handles them one at a time, so no callback borrows the app state.

use palm_core::client::SyncResult;

/// The four views of the main window.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum View {
	Date,
	Address,
	Todo,
	Memo,
}

impl View {
	pub const ALL: [View; 4] = [View::Date, View::Address, View::Todo, View::Memo];

	/// The name in the settings ("view", "startView").
	pub fn key(self) -> &'static str {
		match self {
			View::Date => "date",
			View::Address => "address",
			View::Todo => "todo",
			View::Memo => "memo",
		}
	}

	pub fn from_key(key: &str) -> Option<View> {
		View::ALL.into_iter().find(|v| v.key() == key)
	}
}

pub enum Msg {
	ShowView(View),
	SetShowPrivate(bool),
	SaveAll,
	SyncNow,
	/// A timer or a local change asks for a sync.
	SyncRequest,
	/// The window came to the front: sync when the last sync is more than a minute old.
	SyncIfStale,
	/// A local write: sync 1 s after the last one.
	Changed,
	/// The server answer of the round on the wire, or the error text of the call.
	SyncAnswer(Result<SyncResult, String>),
	SyncOptions,
	SyncDialogSetUp,
	SyncDialogSyncNow,
	SyncDialogTurnOff,
	SyncDialogClose,
	/// The key of a set up, None for a wrong passphrase, or the error text.
	SetUpDone {
		result: Result<Option<Vec<u8>>, String>,
		server: String,
		token: String,
	},
	About,
	Exit,
}
