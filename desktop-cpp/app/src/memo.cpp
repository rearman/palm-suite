// The Memo Pad view, as desktop-rs/app/src/memo.rs: the Memo List with its category filter on the left, and the memo
// editor with its category, private flag, and Apply button on the right. The Rust closures of the buttons and of the
// list become static callbacks that take the sender, and the subclasses of the list and the editor.
#include "palm/memo.hpp"

#include "palm/combo.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/split.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_Table_Row.H>
#include <FL/Fl_Text_Buffer.H>
#include <FL/Fl_Text_Display.H>
#include <FL/Fl_Text_Editor.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cstddef>
#include <initializer_list>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace palm::memo {

namespace {

// The lines of a text as Rust's str::lines gives them: a line ends at "\n" or "\r\n", and a final line ending adds no
// empty line.
std::vector<std::string> lines_of(std::string_view s) {
	std::vector<std::string> out;
	std::size_t start = 0;
	while (start < s.size()) {
		const std::size_t nl = s.find('\n', start);
		std::string_view line = s.substr(start, (nl == std::string_view::npos ? s.size() : nl) - start);
		if (nl != std::string_view::npos && !line.empty() && line.back() == '\r') {
			line.remove_suffix(1);
		}
		out.emplace_back(line);
		if (nl == std::string_view::npos) {
			break;
		}
		start = nl + 1;
	}
	return out;
}

// A title as one FLTK label: "@" starts a symbol code, so each "@" is doubled.
std::string escape_symbols(const std::string& s) {
	std::string out;
	out.reserve(s.size());
	for (const char c : s) {
		if (c == '@') {
			out += "@@";
		} else {
			out += c;
		}
	}
	return out;
}

// The messages of the Memo List. A printable key starts a new memo that holds it, as on the Palm.
const records::ListMsgs LIST_MSGS{
    .picked = []() -> msg::Msg { return msg::Memo{msg::MemoMsg{msg::memo::Picked{}}}; },
    .menu = []() -> msg::Msg { return msg::Memo{msg::MemoMsg{msg::memo::ListMenu{}}}; },
    .paste = [](std::string text) -> msg::Msg {
	    return msg::Memo{msg::MemoMsg{msg::memo::PasteText{std::move(text)}}};
    },
    .typed = [](char32_t ch) -> msg::Msg { return msg::Memo{msg::MemoMsg{msg::memo::TypeNew{ch}}}; },
    .open = std::nullopt,
    .cell = std::nullopt,
};

// The button callbacks: each sends its message through the sender that the data points to.
void list_by_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::ListBy{});
}

void new_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::Memo{msg::MemoMsg{msg::memo::New{}}});
}

void apply_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::Memo{msg::MemoMsg{msg::memo::Apply{}}});
}

} // namespace

// The Memo List. Each row holds the first line of a memo and whether the memo is private. The list draws its rows,
// and its events go to records::list_event.
class MemoTable : public records::RecordTable {
public:
	// One line of the Memo List: the first line, and the key icon at the right for a private memo.
	struct Row {
		std::string title;
		bool is_private;
	};

	// The rows of the list, in list order.
	std::vector<Row> lines_;

	MemoTable(int x, int y, int w, int h, std::shared_ptr<int> lead, msg::Sender s);

	void draw_cell(TableContext context, int row, int col, int x, int y, int w, int h) override;
	int handle(int event) override;

private:
	std::unique_ptr<Fl_PNG_Image> note_icon_;
	std::unique_ptr<Fl_PNG_Image> key_icon_;
	std::shared_ptr<int> lead_;
	msg::Sender s_;
};

MemoTable::MemoTable(int x, int y, int w, int h, std::shared_ptr<int> lead, msg::Sender s)
    : records::RecordTable(x, y, w, h), note_icon_(theme::icon("note")), key_icon_(theme::icon("private")),
      lead_(std::move(lead)), s_(std::move(s)) {
	type(Fl_Table_Row::SELECT_MULTI);
	rows(0);
	cols(1);
	row_height_all(16);
	col_header(0);
	row_header(0);
	box(FL_DOWN_BOX);
	color(FL_WHITE);
	end();
}

