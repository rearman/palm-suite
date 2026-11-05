// The To Do List view, as desktop-rs/app/src/todo.rs. See todo.hpp.
#include "palm/todo.hpp"

#include "palm/combo.hpp"
#include "palm/date_picker.hpp"
#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/note_dialog.hpp"
#include "palm/platform.hpp"
#include "palm/runner.hpp"
#include "palm/split.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_Output.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Table_Row.H>
#include <FL/Fl_Text_Buffer.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <format>
#include <limits>
#include <string>
#include <utility>
#include <variant>
#include <vector>

namespace palm::todo {

// The columns of the table.
enum class Col {
	Done,
	Priority,
	Title,
	Due,
	Category,
	// The note and private icons.
	Marks,
};

// One row of the table as it draws.
struct Row {
	model::Todo todo;
	std::string category;
	std::string due;
	bool overdue;
};

namespace {

constexpr int ROW_H = 16;

// The width of the Due box of the details; the day name takes the rest of the row, as in Palm Desktop 3.1.1.
constexpr int DUE_W = 90;

// The Sort by menu, in Palm Desktop 3.1.1 order.
constexpr std::array<std::string_view, 4> SORT_NAMES = {
    "Priority, Due Date",
    "Due Date, Priority",
    "Category, Due Date",
    "Category, Priority",
};

// The check boxes of the View Options group: the label, the setting, and its default.
struct CheckSpec {
	std::string_view label;
	std::string_view key;
	bool def;
};

constexpr std::array<CheckSpec, 6> VIEW_CHECKS = {{
    {"Show &completed items", "todoShowDone", false},
    {"Show only due &items", "todoOnlyDue", false},
    {"&Record completion date", "todoRecordDone", false},
    {"Show &due dates", "todoShowDue", true},
    {"Show &priorities", "todoShowPri", false},
    {"Show cate&gories", "todoShowCat", true},
}};

// A To Do message of the event loop.
msg::Msg todo_msg(msg::TodoMsg e) {
	return msg::Msg{msg::Todo{std::move(e)}};
}

// Sends a To Do message from a widget callback, where `data` is the sender of the view.
void send_todo(void* data, msg::TodoMsg e) {
	static_cast<msg::Sender*>(data)->send(todo_msg(std::move(e)));
}

msg::Msg filter_changed() {
	return todo_msg(msg::todo::Filter{});
}

// The title with each newline as a space, as the table and the print list show it.
std::string flat_title(std::string s) {
	std::ranges::replace(s, '\n', ' ');
	return s;
}

// The first line of `s`, without its line break (Rust's str::lines).
std::string_view first_line(std::string_view s) {
	auto line = s.substr(0, s.find('\n'));
	if (!line.empty() && line.back() == '\r') {
		line.remove_suffix(1);
	}
	return line;
}

// The text of the Due box: M/d/yyyy.
std::string date_text(std::int64_t due) {
	const auto d = days::ymd(due);
	return std::format("{}/{}/{}", d.month, d.day, d.year);
}

// The width of a fixed column; the To Do text takes the rest of the width.
int fixed_width(Col c) {
	switch (c) {
	case Col::Done:
		return 20;
	// With the 4 px pad, 10 px from the priority to the To Do text, as in Palm Desktop.
	case Col::Priority:
		return 18;
	case Col::Due:
		return 45;
	case Col::Category:
		return 50;
	case Col::Marks:
		return 34;
	case Col::Title:
		return 0;
	}
	return 0;
}

// The columns the Show Options ask for.
std::vector<Col> columns(const Settings& settings) {
	std::vector<Col> c = {Col::Done};
	if (settings.get_bool("todoShowPri", false)) {
		c.push_back(Col::Priority);
	}
	c.push_back(Col::Title);
	if (settings.get_bool("todoShowDue", true)) {
		c.push_back(Col::Due);
	}
	if (settings.get_bool("todoShowCat", true)) {
		c.push_back(Col::Category);
	}
	c.push_back(Col::Marks);
	return c;
}

// The To Do item with `id` in `desk`, or nullptr.
const model::Todo* find_todo(const store::DesktopStore& desk, std::int64_t id) {
	for (const auto& t : desk.todos) {
		if (t.id == id) {
			return &t;
		}
	}
	return nullptr;
}

// A new item with `title`, in the category the filter shows, as the New button makes it.
model::Todo new_todo(const Settings& settings, std::string_view title) {
	model::Todo t;
	t.title = std::string(title);
	t.priority = 1;
	t.category = std::max(settings.get_int("todoCategory", category::ALL), category::UNFILED);
	return t;
}

// Makes `t` show in the table by changing the filters when needed.
void reveal(const model::Todo& t, Settings& settings, std::int64_t today) {
	const auto cat = settings.get_int("todoCategory", category::ALL);
	if (cat != category::ALL && t.category != cat) {
		static_cast<void>(settings.set_int("todoCategory", category::ALL));
	}
	if (t.done && !settings.get_bool("todoShowDone", false)) {
		static_cast<void>(settings.set_bool("todoShowDone", true));
	}
	if (settings.get_bool("todoOnlyDue", false) && !is_due_by_today(t, today)) {
		static_cast<void>(settings.set_bool("todoOnlyDue", false));
	}
}

// The items the table shows: the filter's category, the items the Show Options let show, in their order.
std::vector<model::Todo> visible(const view::Ctx& ctx, std::int64_t today) {
	const auto cat = ctx.settings.get_int("todoCategory", category::ALL);
	std::vector<model::Todo> list;
	for (const auto& t : ctx.store.todos) {
		if ((cat == category::ALL || t.category == cat) && todo_shown(t, ctx.settings, ctx.show_private, today)) {
			list.push_back(t);
		}
	}
	return todo_order(std::move(list), ctx.settings, ctx.store);
}

// Shows a menu of `labels` at the pointer; the index of the pick.
std::optional<std::size_t> popup(const std::vector<std::string>& labels) {
	Fl_Menu_Button mb(0, 0, 0, 0);
	mb.type(Fl_Menu_Button::POPUP3);
	for (const auto& l : labels) {
		mb.add(l.c_str());
	}
	draw::style_menu(&mb);
	if (!mb.popup()) {
		return std::nullopt;
	}
	const int v = mb.value();
	if (v < 0) {
		return std::nullopt;
	}
	return static_cast<std::size_t>(v);
}

// Fits the fixed columns and gives the To Do text the rest of the width inside the edge and the scroll bar.
void fit_columns(Fl_Table& t, const std::vector<Col>& cols) {
	const int room = t.w() - 4 - (t.rows() * ROW_H > t.h() - 4 ? 17 : 0);
	int fixed = 0;
	for (const Col c : cols) {
		fixed += fixed_width(c);
	}
	for (std::size_t i = 0; i < cols.size(); ++i) {
		const int w = cols[i] == Col::Title ? std::max(room - fixed, 20) : fixed_width(cols[i]);
		if (t.col_width(static_cast<int>(i)) != w) {
			t.col_width(static_cast<int>(i), w);
		}
	}
}

// One cell of the table. A selected row is filled; an overdue item is red, yellow when selected, as in Palm Desktop.
void paint_cell(const Row& r, Col kind, bool selected, draw::Rect rect, Fl_Image* note_img, Fl_Image* private_img) {
	const auto& fonts = theme::fonts();
	const auto [x, y, w, h] = rect;
	// The selection fills the title only; the other cells stay white.
	const bool filled = selected && kind == Col::Title;
	fl_rectf(x, y, w, h, filled ? theme::SELECT : FL_WHITE);
	// Only the title of an overdue item is red; the other cells are black on white.
	Fl_Color color = FL_BLACK;
	if (kind == Col::Title) {
		if (selected) {
			color = r.overdue ? FL_YELLOW : FL_WHITE;
		} else if (r.overdue) {
			color = FL_RED;
		}
	}
	fl_font(fonts.sans, 11);
	fl_color(color);
	const int base = y + (h - fl_height()) / 2 + fl_height() - fl_descent();
	switch (kind) {
	case Col::Done:
		draw_check(x + (w - 13) / 2, y + (h - 13) / 2, r.todo.done);
		break;
	case Col::Priority:
		fl_draw(std::to_string(r.todo.priority).c_str(), x + 5, base);
		break;
	case Col::Title:
		fl_draw(draw::fit(flat_title(r.todo.title), w - 4, draw::text_w).c_str(), x + 2, base);
		break;
	case Col::Due: {
		// The overdue "!" is bold, as Palm Desktop draws it.
		std::string plain = r.due;
		while (!plain.empty() && plain.back() == '!') {
			plain.pop_back();
		}
		fl_draw(plain.c_str(), x + 2, base);
		if (plain.size() < r.due.size()) {
			const int at = x + 2 + draw::text_w(plain);
			fl_font(fonts.sans_bold, 11);
			fl_draw("!", at, base);
		}
		break;
	}
	case Col::Category: {
		// Cut hard at the edge of the column (the cell clip), with no "...", as Palm Desktop does.
		const draw::Clip clip(x, y, w, h);
		fl_draw(r.category.c_str(), x + 2, base);
		break;
	}
	case Col::Marks: {
		int ix = x + 2;
		const std::array<std::pair<bool, Fl_Image*>, 2> marks = {
		    std::pair{!r.todo.note.empty(), note_img},
		    std::pair{r.todo.is_private, private_img},
		};
		for (const auto& [on, img] : marks) {
			if (on) {
				img->draw(ix, y + (h - img->h()) / 2, img->w(), img->h());
				ix += img->w() + 2;
			}
		}
		break;
	}
	}
}

// The record messages of the To Do table. A printable key starts a new item that holds it.
const records::ListMsgs kListMsgs{
    .picked = +[]() -> msg::Msg { return todo_msg(msg::todo::Picked{}); },
    .menu = +[]() -> msg::Msg { return todo_msg(msg::todo::ListMenu{}); },
    .paste = +[](std::string text) -> msg::Msg { return todo_msg(msg::todo::PasteText{std::move(text)}); },
    .typed = +[](char32_t c) -> msg::Msg { return todo_msg(msg::todo::TypeNew{c}); },
    .open = std::nullopt,
    .cell =
        +[](int row, int column, bool second) -> msg::Msg { return todo_msg(msg::todo::Cell{row, column, second}); },
};

// The titles of `list`, one to a line, and the records that a Cut or Copy keeps.
std::pair<std::string, std::vector<runner::Record>> clipboard_of(const std::vector<model::Todo>& list) {
	std::string text;
	std::vector<runner::Record> recs;
	for (std::size_t i = 0; i < list.size(); ++i) {
		if (i > 0) {
			text += '\n';
		}
		text += list[i].title;
		recs.emplace_back(list[i]);
	}
	return {std::move(text), std::move(recs)};
}

// A drop-down that is the combo::Choice of this view, styled as a Windows menu.
combo::Choice* styled_choice(combo::Choice* c) {
	draw::style_menu(c);
	return c;
}

// Moves the focus to the field that a field label names.
void focus_target(Fl_Widget*, void* target) {
	static_cast<void>(static_cast<Fl_Widget*>(target)->take_focus());
}

// The state of the Show Options dialog while it runs.
struct ShowOptionsState {
	Fl_Window* win = nullptr;
	bool saved = false;
};

void show_options_ok(Fl_Widget*, void* data) {
	auto* st = static_cast<ShowOptionsState*>(data);
	st->saved = true;
	st->win->hide();
}

void show_options_cancel(Fl_Widget*, void* data) {
	static_cast<Fl_Window*>(data)->hide();
}

// The Show Options dialog: the sort order, the view filters and the columns of the list. Saves them and returns true
// on OK.
bool show_options(Settings& settings) {
	auto* win = new Fl_Double_Window(291, 249, "Show Options");
	dialogs::key_label("&Sort by", 12, 22, 40, 22, FL_ALIGN_LEFT);
	auto* sort = styled_choice(new combo::Choice(56, 22, 124, 22, nullptr));
	for (const auto n : SORT_NAMES) {
		sort->add(std::string(n).c_str());
	}
	sort->value(static_cast<int>(menu_sort(sort_index(settings))));
	auto* view = new Fl_Group(14, 70, 162, 156);
	view->box(FL_ENGRAVED_BOX);
	std::vector<std::pair<std::string_view, Fl_Check_Button*>> boxes;
	for (std::size_t i = 0; i < VIEW_CHECKS.size(); ++i) {
		const auto& spec = VIEW_CHECKS[i];
		const int y = 85 + 21 * static_cast<int>(i) + (i >= 3 ? 12 : 0);
		Fl_Check_Button* b = draw::check(24, y, 160, 20, spec.label);
		b->value(settings.get_bool(spec.key, spec.def) ? 1 : 0);
		boxes.emplace_back(spec.key, b);
	}
	view->end();
	// The title sits on the top edge of the group, as in Palm Desktop 3.1.1.
	dialogs::group_title(14, 70, "View Options");
	ShowOptionsState st{win, false};
	auto* ok = new Fl_Button(202, 12, 75, 23, "OK");
	auto* cancel = new Fl_Button(202, 41, 75, 23, "Cancel");
	ok->shortcut(FL_Enter);
	ok->callback(show_options_ok, &st);
	cancel->callback(show_options_cancel, win);
	win->end();
	static_cast<void>(ok->take_focus());
	dialogs::show_modal(win);
	while (win->shown()) {
		Fl::wait();
	}
	mnemonics::forget(win);
	const bool saved = st.saved;
	if (saved) {
		const auto sort_saved = menu_sort(static_cast<std::size_t>(std::max(sort->value(), 0)));
		static_cast<void>(settings.set_int("todoSortBy", static_cast<int>(sort_saved)));
		for (const auto& [key, b] : boxes) {
			static_cast<void>(settings.set_bool(key, b->value() != 0));
		}
	}
	delete win;
	return saved;
}

} // namespace

// The Due box of the details: a Choice that draws the due date over its closed field, as the box of Palm Desktop.
class DueChoice : public combo::Choice {
public:
	using combo::Choice::Choice;

