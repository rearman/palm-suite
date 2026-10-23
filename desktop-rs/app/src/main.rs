//! Palm Desktop for Windows and Linux, after Palm Desktop 3.1.1, as Main.kt and App.kt.
//!
//! `--shot out.ppm` draws the window offscreen at the display scale (FLTK_SCALING_FACTOR sets it), saves
//! it and exits. It shows the device-pixel drawing on Windows only, as in the spike.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use fltk::app::{self, TimeoutHandle};
use fltk::prelude::*;
use palm_core::runner::Record;
use palm_desktop::address::AddressView;
use palm_desktop::date::DateView;
use palm_desktop::dialogs::{self, Icon};
use palm_desktop::memo::MemoView;
use palm_desktop::mnemonics;
use palm_desktop::msg::{EditCmd, Msg, View};
use palm_desktop::records::ListAction;
use palm_desktop::settings::{self, Settings};
use palm_desktop::store::{self, DesktopStore};
use palm_desktop::sync::{DesktopSync, Effect, Report};
use palm_desktop::sync_dialog::SyncDialog;
use palm_desktop::text_edit::{EditState, Field, TextAction};
use palm_desktop::todo::TodoView;
use palm_desktop::view::Ctx;
use palm_desktop::window::Ui;
use palm_desktop::{address_dialog, category, event_dialog, find, options, platform, printing, shot, theme, transfer};
use std::fs::File;

/// A sync runs this long after the last local change, so one action that writes several records syncs once.
const CHANGE_DELAY: f64 = 1.0;
const HOUR: f64 = 3600.0;

struct App {
	store: DesktopStore,
	settings: Settings,
	sync: DesktopSync,
	ui: Ui,
	view: View,
	/// The selected day of the Date views, as an epoch day. Starts on today.
	day: i64,
	/// The day the views show.
	shown_day: i64,
	h24: bool,
	show_private: bool,
	dialog: Option<SyncDialog>,
	change_timer: Option<TimeoutHandle>,
	sender: app::Sender<Msg>,
	/// The last save error shown, so one fault gives one alert.
	shown_save_error: Option<String>,
	memo: MemoView,
	address: AddressView,
	todo: TodoView,
	date: DateView,
	/// The store version the views show.
	shown_version: u64,
	_lock: File,
}

/// Where an Edit command goes.
enum Target {
	Text(Field),
	MemoList,
	AddressList,
	TodoList,
	None,
}

fn fail(text: &str) -> ! {
	dialogs::message("Palm Desktop", Icon::Warning, text);
	std::process::exit(1);
}

/// The view context of an App, from its fields alone, so a view field can be borrowed beside it.
macro_rules! ctx {
	($a:expr) => {
		Ctx {
			store: &mut $a.store,
			settings: &mut $a.settings,
			show_private: $a.show_private,
			no_confirm: false,
			day: &mut $a.day,
			h24: $a.h24,
			s: $a.sender,
		}
	};
}

