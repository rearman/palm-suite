//! The Options dialog of Dialogs.kt: the General tab (the startup view and the delete question) and the Date
//! Book tab (the work day, the first day of the week and the alarm preset).

use crate::date_shared::{count_spinner, time_label, week_begins, work_day};
use crate::dialogs::{key_label, run_modal};
use crate::settings::Settings;
use crate::{combo, draw};
use fltk::button::Button;
use fltk::enums::{Align, FrameType, Key, Shortcut};
use fltk::group::{Group, Tabs};
use fltk::menu::Choice;
use fltk::prelude::*;
use fltk::window::Window;
use std::cell::Cell;
use std::rc::Rc;

const START_VIEWS: [(&str, &str); 4] = [
	("date", "Date Book"),
	("address", "Address Book"),
	("todo", "To Do List"),
	("memo", "Memo Pad"),
];

/// Shows Options and saves the values on OK; true when they were saved.
pub fn show(settings: &mut Settings, h24: bool) -> bool {
	// The 3.1.1 Options dialog is 361 x 275 px client (367 x 299 outer) on every tab.
	let mut win = Window::default().with_size(361, 275).with_label("Options");
	let tabs = Tabs::new(8, 8, 345, 229, None);
	let page = |label: &str| {
		let mut g = Group::new(8, 30, 345, 207, None);
		g.set_label(label);
		g.set_frame(FrameType::UpBox);
		g
	};
	let general = page("&General");
	let mut start = Choice::new(133, 48, 197, 22, None);
	for (_, name) in START_VIEWS {
		start.add_choice(name);
	}
	draw::style_menu(&mut start);
	combo::drop_below(&mut start);
	let cur = settings.get("startView", "date");
	start.set_value(START_VIEWS.iter().position(|(k, _)| *k == cur).unwrap_or(0) as i32);
	let mut start_l = key_label("&Startup application:", 20, 48, 100, 22, Align::Right);
	{
		let s = start.clone();
		start_l.set_callback(move |_| drop(s.clone().take_focus()));
	}
	let mut confirm = draw::check(28, 88, 280, 22, "&Confirm before deleting records");
	confirm.set_value(settings.get_bool("confirmDelete", true));
	general.end();

	let date = page("Date &Book");
	let mut frame = Group::new(20, 40, 321, 108, "Date and Time");
	frame.set_frame(FrameType::EngravedBox);
	frame.set_align(Align::TopLeft | Align::Inside);
	let mut work = Choice::new(142, 58, 175, 22, None);
	for h in 0..24 {
		work.add_choice(&time_label(h * 60, h24));
	}
	draw::style_menu(&mut work);
	combo::drop_below(&mut work);
	work.set_value(work_day(settings));
	let mut week = Choice::new(142, 86, 175, 22, None);
	week.add_choice("Sunday|Monday");
	draw::style_menu(&mut week);
	combo::drop_below(&mut week);
	week.set_value(week_begins(settings) as i32);
	let mut preset = draw::check(142, 114, 18, 22, "");
	preset.set_value(settings.get_bool("alarmPreset", false));
	let mut amount = count_spinner(164, 114, 50, 22, settings.get_int("alarmPresetAmount", 5), 1);
	let mut unit = Choice::new(218, 114, 115, 22, None);
	unit.add_choice("Minutes|Hours|Days");
	draw::style_menu(&mut unit);
	combo::drop_below(&mut unit);
	unit.set_value(settings.get_int("alarmPresetUnit", 0).clamp(0, 2));
	for (text, y, target) in [
		("Work &day begins:", 58, work.as_base_widget()),
		("&Week begins:", 86, week.as_base_widget()),
		("Alarm &preset:", 114, preset.as_base_widget()),
	] {
		let mut l = key_label(text, 30, y, 100, 22, Align::Right);
		l.set_callback(move |_| drop(target.clone().take_focus()));
	}
	frame.end();
	date.end();
	tabs.end();

	let saved = Rc::new(Cell::new(false));
	let mut ok = Button::new(196, 245, 75, 23, "OK");
	ok.set_shortcut(Shortcut::None | Key::Enter);
	let mut cancel = Button::new(278, 245, 75, 23, "Cancel");
	{
		let (saved, mut win) = (saved.clone(), win.clone());
		ok.set_callback(move |_| {
			saved.set(true);
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	// The amount and the unit show only when the alarm preset is checked.
	let show_preset = move |on: bool, amount: &mut fltk::misc::Spinner, unit: &mut Choice| {
		if on {
			amount.show();
			unit.show();
		} else {
			amount.hide();
			unit.hide();
		}
	};
	show_preset(preset.value(), &mut amount, &mut unit);
	{
		let (mut a, mut u) = (amount.clone(), unit.clone());
		preset.set_callback(move |b| show_preset(b.value(), &mut a, &mut u));
	}
	run_modal(&mut win);
	if !saved.get() {
		return false;
	}
	let pairs = [
		("startView", START_VIEWS[start.value().max(0) as usize].0.to_string()),
		("confirmDelete", confirm.value().to_string()),
		("workDay", work.value().max(0).to_string()),
		("weekStart", week.value().max(0).to_string()),
		("alarmPreset", preset.value().to_string()),
		("alarmPresetAmount", (amount.value() as i32).to_string()),
		("alarmPresetUnit", unit.value().max(0).to_string()),
	];
	for (k, v) in pairs {
		let _ = settings.set(k, &v);
	}
	true
}
