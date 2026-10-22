//! Phase 0 spike: the Palm Desktop week grid, a To Do table, a text field, a menu bar, the 3.1.1 icons
//! and one print, to judge how sharp FLTK draws them at 100%, 125% and 150% display scale.
//!
//! View > Scale switches the FLTK scale at run time. `--shot out.ppm` draws the window offscreen at the
//! current scale (FLTK_SCALING_FACTOR sets it at start), saves it and exits. It tests the device-pixel drawing on
//! Windows only: Cairo applies that drawing only on the screen, so CI captures the X screen on Linux.
//! `--fonts` lists the font names.

use fltk::{
	app, button, draw,
	enums::{Align, Color, ColorDepth, Event, Font, FrameType, Shortcut},
	frame, image, input, menu,
	prelude::*,
	printer, surface, table, window,
};

/// The Windows 3D face gray that Swing's classic look reads on Windows 10 and 11.
const PANEL: Color = Color::from_rgb(240, 240, 240);
/// The 3D shadow gray of a raised cell.
const SHADOW: Color = Color::from_rgb(160, 160, 160);
/// The hour lines of the Day and Week grids: gray 128, as in Palm Desktop.
const HOUR_LINE: Color = Color::from_rgb(128, 128, 128);
const SELECT: Color = Color::from_rgb(0, 120, 215);
const YELLOW: Color = Color::from_rgb(255, 255, 0);

// The week grid metrics of WeekView.kt.
const HOUR_H: i32 = 38;
const TIME_W: i32 = 52;
const HEAD_H: i32 = 20;
const UNTIMED_H: i32 = 20;
const COL_W: i32 = 60;
const FIRST_HOUR: i32 = 8;
const HOURS: i32 = 9;

/// The Palm Desktop fonts of Theme.kt, by role.
#[derive(Clone, Copy)]
struct Fonts {
	/// Microsoft Sans Serif, the dialog font.
	sans: Font,
	/// Arial, the view and title font.
	arial: Font,
	arial_bold: Font,
}

/// The first installed font of `names`, else `fallback`. FLTK names a Windows face with a style
/// prefix (" " plain, "B" bold) and a Pango face by its family and style words.
fn pick(names: &[&str], fallback: Font) -> Font {
	let installed = app::get_font_names();
	names
		.iter()
		.find_map(|n| installed.iter().position(|f| f == n))
		.map(Font::by_index)
		.unwrap_or(fallback)
}

fn load_fonts() -> Fonts {
	let arial = pick(&[" Arial", "Arial", "Liberation Sans"], Font::Helvetica);
	let arial_bold = pick(&["BArial", "Arial Bold", "Liberation Sans Bold"], Font::HelveticaBold);
	Fonts {
		sans: pick(&[" Microsoft Sans Serif", "Microsoft Sans Serif"], arial),
		arial,
		arial_bold,
	}
}

/// Device-pixel drawing for the custom views. While a `Dev` lives, FLTK draws at scale 1, so each line is
/// a whole number of device pixels wide at every display scale. Positions are logical values times the
/// scale, rounded; line widths are 1 device pixel below scale 2, then whole multiples.
struct Dev {
	/// Device pixels per logical pixel for the coordinates: the display scale, or 1 where the override has no effect.
	s: f32,
	/// The scale to restore at the end.
	saved: f32,
}

impl Dev {
	fn begin() -> Dev {
		let saved = draw::override_scale();
		// The Cairo driver (Linux) applies the override only on the screen. On an offscreen or print surface
		// a second call still returns the scale; then draw in logical pixels and let Cairo scale them.
		let s = if draw::override_scale() == 1.0 { saved } else { 1.0 };
		Dev { s, saved }
	}

	/// A logical coordinate in device pixels.
	fn p(&self, v: i32) -> i32 {
		(v as f32 * self.s).round() as i32
	}

	/// The width of a 1 px Palm line, in device pixels.
	fn lw(&self) -> i32 {
		(self.s.floor() as i32).max(1)
	}

