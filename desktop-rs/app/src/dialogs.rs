//! The small modal dialogs of Dialogs.kt: the delete question, a name prompt and List By. Each one opens,
//! runs its own event loop until it closes, and returns the answer, as a Swing modal dialog does. The
//! message boxes, the prompt and About follow the Palm Desktop 3.1.1 dialogs of Windows.

use crate::platform;
use crate::settings::Settings;
use crate::theme;
use fltk::app;
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, Color, Font, FrameType, Key, Shortcut};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::input::Input;
use fltk::prelude::*;
use fltk::window::Window;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// The text size of the dialogs, in pixels (MS Sans Serif 8 pt at the Windows display scale).
const TEXT_SIZE: i32 = 11;
/// The size of a dialog button.
const BUTTON_W: i32 = 75;
const BUTTON_H: i32 = 23;
/// The space between two buttons, and the margin at the edges of a message box.
const BUTTON_GAP: i32 = 6;
const MARGIN: i32 = 11;
/// The message box icon, and the left edge and top of its text when there is an icon.
const ICON_SIZE: i32 = 32;
const TEXT_LEFT: i32 = 53;
const TEXT_TOP: i32 = 14;
/// The widest and the narrowest message box, in pixels.
const BOX_MAX_W: i32 = 420;
const BOX_MIN_W: i32 = 230;

thread_local! {
	/// The main window, which the dialogs center over. Set by `set_main` at start-up.
	static MAIN: RefCell<Option<Window>> = const { RefCell::new(None) };
}

/// The icon at the left of a message box.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Icon {
	Question,
	Warning,
	Info,
	None,
}

/// Asks before a record is deleted, with `title` on its title bar, OK and Cancel, as Palm Desktop 3.1.1's
/// "Delete Memo Pad Items". True on OK, and true at once when `no_confirm` is set (Shift+Delete) or the
/// "confirmDelete" setting is off.
pub fn confirm_delete_titled(settings: &Settings, no_confirm: bool, title: &str, question: &str) -> bool {
	if no_confirm || !settings.get_bool("confirmDelete", true) {
		return true;
	}
	ask_icon(title, Icon::Question, question, &["OK", "Cancel"], 0) == Some(0)
}

/// Shows `text` with `icon` and one OK button, as a message box does.
pub fn message(title: &str, icon: Icon, text: &str) {
	ask_icon(title, icon, text, &["OK"], 0);
}

/// Asks `text` with `icon` at the left and one button for each of `buttons`, Enter pressing `default`. The
/// index of the button pressed, or None when the dialog is closed (by Escape, the close box or Cancel).
/// The layout is that of a Windows message box: the icon at (11, 11), the text at x 53, buttons of 75 x 23
/// with 6 px between them, and an 11 px margin.
pub fn ask_icon(title: &str, icon: Icon, text: &str, buttons: &[&str], default: usize) -> Option<usize> {
	draw::set_font(Font::Helvetica, TEXT_SIZE);
	let left = if icon == Icon::None { MARGIN } else { TEXT_LEFT };
	let max_text = BOX_MAX_W - left - MARGIN;
	let measure = |s: &str| draw::measure(s, false).0;
	let lines = wrap_lines(text, max_text, &measure);
	let text_w = lines.iter().map(|l| measure(l)).max().unwrap_or(0).min(max_text);
	let text_h = lines.len() as i32 * draw::height();
	let n = buttons.len() as i32;
	let buttons_w = n * BUTTON_W + (n - 1).max(0) * BUTTON_GAP;
	let w = message_width(left, text_w, buttons_w);
	let text_bottom = TEXT_TOP + text_h;
	let content_bottom = if icon == Icon::None {
		text_bottom
	} else {
		text_bottom.max(MARGIN + ICON_SIZE)
	};
	let by = content_bottom + MARGIN;
	let h = by + BUTTON_H + MARGIN;
	let mut win = Window::default().with_size(w, h).with_label(title);
	if icon != Icon::None {
		let mut badge = Frame::new(MARGIN, MARGIN, ICON_SIZE, ICON_SIZE, None);
		badge.draw(move |f| draw_icon(icon, f.x(), f.y()));
	}
	let mut q = Frame::new(left, TEXT_TOP, text_w, text_h, None);
	q.set_label(&lines.join("\n").replace('@', "@@"));
	q.set_align(Align::Left | Align::Top | Align::Inside);
	q.set_label_font(Font::Helvetica);
	q.set_label_size(TEXT_SIZE);
	let answer = Rc::new(Cell::new(None));
	let x0 = (w - buttons_w) / 2;
	for (i, label) in buttons.iter().enumerate() {
		let is_default = i == default;
		let mut b = push_button(x0 + i as i32 * (BUTTON_W + BUTTON_GAP), by, label, is_default);
		if is_default {
			b.set_shortcut(Shortcut::None | Key::Enter);
		}
		let (answer, mut win) = (answer.clone(), win.clone());
		b.set_callback(move |_| {
			answer.set(Some(i));
			win.hide();
		});
	}
	// Escape and the close box both run this callback, and it closes the box with no answer.
	win.set_callback(|w| w.hide());
	win.end();
	run_modal(&mut win);
	answer.get()
}

