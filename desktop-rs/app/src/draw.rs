//! Device-pixel drawing and the Windows classic edges.
//!
//! FLTK maps each logical pixel to 1 or 2 device pixels at 125% and 150%, so its 1 px lines come out
//! uneven. While a [Dev] lives, FLTK draws at scale 1: positions are logical values times the scale,
//! rounded, and each Palm line is a whole number of device pixels wide (phase 0 of the port measured it).

use fltk::app;
use fltk::draw;
use fltk::enums::{Color, FrameType};

/// Device-pixel drawing for custom views and edges.
pub struct Dev {
	/// Device pixels per logical pixel for the coordinates: the display scale, or 1 where the override
	/// has no effect.
	pub s: f32,
	/// The scale to restore at the end.
	saved: f32,
}

impl Dev {
	pub fn begin() -> Dev {
		let saved = draw::override_scale();
		// The Cairo driver (Linux) applies the override only on the screen. On an offscreen or print
		// surface a second call still returns the scale; then draw in logical pixels and let Cairo scale.
		let s = if draw::override_scale() == 1.0 { saved } else { 1.0 };
		Dev { s, saved }
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
		draw::restore_scale(self.saved);
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
}
