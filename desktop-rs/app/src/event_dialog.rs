//! The Edit Event dialog of EventDialog.kt, after Palm Desktop 3.1.1, with its Change Repeat and Select Time
//! dialogs, and the parsers of its time and date fields.

use crate::combo;
use crate::date_picker::{self, Mode};
use crate::date_shared::{
	apply_alarm_preset, count_spinner, describe_desktop, save_occurrence, week_begins, Occ, ALARM_UNITS, CHANGE_TEXT,
};
use crate::dialogs::{draw_icon, key_label, message, push_button, run_modal, show_modal, Icon};
use crate::note_dialog;
use crate::platform::today;
use crate::text_edit::Field;
use crate::theme::{self, icon, PANEL, SELECT};
use crate::view::Ctx;
use fltk::app;
use fltk::browser::HoldBrowser;
use fltk::button::Button;
use fltk::dialog;
use fltk::draw;
use fltk::enums::{Align, Color, Event as Ev, FrameType, Key, Shortcut};
use fltk::frame::Frame;
use fltk::input::Input;
use fltk::menu::{Choice, MenuButton};
use fltk::output::Output;
use fltk::prelude::*;
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use fltk::window::Window;
use palm_core::days::{dow, epoch_day, month_length, ymd};
use palm_core::model::{is_last_week, repeat, short_date, time, week_index, Event, DOW_LETTER, DOW_LONG};
use std::cell::Cell;
use std::rc::Rc;

/// The text of an untimed start or end field.
const NO_TIME: &str = "None";

/// True when a time field says no time: blank or "None".
fn is_no_time(text: &str) -> bool {
	text.trim().is_empty() || text.trim().eq_ignore_ascii_case(NO_TIME)
}

/// Parses a time field such as "8:00a", "8a", "1:30 PM", "13:30" or "830" to minutes after midnight. Spaces
/// and case do not count; with no suffix the hour is 24 h.
pub fn parse_time(text: &str) -> Option<i32> {
	let s: String = text
		.chars()
		.filter(|c| !c.is_whitespace())
		.collect::<String>()
		.to_lowercase();
	let digits_end = s.find(|c: char| !c.is_ascii_digit() && c != ':').unwrap_or(s.len());
	let (num, suffix) = s.split_at(digits_end);
	if !["", "a", "am", "p", "pm"].contains(&suffix) {
		return None;
	}
	let all_digits = |t: &str| !t.is_empty() && t.chars().all(|c| c.is_ascii_digit());
	let (h, m) = match num.split_once(':') {
		Some((h, m)) if (1..=2).contains(&h.len()) && m.len() == 2 && all_digits(h) && all_digits(m) => {
			(h.parse::<i32>().ok()?, m.parse::<i32>().ok()?)
		}
		Some(_) => return None,
		None if all_digits(num) && num.len() <= 2 => (num.parse().ok()?, 0),
		None if all_digits(num) && num.len() <= 4 => {
			let (h, m) = num.split_at(num.len() - 2);
			(h.parse().ok()?, m.parse().ok()?)
		}
		None => return None,
	};
	if m > 59 {
		return None;
	}
	let hour = match suffix {
		"" => h,
		_ if !(1..=12).contains(&h) => return None,
		s if s.starts_with('a') => h % 12,
		_ => h % 12 + 12,
	};
	(hour <= 23).then_some(hour * 60 + m)
}

/// Parses a date field such as "10/21/2026" or "10/19/26" to an epoch day. Two-digit years 70 to 99 mean
/// 19xx, the others 20xx.
pub fn parse_short_date(text: &str) -> Option<i64> {
	let parts: Vec<&str> = text.trim().split('/').collect();
	let [m, d, y] = parts[..] else { return None };
	let ok =
		|t: &str, n: std::ops::RangeInclusive<usize>| n.contains(&t.len()) && t.chars().all(|c| c.is_ascii_digit());
	if !ok(m, 1..=2) || !ok(d, 1..=2) || !(y.len() == 2 || y.len() == 4) || !ok(y, 2..=4) {
		return None;
	}
	let (m, d, yy): (u32, u32, i64) = (m.parse().ok()?, d.parse().ok()?, y.parse().ok()?);
	let year = match (y.len(), yy) {
		(4, _) => yy,
		(_, 70..) => 1900 + yy,
		_ => 2000 + yy,
	};
	if !(1..=12).contains(&m) || d < 1 || d > month_length(year, m) {
		return None;
	}
	Some(epoch_day(year, m, d))
}

