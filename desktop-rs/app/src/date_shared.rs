//! The parts the Date Book views share, as DateShared.kt and RepeatText.kt: the time labels, the event boxes
//! and their hit tests, the Current, All and Cancel question of a repeating event, Alarm Settings, the menu
//! of an event box, and the repeat summary of Change Repeat.

use crate::combo;
use crate::dialogs::{ask_icon, key_label, run_modal, Icon};
use crate::draw::{Dev, HIGHLIGHT};
use crate::settings::Settings;
use crate::store::DesktopStore;
use crate::theme::{self, icon, PANEL, SELECT, SHADOW};
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, Color, Font, Shortcut};
use fltk::image::PngImage;
use fltk::menu::{Choice, MenuButton, MenuButtonType, MenuFlag};
use fltk::misc::Spinner;
use fltk::prelude::*;
use fltk::window::Window;
use palm_core::days::{dow, ymd};
use palm_core::model::{month_week, ordinal, repeat, short_date, Event, DOW_LONG, DOW_SHORT};
use std::cell::Cell;
use std::rc::Rc;

/// The question of a change to a repeating event.
pub const CHANGE_TEXT: &str = "Apply the changes to the current event only or to all occurrences?";
/// The gray bar on the right edge of an event box.
pub const BAR_W: i32 = 9;
/// The blue bar under a selected event box.
pub const SEL_H: i32 = 4;
/// The outline of a moved or dragged event, as in Palm Desktop.
const DRAG_NAVY: Color = Color::from_rgb(0, 0, 128);
/// The hour lines of the Day and Week grids: gray 128, as in Palm Desktop.
pub const HOUR_LINE: Color = Color::from_rgb(128, 128, 128);

/// One day's occurrence of an event.
#[derive(Clone, Debug, PartialEq)]
pub struct Occ {
	pub event: Event,
	pub day: i64,
}

/// A rectangle in logical pixels.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Rect {
	pub x: i32,
	pub y: i32,
	pub w: i32,
	pub h: i32,
}

impl Rect {
	pub fn new(x: i32, y: i32, w: i32, h: i32) -> Rect {
		Rect { x, y, w, h }
	}

	pub fn contains(&self, px: i32, py: i32) -> bool {
		px >= self.x && px < self.x + self.w && py >= self.y && py < self.y + self.h
	}

	pub fn moved(&self, dx: i32, dy: i32) -> Rect {
		Rect::new(self.x + dx, self.y + dy, self.w, self.h)
	}
}

/// The occurrences of `day`, untimed first, then by time. Private events show only when `show_private`.
pub fn visible_events(store: &DesktopStore, day: i64, show_private: bool) -> Vec<Occ> {
	store
		.day_events(day)
		.into_iter()
		.filter(|e| show_private || !e.is_private)
		.map(|e| Occ { event: e.clone(), day })
		.collect()
}

/// Time of day in minutes after midnight, as "8:00a" or "13:30".
pub fn time_label(min: i32, h24: bool) -> String {
	let (h, m) = (min / 60 % 24, min % 60);
	if h24 {
		return format!("{h}:{m:02}");
	}
	format!("{}:{m:02}{}", (h + 11) % 12 + 1, if h < 12 { "a" } else { "p" })
}

/// The first day of the week: 0 Sunday, 1 Monday.
pub fn week_begins(settings: &Settings) -> u32 {
	settings.get_int("weekStart", 0).clamp(0, 1) as u32
}

/// The hour that the day grids scroll to.
pub fn work_day(settings: &Settings) -> i32 {
	settings.get_int("workDay", 8).clamp(0, 23)
}

/// The index into DOW_SHORT, DOW_LONG and DOW_LETTER of grid column `col`.
pub fn dow_index(col: usize, begins: u32) -> usize {
	(col + begins as usize) % 7
}

/// Sets the alarm of a new event from the preset of the settings, when it is on.
pub fn apply_alarm_preset(e: &mut Event, settings: &Settings) {
	if !settings.get_bool("alarmPreset", false) {
		return;
	}
	e.alarm = true;
	e.alarm_advance = settings.get_int("alarmPresetAmount", 5).max(1);
	e.alarm_unit = settings.get_int("alarmPresetUnit", 0).clamp(0, 2);
}

/// What a box fills with: the dithered yellow of an event, or white for the box of a new event.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Fill {
	Event,
	White,
}