	/// A horizontal line from x1 to x2 inclusive.
	fn hline(&self, x1: i32, y: i32, x2: i32) {
		draw::draw_rectf(self.p(x1), self.p(y), self.p(x2) - self.p(x1) + self.lw(), self.lw());
	}

	/// A vertical line from y1 to y2 inclusive.
	fn vline(&self, x: i32, y1: i32, y2: i32) {
		draw::draw_rectf(self.p(x), self.p(y1), self.lw(), self.p(y2) - self.p(y1) + self.lw());
	}

	fn fill(&self, x: i32, y: i32, w: i32, h: i32, c: Color) {
		draw::draw_rect_fill(
			self.p(x),
			self.p(y),
			self.p(x + w) - self.p(x),
			self.p(y + h) - self.p(y),
			c,
		);
	}

	/// The outline of a box, `lw` device pixels wide, inside its bounds.
	fn frame(&self, x: i32, y: i32, w: i32, h: i32) {
		let (x1, y1, x2, y2, lw) = (self.p(x), self.p(y), self.p(x + w), self.p(y + h), self.lw());
		draw::draw_rectf(x1, y1, x2 - x1, lw);
		draw::draw_rectf(x1, y2 - lw, x2 - x1, lw);
		draw::draw_rectf(x1, y1, lw, y2 - y1);
		draw::draw_rectf(x2 - lw, y1, lw, y2 - y1);
	}

	fn font(&self, face: Font, size: i32) {
		draw::set_font(face, (size as f32 * self.s).round() as i32);
	}

	fn text(&self, s: &str, x: i32, y: i32, w: i32, h: i32, align: Align) {
		draw::draw_text2(
			s,
			self.p(x),
			self.p(y),
			self.p(x + w) - self.p(x),
			self.p(y + h) - self.p(y),
			align,
		);
	}

	fn clip(&self, x: i32, y: i32, w: i32, h: i32) {
		draw::push_clip(
			self.p(x),
			self.p(y),
			self.p(x + w) - self.p(x),
			self.p(y + h) - self.p(y),
		);
	}
}

impl Drop for Dev {
	fn drop(&mut self) {
		draw::restore_scale(self.saved);
	}
}

/// A raised cell as Palm Desktop draws each hour of the time column.
fn raised_cell(d: &Dev, x: i32, y: i32, w: i32, h: i32) {
	d.fill(x, y, w, h, PANEL);
	draw::set_draw_color(Color::White);
	d.hline(x, y, x + w - 1);
	draw::set_draw_color(SHADOW);
	d.hline(x, y + h - 1, x + w - 1);
}

/// The Palm untimed-row mark: a diamond of 7 rows of Palm pixels, centered on (cx, cy).
fn diamond(d: &Dev, cx: i32, cy: i32) {
	let (k, cx, cy) = (d.lw(), d.p(cx), d.p(cy));
	for dy in -3..=3i32 {
		let half = (3 - dy.abs()) * k;
		draw::draw_rectf(cx - half, cy + dy * k, 2 * half + k, k);
	}
}

/// The Day view event fill: the Palm Desktop checkerboard of yellow and white Palm pixels.
fn checkerboard(d: &Dev, x: i32, y: i32, w: i32, h: i32) {
	d.fill(x, y, w, h, Color::White);
	draw::set_draw_color(YELLOW);
	let (k, x1, y1, x2, y2) = (d.lw(), d.p(x), d.p(y), d.p(x + w), d.p(y + h));
	let mut py = y1;
	while py < y2 {
		let mut px = x1 + ((py - y1) / k % 2) * k;
		while px < x2 {
			draw::draw_rectf(px, py, k.min(x2 - px), k.min(y2 - py));
			px += 2 * k;
		}
		py += k;
	}
}