/// The Windows short date with a four-digit year and no leading zeros: "5/3/2026".
pub fn full_date(day: i64) -> String {
	let d = ymd(day);
	format!("{}/{}/{}", d.month, d.day, d.year)
}

fn first_line(s: &str) -> &str {
	s.lines().next().unwrap_or("")
}

/// Shows a warning in the Palm Desktop message box.
fn warn(text: &str) {
	message("Palm Desktop", Icon::Warning, text);
}

/// True for a day in both the 4th and the last week of its month. The monthly repeat by day reads the week
/// from its first date, so such a day asks which of the two is meant.
fn in_4th_and_last_week(day: i64) -> bool {
	week_index(day) == 4 && is_last_week(day)
}

/// The Monthly Repeat question: "4th week" (Some(0)) or "Last week" (Some(1)), or None when the box is
/// closed. The question has a Question icon, two radios and one OK button; "Last week" is set at first when
/// `last` is true.
fn monthly_week_question(last: bool) -> Option<usize> {
	let (w, h) = (230, 130);
	let mut win = Window::default().with_size(w, h).with_label("Monthly Repeat");
	let mut badge = Frame::new(11, 11, 32, 32, None);
	badge.draw(|f| draw_icon(Icon::Question, f.x(), f.y()));
	let mut text = Frame::new(
		53,
		14,
		166,
		34,
		"Does this event occur on the 4th or last week of each month?",
	);
	text.set_align(Align::Left | Align::Top | Align::Inside | Align::Wrap);
	let mut fourth = crate::draw::radio(53, 60, 80, 20, "&4th week");
	fourth.set_value(!last);
	let mut last_r = crate::draw::radio(140, 60, 80, 20, "&Last week");
	last_r.set_value(last);
	let last = last_r;
	let answer: Rc<Cell<Option<usize>>> = Rc::new(Cell::new(None));
	let mut ok = push_button((w - 75) / 2, 92, "OK", true);
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let (answer, mut win, last) = (answer.clone(), win.clone(), last.clone());
		ok.set_callback(move |_| {
			answer.set(Some(usize::from(last.value())));
			win.hide();
		});
	}
	win.set_callback(|w| w.hide());
	win.end();
	run_modal(&mut win);
	answer.get()
}

fn icon_button(x: i32, y: i32, png: &str, tip: &str) -> Button {
	let mut b = Button::new(x, y, 22, 22, None);
	b.set_image(Some(icon(png)));
	b.set_tooltip(tip);
	b
}

/// The text of the Repeat button.
fn repeat_name(e: &Event) -> &'static str {
	match e.repeat {
		repeat::DAY => "Daily...",
		repeat::WEEK => "Weekly...",
		repeat::MONTH_DAY | repeat::MONTH_DATE => "Monthly...",
		repeat::YEAR => "Yearly...",
		_ => "None...",
	}
}

/// What the Edit Event buttons ask for.
#[derive(Clone, Copy)]
enum Act {
	Ok,
	Clock,
	Calendar,
	Note,
	Repeat,
}