void MemoTable::draw_cell(TableContext context, int row, int col, int x, int y, int w, int h) {
	(void)col;
	switch (context) {
	case CONTEXT_STARTPAGE:
		records::fit_column(*this, 16);
		break;
	case CONTEXT_CELL: {
		if (row < 0 || static_cast<std::size_t>(row) >= lines_.size()) {
			return;
		}
		const Row& line = lines_[static_cast<std::size_t>(row)];
		const bool selected = row_selected(row);
		draw::Clip clip(x, y, w, h);
		fl_rectf(x, y, w, h, selected ? theme::SELECT : FL_WHITE);
		note_icon_->draw(x + 2, y + 2, note_icon_->w(), note_icon_->h());
		fl_font(theme::fonts().sans, 11);
		fl_color(selected ? FL_WHITE : FL_BLACK);
		fl_draw(escape_symbols(line.title).c_str(), x + 17, y, w - 32, h, FL_ALIGN_LEFT | FL_ALIGN_CLIP);
		if (line.is_private) {
			key_icon_->draw(x + w - key_icon_->w() - 3, y + 3, key_icon_->w(), key_icon_->h());
		}
		break;
	}
	default:
		break;
	}
}

int MemoTable::handle(int event) {
	// FLTK's own handler runs first; the list events then see the event, and their result joins FLTK's, as fltk-rs
	// does.
	const int ret = table_handle(event);
	const bool used = records::list_event(*this, event, lead_, s_, LIST_MSGS);
	return ret | (used ? 1 : 0);
}

MemoView::MemoView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings)
    : s_(std::move(s)), lead_(std::make_shared<int>(-1)),
      filter_id_(static_cast<std::int32_t>(settings.get_int("memoCategory", category::ALL))) {
	const auto& fonts = theme::fonts();
	const split::Split sp = split::split(x, y, w, h, settings.get_int("memoSplit", 416), "memoSplit", s_);
	root = sp.outer;

	// The list side: the title, the filter, the list, and the List by and New buttons.
	sp.left->begin();
	const int lx = sp.left->x();
	const int ly = sp.left->y();
	const int lw = sp.left->w();
	const int lh = sp.left->h();
	auto* title = new Fl_Box(lx + 4, ly + 4, 112, 22, "Memo Pad");
	title->box(FL_DOWN_BOX);
	title->labelfont(fonts.arial_bold);
	title->labelsize(14);
	title->align(FL_ALIGN_CENTER | FL_ALIGN_INSIDE);
	auto* filter_menu = new combo::Choice(lx + lw - 114, ly + 4, 110, 22);
	draw::style_menu(filter_menu);
	filter_.emplace(filter_menu, 1, s_, []() -> msg::Msg { return msg::Memo{msg::MemoMsg{msg::memo::Filter{}}}; });

	table_ = new MemoTable(lx + 4, ly + 30, lw - 8, lh - 30 - 34, lead_, s_);

	auto* list_by_b = new Fl_Button(lx + lw - 166, ly + lh - 29, 75, 23, "&List by...");
	list_by_b->callback(list_by_pressed, &s_);
	auto* new_b = new Fl_Button(lx + lw - 85, ly + lh - 29, 75, 23, "&New");
	new_b->callback(new_pressed, &s_);
	// The title stays, the filter and the buttons keep to the right and the bottom, the list grows. FLTK widens a child
	// whose span holds the resizable's left edge and moves one to the right of it, so the resizable is 1 px wide: a
	// width taken from the pane would go negative for a narrow list, and FLTK would then slide the list in place of
	// widening it.
	auto* grow = new Fl_Box(lx + 120, ly + 30, 1, lh - 64);
	sp.left->resizable(grow);
	sp.left->end();

	// The detail side: the editor, then Category, Private, and Apply.
	sp.right->begin();
	const int rx = sp.right->x();
	const int ry = sp.right->y();
	const int rw = sp.right->w();
	const int rh = sp.right->h();
	buf_ = std::make_unique<Fl_Text_Buffer>();
	editor_ = new text_edit::Editor(rx + 8, ry + 8, rw - 16, rh - 8 - 64);
	editor_->buffer(buf_.get());
	editor_->wrap_mode(Fl_Text_Display::WRAP_AT_BOUNDS, 0);
	editor_->textfont(fonts.arial);
	editor_->textsize(12);
	editor_->scrollbar_align(FL_ALIGN_RIGHT);
	const int fy = ry + rh - 54;
	auto* cat_label = new Fl_Box(rx + 8, fy, 60, 22, "Category:");
	cat_label->align(FL_ALIGN_RIGHT | FL_ALIGN_INSIDE);
	auto* category_menu = new combo::Choice(rx + 72, fy, rw - 80, 22);
	draw::style_menu(category_menu);
	category_.emplace(category_menu, 1, s_, nullptr);
	auto* private_label = new Fl_Box(rx + 8, fy + 26, 60, 22, "Private:");
	private_label->align(FL_ALIGN_RIGHT | FL_ALIGN_INSIDE);
	private_box_ = draw::check(rx + 72, fy + 28, 18, 18, "");
	apply_ = new Fl_Button(rx + rw - 83, fy + 26, 75, 23, "&Apply");
	apply_->callback(apply_pressed, &s_);
	auto* detail_grow = new Fl_Box(rx + 80, ry + 8, rw - 170, rh - 72);
	sp.right->resizable(detail_grow);
	sp.right->end();
}

