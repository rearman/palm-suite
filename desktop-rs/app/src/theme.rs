//! The Palm Desktop look, as Theme.kt: the Windows classic colors, the Palm Desktop fonts and the 3.1.1
//! icons. The fonts fall back to Liberation Sans, which has the widths of Arial, where the Windows fonts
//! are missing.

use fltk::app;
use fltk::draw;
use fltk::enums::ColorDepth;
use fltk::enums::{Color, Font};
use fltk::image::{PngImage, RgbImage};
use fltk::prelude::*;
use std::sync::OnceLock;

/// The Windows 3D face gray that Swing's classic look reads on Windows 10 and 11.
pub const PANEL: Color = Color::from_rgb(240, 240, 240);
/// The 3D shadow gray of a raised cell.
pub const SHADOW: Color = Color::from_rgb(160, 160, 160);
/// The Windows application workspace gray around the Date view, as in Palm Desktop.
pub const WORKSPACE: Color = Color::from_rgb(171, 171, 171);
pub const SELECT: Color = Color::from_rgb(0, 120, 215);
/// The height of a menu item in device-independent pixels, as a Windows 11 menu item.
pub(crate) const MENU_ITEM_H: i32 = 22;

/// The Palm Desktop fonts by role. Font::Helvetica is Microsoft Sans Serif, the dialog font, so every
/// widget without its own font uses it.
#[derive(Clone, Copy)]
pub struct Fonts {
	/// Microsoft Sans Serif, the dialog font (8 pt, 11 px).
	pub sans: Font,
	pub sans_bold: Font,
	/// Arial, the view, title and caption font.
	pub arial: Font,
	pub arial_bold: Font,
	/// Segoe UI, the launcher font.
	pub segoe: Font,
}

static FONTS: OnceLock<Fonts> = OnceLock::new();

pub fn fonts() -> Fonts {
	*FONTS.get().expect("theme::install runs first")
}

/// The first installed face of `names`. FLTK names a Windows face with a style prefix (" " plain, "B"
/// bold) and a Pango face by its family and style words.
fn face(names: &[&str]) -> Option<String> {
	let installed = app::get_font_names();
	names
		.iter()
		.find(|n| installed.iter().any(|f| f == *n))
		.map(|n| n.to_string())
}

fn font_of(names: &[&str], fallback: Font) -> Font {
	face(names).map_or(fallback, |n| Font::by_name(&n))
}

/// Sets the colors and the fonts. Call it once, after the app starts and before any window is built.
pub fn install() {
	app::set_scheme(app::Scheme::Base);
	app::background(240, 240, 240);
	app::background2(255, 255, 255);
	app::foreground(0, 0, 0);
	app::set_selection_color(0, 120, 215);
	crate::draw::install_classic_boxes();
	let arial = font_of(&[" Arial", "Arial", "Liberation Sans"], Font::Helvetica);
	let arial_bold = font_of(&["BArial", "Arial Bold", "Liberation Sans Bold"], Font::HelveticaBold);
	let segoe = font_of(&[" Segoe UI", "Segoe UI"], Font::Helvetica);
	// The dialog font replaces Helvetica, the default of every widget.
	if let Some(n) = face(&[" Microsoft Sans Serif", "Microsoft Sans Serif", "Liberation Sans"]) {
		Font::set_font(Font::Helvetica, &n);
	}
	if let Some(n) = face(&[
		"BMicrosoft Sans Serif",
		"Microsoft Sans Serif Bold",
		"Liberation Sans Bold",
	]) {
		Font::set_font(Font::HelveticaBold, &n);
	}
	app::set_font_size(11);
	// A popup item is the text height plus the menu line spacing, so the spacing makes it MENU_ITEM_H tall.
	draw::set_font(Font::Helvetica, 11);
	app::set_menu_linespacing((MENU_ITEM_H - draw::height()).max(0));
	let _ = FONTS.set(Fonts {
		sans: Font::Helvetica,
		sans_bold: Font::HelveticaBold,
		arial,
		arial_bold,
		segoe,
	});
}

macro_rules! icon {
	($name:literal) => {
		include_bytes!(concat!(
			env!("CARGO_MANIFEST_DIR"),
			"/../../desktop/src/main/resources/com/rearman/palmdesktop/icons/",
			$name,
			".png"
		))
	};
}

/// The Palm Desktop 3.1.1 bitmaps. See the Palm Desktop artwork note in README.md.
pub fn icon(name: &str) -> PngImage {
	let bytes: &[u8] = match name {
		"date" => icon!("date"),
		"address" => icon!("address"),
		"todo" => icon!("todo"),
		"memo" => icon!("memo"),
		"save" => icon!("save"),
		"print" => icon!("print"),
		"cut" => icon!("cut"),
		"copy" => icon!("copy"),
		"paste" => icon!("paste"),
		"undo" => icon!("undo"),
		"find" => icon!("find"),
		"new" => icon!("new"),
		"private" => icon!("private"),
		"note" => icon!("note"),
		"app48" => icon!("app48"),
		"repeat" => icon!("repeat"),
		"alarm" => icon!("alarm"),
		"clock" => icon!("clock"),
		"calendar" => icon!("calendar"),
		_ => panic!("no icon {name}"),
	};
	PngImage::from_data(bytes).expect("the icons are valid PNG files")
}

/// The inactive form of an icon, as Swing's GrayFilter makes it for a disabled button: the gray value of
/// each pixel, brightened halfway to white, with its alpha kept.
pub fn gray_icon(name: &str) -> RgbImage {
	let img = icon(name);
	let depth = img.depth();
	let channels = match depth {
		ColorDepth::L8 => 1,
		ColorDepth::La8 => 2,
		ColorDepth::Rgb8 => 3,
		ColorDepth::Rgba8 => 4,
	};
	let mut data = img.to_rgb_data();
	if channels >= 3 {
		for px in data.chunks_mut(channels) {
			let g = (0.30 * px[0] as f32 + 0.59 * px[1] as f32 + 0.11 * px[2] as f32) as i32;
			let g = (255 - (255 - g) / 2) as u8;
			px[..3].copy_from_slice(&[g, g, g]);
		}
	}
	RgbImage::new(&data, img.data_w(), img.data_h(), depth).expect("the icon sizes match their data")
}