impl App {
	fn handle(&mut self, msg: Msg) {
		match msg {
			Msg::Memo(m) => {
				let mut ctx = ctx!(self);
				self.memo.handle(m, &mut ctx);
			}
			Msg::Address(m) => {
				let mut ctx = ctx!(self);
				self.address.handle(m, &mut ctx);
			}
			Msg::Todo(m) => {
				let mut ctx = ctx!(self);
				self.todo.handle(m, &mut ctx);
			}
			Msg::Date(m) => {
				let mut ctx = ctx!(self);
				self.date.handle(m, &mut ctx);
			}
			Msg::Import => {
				let mut ctx = ctx!(self);
				transfer::import(&mut ctx);
			}
			Msg::Export => {
				let addresses = self.view == View::Address;
				let mut ctx = ctx!(self);
				if let Some(status) = transfer::export(&mut ctx, addresses) {
					self.ui.set_status(&status);
				}
			}
			Msg::Print => {
				let ctx = ctx!(self);
				let (title, lines) = match self.view {
					View::Date => self.date.print_lines(&ctx),
					View::Address => self.address.print_lines(&ctx),
					View::Todo => self.todo.print_lines(&ctx),
					View::Memo => self.memo.print_lines(),
				};
				printing::print(&title, &lines);
			}
			Msg::Find => {
				let ctx = ctx!(self);
				if let Some(r) = find::show(&ctx) {
					self.go_to(r);
				}
			}
			Msg::Options => {
				if options::show(&mut self.settings, self.h24) {
					self.refresh_views();
				}
			}
			Msg::ShowTodo(id) => {
				self.show_view(View::Todo);
				self.save_setting("view", View::Todo.key());
				let mut ctx = ctx!(self);
				self.todo.select(&mut ctx, id);
			}
			Msg::Purge => match self.view {
				View::Todo => {
					if dialogs::ask_icon(
						"Purge Completed To Do Items",
						Icon::Question,
						"Purge completed To Do item(s)?",
						&["OK", "Cancel"],
						0,
					) == Some(0)
					{
						let n = self.store.purge_todos();
						dialogs::message("Palm Desktop", Icon::Info, &format!("{n} To Do items purged."));
					}
				}
				View::Date => {
					let mut ctx = ctx!(self);
					event_dialog::purge_events(&mut ctx);
				}
				_ => {}
			},
			Msg::Edit(cmd) => self.edit(cmd),
			Msg::EditState => {}
			Msg::EditCategories(app_no) => category::edit_categories(&mut self.store, app_no),
			Msg::Categories => match self.view {
				View::Memo => category::edit_categories(&mut self.store, 1),
				View::Address => category::edit_categories(&mut self.store, 2),
				View::Todo | View::Date => category::edit_categories(&mut self.store, 0),
			},
			Msg::ListBy => {
				let mut ctx = ctx!(self);
				match self.view {
					View::Memo => self.memo.list_by(&mut ctx),
					View::Address => self.address.list_by(&mut ctx),
					_ => {}
				}
			}
			Msg::CustomLabels => {
				let mut ctx = ctx!(self);
				if address_dialog::custom_field_labels(&mut ctx) {
					self.refresh_views();
				}
			}
			Msg::SplitMoved { key, width } => self.save_setting(key, &width.to_string()),
			Msg::ShowView(v) => {
				self.show_view(v);
				self.save_setting("view", v.key());
			}
			Msg::SetShowPrivate(on) => {
				self.show_private = on;
				self.ui.set_private_hidden(!on);
				self.save_setting("showPrivate", if on { "true" } else { "false" });
				self.refresh_views();
			}
			Msg::SaveAll => {
				self.store.persist();
				if self.store.save_error.is_none() {
					self.ui.set_status("Saved.");
				}
			}
			Msg::SyncNow => {
				let e = self.sync.sync_now(&mut self.store, &mut self.settings, Report::Menu);
				self.apply(e);
			}
			Msg::SyncRequest => {
				let e = self.sync.request(&mut self.store, &mut self.settings);
				self.apply(e);
			}
			Msg::SyncIfStale => {
				let e = self.sync.request_if_stale(&mut self.store, &mut self.settings);
				self.apply(e);
			}
			Msg::Changed => {
				if let Some(h) = self.change_timer.take() {
					app::remove_timeout3(h);
				}
				let s = self.sender;
				self.change_timer = Some(app::add_timeout3(CHANGE_DELAY, move |_| s.send(Msg::SyncRequest)));
			}
			Msg::SyncAnswer(net) => {
				let e = self.sync.answer(&mut self.store, &mut self.settings, net);
				self.apply(e);
			}
			Msg::SyncOptions => {
				if self.dialog.is_none() {
					let status = if DesktopSync::is_on(&self.settings) {
						self.settings.get("lastSync", "")
					} else {
						"Sync is off.".to_string()
					};
					let saved_token = !self.settings.get("token", "").is_empty();
					self.dialog = Some(SyncDialog::open(
						self.sender,
						&DesktopSync::server(&self.settings),
						saved_token,
						DesktopSync::is_on(&self.settings),
						&status,
					));
				}
			}
			Msg::SyncDialogSetUp => {
				if let Some(d) = self.dialog.as_mut() {
					d.set_busy(true);
					let typed = d.token.value().trim().to_string();
					let token = if typed.is_empty() {
						self.settings.get("token", "")
					} else {
						typed
					};
					self.sync
						.set_up(d.server.value().trim().to_string(), token, d.pass.value());
				}
			}
			Msg::SetUpDone { result, server, token } => {
				let e = self
					.sync
					.finish_set_up(&mut self.store, &mut self.settings, result, &server, &token);
				self.apply(e);
			}
			Msg::SyncDialogSyncNow => {
				let e = self.sync.sync_now(&mut self.store, &mut self.settings, Report::Dialog);
				self.apply(e);
			}
			Msg::SyncDialogTurnOff => {
				let ask = "Turn off sync on this computer? Your records stay here.";
				if dialogs::ask_icon("Palm Desktop", Icon::Question, ask, &["Turn Off", "Cancel"], 0) == Some(0) {
					if let Err(e) = DesktopSync::turn_off(&mut self.settings) {
						self.alert(&format!("The settings were not saved: {e}"));
					}
					self.close_dialog();
				}
			}
			Msg::SyncDialogClose => self.close_dialog(),
			Msg::About => dialogs::about(),
			Msg::Exit => app::quit(),
		}
		self.check_save();
		if self.store.version != self.shown_version || self.day != self.shown_day {
			self.refresh_views();
		}
		self.update_edit_state();
		mnemonics::refresh();
	}