/// The marker icons of an event: repeat, alarm, note, private.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Marker {
	Repeat,
	Alarm,
	Note,
	Private,
}

pub fn markers(e: &Event) -> Vec<Marker> {
	let mut m = Vec::new();
	if e.repeats() {
		m.push(Marker::Repeat);
	}
	if e.alarm {
		m.push(Marker::Alarm);
	}
	if !e.note.is_empty() {
		m.push(Marker::Note);
	}
	if e.is_private {
		m.push(Marker::Private);
	}
	m
}

fn marker_icon(m: Marker) -> PngImage {
	icon(match m {
		Marker::Repeat => "repeat",
		Marker::Alarm => "alarm",
		Marker::Note => "note",
		Marker::Private => "private",
	})
}

/// The size of the marker icons (all are 13 px square or smaller).
fn marker_size(m: Marker) -> (i32, i32) {
	let i = marker_icon(m);
	(i.w(), i.h())
}

/// Draws an event box in `r` as Palm Desktop does: the fill with a black outline and a gray bar on the right
/// edge. A selected box has a darker bar and a blue bar under it. Drawn in device pixels.
pub fn draw_box(r: Rect, fill: Fill, selected: bool) {
	let d = Dev::begin();
	let (x, y, w, h) = d.rect(r.x, r.y, r.w, r.h);
	if w <= 0 || h <= 0 {
		return;
	}
	match fill {
		Fill::White => draw::draw_rect_fill(x, y, w, h, Color::White),
		Fill::Event => {
			// The yellow and white dither of Palm Desktop, one device pixel per dot: white, then the yellow
			// pixels whose x + y is even. Each row draws 1 x 1 rectangles, so no pixel is antialiased.
			// Cost: about w * h / 2 rectangles per redraw; a full-day box of 200 x 800 px is about 80000.
			// Upgrade path: a cached RGB image of the checker, drawn with draw_image, if redraws slow down.
			draw::draw_rect_fill(x, y, w, h, Color::White);
			draw::set_draw_color(Color::Yellow);
			for py in y..y + h {
				let mut px = if (x + py).rem_euclid(2) == 0 { x } else { x + 1 };
				while px < x + w {
					draw::draw_rectf(px, py, 1, 1);
					px += 2;
				}
			}
		}
	}
	let k = d.lw();
	let bx = d.p(r.x + r.w - BAR_W);
	draw::draw_rect_fill(
		bx,
		y,
		x + w - bx,
		h,
		if selected {
			Color::from_rgb(128, 128, 128)
		} else {
			Color::from_rgb(192, 192, 192)
		},
	);
	draw::set_draw_color(Color::Black);
	for (rx, ry, rw, rh) in [
		(x, y, w, k),
		(x, y + h - k, w, k),
		(x, y, k, h),
		(x + w - k, y, k, h),
		(bx, y, k, h),
	] {
		draw::draw_rectf(rx, ry, rw, rh);
	}
	if selected {
		let (sx, sy, sw, sh) = d.rect(r.x, r.y + r.h, r.w, SEL_H);
		draw::draw_rect_fill(sx, sy, sw, sh, SELECT);
	}
}

/// The top of the marker icons of a box: centered in an untimed header row, else 2 px below the top.
fn marker_y(r: Rect, ih: i32, centered: bool) -> i32 {
	if centered {
		r.y + (r.h - ih) / 2
	} else {
		r.y + 2
	}
}

/// Draws the box of `e` in `r`, then its markers and its title. `centered` is an untimed header row.
pub fn paint_event(r: Rect, e: &Event, selected: bool, centered: bool) {
	draw_box(r, Fill::Event, selected);
	// The title stops at the bar.
	draw::push_clip(r.x, r.y, (r.w - BAR_W).max(0), r.h);
	let mut x = r.x + 3;
	for m in markers(e) {
		let mut i = marker_icon(m);
		let (iw, ih) = (i.w(), i.h());
		i.draw(x, marker_y(r, ih, centered), iw, ih);
		x += iw + 2;
	}
	// The title is Helvetica 11 (MS Sans Serif 8), word-wrapped to the box left of the bar: the first line
	// starts after the marker icons, the others at the left edge. Lines past the bottom are cut by the clip.
	draw::set_font(Font::Helvetica, 11);
	draw::set_draw_color(Color::Black);
	let asc = draw::height() - draw::descent();
	let lh = draw::height();
	let right = r.x + r.w - BAR_W - 2;
	let lines = wrap_words(&e.title, right - x, right - (r.x + 3), &crate::draw::text_w);
	let top = if centered {
		r.y + (r.h - lines.len() as i32 * lh) / 2
	} else {
		r.y + 1
	};
	for (i, line) in lines.iter().enumerate() {
		let lx = if i == 0 { x } else { r.x + 3 };
		draw::draw_text(line, lx, top + asc + i as i32 * lh);
	}
	draw::pop_clip();
}