/// The prompt of Palm Desktop 3.1.1, such as New Category: `label` above a text field holding `initial`, all
/// of it selected, and OK and Cancel. The trimmed text on OK, None on Cancel, on the close box and on a blank.
pub fn prompt_titled(title: &str, label: &str, initial: &str) -> Option<String> {
	let (w, h) = (201, 89);
	let mut win = Window::default().with_size(w, h).with_label(title);
	let mut caption = Frame::new(MARGIN, 8, w - 2 * MARGIN, 14, None);
	caption.set_label(&label.replace('@', "@@"));
	caption.set_align(Align::Left | Align::Inside);
	caption.set_label_font(Font::Helvetica);
	caption.set_label_size(TEXT_SIZE);
	let mut input = Input::new(MARGIN, 24, w - 2 * MARGIN, 20, None);
	input.set_value(initial);
	let _ = input.set_mark(0);
	let _ = input.set_position(initial.len() as i32);
	let _ = input.take_focus();
	let answer: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
	let by = 58;
	let mut ok = push_button(w - MARGIN - 2 * BUTTON_W - BUTTON_GAP, by, "OK", true);
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let (answer, input, mut win) = (answer.clone(), input.clone(), win.clone());
		ok.set_callback(move |_| {
			let text = input.value().trim().to_string();
			if !text.is_empty() {
				*answer.borrow_mut() = Some(text);
			}
			win.hide();
		});
	}
	let mut cancel = push_button(w - MARGIN - BUTTON_W, by, "Cancel", false);
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.set_callback(|w| w.hide());
	win.end();
	run_modal(&mut win);
	answer.take()
}

/// The About box of Palm Desktop: the logo, the name in bold, the version and one OK button.
pub fn about() {
	let (w, h) = (280, 110);
	let mut win = Window::default().with_size(w, h).with_label("About Palm Desktop");
	let logo = theme::icon("app48");
	let mut image = Frame::new(MARGIN, MARGIN, logo.width(), logo.height(), None);
	image.set_image(Some(logo));
	let mut name = Frame::new(72, MARGIN, w - 72 - MARGIN, 20, None);
	name.set_label("Palm Desktop");
	name.set_align(Align::Left | Align::Inside);
	name.set_label_font(theme::fonts().sans_bold);
	name.set_label_size(14);
	let mut version = Frame::new(72, 36, w - 72 - MARGIN, 16, None);
	version.set_label(&format!("Version {}", env!("CARGO_PKG_VERSION")));
	version.set_align(Align::Left | Align::Inside);
	version.set_label_font(Font::Helvetica);
	version.set_label_size(TEXT_SIZE);
	let mut ok = push_button(w - MARGIN - BUTTON_W, h - MARGIN - BUTTON_H, "OK", true);
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let mut win = win.clone();
		ok.set_callback(move |_| win.hide());
	}
	win.set_callback(|w| w.hide());
	win.end();
	run_modal(&mut win);
}

/// Makes `win` the main window that the dialogs center over.
pub fn set_main(win: &Window) {
	MAIN.with(|m| *m.borrow_mut() = Some(win.clone()));
}

