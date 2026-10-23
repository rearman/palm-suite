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

/// The Edit menu and toolbar commands. They go to the focused text field, or to the current view.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EditCmd {
	Undo,
	Cut,
	Copy,
	Paste,
	Delete,
	/// Shift+Delete: delete with no question.
	DeleteNoConfirm,
	SelectAll,
	New,
}

/// The events of the Memo Pad.
pub enum MemoMsg {
	/// The selection of the list moved.
	Picked,
	/// A printable key in the list: a new memo that holds it.
	TypeNew(char),
	New,
	Apply,
	Filter,
	/// A right click on the list.
	ListMenu,
	/// The clipboard text of a Paste into the list.
	PasteText(String),
}

/// The events of the Address Book.
pub enum AddressMsg {
	/// The selection of the list moved.
	Picked,
	Filter,
	/// The text of Look up changed.
	Look,
	/// A double click on a row, or the Edit button.
	Edit,
	New,
	/// A right click on the list.
	ListMenu,
	/// The clipboard text of a Paste into the list.
	PasteText(String),
}

pub enum Msg {
	Memo(MemoMsg),
	Address(AddressMsg),
	Edit(EditCmd),
	/// The focus or a selection moved: gray the Edit items that do not apply.
	EditState,
	/// Edit Categories of an app: 0 To Do, 1 Memo Pad, 2 Address Book.
	EditCategories(i32),
	/// Tools > Categories in the current view.
	Categories,
	ListBy,
	/// Tools > Custom Field Labels of the Address Book.
	CustomLabels,
	/// A divider was released: save the width of its detail pane.
	SplitMoved {
		key: &'static str,
		width: i32,
	},
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
