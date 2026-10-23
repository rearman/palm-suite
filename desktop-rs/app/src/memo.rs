//! The Memo Pad view, as MemoPanel.kt: the Memo List with its category filter on the left, and the memo
//! editor with its category, private flag and Apply button on the right.

use crate::category::{IdChoice, ALL, UNFILED};
use crate::combo;
use crate::dialogs::{confirm_delete_titled, list_by};
use crate::msg::{MemoMsg, Msg};
use crate::records::{self, list_state, ListAction, ListMsgs};
use crate::split::split;
use crate::text_edit::{EditState, Field};
use crate::theme::{self, icon, SELECT};
use crate::view::Ctx;
use fltk::app::{self, Sender};
use fltk::button::{Button, CheckButton};
use fltk::draw;
use fltk::enums::{Align, Color, FrameType};
use fltk::frame::Frame;
use fltk::group::Group;
use fltk::image::PngImage;
use fltk::menu::Choice;
use fltk::prelude::*;
use fltk::table::{TableContext, TableRow, TableRowSelectFlag, TableRowSelectMode};
use fltk::text::{TextBuffer, TextEditor, WrapMode};
use palm_core::model::{memo_order, Memo};
use palm_core::runner::Record;
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// One line of the Memo List: the first line, and the key icon at the right for a private memo.
struct Row {
	title: String,
	private: bool,
}

pub struct MemoView {
	/// The split that holds the view; the view group makes it its resizable widget.
	pub root: Group,
	filter: IdChoice,
	table: TableRow,
	rows: Rc<RefCell<Vec<Row>>>,
	/// The row clicked or moved to last.
	lead: Rc<Cell<i32>>,
	editor: TextEditor,
	buf: TextBuffer,
	category: IdChoice,
	private_box: CheckButton,
	apply: Button,
	/// The memos of the list, in list order.
	shown: Vec<Memo>,
	/// The memo in the editor. Edits go to it on Apply or on a change of selection.
	current: Option<Memo>,
	filter_id: i32,
}