	/// Reads the store again in every view.
	fn refresh_views(&mut self) {
		self.shown_version = self.store.version;
		let mut ctx = ctx!(self);
		self.memo.refresh(&mut ctx);
		self.address.refresh(&mut ctx);
		self.todo.refresh(&mut ctx);
		self.date.refresh(&mut ctx);
		self.shown_day = self.day;
		self.shown_version = self.store.version;
		self.update_position();
	}

	fn target(&self) -> Target {
		let Some(f) = app::focus() else { return Target::None };
		let p = f.as_widget_ptr();
		if p == self.memo.editor().as_widget_ptr() {
			Target::Text(Field::Editor(self.memo.editor().clone()))
		} else if p == self.todo.editor().as_widget_ptr() {
			Target::Text(Field::Editor(self.todo.editor().clone()))
		} else if let Some(f) = self.date.field_widget().filter(|f| f.as_widget_ptr() == p) {
			Target::Text(Field::Input(f))
		} else if p == self.date.look().as_widget_ptr() {
			Target::Text(Field::Input(self.date.look().clone()))
		} else if p == self.address.look().as_widget_ptr() {
			Target::Text(Field::Input(self.address.look().clone()))
		} else if p == self.memo.table().as_widget_ptr() {
			Target::MemoList
		} else if p == self.address.table().as_widget_ptr() {
			Target::AddressList
		} else if p == self.todo.table().as_widget_ptr() {
			Target::TodoList
		} else {
			Target::None
		}
	}

	fn update_edit_state(&mut self) {
		let target = self.target();
		let s = match &target {
			Target::Text(f) => f.state(),
			Target::MemoList => self.memo.list_edit_state(),
			Target::AddressList => self.address.list_edit_state(),
			Target::TodoList => self.todo.list_edit_state(),
			Target::None => EditState::default(),
		};
		self.ui.set_edit_state(s);
		self.update_record_state(&target);
		self.update_position();
	}

	/// Tells the window which record commands apply: Delete when the view has a selected record or the
	/// focused field has a text selection, Select All when the view has records, and the Event items when
	/// the Date Book has a selected event. DateView exposes no accessor for its selection or its records
	/// yet, so the Date Book passes true for all three.
	fn update_record_state(&mut self, target: &Target) {
		let text_selected = matches!(target, Target::Text(f) if f.state().delete);
		let (delete, select_all, event) = match self.view {
			View::Memo => {
				let s = self.memo.list_edit_state();
				(s.delete, s.select_all, false)
			}
			View::Address => {
				let s = self.address.list_edit_state();
				(s.delete, s.select_all, false)
			}
			View::Todo => {
				let s = self.todo.list_edit_state();
				(s.delete, s.select_all, false)
			}
			View::Date => {
				let on = self.date.has_selected_event();
				(on, on, on)
			}
		};
		self.ui.set_record_state(delete || text_selected, select_all, event);
	}