/// Moves `win` so that it is centered over the main window, or over the largest shown window when the main
/// window is not shown. The position is kept inside the work area of the screen that holds the anchor.
pub fn center(win: &mut Window) {
	let main = MAIN.with(|m| m.borrow().clone()).filter(|m| m.shown());
	let anchor = match main {
		Some(m) => Some((m.x(), m.y(), m.w(), m.h())),
		None => app::windows()
			.unwrap_or_default()
			.into_iter()
			.filter(|w| w.shown())
			.max_by_key(|w| w.w() * w.h())
			.map(|w| (w.x(), w.y(), w.w(), w.h())),
	};
	let (ax, ay, aw, ah) = anchor.unwrap_or_else(|| app::screen_work_area(app::screen_num(win.x(), win.y())));
	let (sx, sy, sw, sh) = app::screen_work_area(app::screen_num(ax + aw / 2, ay + ah / 2));
	let x = (ax + (aw - win.w()) / 2).clamp(sx, (sx + sw - win.w()).max(sx));
	let y = (ay + (ah - win.h()) / 2).clamp(sy, (sy + sh - win.h()).max(sy));
	win.set_pos(x, y);
}

/// A field label at (x, y, w, h), aligned by `align`, with `&` before the letter of its Alt key. FLTK
/// underlines that letter on a button only, so the label is a button without edges that takes no focus.
pub fn key_label(text: &str, x: i32, y: i32, w: i32, h: i32, align: Align) -> Button {
	let mut b = Button::new(x, y, w, h, None);
	b.set_label(text);
	b.set_frame(FrameType::NoBox);
	b.set_down_frame(FrameType::NoBox);
	b.set_align(align | Align::Inside);
	b.clear_visible_focus();
	b
}

/// Runs a modal window until it closes. The window is centered first, then shown, and then given the close
/// box that FLTK leaves off a modal window.
pub fn run_modal(win: &mut Window) {
	show_modal(win);
	while win.shown() {
		app::wait();
	}
}

/// Shows a modal window centered on the main window, with a close box, for a dialog that runs its own loop.
pub fn show_modal(win: &mut Window) {
	center(win);
	win.make_modal(true);
	win.show();
	platform::add_close_box(win.raw_handle());
	// The labels of the new window hide their underlines until Alt, as the main window's do.
	crate::mnemonics::refresh();
}

/// The title of a group box whose top-left corner is (x, y), drawn over its top edge with the panel gray behind
/// it, as a Windows group box has it. Call it after the group's end(), so the title draws on top.
pub fn group_title(x: i32, y: i32, text: &str) -> Frame {
	draw::set_font(Font::Helvetica, TEXT_SIZE);
	let title_w = draw::measure(text, false).0 + 4;
	let mut title = Frame::new(x + 8, y - 7, title_w, 14, None);
	title.set_label(&text.replace('@', "@@"));
	title.set_align(Align::Left | Align::Inside);
	title.set_label_font(Font::Helvetica);
	title.set_label_size(TEXT_SIZE);
	title.set_frame(FrameType::FlatBox);
	title.set_color(theme::PANEL);
	title
}

/// The List By form of the Address Book and the Memo Pad: one radio for each choice in a titled group, then
/// OK and Cancel. The index of the chosen radio on OK, None on Cancel.
pub fn list_by(heading: &str, choices: &[&str], selected: usize) -> Option<usize> {
	let n = choices.len() as i32;
	let (w, h) = (198, 94 + 22 * n);
	let mut win = Window::default().with_size(w, h).with_label("List By");
	// The group box: its top edge at y 18, the title drawn over that edge, the radios 14 px below it.
	let (gx, gy, gw, gh) = (11, 18, w - 22, 22 * n + 30);
	let mut group = Group::new(gx, gy, gw, gh, None);
	group.set_frame(FrameType::EngravedBox);
	let mut radios = Vec::new();
	for (i, c) in choices.iter().enumerate() {
		let mut r = crate::draw::radio(gx + 9, gy + 14 + 22 * i as i32, gw - 18, 20, c);
		r.set_value(i == selected);
		radios.push(r);
	}
	group.end();
	group_title(gx, gy, heading);
	let answer = Rc::new(Cell::new(None));
	let by = h - MARGIN - BUTTON_H;
	let cancel_x = w - MARGIN - BUTTON_W;
	let mut ok = push_button(cancel_x - BUTTON_GAP - BUTTON_W, by, "OK", true);
	ok.set_shortcut(Shortcut::None | Key::Enter);
	{
		let (answer, radios, mut win) = (answer.clone(), radios.clone(), win.clone());
		ok.set_callback(move |_| {
			answer.set(radios.iter().position(|r| r.value()));
			win.hide();
		});
	}
	let mut cancel = push_button(cancel_x, by, "Cancel", false);
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	win.end();
	run_modal(&mut win);
	answer.get()
}