impl MemoView {
	/// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group.
	pub fn new(x: i32, y: i32, w: i32, h: i32, s: Sender<Msg>, ctx_settings: &crate::settings::Settings) -> MemoView {
		let fonts = theme::fonts();
		let detail_w = ctx_settings.get_int("memoSplit", 416);
		let sp = split(x, y, w, h, detail_w, "memoSplit", s);

		// The list side: the title, the filter, the list and the List by and New buttons.
		sp.left.begin();
		let (lx, ly, lw, lh) = (sp.left.x(), sp.left.y(), sp.left.w(), sp.left.h());
		let mut title = Frame::new(lx + 4, ly + 4, 112, 22, "Memo Pad");
		title.set_frame(FrameType::DownBox);
		title.set_label_font(fonts.arial_bold);
		title.set_label_size(14);
		title.set_align(Align::Center | Align::Inside);
		let mut filter_menu = Choice::new(lx + lw - 114, ly + 4, 110, 22, None);
		crate::draw::style_menu(&mut filter_menu);
		combo::drop_below(&mut filter_menu);
		let filter = IdChoice::new(filter_menu, Some(1), s, Some(|| Msg::Memo(MemoMsg::Filter)));

		let rows: Rc<RefCell<Vec<Row>>> = Rc::new(RefCell::new(Vec::new()));
		let lead = Rc::new(Cell::new(-1));
		let mut table = TableRow::new(lx + 4, ly + 30, lw - 8, lh - 30 - 34, None);
		table.set_type(TableRowSelectMode::Multi);
		table.set_rows(0);
		table.set_cols(1);
		table.set_row_height_all(16);
		table.set_col_header(false);
		table.set_row_header(false);
		table.set_frame(FrameType::DownBox);
		table.set_color(Color::White);
		table.end();
		let (note, key) = (icon("note"), icon("private"));
		let r = rows.clone();
		table.draw_cell(move |t, ctx, row, _col, cx, cy, cw, ch| match ctx {
			TableContext::StartPage => records::fit_column(t, 16),
			TableContext::Cell => {
				let rows = r.borrow();
				let Some(line) = rows.get(row as usize) else { return };
				let selected = t.row_selected(row);
				draw::push_clip(cx, cy, cw, ch);
				draw::draw_rect_fill(cx, cy, cw, ch, if selected { SELECT } else { Color::White });
				let mut n: PngImage = note.clone();
				n.draw(cx + 2, cy + 2, n.w(), n.h());
				draw::set_font(fonts.sans, 11);
				draw::set_draw_color(if selected { Color::White } else { Color::Black });
				draw::draw_text2(
					&line.title.replace('@', "@@"),
					cx + 17,
					cy,
					cw - 32,
					ch,
					Align::Left | Align::Clip,
				);
				if line.private {
					let mut k: PngImage = key.clone();
					k.draw(cx + cw - k.w() - 3, cy + 3, k.w(), k.h());
				}
				draw::pop_clip();
			}
			_ => {}
		});
		let l = lead.clone();
		table.handle(move |t, ev| records::list_event(t, ev, &l, s, LIST_MSGS));

		let mut list_by_b = Button::new(lx + lw - 166, ly + lh - 29, 75, 23, "&List by...");
		list_by_b.set_callback(move |_| s.send(Msg::ListBy));
		let mut new_b = Button::new(lx + lw - 85, ly + lh - 29, 75, 23, "&New");
		new_b.set_callback(move |_| s.send(Msg::Memo(MemoMsg::New)));
		// The title stays, the filter and the buttons keep to the right and the bottom, the list grows.
		// FLTK widens a child whose span holds the resizable's left edge and moves one to the right of it, so the
		// resizable is 1 px wide: a width taken from the pane would go negative for a narrow list, and FLTK would
		// then slide the list in place of widening it.
		let grow = Frame::new(lx + 120, ly + 30, 1, lh - 64, None);
		sp.left.resizable(&grow);
		sp.left.end();

		// The detail side: the editor, then Category, Private and Apply.
		sp.right.begin();
		let (rx, ry, rw, rh) = (sp.right.x(), sp.right.y(), sp.right.w(), sp.right.h());
		let buf = TextBuffer::default();
		let mut editor = TextEditor::new(rx + 8, ry + 8, rw - 16, rh - 8 - 64, None);
		editor.set_buffer(buf.clone());
		editor.wrap_mode(WrapMode::AtBounds, 0);
		editor.set_text_font(fonts.arial);
		editor.set_text_size(12);
		editor.set_scrollbar_align(Align::Right);
		Field::Editor(editor.clone()).attach_menu();
		let fy = ry + rh - 54;
		let mut cat_label = Frame::new(rx + 8, fy, 60, 22, "Category:");
		cat_label.set_align(Align::Right | Align::Inside);
		let mut category_menu = Choice::new(rx + 72, fy, rw - 80, 22, None);
		crate::draw::style_menu(&mut category_menu);
		combo::drop_below(&mut category_menu);
		let category = IdChoice::new(category_menu, Some(1), s, None);
		let mut private_label = Frame::new(rx + 8, fy + 26, 60, 22, "Private:");
		private_label.set_align(Align::Right | Align::Inside);
		let private_box = crate::draw::check(rx + 72, fy + 28, 18, 18, "");
		let mut apply = Button::new(rx + rw - 83, fy + 26, 75, 23, "&Apply");
		apply.set_callback(move |_| s.send(Msg::Memo(MemoMsg::Apply)));
		let grow = Frame::new(rx + 80, ry + 8, rw - 170, rh - 72, None);
		sp.right.resizable(&grow);
		sp.right.end();

		MemoView {
			root: sp.outer,
			filter,
			table,
			rows,
			lead,
			editor,
			buf,
			category,
			private_box,
			apply,
			shown: Vec::new(),
			current: None,
			filter_id: ctx_settings.get_int("memoCategory", ALL),
		}
	}

	pub fn editor(&self) -> &TextEditor {
		&self.editor
	}

	pub fn table(&self) -> &TableRow {
		&self.table
	}

	/// The list takes the focus when the view opens, as in Palm Desktop.
	pub fn focus(&mut self) {
		let _ = self.table.take_focus();
	}

