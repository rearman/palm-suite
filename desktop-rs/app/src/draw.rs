//! Device-pixel drawing and the Windows classic edges.
//!
//! FLTK maps each logical pixel to 1 or 2 device pixels at 125% and 150%, so its 1 px lines come out
//! uneven. While a [Dev] lives, FLTK draws at scale 1: positions are logical values times the scale,
//! rounded, and each Palm line is a whole number of device pixels wide (phase 0 of the port measured it).

use crate::theme;
use fltk::app;
use fltk::button::{Button, ButtonType, CheckButton, RoundButton};
use fltk::draw;
use fltk::enums::{Color, Font, FrameType};
use fltk::prelude::*;

thread_local! {
	/// The logical clips of the open [Clip] guards, innermost last.
	static CLIPS: std::cell::RefCell<Vec<(i32, i32, i32, i32)>> = const { std::cell::RefCell::new(Vec::new()) };
}

/// A clip in logical pixels for as long as the guard lives. FLTK keeps its clips in logical pixels and
/// selects them at the scale of the moment of a push or a pop, so under the override of a [Dev] they come
/// out at scale 1. A Dev therefore clips to the innermost Clip in device pixels of its own.
pub struct Clip;

impl Clip {
	pub fn push(x: i32, y: i32, w: i32, h: i32) -> Clip {
		draw::push_clip(x, y, w, h);
		CLIPS.with(|c| c.borrow_mut().push((x, y, w, h)));
		Clip
	}

	/// A clip of a table cell (x, y, w, h), cut to the inside of the edge of its table `t`: the table clips
	/// its cells, and a Dev does not see that clip.
	pub fn cell<T: fltk::prelude::WidgetExt>(t: &T, x: i32, y: i32, w: i32, h: i32) -> Clip {
		let (tx, ty, tr, tb) = (t.x() + 2, t.y() + 2, t.x() + t.w() - 2, t.y() + t.h() - 2);
		let (x0, y0) = (x.max(tx), y.max(ty));
		let (x1, y1) = ((x + w).min(tr), (y + h).min(tb));
		Clip::push(x0, y0, (x1 - x0).max(0), (y1 - y0).max(0))
	}
}

impl Drop for Clip {
	fn drop(&mut self) {
		CLIPS.with(|c| c.borrow_mut().pop());
		draw::pop_clip();
	}
}

/// Device-pixel drawing for custom views and edges.
pub struct Dev {
	/// Device pixels per logical pixel for the coordinates: the display scale, or 1 where the override
	/// has no effect.
	pub s: f32,
	/// The scale to restore at the end.
	saved: f32,
	/// The clips the Dev pushed in device pixels, to pop at the end.
	pushed: u8,
}

impl Dev {
	pub fn begin() -> Dev {
		let saved = draw::override_scale();
		// The Cairo driver (Linux) applies the override only on the screen. On an offscreen or print
		// surface a second call still returns the scale; then draw in logical pixels and let Cairo scale.
		let s = if draw::override_scale() == 1.0 { saved } else { 1.0 };
		let mut d = Dev { s, saved, pushed: 0 };
		if s != 1.0 {
			// No clip in logical pixels may stay on top while the scale is 1: a push or a pop inside would
			// select it at the wrong size.
			draw::push_no_clip();
			d.pushed = 1;
			if let Some((x, y, w, h)) = CLIPS.with(|c| c.borrow().last().copied()) {
				let (dx, dy, dw, dh) = d.rect(x, y, w, h);
				draw::push_clip(dx, dy, dw, dh);
				d.pushed = 2;
			}
		}
		d
	}

	/// A logical coordinate in device pixels.
	pub fn p(&self, v: i32) -> i32 {
		(v as f32 * self.s).round() as i32
	}

	/// The width of a 1 px Palm line, in device pixels: 1 below scale 2, then whole multiples.
	pub fn lw(&self) -> i32 {
		(self.s.floor() as i32).max(1)
	}

	/// A logical rectangle in device pixels: (x, y, w, h).
	pub fn rect(&self, x: i32, y: i32, w: i32, h: i32) -> (i32, i32, i32, i32) {
		let (x1, y1) = (self.p(x), self.p(y));
		(x1, y1, self.p(x + w) - x1, self.p(y + h) - y1)
	}
}