/// Opens Edit Event for `orig`, the occurrence on `day`, or for a new event on `day` when `orig` is None. A
/// new event starts at `start_min`, or is untimed at 8:00 am when it is None; `title` is its description.
pub fn edit(ctx: &mut Ctx, orig: Option<Event>, day: i64, start_min: Option<i32>, title: &str) {
	let start = start_min.unwrap_or(8 * 60);
	let mut work = orig.clone().unwrap_or_else(|| Event {
		title: title.to_string(),
		date: day,
		start,
		end: start + 60,
		untimed: start_min.is_none(),
		..Event::default()
	});
	if orig.is_none() {
		apply_alarm_preset(&mut work, ctx.settings);
	}
	let h24 = ctx.h24;
	let mut win = Window::default().with_size(258, 262).with_label("Edit Event");
	let act: Rc<Cell<Option<Act>>> = Rc::new(Cell::new(None));
	let button_act = |b: &mut Button, a: Act| {
		let act = act.clone();
		b.set_callback(move |_| act.set(Some(a)));
	};
	let label = |text: &str, y: i32, target: fltk::widget::Widget| {
		let mut l = key_label(text, 6, y, 60, 22, Align::Right);
		l.set_callback(move |_| drop(target.clone().take_focus()));
	};

	let desc_buf = TextBuffer::default();
	let mut desc = TextEditor::new(70, 4, 182, 50, None);
	desc.set_buffer(desc_buf.clone());
	desc.wrap_mode(WrapMode::AtBounds, 0);
	desc.set_scrollbar_align(Align::Right);
	Field::Editor(desc.clone()).attach_menu();
	label("De&scription:", 4, desc.as_base_widget());
	desc_buf.clone().set_text(&work.title);
	if orig.is_some() {
		// The description of an event is selected on open, so the first key replaces it.
		desc_buf.clone().select(0, desc_buf.length());
	} else {
		// A title typed in the Month view goes on after its first key.
		desc.set_insert_position(desc_buf.length());
	}

	let mut start_f = Input::new(70, 60, 60, 22, None);
	let mut end_f = Input::new(158, 60, 60, 22, None);
	for f in [&start_f, &end_f] {
		Field::Input(f.clone()).attach_menu();
	}
	label("T&ime:", 60, start_f.as_base_widget());
	let mut to = key_label("&to", 134, 60, 22, 22, Align::Center);
	{
		let end_f = end_f.clone();
		to.set_callback(move |_| drop(end_f.clone().take_focus()));
	}
	let mut clock = icon_button(226, 60, "clock", "Set time");
	button_act(&mut clock, Act::Clock);
	let show_time = |s: &mut Input, e: &mut Input, t: &Event| {
		s.set_value(&if t.untimed {
			NO_TIME.to_string()
		} else {
			time(t.start, h24)
		});
		e.set_value(&if t.untimed {
			NO_TIME.to_string()
		} else {
			time(t.end, h24)
		});
	};
	show_time(&mut start_f, &mut end_f, &work);

	let mut date_f = Input::new(70, 90, 80, 22, None);
	Field::Input(date_f.clone()).attach_menu();
	date_f.set_value(&full_date(day));
	label("D&ate:", 90, date_f.as_base_widget());
	let mut day_name = Frame::new(154, 90, 68, 22, None);
	day_name.set_frame(FrameType::DownBox);
	day_name.set_label(DOW_LONG[dow(day) as usize]);
	let mut cal = icon_button(226, 90, "calendar", "Choose date");
	button_act(&mut cal, Act::Calendar);

	let mut note_f = Output::new(70, 120, 152, 22, None);
	note_f.set_color(PANEL);
	note_f.set_value(first_line(&work.note));
	label("Not&e:", 120, note_f.as_base_widget());
	let mut note_b = icon_button(226, 120, "note", "Edit note");
	button_act(&mut note_b, Act::Note);

	let mut repeat_b = Button::new(70, 150, 90, 22, None);
	button_act(&mut repeat_b, Act::Repeat);
	label("&Repeat:", 150, repeat_b.as_base_widget());

	let mut alarm = crate::draw::check(70, 180, 18, 22, "");
	alarm.set_value(work.alarm);
	label("A&larm:", 180, alarm.as_base_widget());
	let amount = count_spinner(92, 180, 44, 22, work.alarm_advance, 1);
	let mut unit = Choice::new(140, 180, 112, 22, None);
	for u in ALARM_UNITS {
		unit.add_choice(u);
	}
	unit.set_value(work.alarm_unit.clamp(0, 2));
	crate::draw::style_menu(&mut unit);
	combo::drop_below(&mut unit);
	let mut private = crate::draw::check(70, 210, 18, 22, "");
	private.set_value(work.is_private);
	label("&Private:", 210, private.as_base_widget());

	let mut ok = Button::new(51, 234, 75, 23, "OK");
	button_act(&mut ok, Act::Ok);
	let mut cancel = Button::new(132, 234, 75, 23, "Cancel");
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	// Enter outside the description is OK, as the default button of the Swing form.
	{
		let act = act.clone();
		win.handle(move |_, ev| {
			if ev == Ev::Shortcut && matches!(app::event_key(), Key::Enter | Key::KPEnter) {
				act.set(Some(Act::Ok));
				return true;
			}
			false
		});
	}

	let show_repeat = |b: &mut Button, e: &Event| {
		b.set_label(repeat_name(e));
		if e.repeats() {
			b.set_tooltip(&describe_desktop(e));
		} else {
			b.set_tooltip("");
		}
	};
	show_repeat(&mut repeat_b, &work);
	show_modal(&mut win);
	let _ = desc.take_focus();
	while win.shown() {
		app::wait();
		// The alarm amount and unit show only for an alarm; the day name follows the date field.
		for w in [&mut amount.as_base_widget(), &mut unit.as_base_widget()] {
			if alarm.value() != w.visible() {
				if alarm.value() {
					w.show();
				} else {
					w.hide();
				}
			}
		}
		if let Some(d) = parse_short_date(&date_f.value()) {
			let name = DOW_LONG[dow(d) as usize];
			if day_name.label() != name {
				day_name.set_label(name);
			}
		}
		let Some(a) = act.take() else { continue };
		match a {
			Act::Clock => {
				// Select Time works on a copy of the time fields; the fields take the copy on OK or No Time.
				let s = parse_time(&start_f.value());
				let mut t = work.clone();
				t.untimed = s.is_none();
				t.start = s.unwrap_or(8 * 60);
				t.end = if t.untimed {
					t.start + 60
				} else {
					parse_time(&end_f.value()).unwrap_or((t.start + 60).min(1439))
				};
				if select_time(&mut t, h24) {
					show_time(&mut start_f, &mut end_f, &t);
				}
			}
			Act::Calendar => {
				let from = parse_short_date(&date_f.value()).unwrap_or(day);
				if let Some(d) = date_picker::pick(Mode::Day, from, today(), week_begins(ctx.settings)) {
					date_f.set_value(&full_date(d));
				}
			}
			Act::Note => {
				if let Some(text) = note_dialog::edit("Note Editor", &work.note) {
					note_f.set_value(first_line(&text));
					work.note = text;
				}
			}
			Act::Repeat => {
				if change_repeat(&mut work, ctx.settings) {
					show_repeat(&mut repeat_b, &work);
				}
			}
			Act::Ok => {
				let Some(new_day) = parse_short_date(&date_f.value()) else {
					warn("Enter a date as M/D/YY.");
					continue;
				};
				let timed = !is_no_time(&start_f.value());
				let s = parse_time(&start_f.value());
				let en = if is_no_time(&end_f.value()) {
					s.map(|s| (s + 60).min(1439))
				} else {
					parse_time(&end_f.value())
				};
				if timed {
					let (Some(s), Some(en)) = (s, en) else {
						warn("Invalid time entered.");
						continue;
					};
					if en < s {
						warn("The end time is before the start time.");
						continue;
					}
					work.start = s;
					work.end = en;
				}
				work.untimed = !timed;
				work.title = desc_buf.text().trim().to_string();
				work.alarm = alarm.value();
				work.alarm_advance = amount.value() as i32;
				work.alarm_unit = unit.value().max(0);
				work.is_private = private.value();
				if work.repeat == repeat::MONTH_DAY && in_4th_and_last_week(new_day) {
					// The first date alone cannot tell the 4th week from the last, so the answer goes into the
					// LAST_WEEK flag of the rule.
					match monthly_week_question(work.week_days & repeat::LAST_WEEK != 0) {
						Some(0) => work.week_days &= !repeat::LAST_WEEK,
						Some(_) => work.week_days |= repeat::LAST_WEEK,
						None => continue,
					}
				}
				match &orig {
					None => {
						work.date = new_day;
						ctx.store.save_event(&mut work);
					}
					Some(o) if *o == work && new_day == day => {}
					Some(o) => {
						let occ = Occ { event: o.clone(), day };
						let mut w = work.clone();
						w.date = new_day;
						if !o.repeats() {
							ctx.store.save_event(&mut w);
						} else if !save_occurrence(ctx.store, &occ, w, CHANGE_TEXT) {
							continue;
						}
					}
				}
				win.hide();
			}
		}
	}
}