MemoView::~MemoView() = default;

Fl_Text_Editor* MemoView::editor() const {
	return editor_;
}

records::RecordTable* MemoView::table() const {
	return table_;
}

void MemoView::focus() {
	(void)table_->take_focus();
}

std::vector<std::size_t> MemoView::selected_rows() const {
	return records::selected_rows(*table_, shown_.size());
}

std::vector<model::Memo> MemoView::selected() const {
	std::vector<model::Memo> out;
	for (const std::size_t i : selected_rows()) {
		out.push_back(shown_[i]);
	}
	return out;
}

std::optional<model::Memo> MemoView::lead_memo() const {
	const int l = *lead_;
	if (l >= 0 && static_cast<std::size_t>(l) < shown_.size() && table_->row_selected(l)) {
		return shown_[static_cast<std::size_t>(l)];
	}
	const auto rows = selected_rows();
	if (rows.empty()) {
		return std::nullopt;
	}
	return shown_[rows.front()];
}

std::string MemoView::position() const {
	const auto rows = selected_rows();
	if (rows.empty()) {
		return "";
	}
	return std::to_string(rows.front() + 1) + " of " + std::to_string(shown_.size());
}

text_edit::EditState MemoView::list_edit_state() const {
	return records::list_state(!selected_rows().empty(), !shown_.empty());
}

void MemoView::refresh(view::Ctx& ctx) {
	const auto old = current_;
	const bool dirty = has_changes();
	reflow(ctx, old, dirty);
}

void MemoView::reflow(view::Ctx& ctx, std::optional<model::Memo> old, bool dirty) {
	refresh_combos(ctx);
	const auto hit = rebuild_list(ctx, old ? std::optional<std::int64_t>(old->id) : std::nullopt);
	const bool same = hit && old && hit->id == old->id;
	if (same && !dirty) {
		load_editor(hit);
	} else if (same) {
		current_ = hit;
	} else if (hit && dirty) {
		current_ = hit;
	} else {
		load_editor(hit);
	}
}

std::vector<model::Memo> MemoView::visible(const view::Ctx& ctx) const {
	std::vector<model::Memo> shown;
	for (const auto& m : ctx.store.memos) {
		if (!ctx.show_private && m.is_private) {
			continue;
		}
		if (filter_id_ != category::ALL && m.category != filter_id_) {
			continue;
		}
		shown.push_back(m);
	}
	return model::memo_order(shown, ctx.settings.get("memoSort", "0") == "1");
}

std::optional<model::Memo> MemoView::rebuild_list(const view::Ctx& ctx, std::optional<std::int64_t> want) {
	std::vector<std::int64_t> keep;
	for (const auto& m : selected()) {
		keep.push_back(m.id);
	}
	const auto lead = lead_memo();
	shown_ = visible(ctx);
	table_->lines_.clear();
	for (const auto& m : shown_) {
		table_->lines_.push_back(MemoTable::Row{m.title(), m.is_private});
	}
	table_->rows(static_cast<int>(shown_.size()));
	// New rows take FLTK's default height; the Memo List rows are 16 px.
	table_->row_height_all(16);
	table_->select_all_rows(0);
	for (std::size_t i = 0; i < shown_.size(); ++i) {
		if (std::find(keep.begin(), keep.end(), shown_[i].id) != keep.end()) {
			(void)table_->select_row(static_cast<int>(i), 1);
		}
	}
	int lead_row = -1;
	if (lead) {
		const auto it =
		    std::find_if(shown_.begin(), shown_.end(), [&](const model::Memo& m) { return m.id == lead->id; });
		if (it != shown_.end()) {
			lead_row = static_cast<int>(it - shown_.begin());
		}
	}
	*lead_ = lead_row;
	if (selected_rows().empty()) {
		pick(want);
	}
	table_->redraw();
	return lead_memo();
}