/// Splits `text` into lines at its spaces: the first line at most `first_w` px wide and the others `rest_w`
/// px, as `measure` gives the width of a string. A word wider than its line is cut between characters.
pub fn wrap_words(text: &str, first_w: i32, rest_w: i32, measure: &dyn Fn(&str) -> i32) -> Vec<String> {
	let mut lines: Vec<String> = Vec::new();
	let mut cur = String::new();
	for word in text.split_whitespace() {
		let mut word = word.to_string();
		loop {
			let limit = if lines.is_empty() { first_w } else { rest_w };
			let candidate = if cur.is_empty() {
				word.clone()
			} else {
				format!("{cur} {word}")
			};
			if measure(&candidate) <= limit {
				cur = candidate;
				break;
			}
			if !cur.is_empty() {
				lines.push(std::mem::take(&mut cur));
				continue;
			}
			// A word wider than the line: cut it after the last character that fits, at least one character.
			let first_char = word.chars().next().map_or(0, char::len_utf8);
			let cut = word
				.char_indices()
				.map(|(i, c)| i + c.len_utf8())
				.take_while(|&end| measure(&word[..end]) <= limit)
				.last()
				.unwrap_or(first_char);
			lines.push(word[..cut].to_string());
			word = word[cut..].to_string();
			if word.is_empty() {
				break;
			}
		}
	}
	if !cur.is_empty() {
		lines.push(cur);
	}
	lines
}

/// X where the title of `e` starts, after its marker icons.
pub fn title_x(r: Rect, e: &Event) -> i32 {
	markers(e).into_iter().fold(r.x + 3, |x, m| x + marker_size(m).0 + 2)
}

/// The marker icon of `e` under (px, py), or None.
pub fn marker_at(r: Rect, e: &Event, px: i32, py: i32, centered: bool) -> Option<Marker> {
	let mut x = r.x + 3;
	for m in markers(e) {
		let (iw, ih) = marker_size(m);
		if Rect::new(x, marker_y(r, ih, centered), iw, ih).contains(px, py) {
			return Some(m);
		}
		x += iw + 2;
	}
	None
}

/// True when (px, py) is on the gray bar at the right edge of the box `r`.
pub fn on_handle(r: Rect, px: i32, py: i32) -> bool {
	r.contains(px, py) && px >= r.x + r.w - BAR_W
}

/// True when (px, py) is on the blue bar under the selected box `r`.
pub fn on_resize_bar(r: Rect, px: i32, py: i32) -> bool {
	px >= r.x && px < r.x + r.w && py >= r.y + r.h && py < r.y + r.h + SEL_H + 2
}

/// Draws `r` as Palm Desktop draws a drag outline: navy, 3 px thick, inside `r`.
pub fn draw_outline(r: Rect) {
	let d = Dev::begin();
	let (x, y, w, h) = d.rect(r.x, r.y, r.w, r.h);
	let t = 3 * d.lw();
	draw::set_draw_color(DRAG_NAVY);
	for (rx, ry, rw, rh) in [(x, y, w, t), (x, y + h - t, w, t), (x, y, t, h), (x + w - t, y, t, h)] {
		draw::draw_rectf(rx, ry, rw.max(0), rh.max(0));
	}
}

/// A raised cell, as Palm Desktop draws each hour of the time column: panel gray, a white top edge and a
/// shadow bottom edge.
pub fn raised_cell(x: i32, y: i32, w: i32, h: i32) {
	let d = Dev::begin();
	let (dx, dy, dw, dh) = d.rect(x, y, w, h);
	let k = d.lw();
	draw::draw_rect_fill(dx, dy, dw, dh, PANEL);
	draw::draw_rect_fill(dx, dy, dw, k, HIGHLIGHT);
	draw::draw_rect_fill(dx, dy + dh - k, dw, k, SHADOW);
}