/// What the Change Repeat controls ask for.
#[derive(Clone, Copy)]
enum RepeatAct {
	Ok,
	Kind(i32),
	End,
	Toggle(usize),
	Refresh,
}

/// The Change Repeat dialog. Edits a copy of the repeat fields of `e`, and writes them back on OK (true).
pub fn change_repeat(e: &mut Event, settings: &crate::settings::Settings) -> bool {
	let begins = week_begins(settings);
	let mut kind = e.repeat;
	let mut month_by = if e.repeat == repeat::MONTH_DAY {
		repeat::MONTH_DAY
	} else {
		repeat::MONTH_DATE
	};
	// Bits 0 to 6 are the week days; a monthly repeat by day keeps its LAST_WEEK flag in bit 7.
	let mut mask = if e.week_days & 0x7F == 0 {
		1 << dow(e.date)
	} else {
		e.week_days & 0x7F
	};
	let mut end = e.repeat_end;
	let mut win = Window::default().with_size(263, 222).with_label("Change Repeat");
	let act: Rc<Cell<Option<RepeatAct>>> = Rc::new(Cell::new(None));
	let send = |a: RepeatAct| {
		let act = act.clone();
		move || act.set(Some(a))
	};
	let mut radios = Vec::new();
	for (i, (label, k)) in [
		("&None", repeat::NONE),
		("&Daily", repeat::DAY),
		("&Weekly", repeat::WEEK),
		("&Monthly", repeat::MONTH_DATE),
		("&Yearly", repeat::YEAR),
	]
	.into_iter()
	.enumerate()
	{
		let mut r = crate::draw::radio(10, 16 + 24 * i as i32, 72, 20, label);
		let on = match k {
			repeat::MONTH_DATE => kind == repeat::MONTH_DATE || kind == repeat::MONTH_DAY,
			_ => kind == k,
		};
		r.set_value(on);
		let f = send(RepeatAct::Kind(k));
		r.set_callback(move |_| f());
		radios.push(r);
	}
	let mut hint = Frame::new(
		96,
		40,
		157,
		56,
		"Click one of the buttons to the left to set a repeat interval.",
	);
	hint.set_align(Align::Left | Align::Top | Align::Inside | Align::Wrap);
	let every_l = key_label("&Every:", 96, 18, 46, 17, Align::Right);
	let mut every = count_spinner(144, 16, 32, 21, e.every, 1);
	{
		let f = send(RepeatAct::Refresh);
		every.set_callback(move |_| f());
	}
	let mut every_unit = Frame::new(192, 18, 66, 17, None);
	every_unit.set_align(Align::Left | Align::Inside);
	let end_l = key_label("End &on:", 96, 44, 46, 17, Align::Right);
	let mut end_f = Output::new(144, 42, 80, 21, None);
	end_f.set_color(PANEL);
	let mut cal = Button::new(224, 42, 20, 20, None);
	cal.set_image(Some(icon("calendar")));
	// The popup of End on: a hidden menu button at the calendar button, in the Windows menu look.
	let mut end_menu = MenuButton::new(cal.x(), cal.y() + cal.h(), 0, 0, None);
	end_menu.add_choice("No End Date|Date...");
	crate::draw::style_menu(&mut end_menu);
	end_menu.hide();
	{
		let f = send(RepeatAct::End);
		cal.set_callback(move |_| f());
	}
	let days_l = key_label("Day&s:", 96, 70, 46, 17, Align::Right);
	// The seven day cells in week order, in 1 px black lines; a click turns a day on or off.
	let mask_cell = Rc::new(Cell::new(mask));
	let mut strip = Frame::new(144, 69, 99, 18, None);
	{
		let m = mask_cell.clone();
		strip.draw(move |f| {
			let fonts = theme::fonts();
			draw::draw_rect_fill(f.x(), f.y(), f.w(), f.h(), Color::Black);
			let cw = (f.w() - 1) / 7;
			for c in 0..7 {
				let day = (c + begins as usize) % 7;
				let on = m.get() & (1 << day) != 0;
				let x = f.x() + 1 + c as i32 * cw;
				draw::draw_rect_fill(x, f.y() + 1, cw - 1, f.h() - 2, if on { SELECT } else { Color::White });
				draw::set_font(fonts.sans, 11);
				draw::set_draw_color(if on { Color::White } else { Color::Black });
				draw::draw_text2(DOW_LETTER[day], x, f.y(), cw - 1, f.h(), Align::Center);
			}
		});
	}
	{
		let act = act.clone();
		strip.handle(move |f, ev| {
			if ev != Ev::Push {
				return false;
			}
			let c = ((app::event_x() - f.x()) * 7 / f.w().max(1)).clamp(0, 6) as usize;
			act.set(Some(RepeatAct::Toggle((c + begins as usize) % 7)));
			true
		});
	}
	let by_l = key_label("&By:", 96, 70, 46, 17, Align::Right);
	let mut by = Choice::new(144, 68, 70, 21, None);
	by.add_choice("Day|Date");
	by.set_value(if month_by == repeat::MONTH_DAY { 0 } else { 1 });
	crate::draw::style_menu(&mut by);
	combo::drop_below(&mut by);
	{
		let f = send(RepeatAct::Refresh);
		by.set_callback(move |_| f());
	}
	let mut summary = Frame::new(10, 136, 243, 52, None);
	summary.set_frame(FrameType::DownBox);
	summary.set_color(PANEL);
	summary.set_align(Align::Left | Align::Top | Align::Inside | Align::Wrap);
	let mut ok = Button::new(10, 194, 68, 23, "OK");
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let f = send(RepeatAct::Ok);
		ok.set_callback(move |_| f());
	}
	let mut cancel = Button::new(82, 194, 68, 23, "Cancel");
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	let first = e.date;
	let preview = |kind: i32, every: i32, end: i64, mask: i32| {
		let on = kind != repeat::NONE;
		Event {
			repeat: kind,
			every,
			repeat_end: if on { end } else { -1 },
			week_days: if !on {
				0
			} else if kind == repeat::MONTH_DAY {
				e.week_days & repeat::LAST_WEEK
			} else if mask == 0 {
				1 << dow(first)
			} else {
				mask
			},
			..e.clone()
		}
	};
	let (mut hint, mut every_w, mut every_unit, mut end_w, mut cal_w, mut strip_w, mut by_w) = (
		hint.clone(),
		every.clone(),
		every_unit.clone(),
		end_f.clone(),
		cal.clone(),
		strip.clone(),
		by.clone(),
	);
	let (mut summary, mut win_w) = (summary.clone(), win.clone());
	let mut refresh = move |kind: i32, end: i64, mask: i32| {
		let on = kind != repeat::NONE;
		let n = every_w.value() as i32;
		let monthly = kind == repeat::MONTH_DATE || kind == repeat::MONTH_DAY;
		let shows: [(&mut dyn WidgetExt, bool); 10] = [
			(&mut hint, !on),
			(&mut every_l.clone(), on),
			(&mut every_w, on),
			(&mut every_unit, on),
			(&mut end_l.clone(), on),
			(&mut end_w, on),
			(&mut cal_w, on),
			(&mut days_l.clone(), kind == repeat::WEEK),
			(&mut strip_w, kind == repeat::WEEK),
			(&mut by_w, monthly),
		];
		for (w, show) in shows {
			if show {
				w.show();
			} else {
				w.hide();
			}
		}
		if monthly {
			by_l.clone().show();
		} else {
			by_l.clone().hide();
		}
		let unit = match kind {
			repeat::DAY => "Day",
			repeat::WEEK => "Week",
			repeat::YEAR => "Year",
			_ => "Month",
		};
		every_unit.set_label(&format!("{unit}{}", if n == 1 { "" } else { "s" }));
		end_w.set_value(&if end < 0 {
			"No End Date".to_string()
		} else {
			short_date(end)
		});
		let text = if on {
			describe_desktop(&preview(kind, n, end, mask))
		} else {
			String::new()
		};
		summary.set_label(&text);
		mask_cell.set(mask);
		strip_w.redraw();
		win_w.redraw();
	};
	refresh(kind, end, mask);
	show_modal(&mut win);
	while win.shown() {
		app::wait();
		let Some(a) = act.take() else { continue };
		match a {
			RepeatAct::Kind(k) => kind = if k == repeat::MONTH_DATE { month_by } else { k },
			RepeatAct::Refresh => {
				month_by = if by.value() == 0 {
					repeat::MONTH_DAY
				} else {
					repeat::MONTH_DATE
				};
				if kind == repeat::MONTH_DATE || kind == repeat::MONTH_DAY {
					kind = month_by;
				}
			}
			RepeatAct::Toggle(day) => mask ^= 1 << day,
			RepeatAct::End => {
				let pick = end_menu.popup().and_then(|i| i.label());
				match pick.as_deref() {
					Some("No End Date") => end = -1,
					Some(_) => {
						let from = if end >= 0 { end } else { e.date };
						if let Some(d) = date_picker::pick(Mode::Day, from, today(), begins) {
							if d < e.date {
								warn("The end date is before the first date.");
							} else {
								end = d;
							}
						}
					}
					None => {}
				}
			}
			RepeatAct::Ok => {
				let w = preview(kind, every.value() as i32, end, mask);
				e.repeat = w.repeat;
				e.every = w.every;
				e.repeat_end = w.repeat_end;
				e.week_days = w.week_days;
				win.hide();
				return true;
			}
		}
		refresh(kind, end, mask);
	}
	false
}