	fn selected_rows(&self) -> Vec<usize> {
		records::selected_rows(&self.table, self.shown.len())
	}

	fn selected(&self) -> Vec<Memo> {
		self.selected_rows()
			.into_iter()
			.map(|i| self.shown[i].clone())
			.collect()
	}

	/// The memo of the lead row when it is selected, else the first selected memo.
	fn lead_memo(&self) -> Option<Memo> {
		let mut t = self.table.clone();
		let l = self.lead.get();
		if l >= 0 && (l as usize) < self.shown.len() && t.row_selected(l) {
			return Some(self.shown[l as usize].clone());
		}
		self.selected_rows().first().map(|i| self.shown[*i].clone())
	}

	/// "3 of 12" for the status bar.
	pub fn position(&self) -> String {
		match self.selected_rows().first() {
			Some(i) => format!("{} of {}", i + 1, self.shown.len()),
			None => String::new(),
		}
	}

	pub fn list_edit_state(&self) -> EditState {
		list_state(!self.selected_rows().is_empty(), !self.shown.is_empty())
	}

	/// Reads the store again: the lists and the combo boxes. Keeps the editor when it holds unsaved text.
	pub fn refresh(&mut self, ctx: &mut Ctx) {
		let (old, dirty) = (self.current.clone(), self.has_changes());
		self.reflow(ctx, old, dirty);
	}

	fn reflow(&mut self, ctx: &mut Ctx, old: Option<Memo>, dirty: bool) {
		self.refresh_combos(ctx);
		let hit = self.rebuild_list(ctx, old.as_ref());
		match (&hit, &old) {
			(Some(h), Some(o)) if h.id == o.id && !dirty => self.load_editor(Some(h.clone())),
			(Some(h), Some(o)) if h.id == o.id => self.current = Some(h.clone()),
			(Some(h), _) if dirty => self.current = Some(h.clone()),
			_ => self.load_editor(hit),
		}
	}

	fn visible(&self, ctx: &Ctx) -> Vec<Memo> {
		let shown: Vec<Memo> = ctx
			.store
			.memos
			.iter()
			.filter(|m| ctx.show_private || !m.is_private)
			.filter(|m| self.filter_id == ALL || m.category == self.filter_id)
			.cloned()
			.collect();
		memo_order(&shown, ctx.settings.get("memoSort", "0") == "1")
	}

	/// Fills the list. Keeps the selected memos that are still shown, and selects `want` only when nothing
	/// is left selected. Returns the lead memo, which the editor shows.
	fn rebuild_list(&mut self, ctx: &Ctx, want: Option<&Memo>) -> Option<Memo> {
		let keep: Vec<i64> = self.selected().iter().map(|m| m.id).collect();
		let lead_id = self.lead_memo().map(|m| m.id);
		self.shown = self.visible(ctx);
		*self.rows.borrow_mut() = self
			.shown
			.iter()
			.map(|m| Row {
				title: m.title(),
				private: m.is_private,
			})
			.collect();
		self.table.set_rows(self.shown.len() as i32);
		// New rows take FLTK's default height; the Memo List rows are 16 px.
		self.table.set_row_height_all(16);
		self.table.select_all_rows(TableRowSelectFlag::Deselect);
		for (i, m) in self.shown.iter().enumerate() {
			if keep.contains(&m.id) {
				let _ = self.table.select_row(i as i32, TableRowSelectFlag::Select);
			}
		}
		self.lead.set(
			lead_id
				.and_then(|id| self.shown.iter().position(|m| m.id == id))
				.map_or(-1, |i| i as i32),
		);
		if self.selected_rows().is_empty() {
			self.pick(want.map(|m| m.id));
		}
		self.table.redraw();
		self.lead_memo()
	}

	/// Selects the memo with `id` alone. With no `id`, selects the first memo, as the view opens on it; with no
	/// memo, nothing.
	fn pick(&mut self, id: Option<i64>) {
		let row = match id {
			Some(id) => self.shown.iter().position(|m| m.id == id),
			None => (!self.shown.is_empty()).then_some(0),
		};
		records::pick_row(&mut self.table, &self.lead, row);
	}