/// The diamond that marks the untimed rows, centered at (cx, cy).
pub fn draw_diamond(cx: i32, cy: i32) {
	draw::set_draw_color(Color::Black);
	for dy in -3i32..=3 {
		let half = 3 - dy.abs();
		draw::draw_line(cx - half, cy + dy, cx + half, cy + dy);
	}
}

/// Draws the hour labels of a time column of `w` px: the label and its tick sit in the middle of the hour,
/// as in Palm Desktop.
pub fn draw_hour_label(min: i32, w: i32, x: i32, mid: i32, h24: bool) {
	draw::set_font(theme::fonts().sans, 11);
	draw::set_draw_color(Color::Black);
	let label = time_label(min, h24);
	let (asc, desc) = (draw::height() - draw::descent(), draw::descent());
	draw::draw_text(&label, x + w - 10 - crate::draw::text_w(&label), mid + (asc - desc) / 2);
	draw::draw_line(x + w - 7, mid, x + w - 3, mid);
}

/// Asks Current or All for a repeating event: Some(true) the current occurrence, Some(false) all, None Cancel.
pub fn ask_repeat_scope(text: &str) -> Option<bool> {
	match ask_icon(
		"Repeating Event",
		Icon::Question,
		text,
		&["&Current", "&All", "Cancel"],
		1,
	)? {
		0 => Some(true),
		1 => Some(false),
		_ => None,
	}
}

/// The occurrence as its own event: the series without its repeat, for a change of the current occurrence.
fn single(e: &Event, day: i64) -> Event {
	Event {
		id: 0,
		uid: String::new(),
		date: day,
		repeat: repeat::NONE,
		every: 1,
		repeat_end: -1,
		week_days: 0,
		exceptions: Default::default(),
		..e.clone()
	}
}

/// Saves `work`, the edited copy of the occurrence `occ`, whose date `work.date` can differ from `occ.day`.
/// A repeating event asks Current or All first: Current splits the occurrence off the series, All moves
/// the whole series by the same days. False on Cancel.
pub fn save_occurrence(store: &mut DesktopStore, occ: &Occ, mut work: Event, text: &str) -> bool {
	if !occ.event.repeats() {
		store.save_event(&mut work);
		return true;
	}
	match ask_repeat_scope(text) {
		Some(true) => {
			let mut series = occ.event.clone();
			series.exceptions.insert(occ.day);
			store.save_event(&mut series);
			let day = work.date;
			store.save_event(&mut single(&work, day));
		}
		Some(false) => {
			let shift = work.date - occ.day;
			work.date = occ.event.date + shift;
			if work.repeat_end >= 0 {
				work.repeat_end += shift;
			}
			work.exceptions = work.exceptions.iter().map(|d| d + shift).collect();
			store.save_event(&mut work);
		}
		None => return false,
	}
	true
}

/// Applies `change` to the occurrence `occ` and saves it, asking Current or All for a repeating event.
/// False on Cancel.
pub fn change_occurrence(store: &mut DesktopStore, occ: &Occ, text: &str, change: impl FnOnce(&mut Event)) -> bool {
	let mut copy = occ.event.clone();
	copy.date = occ.day;
	change(&mut copy);
	save_occurrence(store, occ, copy, text)
}

/// Deletes the occurrence `occ`. A repeating event asks Current or All first. False on Cancel.
pub fn delete_occurrence(store: &mut DesktopStore, settings: &Settings, no_confirm: bool, occ: &Occ) -> bool {
	if occ.event.repeats() {
		match ask_repeat_scope("Delete the current event only or all occurrences?") {
			Some(true) => {
				let mut series = occ.event.clone();
				series.exceptions.insert(occ.day);
				store.save_event(&mut series);
			}
			Some(false) => store.delete_event(occ.event.id),
			None => return false,
		}
	} else {
		let confirm = !no_confirm && settings.get_bool("confirmDelete", true);
		if confirm
			&& ask_icon(
				"Delete Date Book Items",
				Icon::Question,
				"Delete the selected Date Book item(s)?",
				&["OK", "Cancel"],
				0,
			) != Some(0)
		{
			return false;
		}
		store.delete_event(occ.event.id);
	}
	true
}