/// What the Select Time controls ask for.
#[derive(Clone, Copy)]
enum TimeAct {
	Start,
	End,
	Hour,
	Minute,
	NoTime,
	Ok,
}

/// The Select Time dialog. Edits the time of `t`; true on OK or No Time (which makes `t` untimed).
pub fn select_time(t: &mut Event, h24: bool) -> bool {
	let mut win = Window::default().with_size(207, 220).with_label("Select Time");
	let act: Rc<Cell<Option<TimeAct>>> = Rc::new(Cell::new(None));
	let send = |a: TimeAct| {
		let act = act.clone();
		move || act.set(Some(a))
	};
	let mut start_l = Frame::new(8, 8, 80, 17, "Start time:");
	start_l.set_align(Align::Left | Align::Inside);
	let mut start_r = crate::draw::radio(8, 26, 18, 22, "");
	start_r.set_value(true);
	let mut start_show = Output::new(28, 26, 60, 22, None);
	start_show.set_color(PANEL);
	let mut end_l = Frame::new(8, 54, 80, 17, "End time:");
	end_l.set_align(Align::Left | Align::Inside);
	let mut end_r = crate::draw::radio(8, 72, 18, 22, "");
	let mut end_show = Output::new(28, 72, 60, 22, None);
	end_show.set_color(PANEL);
	let mut no_time = Button::new(8, 102, 80, 23, "&No Time");
	{
		let (f, g, h) = (send(TimeAct::Start), send(TimeAct::End), send(TimeAct::NoTime));
		start_r.set_callback(move |_| f());
		end_r.set_callback(move |_| g());
		no_time.set_callback(move |_| h());
	}
	let mut hours = HoldBrowser::new(96, 8, 48, 172, None);
	for h in 0..24 {
		hours.add(&format!("{:02}", if h24 { h } else { (h + 11) % 12 + 1 }));
	}
	let mut minutes = HoldBrowser::new(150, 8, 48, 172, None);
	for m in 0..12 {
		minutes.add(&format!("{:02}", m * 5));
	}
	{
		let (f, g) = (send(TimeAct::Hour), send(TimeAct::Minute));
		hours.set_callback(move |_| f());
		minutes.set_callback(move |_| g());
	}
	let mut ok = Button::new(30, 188, 68, 23, "OK");
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let f = send(TimeAct::Ok);
		ok.set_callback(move |_| f());
	}
	let mut cancel = Button::new(104, 188, 68, 23, "Cancel");
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	// Alt+S and Alt+E check the start and the end time, as the mnemonics of the Swing radios.
	{
		let (act, mut sr, mut er) = (act.clone(), start_r.clone(), end_r.clone());
		win.handle(move |_, ev| {
			if ev != Ev::Shortcut || !app::event_state().contains(fltk::enums::EventState::Alt) {
				return false;
			}
			match app::event_key().to_char().map(|c| c.to_ascii_lowercase()) {
				Some('s') => {
					sr.set_value(true);
					er.set_value(false);
					act.set(Some(TimeAct::Start));
				}
				Some('e') => {
					er.set_value(true);
					sr.set_value(false);
					act.set(Some(TimeAct::End));
				}
				_ => return false,
			}
			true
		});
	}
	let mut edit_start = true;
	let (mut hours_w, mut minutes_w) = (hours.clone(), minutes.clone());
	let mut refresh = move |t: &Event, edit_start: bool| {
		start_show.set_value(&if t.untimed {
			NO_TIME.to_string()
		} else {
			time(t.start, h24)
		});
		end_show.set_value(&if t.untimed {
			NO_TIME.to_string()
		} else {
			time(t.end, h24)
		});
		if t.untimed {
			// No hour or minute is chosen, and the hours start at 08 at the top of the list.
			if hours_w.value() > 0 {
				hours_w.deselect(hours_w.value());
			}
			if minutes_w.value() > 0 {
				minutes_w.deselect(minutes_w.value());
			}
			hours_w.top_line(9);
			return;
		}
		let active = if edit_start { t.start } else { t.end };
		hours_w.select(active / 60 + 1);
		minutes_w.select(active % 60 / 5 + 1);
	};
	refresh(t, edit_start);
	show_modal(&mut win);
	while win.shown() {
		app::wait();
		let Some(a) = act.take() else { continue };
		let active = if edit_start { t.start } else { t.end };
		// Puts `min` on the checked time. A new start moves the end; a new end before the start is refused.
		let place = |t: &mut Event, min: i32| {
			if edit_start {
				let len = (t.end - t.start).max(0);
				t.start = min;
				t.end = (min + len).min(1439);
			} else if min >= t.start {
				t.end = min;
			} else {
				dialog::beep(dialog::BeepType::Default);
			}
			t.untimed = false;
		};
		match a {
			TimeAct::Start => edit_start = true,
			TimeAct::End => edit_start = false,
			TimeAct::Hour if hours.value() > 0 => place(t, (hours.value() - 1) * 60 + active % 60),
			TimeAct::Minute if minutes.value() > 0 => place(t, active / 60 * 60 + (minutes.value() - 1) * 5),
			TimeAct::NoTime => {
				t.untimed = true;
				win.hide();
				return true;
			}
			TimeAct::Ok => {
				win.hide();
				return true;
			}
			_ => {}
		}
		refresh(t, edit_start);
	}
	false
}