impl Drop for Dev {
	fn drop(&mut self) {
		for _ in 0..self.pushed {
			draw::pop_clip();
		}
		draw::restore_scale(self.saved);
		// The pops selected the logical clip at scale 1; select it again at the scale.
		if self.pushed > 0 {
			draw::restore_clip();
		}
	}
}

/// The width of `s` in the current font, in whole pixels.
pub fn text_w(s: &str) -> i32 {
	draw::width(s).ceil() as i32
}

/// `text` cut to `w` px with "..." at its end, as Palm Desktop cuts a list column.
pub fn fit(text: &str, w: i32, measure: &dyn Fn(&str) -> i32) -> String {
	if measure(text) <= w {
		return text.to_string();
	}
	let chars: Vec<char> = text.chars().collect();
	let mut n = chars.len();
	loop {
		let cut = chars[..n].iter().collect::<String>() + "...";
		if n == 0 || measure(&cut) <= w {
			return cut;
		}
		n -= 1;
	}
}

/// The Windows system colors that Swing's classic look reads on Windows 10 and 11.
pub const HIGHLIGHT: Color = Color::from_rgb(255, 255, 255);
pub const LIGHT: Color = Color::from_rgb(227, 227, 227);
pub const SHADOW: Color = Color::from_rgb(160, 160, 160);
pub const DARK_SHADOW: Color = Color::from_rgb(105, 105, 105);

/// Draws rings of a box from the outside in, in device pixels: each ring has its top-left and its
/// bottom-right color. Then fills the inside with `fill` when it is Some.
fn rings(x: i32, y: i32, w: i32, h: i32, rings: &[(Color, Color)], fill: Option<Color>) {
	let d = Dev::begin();
	let (mut x, mut y, mut w, mut h) = d.rect(x, y, w, h);
	let k = d.lw();
	for &(top_left, bottom_right) in rings {
		if w <= 0 || h <= 0 {
			return;
		}
		draw::set_draw_color(bottom_right);
		draw::draw_rectf(x, y + h - k, w, k);
		draw::draw_rectf(x + w - k, y, k, h);
		draw::set_draw_color(top_left);
		draw::draw_rectf(x, y, w - k, k);
		draw::draw_rectf(x, y, k, h - k);
		x += k;
		y += k;
		w -= 2 * k;
		h -= 2 * k;
	}
	if let Some(c) = fill {
		if w > 0 && h > 0 {
			draw::draw_rect_fill(x, y, w, h, c);
		}
	}
}

/// A raised button or panel. FLTK passes the fill color, already grayed for an inactive widget.
fn up_box(x: i32, y: i32, w: i32, h: i32, c: Color) {
	rings(x, y, w, h, &[(HIGHLIGHT, DARK_SHADOW), (LIGHT, SHADOW)], Some(c));
}

fn up_frame(x: i32, y: i32, w: i32, h: i32, _c: Color) {
	rings(x, y, w, h, &[(HIGHLIGHT, DARK_SHADOW), (LIGHT, SHADOW)], None);
}

fn down_box(x: i32, y: i32, w: i32, h: i32, c: Color) {
	rings(x, y, w, h, &[(SHADOW, HIGHLIGHT), (DARK_SHADOW, LIGHT)], Some(c));
}

fn down_frame(x: i32, y: i32, w: i32, h: i32, _c: Color) {
	rings(x, y, w, h, &[(SHADOW, HIGHLIGHT), (DARK_SHADOW, LIGHT)], None);
}

fn thin_up_box(x: i32, y: i32, w: i32, h: i32, c: Color) {
	rings(x, y, w, h, &[(HIGHLIGHT, SHADOW)], Some(c));
}

fn thin_down_box(x: i32, y: i32, w: i32, h: i32, c: Color) {
	rings(x, y, w, h, &[(SHADOW, HIGHLIGHT)], Some(c));
}

/// The etched groove of a status bar or a group: a shadow line, then a highlight line inside it.
fn engraved_box(x: i32, y: i32, w: i32, h: i32, c: Color) {
	rings(x, y, w, h, &[(SHADOW, HIGHLIGHT), (HIGHLIGHT, SHADOW)], Some(c));
}