void MemoView::pick(std::optional<std::int64_t> id) {
	std::optional<std::size_t> row;
	if (id) {
		const auto it = std::find_if(shown_.begin(), shown_.end(), [&](const model::Memo& m) { return m.id == *id; });
		if (it != shown_.end()) {
			row = static_cast<std::size_t>(it - shown_.begin());
		}
	} else if (!shown_.empty()) {
		row = 0;
	}
	records::pick_row(*table_, *lead_, row);
}

void MemoView::refresh_combos(const view::Ctx& ctx) {
	const std::int32_t keep = category_->id();
	std::vector<std::pair<std::string, std::int32_t>> cats;
	for (const auto& c : ctx.store.memo_categories) {
		cats.emplace_back(c.name, c.id);
	}
	std::vector<std::pair<std::string, std::int32_t>> filters{{"All", category::ALL}};
	filters.insert(filters.end(), cats.begin(), cats.end());
	filters.emplace_back("Unfiled", category::UNFILED);
	filter_->set_items(filters, true, filter_id_);
	filter_id_ = filter_->id();
	std::vector<std::pair<std::string, std::int32_t>> items{{"Unfiled", category::UNFILED}};
	items.insert(items.end(), cats.begin(), cats.end());
	category_->set_items(items, true, keep);
}

void MemoView::load_editor(std::optional<model::Memo> m) {
	buf_->text(m ? m->text.c_str() : "");
	editor_->insert_position(0);
	category_->select(m ? m->category : category::UNFILED);
	private_box_->value(m && m->is_private ? 1 : 0);
	const bool on = m.has_value();
	for (Fl_Widget* w : {static_cast<Fl_Widget*>(editor_), static_cast<Fl_Widget*>(category_->choice),
	                     static_cast<Fl_Widget*>(private_box_), static_cast<Fl_Widget*>(apply_)}) {
		if (on) {
			w->activate();
		} else {
			w->deactivate();
		}
	}
	current_ = std::move(m);
}

bool MemoView::has_changes() const {
	if (!current_) {
		return false;
	}
	return text_edit::buffer_text(*buf_) != current_->text || category_->id() != current_->category ||
	       (private_box_->value() != 0) != current_->is_private;
}

void MemoView::save_if_changed(view::Ctx& ctx) {
	if (has_changes()) {
		apply_edits(ctx);
	}
}

void MemoView::apply_edits(view::Ctx& ctx) {
	if (!current_) {
		return;
	}
	model::Memo m = *current_;
	m.text = text_edit::buffer_text(*buf_);
	m.category = category_->id();
	m.is_private = private_box_->value() != 0;
	if (model::is_blank(m.text)) {
		ctx.store.delete_memo(m.id);
	} else {
		ctx.store.save_memo(m);
	}
	current_ = m;
}

model::Memo MemoView::new_memo(const std::string& text) const {
	model::Memo m;
	m.text = text;
	m.category = std::max(filter_id_, category::UNFILED);
	return m;
}

void MemoView::new_record(view::Ctx& ctx) {
	save_if_changed(ctx);
	model::Memo m = new_memo("");
	ctx.store.save_memo(m);
	reflow(ctx, m, false);
	pick(m.id);
	load_editor(m);
	(void)editor_->take_focus();
}

void MemoView::delete_selected(view::Ctx& ctx) {
	const auto ms = selected();
	if (ms.empty() || !dialogs::confirm_delete_titled(ctx.settings, ctx.no_confirm, "Delete Memo Pad Items",
	                                                  "Delete the selected Memo Pad item(s)?")) {
		return;
	}
	load_editor(std::nullopt);
	for (const auto& m : ms) {
		ctx.store.delete_memo(m.id);
	}
}

void MemoView::select(view::Ctx& ctx, std::int64_t id) {
	const auto it =
	    std::find_if(ctx.store.memos.begin(), ctx.store.memos.end(), [id](const model::Memo& m) { return m.id == id; });
	if (it == ctx.store.memos.end()) {
		return;
	}
	const model::Memo m = *it;
	save_if_changed(ctx);
	if (filter_id_ != category::ALL && m.category != filter_id_) {
		filter_id_ = category::ALL;
		ctx.set("memoCategory", std::to_string(category::ALL));
	}
	load_editor(m);
	reflow(ctx, m, false);
	pick(m.id);
}