/// The Purge Events dialog: deletes the events that ended more than the chosen span ago, and reports the
/// count.
pub fn purge_events(ctx: &mut Ctx) {
	let spans = [("1 Week", 7), ("2 Weeks", 14), ("3 Weeks", 21), ("1 Month", 30)];
	let mut win = Window::default().with_size(399, 141).with_label("Purge Events");
	let mut badge = Frame::new(11, 11, 32, 32, None);
	badge.draw(|f| draw_icon(Icon::Question, f.x(), f.y()));
	key_label("&Delete events older than:", 102, 16, 120, 22, Align::Left);
	let mut span = Choice::new(234, 16, 72, 22, None);
	for (name, _) in spans {
		span.add_choice(name);
	}
	span.set_value(0);
	crate::draw::style_menu(&mut span);
	combo::drop_below(&mut span);
	let go = Rc::new(Cell::new(false));
	let mut ok = push_button(121, 107, "OK", true);
	ok.set_shortcut(Shortcut::None | Key::Enter);
	let mut cancel = push_button(202, 107, "Cancel", false);
	{
		let (go, mut win) = (go.clone(), win.clone());
		ok.set_callback(move |_| {
			go.set(true);
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	run_modal(&mut win);
	if !go.get() {
		return;
	}
	let days = spans[span.value().max(0) as usize].1;
	let n = ctx.store.purge_events(today() - days);
	message("Palm Desktop", Icon::Info, &format!("{n} events purged."));
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn time_fields_parse_as_the_swing_form_reads_them() {
		assert_eq!(parse_time("8:00a"), Some(480));
		assert_eq!(parse_time("8a"), Some(480));
		assert_eq!(parse_time("1:30 PM"), Some(810));
		assert_eq!(parse_time("13:30"), Some(810));
		assert_eq!(parse_time("830"), Some(510));
		assert_eq!(parse_time("12:15am"), Some(15));
		assert_eq!(parse_time("12p"), Some(720));
		assert_eq!(parse_time("13p"), None);
		assert_eq!(parse_time("8:75"), None);
		assert_eq!(parse_time("24:00"), None);
		assert_eq!(parse_time("8:0"), None);
		assert_eq!(parse_time("noon"), None);
	}

	#[test]
	fn date_fields_take_two_and_four_digit_years() {
		assert_eq!(parse_short_date("10/21/2026"), Some(epoch_day(2026, 10, 21)));
		assert_eq!(parse_short_date(" 10/19/26 "), Some(epoch_day(2026, 10, 19)));
		assert_eq!(parse_short_date("1/2/70"), Some(epoch_day(1970, 1, 2)));
		assert_eq!(parse_short_date("2/30/2026"), None);
		assert_eq!(parse_short_date("10/21/026"), None);
		assert_eq!(parse_short_date("10-21-2026"), None);
		assert_eq!(full_date(epoch_day(2026, 5, 3)), "5/3/2026");
	}
}