/// The units of an alarm, in menu order.
pub const ALARM_UNITS: [&str; 3] = ["Minutes before", "Hours before", "Days before"];

/// A spinner for a count from `min` up.
pub fn count_spinner(x: i32, y: i32, w: i32, h: i32, value: i32, min: i32) -> Spinner {
	let mut s = Spinner::new(x, y, w, h, None);
	s.set_range(min as f64, 99_999.0);
	s.set_step(1.0);
	s.set_value(value.max(min) as f64);
	s
}

/// The Alarm Settings dialog. Writes the alarm fields of `e` on OK, and returns true; it does not save.
pub fn alarm_dialog(e: &mut Event) -> bool {
	let mut win = Window::default().with_size(227, 89).with_label("Alarm Settings");
	// The check box at the right of its "Alarm:" label, as Palm Desktop 3.1.1 lays it out.
	let mut on = crate::draw::check(47, 19, 13, 13, "");
	on.set_value(e.alarm);
	let mut label = key_label("&Alarm:", 19, 17, 26, 18, Align::Left);
	let mut amount = count_spinner(71, 17, 36, 18, e.alarm_advance, 1);
	let mut unit = Choice::new(115, 17, 102, 18, None);
	for u in ALARM_UNITS {
		unit.add_choice(u);
	}
	unit.set_value(e.alarm_unit.clamp(0, 2));
	crate::draw::style_menu(&mut unit);
	combo::drop_below(&mut unit);
	let saved = Rc::new(Cell::new(false));
	// OK and Cancel at the bottom, 75 x 23 each, as in 3.1.1.
	let mut ok = Button::new(26, 54, 75, 23, "&OK");
	let mut cancel = Button::new(108, 54, 75, 23, "Cancel");
	ok.set_shortcut(Shortcut::None | fltk::enums::Key::Enter);
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
	// The count and the units show only while Alarm is checked.
	let set_on = |amount: &mut Spinner, unit: &mut Choice, on: bool| {
		if on {
			amount.show();
			unit.show();
		} else {
			amount.hide();
			unit.hide();
		}
	};
	set_on(&mut amount, &mut unit, e.alarm);
	{
		let (mut amount, mut unit) = (amount.clone(), unit.clone());
		on.set_callback(move |b| set_on(&mut amount, &mut unit, b.value()));
	}
	// Alt+A on the "Alarm:" label toggles the check box.
	{
		let mut on = on.clone();
		label.set_callback(move |_| {
			on.set_value(!on.value());
			on.do_callback();
		});
	}
	run_modal(&mut win);
	if !saved.get() {
		return false;
	}
	e.alarm = on.value();
	e.alarm_advance = amount.value() as i32;
	e.alarm_unit = unit.value().max(0);
	true
}

/// Opens the note of `occ` and saves a change. A click on the note icon of a box does this too.
pub fn edit_note(store: &mut DesktopStore, occ: &Occ) {
	if let Some(text) = crate::note_dialog::edit("Note Editor", &occ.event.note) {
		change_occurrence(
			store,
			occ,
			"Change the note for the current event only or for all occurrences?",
			|e| e.note = text,
		);
	}
}

/// Opens Alarm Settings for `occ` and saves a change. A click on the alarm icon of a box does this too.
pub fn edit_alarm(store: &mut DesktopStore, occ: &Occ) {
	let mut work = occ.event.clone();
	if !alarm_dialog(&mut work) {
		return;
	}
	change_occurrence(
		store,
		occ,
		"Change the alarm for the current event only or for all occurrences?",
		|e| {
			e.alarm = work.alarm;
			e.alarm_advance = work.alarm_advance;
			e.alarm_unit = work.alarm_unit;
		},
	);
}

/// The items of the menu of an event box.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BoxAction {
	Delete,
	Note,
	Alarm,
	Edit,
}

/// Shows the menu of an event box at the pointer.
pub fn event_menu() -> Option<BoxAction> {
	let mut mb = MenuButton::default();
	mb.set_type(MenuButtonType::Popup3);
	for label in ["&Delete", "&Note...", "&Alarm...", "&Edit..."] {
		mb.add_choice(label);
	}
	// A divider after Delete and after Alarm, as in Palm Desktop 3.1.1.
	for i in [0, 2] {
		if let Some(mut item) = mb.at(i) {
			item.set_flag(MenuFlag::MenuDivider);
		}
	}
	crate::draw::style_menu(&mut mb);
	let pick = mb.popup().map(|_| mb.value()).filter(|v| *v >= 0).map(|v| v as usize);
	MenuButton::delete(mb);
	Some(match pick? {
		0 => BoxAction::Delete,
		1 => BoxAction::Note,
		2 => BoxAction::Alarm,
		_ => BoxAction::Edit,
	})
}