fn draw_week(f: &frame::Frame, fonts: Fonts) {
	let (x0, y0, w, h) = (f.x(), f.y(), f.w(), f.h());
	let d = Dev::begin();
	d.clip(x0, y0, w, h);
	d.fill(x0, y0, w, h, Color::White);

	d.font(fonts.arial_bold, 13);
	draw::set_draw_color(Color::Black);
	for (i, day) in ["Sun 18", "Mon 19", "Tue 20", "Wed 21", "Thu 22", "Fri 23", "Sat 24"]
		.iter()
		.enumerate()
	{
		d.text(day, x0 + TIME_W + i as i32 * COL_W, y0, COL_W, HEAD_H, Align::Center);
	}
	let grid_top = y0 + HEAD_H;
	let hours_top = grid_top + UNTIMED_H;

	raised_cell(&d, x0, grid_top, TIME_W, UNTIMED_H);
	draw::set_draw_color(Color::Black);
	diamond(&d, x0 + TIME_W / 2, grid_top + UNTIMED_H / 2);

	d.font(fonts.sans, 11);
	for hr in 0..HOURS {
		let y = hours_top + hr * HOUR_H;
		raised_cell(&d, x0, y, TIME_W, HOUR_H);
		draw::set_draw_color(Color::Black);
		let hour = FIRST_HOUR + hr;
		let label = format!("{}:00", if hour > 12 { hour - 12 } else { hour });
		d.text(&label, x0, y + 2, TIME_W - 10, 14, Align::Right);
		let mid = y + HOUR_H / 2;
		d.hline(x0 + TIME_W - 7, mid, x0 + TIME_W - 3);
		draw::set_draw_color(HOUR_LINE);
		d.hline(x0 + TIME_W, y, x0 + w - 1);
	}

	draw::set_draw_color(Color::Black);
	for i in 0..=7 {
		d.vline(x0 + TIME_W + i * COL_W, y0, y0 + h - 1);
	}
	d.hline(x0 + TIME_W, grid_top, x0 + w - 1);
	d.hline(x0 + TIME_W, hours_top, x0 + w - 1);

	// Tuesday 9:00 to 10:30.
	let ex = x0 + TIME_W + 2 * COL_W + 2;
	let ey = hours_top + HOUR_H + 2;
	let (ew, eh) = (COL_W - 3, HOUR_H * 3 / 2 - 3);
	checkerboard(&d, ex, ey, ew, eh);
	draw::set_draw_color(Color::Black);
	d.frame(ex, ey, ew, eh);
	d.font(fonts.arial, 13);
	d.text(
		"Staff meeting",
		ex + 3,
		ey + 2,
		ew - 6,
		eh - 4,
		Align::TopLeft | Align::Wrap | Align::Inside,
	);
	draw::pop_clip();
}

/// The plain black To Do check box of Palm Desktop.
fn check_box(x: i32, y: i32, checked: bool) {
	draw::draw_rect_fill(x + 1, y + 1, 11, 11, Color::White);
	draw::set_draw_color(Color::Black);
	draw::draw_rect(x + 1, y + 1, 11, 11);
	if checked {
		for d in 0..2 {
			draw::draw_line(x + 3, y + 6 + d, x + 5, y + 8 + d);
			draw::draw_line(x + 5, y + 8 + d, x + 9, y + 4 + d);
		}
	}
}

const TODOS: [(bool, u8, &str); 8] = [
	(false, 1, "Call Western Refrigeration"),
	(true, 1, "Order VFD for compressor 3"),
	(false, 2, "Update the evap trend screen"),
	(false, 2, "Renew the sync certificate"),
	(true, 3, "Back up the job folder"),
	(false, 3, "Review condenser submittal"),
	(false, 4, "Replace the shop printer toner"),
	(false, 5, "Read the Palm III manual"),
];