	/// The Edit menu and toolbar: the focused text field takes the text commands; the view takes the rest.
	fn edit(&mut self, cmd: EditCmd) {
		let target = self.target();
		if let Target::Text(mut f) = target {
			let a = match cmd {
				EditCmd::Undo => TextAction::Undo,
				EditCmd::Cut => TextAction::Cut,
				EditCmd::Copy => TextAction::Copy,
				EditCmd::Paste => TextAction::Paste,
				EditCmd::Delete => TextAction::Delete,
				EditCmd::SelectAll => TextAction::SelectAll,
				// Shift+Delete in a text field keeps its own delete.
				EditCmd::DeleteNoConfirm => return,
				EditCmd::New => return self.new_record(),
			};
			return f.run(a);
		}
		let list = matches!(target, Target::MemoList | Target::AddressList | Target::TodoList);
		let action = match cmd {
			EditCmd::New => return self.new_record(),
			EditCmd::SelectAll => ListAction::SelectAll,
			EditCmd::Delete | EditCmd::DeleteNoConfirm => ListAction::Delete,
			EditCmd::Cut if list => ListAction::Cut,
			EditCmd::Copy if list => ListAction::Copy,
			EditCmd::Paste if list => ListAction::Paste,
			_ => return,
		};
		let mut ctx = ctx!(self);
		ctx.no_confirm = cmd == EditCmd::DeleteNoConfirm;
		match self.view {
			View::Memo => self.memo.list_action(action, &mut ctx),
			View::Address => self.address.list_action(action, &mut ctx),
			View::Todo => self.todo.list_action(action, &mut ctx),
			// The Date Book deletes the selected event; it has no record clipboard.
			View::Date if action == ListAction::Delete => self.date.delete_selected(&mut ctx),
			View::Date => {}
		}
	}

	/// Shows a record that Find found in its view, selected.
	fn go_to(&mut self, r: Record) {
		let view = match &r {
			Record::Event(_) => View::Date,
			Record::Todo(_) => View::Todo,
			Record::Memo(_) => View::Memo,
			Record::Address(_) => View::Address,
			Record::Category(_) => return,
		};
		self.show_view(view);
		self.save_setting("view", view.key());
		let mut ctx = ctx!(self);
		match r {
			Record::Event(e) => self.date.select(&mut ctx, &e),
			Record::Todo(t) => self.todo.select(&mut ctx, t.id),
			Record::Memo(m) => self.memo.select(&mut ctx, m.id),
			Record::Address(a) => self.address.select(&mut ctx, a.id),
			Record::Category(_) => {}
		}
	}

	/// Edit > New and the New toolbar button in the current view.
	fn new_record(&mut self) {
		let mut ctx = ctx!(self);
		match self.view {
			View::Memo => self.memo.new_record(&mut ctx),
			View::Address => self.address.new_record(&mut ctx),
			View::Todo => self.todo.new_record(&mut ctx),
			View::Date => self.date.new_record(&mut ctx),
		}
	}

	fn apply(&mut self, effects: Vec<Effect>) {
		for e in effects {
			match e {
				Effect::Status(text) => self.ui.set_status(&text),
				// The views come in later phases; the position box is the one view state so far.
				Effect::Refresh => self.update_position(),
				Effect::Alert(text) => self.alert(&text),
				Effect::DialogDone(error) => match error {
					None => {
						self.close_dialog();
						self.ui.set_status("Sync done.");
					}
					Some(e) => {
						if let Some(d) = self.dialog.as_mut() {
							d.set_busy(false);
							d.set_status(&self.settings.get("lastSync", ""));
						}
						self.alert(&e);
					}
				},
			}
		}
	}

	fn close_dialog(&mut self) {
		if let Some(mut d) = self.dialog.take() {
			d.close();
		}
	}

	fn alert(&self, text: &str) {
		dialogs::message("Palm Desktop", Icon::Warning, text);
	}

	fn save_setting(&mut self, key: &str, value: &str) {
		if let Err(e) = self.settings.set(key, value) {
			self.ui.set_status(&format!("The settings were not saved: {e}"));
		}
	}

	/// A failed save of the data file gives one alert, and the status bar keeps the error.
	fn check_save(&mut self) {
		if self.store.save_error != self.shown_save_error {
			self.shown_save_error = self.store.save_error.clone();
			if let Some(e) = &self.shown_save_error {
				let text = format!("Palm Desktop could not save your records: {e}");
				self.ui.set_status(&text);
				self.alert(&text);
			}
		}
	}