/// Draws `s` centered in the box (x, y, w, h).
pub fn draw_centered(s: &str, x: i32, y: i32, w: i32, h: i32) {
	draw::draw_text2(&s.replace('@', "@@"), x, y, w, h, Align::Center);
}

/// The summary text of the Palm Desktop 3.1.1 Change Repeat dialog, measured from the official app:
/// every 1 day "Every day", every 2 days "Every other day"; weekly on Tuesday "Every Tuesday" and "Every
/// other Tuesday"; on Mon and Tue every 3 "Every 3rd week on Mon, and Tue"; by date "The 20th of every
/// month" and "The 20th of every 3rd month"; yearly "October 20 every year". Forms not measured follow
/// that pattern: no frequency word for 1, "other" for 2 and the ordinal from 3.
pub fn describe_desktop(e: &Event) -> String {
	if e.repeat == repeat::NONE {
		return String::new();
	}
	let n = e.every.max(1);
	let freq = match n {
		1 => String::new(),
		2 => "other".to_string(),
		_ => ordinal(n),
	};
	let words = |parts: &[&str]| {
		parts
			.iter()
			.filter(|p| !p.is_empty())
			.copied()
			.collect::<Vec<_>>()
			.join(" ")
	};
	let d = ymd(e.date);
	let base = match e.repeat {
		repeat::DAY => words(&["Every", &freq, "day"]),
		repeat::WEEK => {
			let mask = if e.week_days == 0 {
				1 << dow(e.date)
			} else {
				e.week_days
			};
			let days: Vec<usize> = (0..7).filter(|i| mask & (1 << i) != 0).collect();
			if days.len() == 1 {
				words(&["Every", &freq, DOW_LONG[days[0]]])
			} else {
				let names: Vec<&str> = days.iter().map(|i| DOW_SHORT[*i]).collect();
				let list = format!(
					"{}, and {}",
					names[..names.len() - 1].join(", "),
					names[names.len() - 1]
				);
				words(&["Every", &freq, &format!("week on {list}")])
			}
		}
		repeat::MONTH_DAY => {
			let w = month_week(e);
			let week = if w == 5 { "last".to_string() } else { ordinal(w as i32) };
			format!(
				"The {week} {} of {}",
				DOW_LONG[dow(e.date) as usize],
				words(&["every", &freq, "month"])
			)
		}
		repeat::MONTH_DATE => format!("The {} of {}", ordinal(d.day as i32), words(&["every", &freq, "month"])),
		repeat::YEAR => format!(
			"{} {} {}",
			MONTH_LONG[d.month as usize - 1],
			d.day,
			words(&["every", &freq, "year"])
		),
		_ => String::new(),
	};
	if e.repeat_end >= 0 {
		format!("{base} until {}", short_date(e.repeat_end))
	} else {
		base
	}
}

pub const MONTH_LONG: [&str; 12] = [
	"January",
	"February",
	"March",
	"April",
	"May",
	"June",
	"July",
	"August",
	"September",
	"October",
	"November",
	"December",
];

/// The Date view title: "Friday, October 23, 2026".
pub fn date_title(day: i64) -> String {
	let d = ymd(day);
	format!(
		"{}, {} {}, {}",
		DOW_LONG[dow(day) as usize],
		MONTH_LONG[d.month as usize - 1],
		d.day,
		d.year
	)
}

/// The week number of the Week view title, as java.time's WeekFields weekOfYear: weeks from Sunday with the
/// week of January 1 as week 1, or ISO weeks from Monday (week 1 has four days of the year, the days before
/// it are week 0).
pub fn week_of_year(day: i64, monday: bool) -> i64 {
	let d = ymd(day);
	let doy = day - palm_core::days::epoch_day(d.year, 1, 1) + 1;
	let (sow_iso, min_days) = if monday { (1, 4) } else { (7, 1) };
	let iso_dow = (dow(day) + 6) % 7 + 1;
	let dow1 = (iso_dow as i64 - sow_iso).rem_euclid(7) + 1;
	let week_start = (doy - dow1).rem_euclid(7);
	let offset = if week_start + 1 > min_days {
		7 - week_start
	} else {
		-week_start
	};
	(7 + offset + (doy - 1)) / 7
}