fn todo_table(x: i32, y: i32, w: i32, h: i32, fonts: Fonts) -> table::TableRow {
	let mut t = table::TableRow::new(x, y, w, h, None);
	t.set_rows(TODOS.len() as i32);
	t.set_cols(3);
	t.set_row_height_all(18);
	t.set_col_width(0, 18);
	t.set_col_width(1, 16);
	t.set_col_width(2, w - 18 - 16 - 4);
	t.set_frame(FrameType::DownBox);
	t.set_color(Color::White);
	t.set_type(table::TableRowSelectMode::Single);
	t.end();
	t.draw_cell(move |t, ctx, row, col, x, y, w, h| {
		if ctx != table::TableContext::Cell {
			return;
		}
		let selected = t.row_selected(row);
		let (done, priority, text) = TODOS[row as usize];
		draw::draw_rect_fill(x, y, w, h, if selected { SELECT } else { Color::White });
		draw::set_draw_color(if selected { Color::White } else { Color::Black });
		match col {
			0 => check_box(x + 2, y + 2, done),
			1 => {
				draw::set_font(fonts.sans, 11);
				draw::draw_text2(&priority.to_string(), x, y, w, h, Align::Center);
			}
			_ => {
				draw::set_font(fonts.sans, 11);
				draw::draw_text2(text, x + 2, y, w - 4, h, Align::Left | Align::Clip);
			}
		}
	});
	t
}

/// A flat toolbar button with a 3.1.1 bitmap.
fn tool_button(x: i32, png: &[u8], tip: &str) -> button::Button {
	let mut b = button::Button::new(x, 24, 24, 22, None);
	b.set_frame(FrameType::FlatBox);
	b.set_down_frame(FrameType::ThinDownBox);
	b.set_image(Some(image::PngImage::from_data(png).expect("icon")));
	b.set_tooltip(tip);
	b
}