	fn refresh_combos(&mut self, ctx: &Ctx) {
		let keep = self.category.id();
		let cats: Vec<(String, i32)> = ctx
			.store
			.memo_categories
			.iter()
			.map(|c| (c.name.clone(), c.id))
			.collect();
		let mut filters = vec![("All".to_string(), ALL)];
		filters.extend(cats.iter().cloned());
		filters.push(("Unfiled".to_string(), UNFILED));
		self.filter.set_items(&filters, true, self.filter_id);
		self.filter_id = self.filter.id();
		let mut items = vec![("Unfiled".to_string(), UNFILED)];
		items.extend(cats);
		self.category.set_items(&items, true, keep);
	}

	fn load_editor(&mut self, m: Option<Memo>) {
		self.buf.set_text(m.as_ref().map_or("", |m| m.text.as_str()));
		self.editor.set_insert_position(0);
		self.category.select(m.as_ref().map_or(UNFILED, |m| m.category));
		self.private_box.set_value(m.as_ref().is_some_and(|m| m.is_private));
		let on = m.is_some();
		for w in [
			&mut self.editor.as_base_widget(),
			&mut self.category.choice.as_base_widget(),
			&mut self.private_box.as_base_widget(),
			&mut self.apply.as_base_widget(),
		] {
			if on {
				w.activate();
			} else {
				w.deactivate();
			}
		}
		self.current = m;
	}

	fn has_changes(&self) -> bool {
		let Some(m) = &self.current else { return false };
		self.buf.text() != m.text || self.category.id() != m.category || self.private_box.value() != m.is_private
	}

	fn save_if_changed(&mut self, ctx: &mut Ctx) {
		if self.has_changes() {
			self.apply_edits(ctx);
		}
	}

	/// Writes the editor to the current memo. A memo with blank text is deleted, as on the Palm.
	fn apply_edits(&mut self, ctx: &mut Ctx) {
		let Some(mut m) = self.current.clone() else { return };
		m.text = self.buf.text();
		m.category = self.category.id();
		m.is_private = self.private_box.value();
		if palm_core::model::is_blank(&m.text) {
			ctx.store.delete_memo(m.id);
		} else {
			ctx.store.save_memo(&mut m);
		}
		self.current = Some(m);
	}

	/// A new memo with `text`, in the category the filter shows.
	fn new_memo(&self, text: &str) -> Memo {
		Memo {
			text: text.to_string(),
			category: self.filter_id.max(UNFILED),
			..Memo::default()
		}
	}

	pub fn new_record(&mut self, ctx: &mut Ctx) {
		self.save_if_changed(ctx);
		let mut m = self.new_memo("");
		ctx.store.save_memo(&mut m);
		self.reflow(ctx, Some(m.clone()), false);
		self.pick(Some(m.id));
		self.load_editor(Some(m));
		let _ = self.editor.take_focus();
	}

	fn delete_selected(&mut self, ctx: &mut Ctx) {
		let ms = self.selected();
		if ms.is_empty()
			|| !confirm_delete_titled(
				ctx.settings,
				ctx.no_confirm,
				"Delete Memo Pad Items",
				"Delete the selected Memo Pad item(s)?",
			) {
			return;
		}
		self.load_editor(None);
		for m in ms {
			ctx.store.delete_memo(m.id);
		}
	}

	/// Shows the memo `id`, with the filter on All when it hides the memo, and selects it.
	pub fn select(&mut self, ctx: &mut Ctx, id: i64) {
		let Some(m) = ctx.store.memos.iter().find(|m| m.id == id).cloned() else {
			return;
		};
		self.save_if_changed(ctx);
		if self.filter_id != ALL && m.category != self.filter_id {
			self.filter_id = ALL;
			ctx.set("memoCategory", &ALL.to_string());
		}
		self.load_editor(Some(m.clone()));
		self.reflow(ctx, Some(m.clone()), false);
		self.pick(Some(m.id));
	}

