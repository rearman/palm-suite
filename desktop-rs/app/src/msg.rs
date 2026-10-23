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

/// The events of the To Do List.
pub enum TodoMsg {
	/// The selection of the table moved.
	Picked,
	Filter,
	/// A printable key in the table: a new item that holds it.
	TypeNew(char),
	New,
	Apply,
	/// The Show Options dialog.
	Show,
	/// A pick in the Due box of the details.
	DuePick,
	/// The note button of the details.
	Note,
	/// A left click on a cell: row, column, and true for the second click of a double click.
	Cell(i32, i32, bool),
	/// A right click on the table.
	ListMenu,
	/// The clipboard text of a Paste into the table.
	PasteText(String),
}

/// The Day, Week and Month views of the Date Book.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DateMode {
	Day,
	Week,
	Month,
}

impl DateMode {
	/// The name in the setting "dateMode".
	pub fn key(self) -> &'static str {
		match self {
			DateMode::Day => "day",
			DateMode::Week => "week",
			DateMode::Month => "month",
		}
	}
}

/// What a mouse event of a Date Book view was.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MouseKind {
	Press,
	Drag,
	Release,
	Move,
	/// The wheel turned by this many lines.
	Wheel(i32),
}

/// A mouse event on a Date Book view, in window coordinates.
#[derive(Clone, Copy, Debug)]
pub struct Mouse {
	pub kind: MouseKind,
	pub x: i32,
	pub y: i32,
	pub right: bool,
	/// The second click of a double click.
	pub double: bool,
}

/// An event of the field that types a new event or a title into a box.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FieldEv {
	Enter,
	Escape,
	/// The field lost the focus.
	Lost,
	Double,
}

/// The events of the Date Book.
pub enum DateMsg {
	Mode(DateMode),
	/// Today, or a double click on a tab.
	Today,
	New,
	/// The Edit button, or Edit Event of the Edit menu.
	Edit,
	/// A double click on the Day title: pick a day.
	TitleDouble,
	/// The year arrows of the right pane.
	Year(i32),
	/// A month button of the right pane, 1 to 12.
	Month(u32),
	/// A click on the calendar of the right pane.
	Calendar(Mouse),
	/// The Address (true) or To Do segment of the right pane.
	Pane(bool),
	Category,
	Look,
	/// The mouse on the list of the right pane.
	PaneList(Mouse),
	/// The mouse on the untimed rows (true) or the hours of the Day view.
	Day(bool, Mouse),
	Scrolled,
	Field(FieldEv),
	/// The arrows of the Week and Month views: back (-1) or forward (1).
	Step(i32),
	GoTo,
	WeekHeader(Mouse),
	WeekGrid(Mouse),
	MonthGrid(Mouse),
	/// A printable key in the Month view: a new event that holds it.
	MonthKey(char),
}

pub enum Msg {
	Memo(MemoMsg),
	Date(DateMsg),
	/// Shows an item in the To Do List: a double click on the To Do pane of the Day view.
	ShowTodo(i64),
	Address(AddressMsg),
	Todo(TodoMsg),
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
	/// Tools > Purge in the current view.
	Purge,
	Import,
	Export,
	Print,
	Find,
	Options,
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