/// A push button of the dialogs, 75 x 23 at (x, y). The default button has a 1 px black outline, drawn as
/// Palm Desktop 3.1.1 draws it: a black frame behind the button, which sits 1 px inside it. The other buttons
/// show no dotted focus rectangle.
pub(crate) fn push_button(x: i32, y: i32, label: &str, default: bool) -> Button {
	let inset = i32::from(default);
	if default {
		let mut outline = Frame::new(x, y, BUTTON_W, BUTTON_H, None);
		outline.draw(|f| {
			draw::set_draw_color(Color::Black);
			draw::draw_rectf(f.x(), f.y(), f.w(), f.h());
		});
	}
	let mut b = Button::new(x + inset, y + inset, BUTTON_W - 2 * inset, BUTTON_H - 2 * inset, None);
	b.set_label(label);
	if default {
		let _ = b.take_focus();
	} else {
		b.clear_visible_focus();
	}
	b
}

/// Draws the 32 x 32 message box icon at (x, y): a blue disc with a white "?" or "i", or a yellow warning
/// triangle with a black outline and a black "!".
pub(crate) fn draw_icon(icon: Icon, x: i32, y: i32) {
	let blue = Color::from_rgb(0, 120, 215);
	match icon {
		Icon::None => {}
		Icon::Question => {
			disc(x, y, blue);
			glyph(x, y, "?", Color::White);
		}
		Icon::Info => {
			disc(x, y, blue);
			glyph(x, y, "i", Color::White);
		}
		Icon::Warning => {
			let points = [(x + 16, y + 1), (x + 31, y + 30), (x + 1, y + 30)];
			draw::set_draw_color(Color::from_rgb(255, 200, 0));
			draw::begin_polygon();
			for (px, py) in points {
				draw::vertex(f64::from(px), f64::from(py));
			}
			draw::end_polygon();
			draw::set_draw_color(Color::Black);
			draw::begin_loop();
			for (px, py) in points {
				draw::vertex(f64::from(px), f64::from(py));
			}
			draw::end_loop();
			glyph(x, y + 4, "!", Color::Black);
		}
	}
}

/// A filled disc that fills the icon box at (x, y).
fn disc(x: i32, y: i32, color: Color) {
	draw::set_draw_color(color);
	draw::draw_pie(x, y, ICON_SIZE, ICON_SIZE, 0.0, 360.0);
}

/// A bold glyph centered in the 32 x 32 icon box at (x, y), in the top 28 px of it.
fn glyph(x: i32, y: i32, text: &str, color: Color) {
	draw::set_draw_color(color);
	draw::set_font(Font::HelveticaBold, 22);
	draw::draw_text2(text, x, y, ICON_SIZE, ICON_SIZE - 4, Align::Center);
}

/// The lines of `text` word-wrapped to `max_w`, where `measure` gives the width of a string in pixels. A
/// newline in `text` starts a new line.
fn wrap_lines(text: &str, max_w: i32, measure: &dyn Fn(&str) -> i32) -> Vec<String> {
	let mut out = Vec::new();
	for para in text.split('\n') {
		let mut line = String::new();
		for word in para.split(' ') {
			let candidate = if line.is_empty() {
				word.to_string()
			} else {
				format!("{line} {word}")
			};
			if line.is_empty() || measure(&candidate) <= max_w {
				line = candidate;
			} else {
				out.push(std::mem::take(&mut line));
				line = word.to_string();
			}
		}
		out.push(line);
	}
	out
}

/// The width of a message box with a text column of `text_w` at `left`, and buttons `buttons_w` wide: the
/// text with its margin, or the buttons with theirs, within the narrowest and the widest box.
fn message_width(left: i32, text_w: i32, buttons_w: i32) -> i32 {
	(left + text_w + MARGIN)
		.max(buttons_w + 2 * MARGIN)
		.clamp(BOX_MIN_W, BOX_MAX_W)
}

#[cfg(test)]
mod tests {
	#[test]
	fn message_text_wraps_and_the_box_fits_it() {
		// Ten pixels per character: "aaa bbb" is 70 px, so it fits a 70 px column and "ccc" goes to a new line.
		let chars = |s: &str| s.len() as i32 * 10;
		assert_eq!(
			super::wrap_lines("aaa bbb ccc\nd", 70, &chars),
			vec!["aaa bbb", "ccc", "d"]
		);
		assert_eq!(super::message_width(53, 100, 0), 230);
		assert_eq!(super::message_width(53, 400, 0), 420);
		assert_eq!(super::message_width(11, 200, 3 * 75 + 2 * 6), 259);
	}
}