std::pair<std::string, std::vector<std::string>> MemoView::print_lines() const {
	if (!current_) {
		std::vector<std::string> titles;
		for (std::size_t i = 0; i < shown_.size(); ++i) {
			titles.push_back(std::to_string(i + 1) + ". " + shown_[i].title());
		}
		return {"Memo Pad", titles};
	}
	const std::vector<std::string> lines = lines_of(text_edit::buffer_text(*buf_));
	const std::string title = lines.empty() ? std::string() : std::string(text::trim(lines.front()));
	return {title, lines};
}

void MemoView::list_by(view::Ctx& ctx) {
	const std::size_t now = ctx.settings.get("memoSort", "0") == "1" ? 1 : 0;
	if (const auto order = dialogs::list_by("Record Order", {"&Order on handheld", "&Alphabetical"}, now)) {
		ctx.set("memoSort", std::to_string(*order));
		refresh(ctx);
	}
}

void MemoView::handle(msg::MemoMsg event, view::Ctx& ctx) {
	if (std::holds_alternative<msg::memo::Picked>(event)) {
		const auto next = lead_memo();
		const auto cur_id = current_ ? std::optional<std::int64_t>(current_->id) : std::nullopt;
		const auto next_id = next ? std::optional<std::int64_t>(next->id) : std::nullopt;
		if (next_id != cur_id) {
			save_if_changed(ctx);
			// The save can rebuild the list; read the lead again.
			std::optional<model::Memo> fresh;
			if (next) {
				const auto it = std::find_if(ctx.store.memos.begin(), ctx.store.memos.end(),
				                             [&](const model::Memo& m) { return m.id == next->id; });
				if (it != ctx.store.memos.end()) {
					fresh = *it;
				}
			}
			load_editor(fresh);
		}
	} else if (const auto* typed = std::get_if<msg::memo::TypeNew>(&event)) {
		new_record(ctx);
		std::string s;
		text::append_utf8(s, typed->ch);
		buf_->text(s.c_str());
		editor_->insert_position(buf_->length());
	} else if (std::holds_alternative<msg::memo::New>(event)) {
		new_record(ctx);
	} else if (std::holds_alternative<msg::memo::Apply>(event)) {
		apply_edits(ctx);
	} else if (std::holds_alternative<msg::memo::Filter>(event)) {
		save_if_changed(ctx);
		filter_id_ = filter_->id();
		ctx.set("memoCategory", std::to_string(filter_id_));
		const auto cur = current_;
		reflow(ctx, cur, false);
	} else if (std::holds_alternative<msg::memo::ListMenu>(event)) {
		const auto picked = records::list_menu(list_edit_state(), {});
		// The popup covered rows of the list, and rows it covered were not repainted when it closed.
		table_->redraw();
		if (picked) {
			list_action(*picked, ctx);
		}
	} else if (const auto* paste = std::get_if<msg::memo::PasteText>(&event)) {
		paste_text(ctx, paste->text);
	}
}

void MemoView::list_action(records::ListAction a, view::Ctx& ctx) {
	if (std::holds_alternative<records::action::Copy>(a) || std::holds_alternative<records::action::Cut>(a)) {
		const bool cut = std::holds_alternative<records::action::Cut>(a);
		const auto ms = selected();
		if (ms.empty()) {
			return;
		}
		std::string text;
		std::vector<runner::Record> recs;
		for (std::size_t i = 0; i < ms.size(); ++i) {
			if (i > 0) {
				text += "\n";
			}
			text += ms[i].text;
			recs.emplace_back(ms[i]);
		}
		records::put("memo", std::move(recs), std::move(text));
		if (cut) {
			load_editor(std::nullopt);
			for (const auto& m : ms) {
				ctx.store.delete_memo(m.id);
			}
		}
	} else if (std::holds_alternative<records::action::Paste>(a)) {
		Fl::paste(*table_, 1);
	} else if (std::holds_alternative<records::action::Delete>(a)) {
		delete_selected(ctx);
	} else if (std::holds_alternative<records::action::SelectAll>(a)) {
		table_->select_all_rows(1);
		table_->redraw();
	}
}

void MemoView::paste_text(view::Ctx& ctx, const std::string& text) {
	const auto recs = records::records_for("memo", text);
	if (recs) {
		for (const auto& r : *recs) {
			if (const auto* m = std::get_if<model::Memo>(&r)) {
				model::Memo copy = *m;
				copy.id = 0;
				copy.uid.clear();
				ctx.store.save_memo(copy);
			}
		}
	} else {
		model::Memo m = new_memo(text);
		ctx.store.save_memo(m);
	}
}

} // namespace palm::memo