/// A launcher button: the 24 px app icon over its name.
fn launcher_button(y: i32, png: &[u8], name: &str, fonts: Fonts) -> button::Button {
	let mut b = button::Button::new(4, y, 56, 52, None);
	b.set_label(name);
	b.set_label_font(fonts.sans);
	b.set_label_size(11);
	b.set_frame(FrameType::FlatBox);
	b.set_down_frame(FrameType::DownBox);
	b.set_align(Align::Inside | Align::Bottom);
	b.set_image(Some(image::PngImage::from_data(png).expect("icon")));
	b
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

fn status_text() -> String {
	format!("Scale {:.2}, FLTK {}", app::screen_scale(0), app::version_str())
}

/// Prints the week grid on one page.
fn print_week(week: &frame::Frame) {
	let mut p = printer::Printer::default();
	if p.begin_job(1).is_ok() {
		if p.begin_page().is_ok() {
			p.print_widget(week, 0, 0);
			let _ = p.end_page();
		}
		p.end_job();
	}
}

/// Draws `win` offscreen at the current scale and writes it to `path` as a binary PPM.
fn save_shot(win: &window::Window, path: &str) -> std::io::Result<()> {
	let surf = surface::ImageSurface::new(win.w(), win.h(), true);
	surface::ImageSurface::push_current(&surf);
	surf.draw(win, 0, 0);
	surface::ImageSurface::pop_current();
	let img = surf.image().expect("surface image");
	let (w, h) = (img.data_w() as usize, img.data_h() as usize);
	let channels = match img.depth() {
		ColorDepth::L8 => 1,
		ColorDepth::La8 => 2,
		ColorDepth::Rgb8 => 3,
		ColorDepth::Rgba8 => 4,
	};
	let data = img.to_rgb_data();
	let mut out = format!("P6\n{w} {h}\n255\n").into_bytes();
	for px in data.chunks(channels) {
		match channels {
			1 | 2 => out.extend_from_slice(&[px[0], px[0], px[0]]),
			_ => out.extend_from_slice(&px[..3]),
		}
	}
	std::fs::write(path, out)
}

fn main() {
	let args: Vec<String> = std::env::args().collect();
	let a = app::App::default().with_scheme(app::Scheme::Base).load_system_fonts();
	if args.iter().any(|x| x == "--fonts") {
		for f in app::get_font_names() {
			println!("{f:?}");
		}
		return;
	}
	image::Image::set_scaling_algorithm(image::RgbScaling::Nearest);
	app::background(240, 240, 240);
	app::set_visible_focus(false);
	let fonts = load_fonts();

	let mut win = window::Window::new(60, 60, 760, 520, "Palm Desktop spike");
	win.set_color(PANEL);

	let mut bar = menu::MenuBar::new(0, 0, 760, 22, None);
	bar.set_text_font(fonts.sans);
	bar.set_text_size(11);
	bar.set_frame(FrameType::FlatBox);

	let mut toolbar_x = 4;
	for (png, tip) in [
		(&icon!("new")[..], "New"),
		(&icon!("save")[..], "Save"),
		(&icon!("print")[..], "Print"),
		(&icon!("cut")[..], "Cut"),
		(&icon!("copy")[..], "Copy"),
		(&icon!("paste")[..], "Paste"),
		(&icon!("undo")[..], "Undo"),
		(&icon!("find")[..], "Find"),
	] {
		tool_button(toolbar_x, png, tip);
		toolbar_x += 26;
	}

	launcher_button(52, icon!("date"), "Date", fonts);
	launcher_button(108, icon!("address"), "Address", fonts);
	launcher_button(164, icon!("todo"), "To Do", fonts);
	launcher_button(220, icon!("memo"), "Memo", fonts);

	let mut week = frame::Frame::new(
		70,
		54,
		TIME_W + 7 * COL_W + 1,
		HEAD_H + UNTIMED_H + HOURS * HOUR_H + 1,
		None,
	);
	week.draw(move |f| draw_week(f, fonts));

	todo_table(552, 54, 200, 220, fonts);

	let mut field = input::Input::new(552, 300, 200, 22, None);
	field.set_label("Description:");
	field.set_label_font(fonts.sans);
	field.set_label_size(11);
	field.set_align(Align::TopLeft);
	field.set_text_font(fonts.arial);
	field.set_text_size(13);
	field.set_value("Staff meeting");

	for (x, label) in [(552, "New To Do"), (652, "Details...")] {
		let mut b = button::Button::new(x, 332, 90, 23, None);
		b.set_label(label);
		b.set_label_font(fonts.sans);
		b.set_label_size(11);
	}

	let mut status = frame::Frame::new(0, 498, 760, 22, None);
	status.set_frame(FrameType::ThinDownBox);
	status.set_align(Align::Left | Align::Inside);
	status.set_label_font(fonts.sans);
	status.set_label_size(12);
	status.set_label(&status_text());

	win.end();
	win.make_resizable(false);

	{
		let week = week.clone();
		bar.add(
			"&File/&Print...\t",
			Shortcut::Ctrl | 'p',
			menu::MenuFlag::Normal,
			move |_| print_week(&week),
		);
	}
	bar.add("&File/E&xit", Shortcut::None, menu::MenuFlag::Normal, |_| app::quit());
	for (label, factor) in [
		("&View/Scale &100%", 1.0f32),
		("&View/Scale 1&25%", 1.25),
		("&View/Scale 1&50%", 1.5),
	] {
		let mut status = status.clone();
		bar.add(label, Shortcut::None, menu::MenuFlag::Normal, move |_| {
			app::set_screen_scale(0, factor);
			status.set_label(&status_text());
		});
	}
	win.handle(|_, ev| ev == Event::Shortcut && app::event_key() == fltk::enums::Key::Escape);

	win.show();
	if let Some(i) = args.iter().position(|x| x == "--shot") {
		let path = args.get(i + 1).cloned().unwrap_or_else(|| "shot.ppm".into());
		let win = win.clone();
		app::add_timeout3(0.5, move |_| {
			if let Err(e) = save_shot(&win, &path) {
				eprintln!("shot: {e}");
			}
			app::quit();
		});
	}
	a.run().unwrap();
}