	/// The title and the lines that Print prints: the memo in the editor, else the titles of the list.
	pub fn print_lines(&self) -> (String, Vec<String>) {
		if self.current.is_none() {
			let titles = self
				.shown
				.iter()
				.enumerate()
				.map(|(i, m)| format!("{}. {}", i + 1, m.title()))
				.collect();
			return ("Memo Pad".to_string(), titles);
		}
		let body = self.buf.text();
		let title = body.lines().next().unwrap_or("").trim().to_string();
		(title, body.lines().map(str::to_string).collect())
	}

	pub fn list_by(&mut self, ctx: &mut Ctx) {
		let now = if ctx.settings.get("memoSort", "0") == "1" { 1 } else { 0 };
		if let Some(order) = list_by("Record Order", &["&Order on handheld", "&Alphabetical"], now) {
			ctx.set("memoSort", &order.to_string());
			self.refresh(ctx);
		}
	}

	pub fn handle(&mut self, msg: MemoMsg, ctx: &mut Ctx) {
		match msg {
			MemoMsg::Picked => {
				let next = self.lead_memo();
				if next.as_ref().map(|m| m.id) != self.current.as_ref().map(|m| m.id) {
					self.save_if_changed(ctx);
					// The save can rebuild the list; read the lead again.
					let next = next.and_then(|n| ctx.store.memos.iter().find(|m| m.id == n.id).cloned());
					self.load_editor(next);
				}
			}
			MemoMsg::TypeNew(c) => {
				self.new_record(ctx);
				self.buf.set_text(&c.to_string());
				self.editor.set_insert_position(self.buf.length());
			}
			MemoMsg::New => self.new_record(ctx),
			MemoMsg::Apply => self.apply_edits(ctx),
			MemoMsg::Filter => {
				self.save_if_changed(ctx);
				self.filter_id = self.filter.id();
				ctx.set("memoCategory", &self.filter_id.to_string());
				let cur = self.current.clone();
				self.reflow(ctx, cur, false);
			}
			MemoMsg::ListMenu => {
				let picked = records::list_menu(self.list_edit_state(), &[]);
				// The popup covered rows of the list, and rows it covered were not repainted when it closed.
				self.table.redraw();
				if let Some(a) = picked {
					self.list_action(a, ctx);
				}
			}
			MemoMsg::PasteText(text) => self.paste_text(ctx, &text),
		}
	}

	/// Cut, Copy, Paste, Delete and Select All of the selected memos.
	pub fn list_action(&mut self, a: ListAction, ctx: &mut Ctx) {
		match a {
			ListAction::Copy | ListAction::Cut => {
				let ms = self.selected();
				if ms.is_empty() {
					return;
				}
				let text = ms.iter().map(|m| m.text.as_str()).collect::<Vec<_>>().join("\n");
				records::put("memo", ms.iter().cloned().map(Record::Memo).collect(), text);
				if a == ListAction::Cut {
					self.load_editor(None);
					for m in ms {
						ctx.store.delete_memo(m.id);
					}
				}
			}
			ListAction::Paste => app::paste_text(&self.table),
			ListAction::Delete => self.delete_selected(ctx),
			ListAction::SelectAll => {
				self.table.select_all_rows(TableRowSelectFlag::Select);
				self.table.redraw();
			}
			ListAction::Extra(_) => {}
		}
	}

	/// A paste into the list: the kept memos as copies when the text is theirs, else one new memo.
	fn paste_text(&mut self, ctx: &mut Ctx, text: &str) {
		match records::records_for("memo", text) {
			Some(recs) => {
				for r in recs {
					if let Record::Memo(m) = r {
						ctx.store.save_memo(&mut Memo {
							id: 0,
							uid: String::new(),
							..m
						});
					}
				}
			}
			None => {
				let mut m = self.new_memo(text);
				ctx.store.save_memo(&mut m);
			}
		}
	}
}

/// The messages of the Memo List. A printable key starts a new memo that holds it, as on the Palm.
const LIST_MSGS: ListMsgs = ListMsgs {
	picked: || Msg::Memo(MemoMsg::Picked),
	menu: || Msg::Memo(MemoMsg::ListMenu),
	paste: |t| Msg::Memo(MemoMsg::PasteText(t)),
	typed: Some(|c| Msg::Memo(MemoMsg::TypeNew(c))),
	open: None,
	cell: None,
};
