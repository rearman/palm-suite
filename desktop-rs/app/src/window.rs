//! The main window, as MainFrame.kt: the menus, the toolbar, the launcher bar on the left, the four views in
//! the middle and the status bar at the bottom. Every control sends a [Msg]; the main loop does the work.

use crate::msg::{DateMsg, EditCmd, Msg, TodoMsg, View};
use crate::text_edit::EditState;
use crate::theme::{self, icon, PANEL, WORKSPACE};
use fltk::app::Sender;
use fltk::button::{Button, RadioButton};
use fltk::enums::{Align, Color, FrameType, Key, Shortcut};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::menu::{MenuBar, MenuFlag};
use fltk::prelude::*;
use fltk::window::Window;

const W: i32 = 1000;
const H: i32 = 700;
const MENU_H: i32 = 20;
const TOOL_H: i32 = 30;
const STATUS_H: i32 = 22;
const LAUNCHER_W: i32 = 72;

/// The View menu radio item of each view.
type ViewItems = Vec<(View, &'static str)>;
/// Menu items that show in some views only.
type OnlyIn = Vec<(&'static str, &'static [View])>;

pub struct Ui {
	pub win: Window,
	bar: MenuBar,
	launchers: Vec<(View, RadioButton)>,
	views: Vec<(View, Group)>,
	status: Frame,
	position: Frame,
	private_icon: Frame,
	view_items: ViewItems,
	only_in: OnlyIn,
	/// The toolbar buttons whose state changes: "cut", "copy", "paste", "undo" and "new".
	tools: Vec<(&'static str, Button)>,
}

fn ctrl(c: char) -> Shortcut {
	Shortcut::Ctrl | c
}

impl Ui {
	pub fn build(s: Sender<Msg>) -> Ui {
		let mut win = Window::default().with_size(W, H).with_label("Palm Desktop");
		win.set_color(PANEL);
		win.size_range(640, 480, 0, 0);
		win.set_icon(Some(icon("app48")));

		let (bar, view_items, only_in) = menu_bar(s);
		let (mut private_icon, tools) = toolbar(s);
		private_icon.hide();

		let mut launcher = Group::new(0, MENU_H + TOOL_H, LAUNCHER_W, H - MENU_H - TOOL_H - STATUS_H, None);
		launcher.set_frame(FrameType::UpBox);
		let mut launchers = Vec::new();
		let mut y = launcher.y() + 6;
		for (view, label, png) in [
			(View::Date, "Date", "date"),
			(View::Address, "Address", "address"),
			(View::Todo, "To Do", "todo"),
			(View::Memo, "Memo", "memo"),
		] {
			launchers.push((view, launcher_button(y, label, png, s, view)));
			y += 58 + 6;
		}
		// The empty space below the buttons takes the height of a taller window.
		let spacer = Frame::new(0, y, LAUNCHER_W, launcher.h() - (y - launcher.y()), None);
		launcher.resizable(&spacer);
		launcher.end();

		let cx = LAUNCHER_W;
		let cy = MENU_H + TOOL_H;
		let (cw, ch) = (W - LAUNCHER_W, H - MENU_H - TOOL_H - STATUS_H);
		let mut center = Group::new(cx, cy, cw, ch, None);
		center.set_frame(FrameType::FlatBox);
		center.set_color(PANEL);
		// A 3 px margin, then the raised bevel of the official window.
		let mut bevel = Frame::new(cx + 3, cy + 3, cw - 6, ch - 6, None);
		bevel.set_frame(FrameType::UpBox);
		let mut views = Vec::new();
		for view in View::ALL {
			views.push((view, view_group(view, cx + 5, cy + 5, cw - 10, ch - 10)));
		}
		center.resizable(&bevel);
		center.end();

		let (status, position) = status_bar();
		win.resizable(&center);
		win.end();
		win.handle(move |_, ev| {
			use fltk::enums::Event;
			match ev {
				Event::Focus => s.send(Msg::SyncIfStale),
				Event::Push | Event::Released | Event::KeyUp => s.send(Msg::EditState),
				Event::KeyDown
					if fltk::app::event_key() == Key::Delete
						&& fltk::app::event_state().contains(fltk::enums::EventState::Shift) =>
				{
					s.send(Msg::Edit(EditCmd::DeleteNoConfirm))
				}
				_ => {}
			}
			false
		});
		// FLTK closes a window on Escape; the Palm Desktop main window closes from its close box and
		// File > Exit only.
		win.set_callback(move |_| {
			if fltk::app::event() == fltk::enums::Event::Close {
				s.send(Msg::Exit);
			}
		});
		Ui {
			win,
			bar,
			launchers,
			views,
			status,
			position,
			private_icon,
			view_items,
			only_in,
			tools,
		}
	}

	/// Shows the view and checks its launcher button and View menu item, and shows the menu items that
	/// belong to it.
	pub fn show_view(&mut self, view: View) {
		for (v, g) in self.views.iter_mut() {
			if *v == view {
				g.show();
			} else {
				g.hide();
			}
		}
		for (v, b) in self.launchers.iter_mut() {
			b.set_value(*v == view);
		}
		for (v, path) in &self.view_items {
			if let Some(mut item) = self.bar.find_item(path) {
				if *v == view {
					item.set();
				} else {
					item.clear();
				}
			}
		}
		for (path, views) in &self.only_in {
			if let Some(mut item) = self.bar.find_item(path) {
				if views.contains(&view) {
					item.show();
				} else {
					item.hide();
				}
			}
		}
		self.win.redraw();
	}

	fn set_tool(&mut self, name: &str, on: bool) {
		for (n, b) in self.tools.iter_mut() {
			if *n == name {
				if on {
					b.activate();
				} else {
					b.deactivate();
				}
			}
		}
	}

	/// Grays the Edit items and toolbar buttons that do not apply to the focus owner.
	pub fn set_edit_state(&mut self, s: EditState) {
		for (path, on) in [
			("&Edit/&Undo", s.undo),
			("&Edit/Cu&t", s.cut),
			("&Edit/&Copy", s.copy),
			("&Edit/&Paste", s.paste),
		] {
			if let Some(mut item) = self.bar.find_item(path) {
				if on {
					item.activate();
				} else {
					item.deactivate();
				}
			}
		}
		for (name, on) in [("undo", s.undo), ("cut", s.cut), ("copy", s.copy), ("paste", s.paste)] {
			self.set_tool(name, on);
		}
	}

	/// The group of one view, for the view to build its widgets in.
	pub fn view_group(&self, view: View) -> Group {
		self.views
			.iter()
			.find(|(v, _)| *v == view)
			.map(|(_, g)| g.clone())
			.expect("every view has a group")
	}

	pub fn set_status(&mut self, text: &str) {
		self.status.set_label(&text.replace('@', "@@"));
	}

	pub fn set_position(&mut self, text: &str) {
		self.position.set_label(text);
	}

	/// The toolbar shows the private-record mark while private records are hidden.
	pub fn set_private_hidden(&mut self, hidden: bool) {
		if hidden {
			self.private_icon.show();
		} else {
			self.private_icon.hide();
		}
		let (show, hide) = ("&View/&Show Private Records", "&View/&Hide Private Records");
		for (path, on) in [(show, !hidden), (hide, hidden)] {
			if let Some(mut item) = self.bar.find_item(path) {
				if on {
					item.set();
				} else {
					item.clear();
				}
			}
		}
		self.win.redraw();
	}
}

/// The menu bar of MainFrame.kt. Items whose feature comes in a later phase of the port are grayed.
fn menu_bar(s: Sender<Msg>) -> (MenuBar, ViewItems, OnlyIn) {
	let mut bar = MenuBar::new(0, 0, W, MENU_H, None);
	bar.set_frame(FrameType::FlatBox);
	bar.set_text_size(11);
	let send = move |m: fn() -> Msg| move |_: &mut MenuBar| s.send(m());
	let normal = MenuFlag::Normal;
	let divider = MenuFlag::MenuDivider;
	let later = MenuFlag::Inactive;
	let later_div = MenuFlag::Inactive | MenuFlag::MenuDivider;
	let none = |_: &mut MenuBar| {};

	bar.add("&File/&Save All", ctrl('s'), divider, send(|| Msg::SaveAll));
	bar.add("&File/&Import...", Shortcut::None, later, none);
	bar.add("&File/&Export...", Shortcut::None, later_div, none);
	bar.add("&File/Page Set&up...", Shortcut::None, later, none);
	bar.add("&File/&Print...", ctrl('p'), later_div, none);
	bar.add("&File/E&xit", Shortcut::None, normal, send(|| Msg::Exit));

	let edit = move |c: EditCmd| move |_: &mut MenuBar| s.send(Msg::Edit(c));
	bar.add("&Edit/&Undo", ctrl('z'), later_div, edit(EditCmd::Undo));
	bar.add("&Edit/Cu&t", ctrl('x'), later, edit(EditCmd::Cut));
	bar.add("&Edit/&Copy", ctrl('c'), later, edit(EditCmd::Copy));
	bar.add("&Edit/&Paste", ctrl('v'), later, edit(EditCmd::Paste));
	bar.add(
		"&Edit/&Delete",
		Shortcut::from_key(Key::Delete),
		divider,
		edit(EditCmd::Delete),
	);
	bar.add("&Edit/Select &All", ctrl('a'), normal, edit(EditCmd::SelectAll));
	bar.add("&Edit/&New Event...", ctrl('n'), normal, edit(EditCmd::New));
	bar.add(
		"&Edit/&Edit Event...",
		Shortcut::None,
		normal,
		send(|| Msg::Date(DateMsg::Edit)),
	);
	bar.add("&Edit/De&lete Event", Shortcut::None, normal, edit(EditCmd::Delete));
	bar.add("&Edit/&New To Do", ctrl('n'), normal, edit(EditCmd::New));
	bar.add("&Edit/&New Memo", ctrl('n'), normal, edit(EditCmd::New));
	bar.add("&Edit/&New Address...", ctrl('n'), divider, edit(EditCmd::New));
	bar.add("&Edit/&Find...", ctrl('f'), later, none);

	let views: [(View, &'static str, Key); 4] = [
		(View::Date, "&View/&Date Book", Key::F2),
		(View::Address, "&View/&Address Book", Key::F3),
		(View::Todo, "&View/T&o Do List", Key::F4),
		(View::Memo, "&View/&Memo Pad", Key::F5),
	];
	for (i, (view, path, key)) in views.iter().enumerate() {
		let flag = if i == 3 {
			MenuFlag::Radio | MenuFlag::MenuDivider
		} else {
			MenuFlag::Radio
		};
		let view = *view;
		bar.add(path, Shortcut::from_key(*key), flag, move |_| {
			s.send(Msg::ShowView(view))
		});
	}
	bar.add(
		"&View/&Show Private Records",
		Shortcut::None,
		MenuFlag::Radio,
		move |_| s.send(Msg::SetShowPrivate(true)),
	);
	bar.add(
		"&View/&Hide Private Records",
		Shortcut::None,
		MenuFlag::Radio | MenuFlag::MenuDivider,
		move |_| s.send(Msg::SetShowPrivate(false)),
	);
	bar.add(
		"&View/Sh&ow...",
		Shortcut::None,
		normal,
		send(|| Msg::Todo(TodoMsg::Show)),
	);
	bar.add("&View/&List By...", Shortcut::None, normal, send(|| Msg::ListBy));

	bar.add(
		"&Tools/&Categories...",
		Shortcut::None,
		divider,
		send(|| Msg::Categories),
	);
	bar.add(
		"&Tools/Custom &Field Labels...",
		Shortcut::None,
		divider,
		send(|| Msg::CustomLabels),
	);
	// Purge names what it deletes: the events of the Date Book, the completed items of the To Do List.
	bar.add("&Tools/&Purge Events...", Shortcut::None, divider, send(|| Msg::Purge));
	bar.add(
		"&Tools/&Purge Completed To Do's...",
		Shortcut::None,
		divider,
		send(|| Msg::Purge),
	);
	bar.add("&Tools/&Options...", Shortcut::None, later, none);

	bar.add("&Sync/&Options...", Shortcut::None, normal, send(|| Msg::SyncOptions));
	bar.add("&Sync/Sync &Now", Shortcut::None, normal, send(|| Msg::SyncNow));

	bar.add(
		"&Help/&About Palm Desktop...",
		Shortcut::None,
		normal,
		send(|| Msg::About),
	);

	let view_items = views.iter().map(|(v, p, _)| (*v, *p)).collect();
	let only_in: OnlyIn = vec![
		("&Edit/&New Event...", &[View::Date]),
		("&Edit/&Edit Event...", &[View::Date]),
		("&Edit/De&lete Event", &[View::Date]),
		("&Edit/&New To Do", &[View::Todo]),
		("&Edit/&New Memo", &[View::Memo]),
		("&Edit/&New Address...", &[View::Address]),
		("&View/Sh&ow...", &[View::Todo]),
		("&View/&List By...", &[View::Memo, View::Address]),
		("&Tools/Custom &Field Labels...", &[View::Address]),
		("&Tools/&Purge Events...", &[View::Date]),
		("&Tools/&Purge Completed To Do's...", &[View::Todo]),
	];
	(bar, view_items, only_in)
}

/// The toolbar of MainFrame.kt. Returns the private-record mark at its right end, and the buttons whose
/// state changes.
fn toolbar(s: Sender<Msg>) -> (Frame, Vec<(&'static str, Button)>) {
	let mut g = Group::new(0, MENU_H, W, TOOL_H, None);
	g.set_frame(FrameType::FlatBox);
	let mut x = 2;
	let mut tools = Vec::new();
	let mut add = |png: &'static str, tip: &str, msg: Option<fn() -> Msg>, gap: bool| {
		let mut b = Button::new(x, MENU_H + 3, 24, 24, None);
		b.set_frame(FrameType::UpBox);
		b.set_down_frame(FrameType::DownBox);
		b.set_image(Some(icon(png)));
		b.set_deimage(Some(theme::gray_icon(png)));
		b.set_tooltip(tip);
		b.clear_visible_focus();
		match msg {
			Some(m) => b.set_callback(move |_| s.send(m())),
			// The feature comes in a later phase of the port.
			None => b.deactivate(),
		}
		x += 24 + if gap { 10 } else { 0 };
		tools.push((png, b));
	};
	add("save", "Save All", Some(|| Msg::SaveAll), false);
	add("print", "Print", None, true);
	add("cut", "Cut", Some(|| Msg::Edit(EditCmd::Cut)), false);
	add("copy", "Copy", Some(|| Msg::Edit(EditCmd::Copy)), false);
	add("paste", "Paste", Some(|| Msg::Edit(EditCmd::Paste)), false);
	add("undo", "Undo", Some(|| Msg::Edit(EditCmd::Undo)), true);
	add("find", "Find", None, true);
	add("new", "New Item", Some(|| Msg::Edit(EditCmd::New)), false);
	let spacer = Frame::new(x, MENU_H, W - x - 30, TOOL_H, None);
	let mut mark = Frame::new(W - 26, MENU_H + 3, 24, 24, None);
	mark.set_image(Some(icon("private")));
	mark.set_tooltip("Private records are hidden");
	g.resizable(&spacer);
	g.end();
	tools.retain(|(n, _)| ["cut", "copy", "paste", "undo", "new"].contains(n));
	(mark, tools)
}

/// A launcher button: the 24 px app icon over its name. It has no Alt key: F2 to F5 from the View menu
/// select the views, as in Palm Desktop.
fn launcher_button(y: i32, label: &str, png: &str, s: Sender<Msg>, view: View) -> RadioButton {
	let mut b = RadioButton::new(7, y, 58, 58, None);
	b.set_label(label);
	b.set_label_font(theme::fonts().segoe);
	b.set_label_size(12);
	b.set_frame(FrameType::UpBox);
	b.set_down_frame(FrameType::DownBox);
	b.set_selection_color(Color::from_rgb(250, 250, 250));
	b.set_image(Some(icon(png)));
	b.set_align(Align::Inside | Align::Center);
	b.clear_visible_focus();
	b.set_callback(move |_| s.send(Msg::ShowView(view)));
	b
}

/// The area of one view; each view builds itself in its group.
fn view_group(view: View, x: i32, y: i32, w: i32, h: i32) -> Group {
	let mut g = Group::new(x, y, w, h, None);
	g.set_frame(FrameType::FlatBox);
	g.set_color(if view == View::Date { WORKSPACE } else { PANEL });
	g.end();
	g.hide();
	g
}

/// The status bar: the status text at the left, and the position box (the date or "3 of 12") at the right.
fn status_bar() -> (Frame, Frame) {
	let mut g = Group::new(0, H - STATUS_H, W, STATUS_H, None);
	g.set_frame(FrameType::EngravedBox);
	let mut status = Frame::new(5, H - STATUS_H + 2, W - 110, STATUS_H - 4, "Ready");
	status.set_align(Align::Left | Align::Inside);
	status.set_label_size(12);
	let mut position = Frame::new(W - 98, H - STATUS_H + 2, 90, 17, None);
	position.set_frame(FrameType::ThinDownBox);
	position.set_align(Align::Left | Align::Inside);
	position.set_label_size(12);
	g.resizable(&status);
	g.end();
	(status, position)
}
