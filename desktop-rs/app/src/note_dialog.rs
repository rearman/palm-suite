//! The Note Editor of EventDialog.kt (NoteDialog): a text area that wraps its lines, under a toolbar of Cut,
//! Copy, Paste and Undo, with OK and Cancel. The To Do List and the Date Book use it.

use crate::text_edit::{Field, TextAction};
use crate::theme::{gray_icon, icon};
use fltk::app;
use fltk::button::Button;
use fltk::enums::{Align, FrameType};
use fltk::frame::Frame;
use fltk::prelude::*;
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use fltk::window::Window;
use std::cell::Cell;
use std::rc::Rc;

/// Edits `text` in a dialog named `title`. The new text on OK, None on Cancel.
pub fn edit(title: &str, text: &str) -> Option<String> {
	// The official Note Editor is 273 x 300 px. The text area takes the space, and the dialog stays resizable.
	let (w, h) = (273, 300);
	let mut win = Window::default().with_size(w, h).with_label(title);
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
		let mut b = Button::new(2 + 26 * i as i32, 2, 24, 24, None);
		b.set_frame(FrameType::UpBox);
		b.set_down_frame(FrameType::DownBox);
		b.set_image(Some(icon(png)));
		b.set_deimage(Some(gray_icon(png)));
		b.set_tooltip(tip);
		b.clear_visible_focus();
		tools.push((a, b));
	}
	let buf = TextBuffer::default();
	let mut area = TextEditor::new(4, 30, w - 8, h - 30 - 38, None);
	area.set_buffer(buf.clone());
	area.wrap_mode(WrapMode::AtBounds, 0);
	area.set_scrollbar_align(Align::Right);
	buf.clone().set_text(text);
	let field = Field::Editor(area.clone());
	field.attach_menu();
	for (a, b) in tools.iter_mut() {
		let (mut f, a) = (field.clone(), *a);
		b.set_callback(move |_| f.run(a));
	}
	let answer = Rc::new(Cell::new(false));
	let mut ok = Button::new(w / 2 - 78, h - 31, 75, 23, "OK");
	let mut cancel = Button::new(w / 2 + 3, h - 31, 75, 23, "Cancel");
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
	// The toolbar and the buttons keep their size, OK at the left of the middle and Cancel at its right; the
	// text area takes the space.
	let grow = Frame::new(w / 2 - 3, 30, 6, h - 68, None);
	win.resizable(&grow);
	win.end();
	win.make_modal(true);
	win.show();
	let _ = area.take_focus();
	while win.shown() {
		app::wait();
		// Gray the buttons that do not apply, as the main toolbar does.
		let s = field.state();
		for (a, b) in tools.iter_mut() {
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
	}
	answer.get().then(|| buf.text())
}