/// Replaces FLTK's raised, sunken and etched boxes with the Windows classic edges. The edge widths stay
/// 2 px (1 px for the thin boxes), so the widget insets FLTK computes are unchanged.
pub fn install_classic_boxes() {
	app::set_frame_type_cb(FrameType::UpBox, up_box, 2, 2, 4, 4);
	app::set_frame_type_cb(FrameType::UpFrame, up_frame, 2, 2, 4, 4);
	app::set_frame_type_cb(FrameType::DownBox, down_box, 2, 2, 4, 4);
	app::set_frame_type_cb(FrameType::DownFrame, down_frame, 2, 2, 4, 4);
	app::set_frame_type_cb(FrameType::ThinUpBox, thin_up_box, 1, 1, 2, 2);
	app::set_frame_type_cb(FrameType::ThinDownBox, thin_down_box, 1, 1, 2, 2);
	app::set_frame_type_cb(FrameType::EngravedBox, engraved_box, 2, 2, 4, 4);
	app::set_frame_type_cb(menu_frame_type(), menu_box, 1, 1, 2, 2);
}

/// The box type of the popup frame of a menu. FrameType::FreeBoxType is FLTK's index 56, which is the oxy
/// box FL_OXY_UP_BOX; FL_FREE_BOXTYPE, the first unused index, is 68 in FLTK 1.4 (Enumerations.H).
const MENU_FRAME: i32 = 68;

fn menu_frame_type() -> FrameType {
	// Safety: 68 is a valid box index below FL_MAX_BOXTYPE (255), and FLTK accepts any index there.
	unsafe { FrameType::from_i32(MENU_FRAME) }
}

/// The popup frame of a Windows menu: a 1 px gray border around a white face. FLTK passes the menu's
/// own color, which is gray in a scheme, so the face is white here.
fn menu_box(x: i32, y: i32, w: i32, h: i32, _c: Color) {
	rings(x, y, w, h, &[(SHADOW, SHADOW)], Some(Color::White));
}

/// Makes the popup of a menu, choice or menu button look like a Windows menu: the bordered white popup,
/// Helvetica (Microsoft Sans Serif) 11 px black text and the Windows selection blue.
pub fn style_menu<M: MenuExt>(m: &mut M) {
	m.set_menu_frame(menu_frame_type());
	m.set_text_font(Font::Helvetica);
	m.set_text_size(11);
	m.set_text_color(Color::Black);
	m.set_selection_color(theme::SELECT);
}

/// The side of a check box and a radio button in logical pixels, as Windows draws them at 100%.
const BOX: i32 = 13;
const RADIO: i32 = 12;

/// The ink of a widget: black, or the gray SHADOW when the widget is inactive.
fn ink(active: bool) -> Color {
	if active {
		Color::Black
	} else {
		SHADOW
	}
}

/// Draws `text` on one line with its center at `cy`, the accelerator letter underlined. The baseline
/// comes from the font height, so the line is centered on `cy`.
fn label_at(text: &str, font: Font, size: i32, x: i32, cy: i32, c: Color) {
	let (plain, accel) = accel_split(text);
	draw::set_font(font, size);
	let h = draw::height();
	let base = cy - h / 2 + h - draw::descent();
	draw::set_draw_color(c);
	draw::draw_text(&plain, x, base);
	let Some(i) = accel else { return };
	let len = plain[i..].chars().next().map_or(0, char::len_utf8);
	let before = draw::width(&plain[..i]).ceil() as i32;
	let under = draw::width(&plain[i..i + len]).ceil() as i32;
	let d = Dev::begin();
	let (ux, uy, uw, _) = d.rect(x + before, base + 1, under, 0);
	draw::draw_rectf(ux, uy, uw, d.lw());
}

/// Splits a label at its accelerator: the text with each "&&" as one "&", and the byte index in that text
/// of the letter after the first lone "&", or None when the label has no accelerator.
fn accel_split(label: &str) -> (String, Option<usize>) {
	let mut text = String::new();
	let mut accel = None;
	let mut chars = label.chars().peekable();
	while let Some(c) = chars.next() {
		if c != '&' {
			text.push(c);
		} else if chars.peek() == Some(&'&') {
			chars.next();
			text.push('&');
		} else if accel.is_none() && chars.peek().is_some() {
			accel = Some(text.len());
		}
	}
	(text, accel)
}

