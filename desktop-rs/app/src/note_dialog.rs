//! The Note Editor of EventDialog.kt (NoteDialog): a text area that wraps its lines, under a toolbar of Cut,
//! Copy, Paste and Undo, with OK and Cancel. The To Do List and the Date Book use it, and the Note tab of Edit
//! Address uses its [NoteBox].

use crate::dialogs;
use crate::text_edit::{Field, TextAction};
use crate::theme::{gray_icon, icon};
use fltk::app;
use fltk::button::Button;
use fltk::draw;
use fltk::enums::{Align, FrameType};
use fltk::prelude::*;
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use fltk::valuator::{Scrollbar, ScrollbarType};
use fltk::window::Window;
use std::cell::Cell;
use std::rc::Rc;

/// A note box as Palm Desktop 3.1.1 draws it: a toolbar of Cut, Copy, Paste and Undo, and a text area that
/// wraps its lines, with a vertical scroll bar that is always shown. Call [NoteBox::sync] after each event,
/// which grays the buttons that do not apply and keeps the bar in step with the text.
pub struct NoteBox {
	pub area: TextEditor,
	pub buf: TextBuffer,
	pub field: Field,
	tools: Vec<(TextAction, Button)>,
	bar: Scrollbar,
	view_rows: i32,
}

impl NoteBox {
	/// The toolbar at (tool_x, tool_y), its buttons `step` px apart, and the text box in (x, y, w, h).
	pub fn new(tool_x: i32, tool_y: i32, step: i32, (x, y, w, h): (i32, i32, i32, i32)) -> NoteBox {
		let mut tools = Vec::new();
		for (i, (png, tip, a)) in [
			("cut", "Cut", TextAction::Cut),
			("copy", "Copy", TextAction::Copy),
			("paste", "Paste", TextAction::Paste),
			("undo", "Undo", TextAction::Undo),
		]
		.into_iter()
		.enumerate()
		{
			let mut b = Button::new(tool_x + step * i as i32, tool_y, 24, 24, None);
			b.set_frame(FrameType::UpBox);
			b.set_down_frame(FrameType::DownBox);
			b.set_image(Some(icon(png)));
			b.set_deimage(Some(gray_icon(png)));
			b.set_tooltip(tip);
			b.clear_visible_focus();
			tools.push((a, b));
		}
		let buf = TextBuffer::default();
		let sb_w = app::scrollbar_size();
		// The text area draws its own scroll bar only when the text overflows, so the box gets a scroll bar of
		// its own at its right, kept in step with the text area by sync.
		let mut area = TextEditor::new(x, y, w - sb_w, h, None);
		area.set_buffer(buf.clone());
		area.wrap_mode(WrapMode::AtBounds, 0);
		area.set_scrollbar_align(Align::empty());
		let field = Field::Editor(area.clone());
		field.attach_menu();
		for (a, b) in tools.iter_mut() {
			let (mut f, a) = (field.clone(), *a);
			b.set_callback(move |_| f.run(a));
		}
		draw::set_font(area.text_font(), area.text_size());
		// The display rows that fit in the text area, which keeps 2 px of frame on each side.
		let view_rows = ((h - 4) / draw::height().max(1)).max(1);
		let mut bar = Scrollbar::new(x + w - sb_w, y, sb_w, h, None);
		bar.set_type(ScrollbarType::Vertical);
		bar.set_linesize(1);
		{
			let mut ed = area.clone();
			// The text area numbers its top row from 1, the bar from 0.
			bar.set_callback(move |b| ed.scroll(b.value() as i32 + 1, 0));
		}
		NoteBox {
			area,
			buf,
			field,
			tools,
			bar,
			view_rows,
		}
	}

	/// Grays the toolbar buttons that do not apply, as the main toolbar does, and sets the bar to the top row
	/// of the text area, its thumb sized to the rows in view.
	pub fn sync(&mut self) {
		let s = self.field.state();
		for (a, b) in self.tools.iter_mut() {
			let on = match a {
				TextAction::Cut => s.cut,
				TextAction::Copy => s.copy,
				TextAction::Paste => s.paste,
				_ => s.undo,
			};
			if on != b.active() {
				if on {
					b.activate();
				} else {
					b.deactivate();
				}
			}
		}
		let rows = self.area.count_lines(0, self.buf.length(), true) + 1;
		let max = (rows - self.view_rows).max(0);
		self.bar.set_bounds(0.0, f64::from(max));
		self.bar.set_slider_size((self.view_rows as f32 / rows as f32).min(1.0));
		self.bar
			.set_value(f64::from(self.area.scroll_row() - 1).clamp(0.0, f64::from(max)));
	}
}

/// Edits `text` in a dialog named `title`. The new text on OK, None on Cancel.
pub fn edit(title: &str, text: &str) -> Option<String> {
	// The official Note Editor has a fixed 267 x 276 px client area. The text box sits between the toolbar and
	// the buttons, as in Palm Desktop 3.1.1.
	let (w, h) = (267, 276);
	let mut win = Window::default().with_size(w, h).with_label(title);
	let mut note = NoteBox::new(2, 2, 26, (11, 36, 244, 192));
	note.buf.set_text(text);
	let answer = Rc::new(Cell::new(false));
	let mut ok = Button::new(10, 239, 75, 23, "OK");
	let mut cancel = Button::new(95, 239, 75, 23, "Cancel");
	{
		let (answer, mut win) = (answer.clone(), win.clone());
		ok.set_callback(move |_| {
			answer.set(true);
			win.hide();
		});
	}
	{
		let mut win = win.clone();
		cancel.set_callback(move |_| win.hide());
	}
	// A window whose resizable widget is NULL gets no resize border and no maximize box.
	win.make_resizable(false);
	win.end();
	dialogs::show_modal(&mut win);
	let _ = note.area.take_focus();
	while win.shown() {
		app::wait();
		note.sync();
	}
	answer.get().then(|| note.buf.text())
}