	fn show_view(&mut self, v: View) {
		self.view = v;
		self.ui.show_view(v);
		match v {
			View::Memo => self.memo.focus(),
			View::Address => self.address.focus(),
			View::Todo => self.todo.focus(),
			View::Date => self.date.focus(),
		}
		self.update_edit_state();
	}

	/// The selected date in the Date Book, and the position in the other views.
	fn update_position(&mut self) {
		let text = match self.view {
			View::Date => {
				let d = palm_core::days::ymd(platform::today());
				format!("{}/{}/{}", d.month, d.day, d.year)
			}
			View::Memo => self.memo.position(),
			View::Address => self.address.position(),
			View::Todo => self.todo.position(),
		};
		self.ui.set_position(&text);
	}
}

fn main() {
	let args: Vec<String> = std::env::args().collect();
	let fl = app::App::default().load_system_fonts();
	theme::install();
	let (sender, receiver) = app::channel::<Msg>();

	let dir = settings::config_dir();
	let lock = store::lock(&dir).unwrap_or_else(|e| fail(&e));
	let path = dir.join(store::FILE_NAME);
	let mut store = DesktopStore::open(&path).unwrap_or_else(|e| {
		fail(&format!(
			"Palm Desktop could not read {}: {e}\nThe file stays as it is.",
			path.display()
		))
	});
	store.on_change = Some(Box::new(move || sender.send(Msg::Changed)));
	let settings = Settings::open(&dir);
	let h24 = platform::uses_24_hours();

	let mut ui = Ui::build(sender);
	let g = ui.view_group(View::Memo);
	g.begin();
	let memo = MemoView::new(g.x() + 6, g.y() + 6, g.w() - 12, g.h() - 12, sender, &settings);
	g.resizable(&memo.root);
	g.end();
	let g = ui.view_group(View::Address);
	g.begin();
	let address = AddressView::new(g.x() + 6, g.y() + 6, g.w() - 12, g.h() - 12, sender, &settings);
	g.resizable(&address.root);
	g.end();
	let g = ui.view_group(View::Todo);
	g.begin();
	let todo = TodoView::new(g.x() + 6, g.y() + 6, g.w() - 12, g.h() - 12, sender, &settings);
	g.resizable(&todo.root);
	g.end();
	let g = ui.view_group(View::Date);
	g.begin();
	let date = DateView::new(g.x(), g.y(), g.w(), g.h(), sender, &settings);
	g.resizable(&date.root);
	g.end();
	mnemonics::install();
	let show_private = settings.get_bool("showPrivate", false);
	ui.set_private_hidden(!show_private);
	let mut a = App {
		store,
		sync: DesktopSync::new(sender, h24),
		ui,
		view: View::Date,
		day: platform::today(),
		shown_day: i64::MIN,
		h24,
		show_private,
		dialog: None,
		change_timer: None,
		sender,
		shown_save_error: None,
		memo,
		address,
		todo,
		date,
		shown_version: u64::MAX,
		settings,
		_lock: lock,
	};
	// The startView setting wins. Without it, the view of the last session opens.
	let start = Some(a.settings.get("startView", ""))
		.filter(|s| !s.is_empty())
		.unwrap_or_else(|| a.settings.get("view", "date"));
	a.refresh_views();
	a.show_view(View::from_key(&start).unwrap_or(View::Date));
	a.ui.win.show();
	mnemonics::refresh();
	let shot_at = args.iter().position(|x| x == "--shot");
	if shot_at.is_none() {
		a.ui.win.maximize();
	}

	// Sync now when sync is on, then every hour, 1 s after each local change, and when the window comes
	// to the front after a minute without a sync.
	sender.send(Msg::SyncRequest);
	app::add_timeout3(HOUR, move |h| {
		sender.send(Msg::SyncRequest);
		app::repeat_timeout3(HOUR, h);
	});

	if let Some(i) = shot_at {
		let out = args.get(i + 1).cloned().unwrap_or_else(|| "shot.ppm".into());
		let win = a.ui.win.clone();
		app::add_timeout3(1.0, move |_| {
			if let Err(e) = shot::save(&win, &out) {
				eprintln!("shot: {e}");
			}
			app::quit();
		});
	}

	while fl.wait() {
		while let Some(msg) = receiver.recv() {
			a.handle(msg);
		}
	}
}