/// The 7x7 check mark as pixel runs (x, y, w, h) in its box: a column of 3 px for each of the 7 columns,
/// centered on the row of `TICK_ROWS` for that column.
fn tick_rects() -> [(i32, i32, i32, i32); 7] {
	const TICK_ROWS: [i32; 7] = [3, 4, 5, 4, 3, 2, 1];
	let mut runs = [(0, 0, 0, 0); 7];
	for (c, &row) in TICK_ROWS.iter().enumerate() {
		runs[c] = (c as i32, row - 1, 1, 3);
	}
	runs
}

/// Draws the check mark at (x, y), its 7x7 box, in device pixels.
fn tick(x: i32, y: i32, c: Color) {
	let d = Dev::begin();
	draw::set_draw_color(c);
	for (dx, dy, w, h) in tick_rects() {
		let (rx, ry, rw, rh) = d.rect(x + dx, y + dy, w, h);
		draw::draw_rectf(rx, ry, rw, rh);
	}
}

/// The sunken 13x13 box of a check button, then the mark and the label.
fn draw_check(b: &mut CheckButton) {
	let (x, y, h) = (b.x(), b.y(), b.h());
	let on = b.active();
	let by = y + (h - BOX) / 2;
	let fill = if on { Color::White } else { theme::PANEL };
	down_box(x, by, BOX, BOX, fill);
	if b.value() {
		tick(x + 3, by + 3, ink(on));
	}
	label_at(&b.label(), b.label_font(), b.label_size(), x + 17, y + h / 2, ink(on));
}

/// A check box at (x, y) with `label`: a 13x13 sunken box centered on the height of the widget, a 7x7
/// check mark when set, and the label 17 px from the left. The default drawing is replaced, so no focus
/// rectangle is drawn.
pub fn check(x: i32, y: i32, w: i32, h: i32, label: &str) -> CheckButton {
	let mut b = CheckButton::new(x, y, w, h, label);
	b.set_frame(FrameType::NoBox);
	// The default draw would add FLTK's own label and focus rectangle, so it is off.
	b.super_draw(false);
	b.clear_visible_focus();
	b.draw(draw_check);
	b
}

/// The two-tone ring of a radio button at (x, y), 12 px wide, and its white face. The outer ring splits on
/// the diagonal: SHADOW on the top-left half, HIGHLIGHT on the bottom-right half; the inner ring uses
/// DARK_SHADOW and LIGHT.
fn radio_box(x: i32, y: i32, fill: Color) {
	let d = Dev::begin();
	let (dx, dy, dw, dh) = d.rect(x, y, RADIO, RADIO);
	let k = d.lw();
	half_disk(dx, dy, dw, dh, SHADOW, HIGHLIGHT);
	half_disk(dx + k, dy + k, dw - 2 * k, dh - 2 * k, DARK_SHADOW, LIGHT);
	draw::set_draw_color(fill);
	draw::draw_pie(dx + 2 * k, dy + 2 * k, dw - 4 * k, dh - 4 * k, 0.0, 360.0);
}

/// A disk in device pixels, its top-left half (45 to 225 degrees) in `top_left`, its bottom-right half in
/// `bottom_right`.
fn half_disk(x: i32, y: i32, w: i32, h: i32, top_left: Color, bottom_right: Color) {
	draw::set_draw_color(top_left);
	draw::draw_pie(x, y, w, h, 45.0, 225.0);
	draw::set_draw_color(bottom_right);
	draw::draw_pie(x, y, w, h, 225.0, 405.0);
}

/// The 4x4 dot of a set radio button, centered in its 12 px box.
fn radio_dot(x: i32, y: i32, c: Color) {
	let d = Dev::begin();
	let (dx, dy, dw, dh) = d.rect(x + 4, y + 4, 4, 4);
	draw::set_draw_color(c);
	draw::draw_pie(dx, dy, dw, dh, 0.0, 360.0);
}

/// The radio ring, the dot when set, and the label.
fn draw_radio(b: &mut RoundButton) {
	let (x, y, h) = (b.x(), b.y(), b.h());
	let on = b.active();
	let by = y + (h - RADIO) / 2;
	let fill = if on { Color::White } else { theme::PANEL };
	radio_box(x, by, fill);
	if b.value() {
		radio_dot(x, by, ink(on));
	}
	label_at(&b.label(), b.label_font(), b.label_size(), x + 17, y + h / 2, ink(on));
}