	// The date that the closed box shows, or a negative value for none. The view owns it.
	const std::int64_t* due = nullptr;

	void draw() override {
		Fl_Choice::draw();
		if (due && *due >= 0) {
			const auto& fonts = theme::fonts();
			fl_font(fonts.sans, 11);
			fl_color(active() ? FL_BLACK : fl_rgb_color(128, 128, 128));
			const std::string text = date_text(*due);
			fl_draw(text.c_str(), x() + 4, y(), w() - 24, h(), FL_ALIGN_LEFT);
		}
	}
};

// The table of the list. It is a record list: its events are the ones the Date Book, the Memo Pad, and the Address
// Book share, and its cells are drawn here.
class TodoTable : public records::RecordTable {
public:
	TodoTable(int x, int y, int w, int h, msg::Sender s)
	    : records::RecordTable(x, y, w, h), lead(std::make_shared<int>(-1)), sender_(std::move(s)),
	      note_img_(theme::icon("note")), private_img_(theme::icon("private")) {
		type(Fl_Table_Row::SELECT_MULTI);
		rows(0);
		cols(0);
		row_height_all(ROW_H);
		col_header(0);
		row_header(0);
		box(FL_DOWN_BOX);
		color(FL_WHITE);
		end();
	}

	// The rows of the table, in table order, and the columns the Show Options ask for.
	std::vector<Row> lines;
	std::vector<Col> columns;
	// The lead row, shared with the view.
	std::shared_ptr<int> lead;