#[cfg(test)]
mod tests {
	use super::*;
	use palm_core::days::epoch_day;

	fn ev(date: i64, kind: i32, every: i32) -> Event {
		Event {
			date,
			repeat: kind,
			every,
			..Event::default()
		}
	}

	#[test]
	fn the_repeat_summary_reads_as_palm_desktop_writes_it() {
		// 2026-10-20 is a Tuesday.
		let tue = epoch_day(2026, 10, 20);
		assert_eq!(describe_desktop(&ev(tue, repeat::NONE, 1)), "");
		assert_eq!(describe_desktop(&ev(tue, repeat::DAY, 1)), "Every day");
		assert_eq!(describe_desktop(&ev(tue, repeat::DAY, 2)), "Every other day");
		assert_eq!(describe_desktop(&ev(tue, repeat::WEEK, 1)), "Every Tuesday");
		assert_eq!(describe_desktop(&ev(tue, repeat::WEEK, 2)), "Every other Tuesday");
		let mut mon_tue = ev(tue, repeat::WEEK, 3);
		mon_tue.week_days = 0b110;
		assert_eq!(describe_desktop(&mon_tue), "Every 3rd week on Mon, and Tue");
		assert_eq!(
			describe_desktop(&ev(tue, repeat::MONTH_DATE, 1)),
			"The 20th of every month"
		);
		assert_eq!(
			describe_desktop(&ev(tue, repeat::MONTH_DATE, 3)),
			"The 20th of every 3rd month"
		);
		assert_eq!(describe_desktop(&ev(tue, repeat::YEAR, 1)), "October 20 every year");
		assert_eq!(
			describe_desktop(&ev(tue, repeat::MONTH_DAY, 1)),
			"The 3rd Tuesday of every month"
		);
		// 2026-10-25 is both the 4th and the last Sunday of its month; the LAST_WEEK flag picks the last.
		let mut sun = ev(epoch_day(2026, 10, 25), repeat::MONTH_DAY, 1);
		assert_eq!(describe_desktop(&sun), "The 4th Sunday of every month");
		sun.week_days = repeat::LAST_WEEK;
		assert_eq!(describe_desktop(&sun), "The last Sunday of every month");
		let mut until = ev(tue, repeat::DAY, 1);
		until.repeat_end = epoch_day(2026, 12, 31);
		assert_eq!(describe_desktop(&until), "Every day until 12/31/26");
	}

	#[test]
	fn titles_time_labels_and_week_numbers() {
		assert_eq!(date_title(epoch_day(2026, 10, 23)), "Friday, October 23, 2026");
		assert_eq!(time_label(0, false), "12:00a");
		assert_eq!(time_label(13 * 60 + 5, false), "1:05p");
		assert_eq!(time_label(13 * 60 + 5, true), "13:05");
		assert_eq!(week_of_year(epoch_day(2026, 10, 18), false), 43);
		assert_eq!(week_of_year(epoch_day(2026, 10, 19), true), 43);
		assert_eq!(week_of_year(epoch_day(2026, 1, 1), false), 1);
		// 2027 starts on a Friday: its first days are ISO week 0 in weekOfYear.
		assert_eq!(week_of_year(epoch_day(2027, 1, 1), true), 0);
	}
}

#[cfg(test)]
mod wrap_tests {
	use super::wrap_words;

	/// Six px per character, so a line of 12 px holds two characters.
	fn six(s: &str) -> i32 {
		s.chars().count() as i32 * 6
	}

	#[test]
	fn words_wrap_at_spaces_and_long_words_cut_between_characters() {
		assert_eq!(wrap_words("aa bb cc", 12, 12, &six), vec!["aa", "bb", "cc"]);
		assert_eq!(wrap_words("abcdefgh", 12, 12, &six), vec!["ab", "cd", "ef", "gh"]);
		// The first line, after the markers, may be wider than the rest.
		assert_eq!(wrap_words("a b cd", 18, 12, &six), vec!["a b", "cd"]);
		assert!(wrap_words("   ", 12, 12, &six).is_empty());
	}
}