/// A round radio button at (x, y) with `label`, drawn as the Windows classic radio.
pub fn radio(x: i32, y: i32, w: i32, h: i32, label: &str) -> RoundButton {
	let mut b = RoundButton::new(x, y, w, h, label);
	b.set_type(ButtonType::Radio);
	b.set_frame(FrameType::NoBox);
	b.super_draw(false);
	b.clear_visible_focus();
	b.draw(draw_radio);
	b
}

/// The pixel rows of a 4 px wide, 7 px tall triangle, as (x offset, y, length) in its box. The base is
/// the left column for a right arrow and the right column for a left arrow; the tip is on row 3.
fn arrow_rows(right: bool) -> [(i32, i32, i32); 7] {
	const LENGTHS: [i32; 7] = [1, 2, 3, 4, 3, 2, 1];
	let mut rows = [(0, 0, 0); 7];
	for (r, &len) in LENGTHS.iter().enumerate() {
		let dx = if right { 0 } else { 4 - len };
		rows[r] = (dx, r as i32, len);
	}
	rows
}

/// Draws the arrow in its 4x7 box at (x, y), in device pixels.
fn arrow_glyph(x: i32, y: i32, right: bool, c: Color) {
	let d = Dev::begin();
	draw::set_draw_color(c);
	for (dx, dy, len) in arrow_rows(right) {
		let (rx, ry, rw, rh) = d.rect(x + dx, y + dy, len, 1);
		draw::draw_rectf(rx, ry, rw, rh);
	}
}

/// The raised push button with a black triangle centered in it, pointing right or left. The triangle is
/// gray when the button is inactive. The button is drawn down while it is pressed.
fn draw_arrow(b: &mut Button, right: bool) {
	let (x, y, w, h) = (b.x(), b.y(), b.w(), b.h());
	let frame = if b.value() {
		FrameType::DownBox
	} else {
		FrameType::UpBox
	};
	draw::draw_box(frame, x, y, w, h, b.color());
	arrow_glyph(x + (w - 4) / 2, y + (h - 7) / 2, right, ink(b.active()));
}

/// A push button at (x, y) with a triangle arrow, replacing an FLTK "@>" or "@<" glyph button.
pub fn arrow(x: i32, y: i32, w: i32, h: i32, right: bool) -> Button {
	let mut b = Button::new(x, y, w, h, None);
	b.set_frame(FrameType::UpBox);
	b.super_draw(false);
	b.clear_visible_focus();
	b.draw(move |b| draw_arrow(b, right));
	b
}

#[cfg(test)]
mod tests {
	#[test]
	fn fit_cuts_with_dots() {
		// One pixel per character.
		let m = |s: &str| s.chars().count() as i32;
		assert_eq!(super::fit("Hello", 5, &m), "Hello");
		assert_eq!(super::fit("Hello", 4, &m), "H...");
		assert_eq!(super::fit("Hello", 2, &m), "...");
	}

	#[test]
	fn arrow_is_a_4_by_7_triangle_on_its_base() {
		// The base is the left column of a right arrow and the right column of a left arrow; the tip is on row 3.
		for right in [true, false] {
			let rows = super::arrow_rows(right);
			let pixels: i32 = rows.iter().map(|r| r.2).sum();
			assert_eq!(pixels, 16);
			for (r, &(dx, dy, len)) in rows.iter().enumerate() {
				assert_eq!(dy, r as i32);
				assert!(dx >= 0 && dx + len <= 4);
			}
		}
		let right = super::arrow_rows(true);
		let left = super::arrow_rows(false);
		assert_eq!(right[3], (0, 3, 4));
		assert_eq!(left[3], (0, 3, 4));
		assert_eq!(right[0], (0, 0, 1));
		assert_eq!(left[0], (3, 0, 1));
	}

	#[test]
	fn tick_stays_in_its_7_by_7_box() {
		for (x, y, w, h) in super::tick_rects() {
			assert!(x >= 0 && x + w <= 7);
			assert!(y >= 0 && y + h <= 7);
			assert_eq!((w, h), (1, 3));
		}
	}

	#[test]
	fn accel_split_finds_the_letter() {
		assert_eq!(super::accel_split("&Print"), ("Print".to_string(), Some(0)));
		assert_eq!(super::accel_split("Save &As"), ("Save As".to_string(), Some(5)));
		assert_eq!(super::accel_split("A && B"), ("A & B".to_string(), None));
		assert_eq!(super::accel_split("Plain"), ("Plain".to_string(), None));
	}
}