	int handle(int ev) override {
		// FLTK's own handler runs first; the record events then see the event, and their result joins FLTK's.
		const int ret = table_handle(ev);
		const bool taken = records::list_event(*this, ev, lead, sender_, kListMsgs);
		return ret | (taken ? 1 : 0);
	}

	void draw_cell(TableContext context, int R = 0, int C = 0, int X = 0, int Y = 0, int W = 0, int H = 0) override {
		switch (context) {
		case CONTEXT_STARTPAGE:
			fit_columns(*this, columns);
			break;
		case CONTEXT_CELL: {
			if (R < 0 || static_cast<std::size_t>(R) >= lines.size() || C < 0 ||
			    static_cast<std::size_t>(C) >= columns.size()) {
				return;
			}
			const draw::Clip clip = draw::Clip::cell(this, X, Y, W, H);
			paint_cell(lines[static_cast<std::size_t>(R)], columns[static_cast<std::size_t>(C)], row_selected(R) != 0,
			           draw::Rect{X, Y, W, H}, note_img_.get(), private_img_.get());
			break;
		}
		default:
			break;
		}
	}

private:
	msg::Sender sender_;
	std::unique_ptr<Fl_PNG_Image> note_img_;
	std::unique_ptr<Fl_PNG_Image> private_img_;
};

TodoView::TodoView(int x, int y, int w, int h, msg::Sender s, const Settings& settings) : s_(std::move(s)) {
	const auto& fonts = theme::fonts();
	const split::Split sp =
	    split::split_grow(x, y, w, h, settings.get_int("todoSplit", 272), "todoSplit", split::Grow::Share, s_);
	root = sp.outer;

	// The list side: the title, the filter, the table, and the Show and New buttons.
	sp.left->begin();
	const int lx = sp.left->x();
	const int ly = sp.left->y();
	const int lw = sp.left->w();
	const int lh = sp.left->h();
	auto* title = new Fl_Box(lx + 4, ly + 4, 112, 22, "To Do List");
	title->box(FL_DOWN_BOX);
	title->labelfont(fonts.arial_bold);
	title->labelsize(14);
	title->align(static_cast<Fl_Align>(FL_ALIGN_CENTER | FL_ALIGN_INSIDE));
	filter_.emplace(styled_choice(new combo::Choice(lx + lw - 114, ly + 4, 110, 22, nullptr)), 0, s_, filter_changed);
	table_ = new TodoTable(lx + 4, ly + 30, lw - 8, lh - 30 - 34, s_);
	table_->columns = columns(settings);
	table_->cols(static_cast<int>(table_->columns.size()));
	lead_ = table_->lead;
	auto* show_b = new Fl_Button(lx + lw - 166, ly + lh - 29, 75, 23, "Sh&ow...");
	show_b->callback([](Fl_Widget*, void* sender) { send_todo(sender, msg::todo::Show{}); }, &s_);
	auto* new_b = new Fl_Button(lx + lw - 85, ly + lh - 29, 75, 23, "&New");
	new_b->callback([](Fl_Widget*, void* sender) { send_todo(sender, msg::todo::New{}); }, &s_);
	// FLTK widens a child whose span holds the resizable's left edge and moves one to the right of it, so the resizable
	// is 1 px wide: a width taken from the pane would go negative for a narrow list, and FLTK would then slide the list
	// in place of widening it.
	auto* grow = new Fl_Box(lx + 120, ly + 30, 1, lh - 64, nullptr);
	sp.left->resizable(grow);
	sp.left->end();

	// The details: a label column at the left, the fields at the right.
	sp.right->begin();
	const int rx = sp.right->x();
	const int ry = sp.right->y();
	const int rw = sp.right->w();
	const int rh = sp.right->h();
	const int fx = rx + 66;
	const int fw = rw - 70;
	int y0 = ry + 3;
	auto label = [rx](std::string_view text, int h_, Fl_Widget* target, int ly_) {
		Fl_Button* b = dialogs::key_label(text, rx + 4, ly_, 58, std::min(h_, 22), FL_ALIGN_RIGHT);
		if (target) {
			b->callback(focus_target, target);
		}
	};

	text_buf_ = std::make_unique<Fl_Text_Buffer>();
	text_ = new text_edit::Editor(fx, y0, fw, 48);
	text_->buffer(text_buf_.get());
	text_->wrap_mode(Fl_Text_Display::WRAP_AT_BOUNDS, 0);
	text_->scrollbar_align(FL_ALIGN_RIGHT);
	label("&To Do:", 48, text_, y0);
	y0 += 48 + 6;

	auto* prio = new Fl_Group(fx, y0, fw, 23);
	prio->box(FL_DOWN_BOX);
	for (int n = 0; n < 5; ++n) {
		const int cell = (fw - 4) / 5;
		radios_[static_cast<std::size_t>(n)] =
		    draw::radio(fx + 2 + n * cell + cell / 2 - 14, y0 + 1, 30, 21, std::format("&{}", n + 1));
	}
	prio->end();
	label("P&riority:", 23, radios_[0], y0);
	y0 += 23 + 6;

	due_box_ = new DueChoice(fx, y0, DUE_W, 22);
	draw::style_menu(due_box_);
	for (const auto n : DUE_NAMES) {
		due_box_->add(std::string(n).c_str());
	}
	due_box_->value(-1);
	due_box_->due = &pending_due_;
	due_box_->callback([](Fl_Widget*, void* sender) { send_todo(sender, msg::todo::DuePick{}); }, &s_);
	due_day_ = new Fl_Output(fx + DUE_W + 4, y0, fw - DUE_W - 4, 22);
	due_day_->box(FL_DOWN_BOX);
	due_day_->color(theme::PANEL);
	label("D&ue:", 22, due_box_, y0);
	y0 += 22 + 6;

	category_.emplace(styled_choice(new combo::Choice(fx, y0, fw, 22, nullptr)), 0, s_, nullptr);
	label("Cate&gory:", 22, category_->choice, y0);
	y0 += 22 + 6;

	note_field_ = new Fl_Output(fx, y0 + 2, fw - 30, 22);
	note_field_->color(theme::PANEL);
	note_b_ = new Fl_Button(fx + fw - 26, y0, 26, 26);
	note_icon_ = theme::icon("note");
	note_deimage_ = theme::gray_icon("note");
	note_b_->image(note_icon_.get());
	note_b_->deimage(note_deimage_.get());
	note_b_->callback([](Fl_Widget*, void* sender) { send_todo(sender, msg::todo::Note{}); }, &s_);
	label("Not&e:", 26, note_b_, y0 + 2);
	y0 += 26 + 6;

	private_box_ = draw::check(fx, y0, 18, 21, "");
	label("Pri&vate:", 21, private_box_, y0);
	y0 += 21 + 6;

	complete_box_ = draw::check(fx, y0 + 1, 18, 21, "");
	apply_ = new Fl_Button(fx + fw - 75, y0, 75, 23, "&Apply");
	apply_->callback([](Fl_Widget*, void* sender) { send_todo(sender, msg::todo::Apply{}); }, &s_);
	label("Co&mplete:", 23, complete_box_, y0);
	y0 += 23 + 6;
	// The fields widen with the pane; the buttons at the right move and keep their size. FLTK widens a widget whose
	// span holds the resizable's left edge, moves one right of it and keeps one left of it, so a 1 px resizable inside
	// the day name box widens the day name, the To Do text, the priorities, the category and the note, keeps the Due
	// box and the check boxes, and moves the buttons.
	auto* grow_r = new Fl_Box(fx + DUE_W + 14, y0, 1, std::max(ry + rh - y0, 1), nullptr);
	sp.right->resizable(grow_r);
	sp.right->end();

	clear_details();
}

TodoView::~TodoView() = default;

Fl_Table_Row* TodoView::table() const {
	return table_;
}

text_edit::Editor* TodoView::editor() const {
	return text_;
}

void TodoView::focus() {
	static_cast<void>(table_->take_focus());
}

std::vector<std::size_t> TodoView::selected_rows() const {
	return records::selected_rows(*table_, shown_.size());
}

std::vector<model::Todo> TodoView::selected() const {
	std::vector<model::Todo> out;
	for (const auto i : selected_rows()) {
		out.push_back(shown_[i]);
	}
	return out;
}

std::optional<std::size_t> TodoView::lead_row() const {
	const int l = *lead_;
	if (l >= 0 && static_cast<std::size_t>(l) < shown_.size() && table_->row_selected(l) != 0) {
		return static_cast<std::size_t>(l);
	}
	const auto rows = selected_rows();
	if (rows.empty()) {
		return std::nullopt;
	}
	return rows.front();
}

std::string TodoView::position() const {
	if (const auto i = lead_row()) {
		return std::format("{} of {}", *i + 1, shown_.size());
	}
	return "";
}

text_edit::EditState TodoView::list_edit_state() const {
	return records::list_state(!selected_rows().empty(), !shown_.empty());
}

void TodoView::refresh(view::Ctx& ctx) {
	const auto cur = ctx.settings.get_int("todoCategory", category::ALL);
	std::vector<std::pair<std::string, std::int32_t>> filters = {{"All", category::ALL}};
	for (const auto& c : ctx.store.categories) {
		filters.emplace_back(c.name, c.id);
	}
	filters.emplace_back("Unfiled", category::UNFILED);
	const bool known = std::ranges::any_of(filters, [cur](const auto& f) { return f.second == cur; });
	if (!known) {
		ctx.set("todoCategory", std::to_string(category::ALL));
	}
	filter_->set_items(filters, true, cur);
	const auto keep = category_->id();
	std::vector<std::pair<std::string, std::int32_t>> items;
	for (const auto& c : ctx.store.categories) {
		items.emplace_back(c.name, c.id);
	}
	items.emplace_back("Unfiled", category::UNFILED);
	category_->set_items(items, true, keep);
	rebuild(ctx);
}

void TodoView::rebuild(const view::Ctx& ctx) {
	const auto today = platform::today();
	const auto keep = selected_id_;
	const auto find_index = [this](std::int64_t id) -> std::optional<std::size_t> {
		for (std::size_t i = 0; i < shown_.size(); ++i) {
			if (shown_[i].id == id) {
				return i;
			}
		}
		return std::nullopt;
	};
	std::optional<std::size_t> old_pos;
	if (keep) {
		old_pos = find_index(*keep);
	}
	shown_ = visible(ctx, today);
	std::optional<std::size_t> idx;
	if (keep) {
		idx = find_index(*keep);
	}
	// The selected item, else the row where it was (the next item when it left the list), else the first.
	std::optional<std::size_t> pick = idx;
	if (!pick && !shown_.empty()) {
		pick = std::min(old_pos.value_or(last_pos_), shown_.size() - 1);
	}
	if (pick) {
		last_pos_ = *pick;
	}
	table_->lines.clear();
	for (const auto& t : shown_) {
		table_->lines.push_back(Row{
		    .todo = t,
		    .category = ctx.store.category_name(t.category),
		    .due = due_label(t, today),
		    .overdue = is_overdue(t, today),
		});
	}
	const auto cols = columns(ctx.settings);
	if (table_->columns != cols) {
		table_->columns = cols;
		table_->cols(static_cast<int>(cols.size()));
	}
	table_->rows(static_cast<int>(shown_.size()));
	table_->row_height_all(ROW_H);
	records::pick_row(*table_, *lead_, pick);
	if (!pick) {
		clear_details();
	} else if (!idx || loaded_id_ != keep) {
		load_details(shown_[*pick]);
	} else if (!(loaded_ && *loaded_ == shown_[*pick]) && !has_edits()) {
		// A sync changed the item: show the new copy. Edits that are not applied yet stay, and Apply saves them.
		load_details(shown_[*pick]);
	}
}

void TodoView::set_controls(bool on) {
	std::vector<Fl_Widget*> ws = {
	    text_, due_box_, category_->choice, note_b_, private_box_, complete_box_, apply_,
	};
	for (auto* r : radios_) {
		ws.push_back(r);
	}
	for (Fl_Widget* w : ws) {
		if (on) {
			w->activate();
		} else {
			w->deactivate();
		}
	}
}

void TodoView::load_details(const model::Todo& t) {
	selected_id_ = t.id;
	loaded_id_ = t.id;
	loaded_ = t;
	text_buf_->text(t.title.c_str());
	text_->insert_position(0);
	const int p = std::clamp(t.priority, 1, 5);
	for (int i = 0; i < 5; ++i) {
		radios_[static_cast<std::size_t>(i)]->value(i + 1 == p ? 1 : 0);
	}
	pending_due_ = t.due;
	show_due();
	category_->select(t.category);
	pending_note_ = t.note;
	const std::string note_line(first_line(t.note));
	note_field_->value(note_line.c_str());
	private_box_->value(t.is_private ? 1 : 0);
	complete_box_->value(t.done ? 1 : 0);
	set_controls(true);
}

void TodoView::clear_details() {
	selected_id_.reset();
	loaded_id_.reset();
	loaded_.reset();
	text_buf_->text("");
	for (auto* r : radios_) {
		r->value(0);
	}
	pending_due_ = -1;
	show_due();
	category_->select(category::UNFILED);
	pending_note_.clear();
	note_field_->value("");
	private_box_->value(0);
	complete_box_->value(0);
	set_controls(false);
}

void TodoView::show_due() {
	due_box_->value(-1);
	due_box_->redraw();
	const std::string day_name =
	    pending_due_ < 0 ? std::string() : std::string(model::DOW_LONG[days::dow(pending_due_)]);
	due_day_->value(day_name.c_str());
}

int TodoView::priority() const {
	for (int i = 0; i < 5; ++i) {
		if (radios_[static_cast<std::size_t>(i)]->value() != 0) {
			return i + 1;
		}
	}
	return 0;
}

bool TodoView::has_edits() const {
	if (!loaded_) {
		return false;
	}
	const auto& t = *loaded_;
	return text_edit::buffer_text(*text_buf_) != t.title || priority() != t.priority || pending_due_ != t.due ||
	       category_->id() != t.category || pending_note_ != t.note || (private_box_->value() != 0) != t.is_private ||
	       (complete_box_->value() != 0) != t.done;
}

void TodoView::apply_fields(view::Ctx& ctx) {
	if (!selected_id_) {
		return;
	}
	const model::Todo* found = find_todo(ctx.store, *selected_id_);
	if (!found) {
		return;
	}
	model::Todo t = *found;
	const std::string title = text_edit::buffer_text(*text_buf_);
	t.title = std::string(text::trim(title));
	t.priority = std::max(priority(), 1);
	t.due = pending_due_;
	t.category = category_->id();
	t.note = pending_note_;
	t.is_private = private_box_->value() != 0;
	mark_done(t, complete_box_->value() != 0, ctx.settings, platform::today());
	ctx.store.save_todo(t);
	// Reload the details from the saved item.
	loaded_id_.reset();
}

void TodoView::save_from_cell(view::Ctx& ctx, model::Todo t) {
	ctx.store.save_todo(t);
	if (selected_id_ && *selected_id_ == t.id) {
		loaded_id_.reset();
	}
}

void TodoView::delete_selected(view::Ctx& ctx) {
	const auto list = selected();
	if (list.empty() || !dialogs::confirm_delete_titled(ctx.settings, ctx.no_confirm, "Delete To Do Items",
	                                                    "Delete the selected To Do item(s)?")) {
		return;
	}
	selected_id_.reset();
	for (const auto& t : list) {
		ctx.store.delete_todo(t.id);
	}
}

void TodoView::cell_click(view::Ctx& ctx, int row, int col, bool double_click) {
	if (row < 0 || static_cast<std::size_t>(row) >= shown_.size() || col < 0 ||
	    static_cast<std::size_t>(col) >= table_->columns.size()) {
		return;
	}
	model::Todo t = shown_[static_cast<std::size_t>(row)];
	const Col kind = table_->columns[static_cast<std::size_t>(col)];
	if (double_click && kind != Col::Done) {
		return;
	}
	switch (kind) {
	case Col::Done: {
		const bool done = !t.done;
		mark_done(t, done, ctx.settings, platform::today());
		save_from_cell(ctx, t);
		break;
	}
	case Col::Priority: {
		const std::vector<std::string> labels = {"1", "2", "3", "4", "5"};
		if (const auto i = popup(labels)) {
			t.priority = static_cast<std::int32_t>(*i) + 1;
			save_from_cell(ctx, t);
		}
		break;
	}
	case Col::Due: {
		std::vector<std::string> labels;
		for (const auto n : DUE_NAMES) {
			labels.emplace_back(n);
		}
		if (const auto pick = popup(labels)) {
			if (const auto d = due_for(*pick, t.due, platform::today(), ctx.settings)) {
				t.due = *d;
				save_from_cell(ctx, t);
			}
		}
		break;
	}
	case Col::Category: {
		std::vector<std::pair<std::string, std::int32_t>> items;
		for (const auto& c : ctx.store.categories) {
			items.emplace_back(c.name, c.id);
		}
		items.emplace_back("Unfiled", category::UNFILED);
		std::vector<std::string> labels;
		for (const auto& item : items) {
			labels.push_back(category::menu_label(item.first));
		}
		labels.emplace_back(category::EDIT_CATEGORIES);
		if (const auto pick = popup(labels)) {
			if (*pick < items.size()) {
				t.category = items[*pick].second;
				save_from_cell(ctx, t);
			} else {
				ctx.s.send(msg::Msg{msg::EditCategories{0}});
			}
		}
		break;
	}
	case Col::Title:
	case Col::Marks:
		break;
	}
}

void TodoView::handle(msg::TodoMsg m, view::Ctx& ctx) {
	std::visit(msg::Overload{
	               [this](const msg::todo::Picked&) {
		               const auto i = lead_row();
		               if (!i) {
			               return;
		               }
		               if (loaded_id_ != shown_[*i].id) {
			               load_details(shown_[*i]);
		               }
	               },
	               [this, &ctx](const msg::todo::Filter&) {
		               ctx.set("todoCategory", std::to_string(filter_->id()));
		               rebuild(ctx);
	               },
	               [this, &ctx](const msg::todo::TypeNew& e) {
		               new_record(ctx);
		               std::string typed;
		               text::append_utf8(typed, e.ch);
		               text_buf_->text(typed.c_str());
		               text_->insert_position(text_buf_->length());
	               },
	               [this, &ctx](const msg::todo::New&) { new_record(ctx); },
	               [this, &ctx](const msg::todo::Apply&) { apply_fields(ctx); },
	               [this, &ctx](const msg::todo::Show&) {
		               if (show_options(ctx.settings)) {
			               refresh(ctx);
		               }
	               },
	               [this, &ctx](const msg::todo::DuePick&) {
		               const int pick = due_box_->value();
		               due_box_->value(-1);
		               if (pick >= 0) {
			               if (const auto d = due_for(static_cast<std::size_t>(pick), pending_due_, platform::today(),
			                                          ctx.settings)) {
				               pending_due_ = *d;
			               }
		               }
		               show_due();
	               },
	               [this](const msg::todo::Note&) {
		               if (const auto edited = note_dialog::edit("Note Editor", pending_note_)) {
			               const std::string line(first_line(*edited));
			               note_field_->value(line.c_str());
			               pending_note_ = *edited;
		               }
	               },
	               [this, &ctx](const msg::todo::Cell& e) { cell_click(ctx, e.row, e.column, e.second_click); },
	               [this, &ctx](const msg::todo::ListMenu&) {
		               if (const auto a = records::list_menu(list_edit_state(), {})) {
			               list_action(*a, ctx);
		               }
	               },
	               [this, &ctx](const msg::todo::PasteText& e) { paste_text(ctx, e.text); },
	           },
	           m);
}

void TodoView::new_record(view::Ctx& ctx) {
	model::Todo t = new_todo(ctx.settings, "");
	ctx.store.save_todo(t);
	reveal(t, ctx.settings, platform::today());
	selected_id_ = t.id;
	loaded_id_.reset();
	refresh(ctx);
	static_cast<void>(text_->take_focus());
}

void TodoView::select(view::Ctx& ctx, std::int64_t id) {
	const model::Todo* found = find_todo(ctx.store, id);
	if (!found) {
		return;
	}
	const model::Todo t = *found;
	reveal(t, ctx.settings, platform::today());
	selected_id_ = id;
	loaded_id_.reset();
	refresh(ctx);
	static_cast<void>(table_->take_focus());
}

std::pair<std::string, std::vector<std::string>> TodoView::print_lines(const view::Ctx& ctx) const {
	std::vector<std::string> lines;
	for (const auto& t : shown_) {
		const std::string due = t.due < 0 ? std::string("-") : model::short_date(t.due);
		lines.push_back(std::format("{} {} {} {} {}", t.done ? "[x]" : "[ ]", t.priority, flat_title(t.title), due,
		                            ctx.store.category_name(t.category)));
	}
	return {"To Do List", std::move(lines)};
}

void TodoView::list_action(records::ListAction a, view::Ctx& ctx) {
	if (std::holds_alternative<records::action::Copy>(a) || std::holds_alternative<records::action::Cut>(a)) {
		const auto list = selected();
		if (list.empty()) {
			return;
		}
		auto [text, recs] = clipboard_of(list);
		records::put("todo", std::move(recs), std::move(text));
		if (std::holds_alternative<records::action::Cut>(a)) {
			selected_id_.reset();
			for (const auto& t : list) {
				ctx.store.delete_todo(t.id);
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

void TodoView::paste_text(view::Ctx& ctx, std::string_view clip) {
	if (auto recs = records::records_for("todo", clip)) {
		for (auto& r : *recs) {
			if (const auto* t = std::get_if<model::Todo>(&r)) {
				model::Todo copy = *t;
				copy.id = 0;
				copy.uid.clear();
				ctx.store.save_todo(copy);
			}
		}
		return;
	}
	for (const auto line : text::split(clip, '\n')) {
		const auto trimmed = text::trim(line);
		if (!trimmed.empty()) {
			model::Todo t = new_todo(ctx.settings, trimmed);
			ctx.store.save_todo(t);
		}
	}
}

std::optional<std::int64_t> due_for(std::size_t pick, std::int64_t current, std::int64_t today,
                                    const Settings& settings) {
	switch (pick) {
	case 0:
		return today;
	case 1:
		return today + 1;
	case 2:
		return today + 7;
	case 3:
		return -1;
	default: {
		const auto begins = static_cast<std::uint32_t>(std::clamp(settings.get_int("weekStart", 0), 0, 1));
		return date_picker::pick(date_picker::Mode::Day, current >= 0 ? current : today, today, begins);
	}
	}
}

bool is_overdue(const model::Todo& t, std::int64_t today) {
	return !t.done && t.due >= 0 && t.due < today;
}

bool is_due_by_today(const model::Todo& t, std::int64_t today) {
	return t.due < 0 || t.due <= today;
}

void mark_done(model::Todo& t, bool done, const Settings& settings, std::int64_t today) {
	if (done && !t.done && settings.get_bool("todoRecordDone", false)) {
		t.due = today;
	}
	t.done = done;
}

bool todo_shown(const model::Todo& t, const Settings& settings, bool show_private, std::int64_t today) {
	return (show_private || !t.is_private) && (settings.get_bool("todoShowDone", false) || !t.done) &&
	       (!settings.get_bool("todoOnlyDue", false) || is_due_by_today(t, today));
}

std::size_t menu_sort(std::size_t i) {
	switch (i) {
	case 2:
		return 3;
	case 3:
		return 2;
	default:
		return i;
	}
}

std::size_t sort_index(const Settings& settings) {
	const int saved = settings.get_int("todoSortBy", -1);
	if (saved >= 0) {
		return static_cast<std::size_t>(std::min(saved, 3));
	}
	// The old order put Due Date, Priority first: its first two entries swap places.
	const int old = settings.get_int("todoSort", 0);
	switch (old) {
	case 0:
		return 1;
	case 1:
		return 0;
	default:
		return static_cast<std::size_t>(std::clamp(old, 2, 3));
	}
}

std::vector<model::Todo> todo_order(std::vector<model::Todo> list, const Settings& settings,
                                    const store::DesktopStore& desk) {
	const auto due = [](const model::Todo& t) { return t.due < 0 ? std::numeric_limits<std::int64_t>::max() : t.due; };
	// Unfiled sorts after the named categories, which sort by name.
	const auto cat = [&desk](const model::Todo& t) {
		return std::pair<bool, std::string>{t.category == category::UNFILED,
		                                    text::to_lowercase(desk.category_name(t.category))};
	};
	switch (sort_index(settings)) {
	case 1:
		std::stable_sort(list.begin(), list.end(), [&due](const model::Todo& a, const model::Todo& b) {
			if (due(a) != due(b)) {
				return due(a) < due(b);
			}
			return a.priority < b.priority;
		});
		break;
	case 2:
		std::stable_sort(list.begin(), list.end(), [&cat](const model::Todo& a, const model::Todo& b) {
			if (cat(a) != cat(b)) {
				return cat(a) < cat(b);
			}
			return a.priority < b.priority;
		});
		break;
	case 3:
		std::stable_sort(list.begin(), list.end(), [&due, &cat](const model::Todo& a, const model::Todo& b) {
			if (cat(a) != cat(b)) {
				return cat(a) < cat(b);
			}
			return due(a) < due(b);
		});
		break;
	default:
		std::stable_sort(list.begin(), list.end(), [&due](const model::Todo& a, const model::Todo& b) {
			if (a.priority != b.priority) {
				return a.priority < b.priority;
			}
			return due(a) < due(b);
		});
		break;
	}
	return list;
}

std::string due_label(const model::Todo& t, std::int64_t today) {
	if (t.due < 0) {
		return std::string(NO_DATE);
	}
	const auto d = days::ymd(t.due);
	return std::format("{}/{}{}", d.month, d.day, is_overdue(t, today) ? "!" : "");
}

void draw_check(int x, int y, bool done) {
	const auto d = draw::Dev::begin();
	const auto [bx, by, bw, bh] = d.rect(x + 1, y + 1, 11, 11);
	fl_rectf(bx, by, bw, bh, FL_WHITE);
	const int k = d.lw();
	fl_color(FL_BLACK);
	const std::array<draw::Rect, 4> edges = {
	    draw::Rect{bx, by, bw, k},
	    draw::Rect{bx, by + bh - k, bw, k},
	    draw::Rect{bx, by, k, bh},
	    draw::Rect{bx + bw - k, by, k, bh},
	};
	for (const auto& e : edges) {
		fl_rectf(e.x, e.y, e.w, e.h);
	}
	if (done) {
		fl_line_style(FL_SOLID, k);
		const std::array<std::array<int, 4>, 4> ticks = {{{3, 6, 5, 8}, {5, 8, 9, 4}, {3, 7, 5, 9}, {5, 9, 9, 5}}};
		for (const auto& [x1, y1, x2, y2] : ticks) {
			fl_line(d.p(x + x1), d.p(y + y1), d.p(x + x2), d.p(y + y2));
		}
		fl_line_style(FL_SOLID, 0);
	}
}

} // namespace palm::todo
