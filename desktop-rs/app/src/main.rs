//! Palm Desktop for Windows and Linux, after Palm Desktop 3.1.1, as Main.kt and App.kt.
//!
//! `--shot out.ppm` draws the window offscreen at the display scale (FLTK_SCALING_FACTOR sets it), saves
//! it and exits. It shows the device-pixel drawing on Windows only, as in the spike.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use fltk::app::{self, TimeoutHandle};
use fltk::dialog;
use fltk::prelude::*;
use palm_desktop::msg::{Msg, View};
use palm_desktop::settings::{self, Settings};
use palm_desktop::store::{self, DesktopStore};
use palm_desktop::sync::{DesktopSync, Effect, Report};
use palm_desktop::sync_dialog::SyncDialog;
use palm_desktop::window::Ui;
use palm_desktop::{platform, shot, theme};
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
	show_private: bool,
	dialog: Option<SyncDialog>,
	change_timer: Option<TimeoutHandle>,
	sender: app::Sender<Msg>,
	/// The last save error shown, so one fault gives one alert.
	shown_save_error: Option<String>,
	_lock: File,
}

fn today() -> i64 {
	let d = jiff::Zoned::now().date();
	palm_core::days::epoch_day(d.year() as i64, d.month() as u32, d.day() as u32)
}

fn fail(text: &str) -> ! {
	dialog::alert_default(text);
	std::process::exit(1);
}

impl App {
	fn handle(&mut self, msg: Msg) {
		match msg {
			Msg::ShowView(v) => {
				self.show_view(v);
				self.save_setting("view", v.key());
			}
			Msg::SetShowPrivate(on) => {
				self.show_private = on;
				self.ui.set_private_hidden(!on);
				self.save_setting("showPrivate", if on { "true" } else { "false" });
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
				if dialog::choice2_default(ask, "Turn Off", "Cancel", "") == Some(0) {
					if let Err(e) = DesktopSync::turn_off(&mut self.settings) {
						self.alert(&format!("The settings were not saved: {e}"));
					}
					self.close_dialog();
				}
			}
			Msg::SyncDialogClose => self.close_dialog(),
			Msg::About => dialog::message_default("Palm Desktop for the palm-suite apps"),
			Msg::Exit => app::quit(),
		}
		self.check_save();
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
		dialog::alert_default(text);
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
		self.update_position();
	}

	/// The selected date in the Date Book; the position in the other views comes with them.
	fn update_position(&mut self) {
		let text = if self.view == View::Date {
			let d = palm_core::days::ymd(self.day);
			format!("{}/{}/{}", d.month, d.day, d.year)
		} else {
			String::new()
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
	let show_private = settings.get_bool("showPrivate", false);
	ui.set_private_hidden(!show_private);
	let mut a = App {
		store,
		sync: DesktopSync::new(sender, h24),
		ui,
		view: View::Date,
		day: today(),
		show_private,
		dialog: None,
		change_timer: None,
		sender,
		shown_save_error: None,
		settings,
		_lock: lock,
	};
	// The startView setting wins. Without it, the view of the last session opens.
	let start = Some(a.settings.get("startView", ""))
		.filter(|s| !s.is_empty())
		.unwrap_or_else(|| a.settings.get("view", "date"));
	a.show_view(View::from_key(&start).unwrap_or(View::Date));
	a.ui.win.show();
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
