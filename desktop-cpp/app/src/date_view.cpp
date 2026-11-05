// The Date view's members, as desktop-rs/app/src/date.rs (impl DateView): the Day view with its right pane, the
// calendar, the To Do and Address lists, the title editors, and the drag of a box or a pane row. The widget subclasses,
// the free functions of the view, and Place::apply are in date.cpp and date_internal.hpp.
//
// Where the Rust sets a closure on a widget, this file constructs the subclass that date.hpp declares. The Day group
// has no declared subclass, so DayGroup below overrides resize() and runs the layout the Rust resize_callback ran.
// Widgets that the Rust builds before the day group are created while the root group is current, and the day group
// takes the scroll bar and the two drop-downs with add() once it exists, since FLTK's add() moves a widget.

#include "palm/date.hpp"

#include "palm/address.hpp"
#include "palm/address_dialog.hpp"
#include "palm/category.hpp"
#include "palm/combo.hpp"
#include "palm/date_picker.hpp"
#include "palm/date_shared.hpp"
#include "palm/days.hpp"
#include "palm/draw.hpp"
#include "palm/event_dialog.hpp"
#include "palm/model.hpp"
#include "palm/month.hpp"
#include "palm/msg.hpp"
#include "palm/platform.hpp"
#include "palm/records.hpp"
#include "palm/settings.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"
#include "palm/todo.hpp"
#include "palm/view.hpp"
#include "palm/week.hpp"

#include "date_internal.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Table_Row.H>
#include <FL/Fl_Window.H>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <variant>
#include <vector>

namespace palm::date {

using namespace detail;
using namespace date_shared;

namespace {

// Sends a Date Book message through the Sender that a widget callback was given.
void send_date(void* sender, msg::DateMsg m) {
	static_cast<msg::Sender*>(sender)->send(msg::Date{std::move(m)});
}

// The message of the scroll bar of the Day grid.
msg::Msg scrolled_msg() {
	return msg::Date{msg::DateMsg{msg::date::Scrolled{}}};
}

// The message of the category drop-downs of the right pane.
msg::Msg category_msg() {
	return msg::Date{msg::DateMsg{msg::date::Category{}}};
}

// A drop-down of the right pane. It starts detached and is added to the day group by the constructor, so its place
// is set by Place::apply.
Fl_Choice* pane_choice() {
	auto* c = new combo::Choice(0, 0, 1, 1);
	draw::style_menu(c);
	return c;
}

// A list of the right pane, with the properties of the Rust TableRow.
PaneList* new_pane_list(int x, int y, int h, std::shared_ptr<PaneModel> pane, bool address, msg::Sender s) {
	auto* t = new PaneList(x, y, PANE_W, h, std::move(pane), address, std::move(s));
	t->type(Fl_Table_Row::SELECT_SINGLE);
	t->rows(0);
	t->cols(1);
	t->row_height_all(ROW_H);
	t->col_header(0);
	t->row_header(0);
	t->color(FL_WHITE);
	t->end();
	return t;
}

// The day group. Its resize runs the layout of the Day view, as the Rust resize_callback did.
class DayGroup : public Fl_Group {
public:
	DayGroup(int x, int y, int w, int h) : Fl_Group(x, y, w, h) {}

	void resize(int x, int y, int w, int h) override {
		Fl_Group::resize(x, y, w, h);
		if (place) {
			place->apply(Rect{x, y, w, h});
		}
	}

	// The layout of the widgets, set once they exist.
	std::optional<Place> place;
};

} // namespace

DateView::DateView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings)
    : root(new Fl_Group(x, y, w, h)), s_(s), tab_mode_(std::make_shared<msg::DateMode>(msg::DateMode::Day)),
      scroller_(0, 0, 1, s, scrolled_msg), todo_cat_(pane_choice(), 0, s, category_msg),
      addr_cat_(pane_choice(), 2, s, category_msg) {
	const auto& fonts = theme::fonts();
	root->box(FL_FLAT_BOX);
	root->color(theme::WORKSPACE);

	// The views sit on a raised panel over the workspace gray, and the tabs on the gray beside it.
	const int bx = x + 4;
	const int by = y + 4;
	const int bw = w - 4 - 3 - TABS_W;
	const int bh = h - 4 - 3;
	auto* body = new BodyFrame(bx, by, bw, bh);
	body->box(FL_FLAT_BOX);
	body->color(theme::PANEL);
	const int cx = bx + 8;
	const int cy = by + 8;
	const int cw = bw - 14;
	const int ch = bh - 16;

	// The Day view and the right pane.
	auto* day_group = new DayGroup(cx, cy, cw, ch);
	day_group_ = day_group;
	const int dw = cw - PANE_W - 11;
	model_ = std::make_shared<DayModel>();
	model_->untimed_rows = 1;
	model_->h24 = platform::uses_24_hours();

	title_ = new TitleFrame(cx, cy, dw, TITLE_H, s_);
	title_->box(FL_DOWN_BOX);
	title_->color(FL_WHITE);
	title_->labelfont(fonts.arial_bold);
	title_->labelsize(13);
	title_->labelcolor(FL_BLACK);
	const int gy = cy + TITLE_H + TITLE_GAP;
	const int gh = ch - (gy - cy) - 33;

	// The black edge around the header and the grid.
	auto* edge = new Fl_Box(cx, gy, dw, gh);
	edge->box(FL_BORDER_BOX);
	edge->color(FL_WHITE);
	const int hh = UNTIMED_H + SEL_H;
	header_ = new DayHeaderFrame(cx + 1, gy + 1, dw - 2, hh, s_, model_);
	grid_ = new DayGridFrame(cx + 1, gy + 1 + hh, dw - 2 - Fl::scrollbar_size(), gh - 2 - hh, s_, model_);

	const int by2 = cy + ch - 28;
	auto* today_b = new Fl_Button(cx + dw - 228, by2, BTN_W, BTN_H, "T&oday");
	today_b->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::Today{}); }, &s_);
	edit_b_ = new Fl_Button(cx + dw - 152, by2, BTN_W, BTN_H, "Ed&it...");
	edit_b_->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::Edit{}); }, &s_);
	edit_b_->deactivate();
	auto* new_b = new Fl_Button(cx + dw - 76, by2, BTN_W, BTN_H, "&New...");
	new_b->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::New{}); }, &s_);

	// The right pane.
	const int px = cx + cw - PANE_W;
	auto* back = draw::arrow(px, cy + 2, 13, 20, false);
	back->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::Year{-1}); }, &s_);
	year_ = new Fl_Box(px + 14, cy + 2, PANE_W - 28, 20);
	year_->box(FL_DOWN_BOX);
	year_->color(FL_WHITE);
	year_->labelfont(fonts.sans_bold);
	auto* fwd = draw::arrow(px + PANE_W - 13, cy + 2, 13, 20, true);
	fwd->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::Year{1}); }, &s_);
	const int mw = PANE_W / 6;
	for (std::uint32_t m = 0; m < 12; ++m) {
		const int c = static_cast<int>(m % 6);
		const int r = static_cast<int>(m / 6);
		auto* b = new MonthButton(px + c * mw, cy + 26 + r * MONTH_H, mw, MONTH_H, nullptr);
		b->copy_label(std::string(model::MONTH_SHORT[m]).c_str());
		b->box(FL_FLAT_BOX);
		b->down_box(FL_FLAT_BOX);
		b->clear_visible_focus();
		// The month is the label of the button, which is unique among the twelve.
		b->callback(
		    [](Fl_Widget* w, void* s) {
			    const std::string_view label = w->label();
			    for (std::uint32_t i = 0; i < 12; ++i) {
				    if (label == model::MONTH_SHORT[i]) {
					    send_date(s, msg::date::Month{i + 1});
				    }
			    }
		    },
		    &s_);
		months_.push_back(b);
	}
	auto* month_edge = new MonthEdgeFrame(px, cy + 26, PANE_W, 2 * MONTH_H);

	pane_ = std::make_shared<PaneModel>();
	auto* cal_edge = new Fl_Box(px, cy + 60, PANE_W, 130);
	cal_edge->box(FL_DOWN_BOX);
	cal_ = new CalendarFrame(px + 2, cy + 62, PANE_W - 4, 126, s_, pane_);

	const int hy = cy + 194;
	switch_date_ = new SwitchFrame(px, hy, 117, 18, s_, pane_);
	switch_date_->box(FL_DOWN_BOX);
	day_group->add(todo_cat_.choice);
	day_group->add(addr_cat_.choice);

	const int ly = hy + 22;
	todo_list_ = new_pane_list(px, ly, cy + ch - ly, pane_, false, s_);
	addr_list_ = new_pane_list(px, ly, cy + ch - ly, pane_, true, s_);

	look_label_ = new Fl_Box(px, cy + ch - 20, 44, 20, "Look up:");
	look_label_->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	look_ = new text_edit::Input(px + 48, cy + ch - 20, PANE_W - 48, 20);
	look_->when(FL_WHEN_CHANGED);
	look_->callback([](Fl_Widget*, void* s) { send_date(s, msg::date::Look{}); }, &s_);
	day_group->end();

	// The scroll bar joins the day group, so the Week and Month views hide it with the rest.
	day_group->add(scroller_.bar);

	// The group lays its widgets out on every resize, so the buttons and the right pane never stretch.
	place_ = Place{
	    .title = title_,
	    .edge = edge,
	    .header = header_,
	    .grid = grid_,
	    .bar = scroller_.bar,
	    .today = today_b,
	    .edit = edit_b_,
	    .new_date = new_b,
	    .back = back,
	    .year = year_,
	    .fwd = fwd,
	    .months = months_,
	    .month_edge = month_edge,
	    .cal_edge = cal_edge,
	    .cal = cal_,
	    .switch_date = switch_date_,
	    .todo_cat = todo_cat_.choice,
	    .addr_cat = addr_cat_.choice,
	    .todo_list = todo_list_,
	    .addr_list = addr_list_,
	    .look_label = look_label_,
	    .look = look_,
	    .model = model_,
	};
	day_group->place = place_;
	place_.apply(Rect{cx, cy, cw, ch});

	week_ = std::make_unique<week::WeekView>(cx, cy, cw, ch, s_);
	month_ = std::make_unique<month::MonthView>(cx, cy, cw, ch, s_);

	// The tabs on the right edge.
	tabs_ = new TabsFrame(bx + bw, by, TABS_W, bh, s_, tab_mode_);
	auto* rgrow = new Fl_Box(bx + 20, by + 20, bw - 40, bh - 40);
	root->resizable(rgrow);
	root->end();

	const std::string saved = settings.get("dateMode", "day");
	const msg::DateMode start = saved == "week"    ? msg::DateMode::Week
	                            : saved == "month" ? msg::DateMode::Month
	                                               : msg::DateMode::Day;
	show_mode(start);
	// The Day grid opens at the work day hour, as in Palm Desktop.
	const int wd = work_day(settings);
	scroller_.scroll_later(y_of(wd * 60));
	week_->scroll_to_hour(wd);
}

DateView::~DateView() = default;

Fl_Input* DateView::field_widget() const {
	if (typing_) {
		return typing_->field.input;
	}
	if (title_edit_) {
		return title_edit_->field.input;
	}
	return week_->field();
}

text_edit::Input* DateView::look() const {
	return look_;
}

void DateView::focus() {
	switch (mode) {
	case msg::DateMode::Day:
		static_cast<void>(grid_->take_focus());
		break;
	case msg::DateMode::Week:
		week_->focus();
		break;
	case msg::DateMode::Month:
		month_->focus();
		break;
	}
}

void DateView::show_mode(msg::DateMode shown) {
	mode = shown;
	*tab_mode_ = shown;
	const auto set_shown = [](Fl_Group* g, bool on) {
		if (on) {
			g->show();
		} else {
			g->hide();
		}
	};
	set_shown(day_group_, shown == msg::DateMode::Day);
	set_shown(week_->root, shown == msg::DateMode::Week);
	set_shown(month_->root, shown == msg::DateMode::Month);
	tabs_->redraw();
	root->redraw();
}

void DateView::switch_mode(view::Ctx& ctx, msg::DateMode next) {
	end_typing_ctx(ctx, true);
	end_title_edit_ctx(ctx, true);
	week_->close_saving(ctx);
	show_mode(next);
	ctx.set("dateMode", msg::key(next));
	refresh(ctx);
}

void DateView::refresh(view::Ctx& ctx) {
	const std::int64_t day = ctx.day;
	if (typing_ && typing_->day != day) {
		// The field belongs to the day it was opened on.
		end_typing_ctx(ctx, true);
	}
	end_title_edit_ctx(ctx, true);
	title_->copy_label(date_title(day).c_str());
	// The title is red only for today.
	title_->labelcolor(day == platform::today() ? FL_RED : FL_BLACK);
	if (shown_day_ != day) {
		// A newly shown day opens at the work day start.
		shown_day_ = day;
		scroller_.scroll_later(y_of(work_day(ctx.settings) * 60));
	}
	occs_ = visible_events(ctx.store, day, ctx.show_private);
	if (!std::any_of(occs_.begin(), occs_.end(), [this](const Occ& o) { return selected_id_ == o.event.id; })) {
		selected_id_.reset();
	}
	if (selected_id_) {
		edit_b_->activate();
	} else {
		edit_b_->deactivate();
	}
	const days::Ymd d = days::ymd(day);
	year_->copy_label(std::to_string(d.year).c_str());
	for (std::size_t i = 0; i < months_.size(); ++i) {
		const bool on = i + 1 == d.month;
		months_[i]->color(on ? theme::SELECT : FL_WHITE);
		months_[i]->labelcolor(on ? FL_WHITE : FL_BLACK);
		months_[i]->redraw();
	}
	const bool address = ctx.settings.get("datePane", "todo") == "address";
	refresh_categories(ctx, address);
	refresh_lists(ctx, address);
	{
		auto& p = *pane_;
		p.day = day;
		p.today = platform::today();
		p.begins = week_begins(ctx.settings);
		p.address = address;
	}
	sync_model();
	week_->refresh(ctx);
	month_->refresh(ctx);
	cal_->redraw();
	switch_date_->redraw();
	root->redraw();
}

void DateView::refresh_categories(view::Ctx& ctx, bool address) {
	if (address) {
		const std::int32_t cur = ctx.settings.get_int("addressCategory", category::ALL);
		std::vector<std::pair<std::string, std::int32_t>> items{{"All", category::ALL}};
		for (const auto& c : ctx.store.address_categories) {
			items.emplace_back(c.name, c.id);
		}
		items.emplace_back("Unfiled", category::UNFILED);
		addr_cat_.set_items(items, true, cur);
		addr_cat_.choice->show();
		todo_cat_.choice->hide();
	} else {
		const bool found = std::any_of(ctx.store.categories.begin(), ctx.store.categories.end(),
		                               [this](const model::Category& c) { return todo_cat_id_ == c.id; });
		if (!found) {
			todo_cat_id_.reset();
		}
		std::vector<std::pair<std::string, std::int32_t>> items{{"All", category::ALL}};
		for (const auto& c : ctx.store.categories) {
			items.emplace_back(c.name, c.id);
		}
		todo_cat_.set_items(items, true, todo_cat_id_.value_or(category::ALL));
		todo_cat_.choice->show();
		addr_cat_.choice->hide();
	}
}

void DateView::refresh_lists(view::Ctx& ctx, bool address) {
	const std::int64_t today = platform::today();
	std::vector<model::Todo> list;
	for (const auto& t : ctx.store.todos) {
		if ((!todo_cat_id_ || t.category == *todo_cat_id_) &&
		    todo::todo_shown(t, ctx.settings, ctx.show_private, today)) {
			list.push_back(t);
		}
	}
	pane_todos_ = todo::todo_order(std::move(list), ctx.settings, ctx.store);
	const auto keep = selected_addr();
	pane_addrs_ = address::visible(ctx);
	{
		auto& p = *pane_;
		p.todos.clear();
		for (const auto& t : pane_todos_) {
			p.todos.push_back(PaneTodo{t, todo::due_label(t, today), todo::is_overdue(t, today)});
		}
		p.show_pri = ctx.settings.get_bool("todoShowPri", false);
		p.show_due = ctx.settings.get_bool("todoShowDue", true);
		const bool by = address::by_company(ctx.settings);
		p.addrs.clear();
		for (const auto& a : pane_addrs_) {
			p.addrs.push_back(address::row_of(a, by));
		}
	}
	const auto fill = [](Fl_Table_Row* t, std::size_t n) {
		t->rows(static_cast<int>(n));
		t->row_height_all(ROW_H);
		t->redraw();
	};
	fill(todo_list_, pane_todos_.size());
	fill(addr_list_, pane_addrs_.size());
	addr_list_->select_all_rows(0);
	if (keep) {
		const auto found = std::find_if(pane_addrs_.begin(), pane_addrs_.end(),
		                                [&keep](const model::Address& a) { return a.id == *keep; });
		if (found != pane_addrs_.end()) {
			static_cast<void>(addr_list_->select_row(static_cast<int>(found - pane_addrs_.begin()), 1));
		}
	}
	if (address) {
		todo_list_->hide();
		addr_list_->show();
		look_label_->show();
		look_->show();
	} else {
		addr_list_->hide();
		todo_list_->show();
		look_label_->hide();
		look_->hide();
	}
}

std::optional<std::int64_t> DateView::selected_addr() const {
	const auto i = records_selected(*addr_list_, pane_addrs_.size());
	if (!i) {
		return std::nullopt;
	}
	return pane_addrs_[*i].id;
}

void DateView::sync_model() {
	const bool extra = typing_ && typing_->untimed;
	const auto untimed_count = std::count_if(occs_.begin(), occs_.end(), [](const Occ& o) { return o.event.untimed; });
	const std::int32_t rows =
	    std::max(static_cast<std::int32_t>(untimed_count) + (extra ? 1 : 0), static_cast<std::int32_t>(1));
	model_->occs = occs_;
	model_->selected = selected_id_;
	model_->untimed_rows = rows;
	if (title_edit_) {
		model_->editing = title_edit_->occ.event.id;
	} else {
		model_->editing.reset();
	}
	// The header grows by one row for each untimed event, and the grid takes the rest of the height.
	place_.apply(Rect{day_group_->x(), day_group_->y(), day_group_->w(), day_group_->h()});
	if (typing_) {
		const int w = typing_->untimed ? header_->w() : grid_->w();
		const Rect r = typing_->untimed ? Rect{TIME_W + 2, (rows - 1) * UNTIMED_H + 1, w - TIME_W - 4, UNTIMED_H - 2}
		                                : hour_box(typing_->min, w);
		model_->typing = std::pair<bool, Rect>{typing_->untimed, r};
	} else {
		model_->typing.reset();
	}
	const int gh = grid_->h();
	scroller_.set_range(gh, y_of(24 * 60) + 1);
	model_->offset = scroller_.offset();
	place_fields();
	header_->redraw();
	grid_->redraw();
	scroller_.bar->redraw();
}

void DateView::place_fields() {
	const auto typing_box = model_->typing;
	if (!typing_ || !typing_box) {
		return;
	}
	const bool untimed = typing_box->first;
	const Rect r = typing_box->second;
	const int wx = untimed ? header_->x() : grid_->x();
	const int wy = untimed ? header_->y() : grid_->y() - scroller_.offset();
	typing_->field.place(wx + r.x + 3, wy + title_top(r, untimed), r.w - BAR_W - 4);
}

std::optional<Occ> DateView::selected_occ() const {
	for (const auto& o : occs_) {
		if (selected_id_ && o.event.id == *selected_id_) {
			return o;
		}
	}
	return std::nullopt;
}

std::optional<Occ> DateView::current_occ() const {
	if (mode == msg::DateMode::Week) {
		return week_->selected_occ();
	}
	return selected_occ();
}

bool DateView::has_selected_event() const {
	return current_occ().has_value();
}

void DateView::select(view::Ctx& ctx, const model::Event& e) {
	ctx.day = e.date;
	selected_id_ = e.id;
	switch_mode(ctx, msg::DateMode::Day);
}

std::pair<std::string, std::vector<std::string>> DateView::print_lines(const view::Ctx& ctx) const {
	std::vector<std::string> lines;
	lines.reserve(occs_.size());
	for (const auto& o : occs_) {
		const model::Event& e = o.event;
		const std::string when = e.untimed ? "Untimed" : model::time(e.start, ctx.h24);
		lines.push_back(when + "  " + e.title);
	}
	return {date_title(ctx.day), std::move(lines)};
}

void DateView::new_record(view::Ctx& ctx) {
	const std::int64_t day = ctx.day;
	event_dialog::edit(ctx, std::nullopt, day, std::nullopt, "");
}

void DateView::edit_selected(view::Ctx& ctx) {
	if (const auto o = current_occ()) {
		event_dialog::edit(ctx, o->event, o->day, std::nullopt, "");
	}
}

void DateView::delete_selected(view::Ctx& ctx) {
	if (const auto o = current_occ()) {
		if (delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, *o) && mode == msg::DateMode::Day) {
			selected_id_.reset();
		}
	}
}

void DateView::handle(msg::DateMsg message, view::Ctx& ctx) {
	std::visit(msg::Overload{
	               [this, &ctx](const msg::date::Mode& m) { switch_mode(ctx, m.mode); },
	               [&ctx](const msg::date::Today&) { ctx.day = platform::today(); },
	               [this, &ctx](const msg::date::New&) { new_record(ctx); },
	               [this, &ctx](const msg::date::Edit&) { edit_selected(ctx); },
	               [&ctx](const msg::date::TitleDouble&) {
		               if (const auto d = date_picker::pick(date_picker::Mode::Day, ctx.day, platform::today(),
		                                                    week_begins(ctx.settings))) {
			               ctx.day = *d;
		               }
	               },
	               [&ctx](const msg::date::Year& y) {
		               ctx.day = date_picker::add_months(ctx.day, 12 * static_cast<std::int64_t>(y.delta));
	               },
	               [&ctx](const msg::date::Month& m) {
		               const days::Ymd d = days::ymd(ctx.day);
		               ctx.day = date_picker::add_months(ctx.day, static_cast<std::int64_t>(m.month) -
		                                                              static_cast<std::int64_t>(d.month));
	               },
	               [this, &ctx](const msg::date::Calendar& c) {
		               if (std::holds_alternative<msg::mouse::Press>(c.mouse.kind) && !c.mouse.right) {
			               if (const auto d = calendar_day(c.mouse.x, c.mouse.y)) {
				               ctx.day = *d;
			               }
		               }
	               },
	               [this, &ctx](const msg::date::Pane& p) {
		               ctx.set("datePane", p.address ? "address" : "todo");
		               refresh(ctx);
	               },
	               [this, &ctx](const msg::date::Category&) {
		               if (pane_->address) {
			               // The category is shared with the Address view.
			               ctx.set("addressCategory", std::to_string(addr_cat_.id()));
		               } else {
			               const std::int32_t id = todo_cat_.id();
			               todo_cat_id_ = id != category::ALL ? std::optional<std::int32_t>(id) : std::nullopt;
		               }
		               refresh(ctx);
	               },
	               [this, &ctx](const msg::date::Look&) { seek_address(ctx); },
	               [this, &ctx](const msg::date::PaneList& pl) { pane_mouse(ctx, pl.mouse); },
	               [this, &ctx](const msg::date::Day& d) { day_mouse(ctx, d.untimed, d.mouse); },
	               [this](const msg::date::Scrolled&) {
		               if (mode == msg::DateMode::Week) {
			               week_->scrolled();
			               return;
		               }
		               model_->offset = scroller_.offset();
		               place_fields();
		               grid_->redraw();
		               root->redraw();
	               },
	               [this, &ctx](const msg::date::Field& f) { field_event(ctx, f.event); },
	               [this, &ctx](const msg::date::Step& st) {
		               if (mode == msg::DateMode::Week) {
			               ctx.day += 7 * static_cast<std::int64_t>(st.delta);
		               } else {
			               ctx.day = date_picker::add_months(ctx.day, st.delta);
		               }
	               },
	               [this, &ctx](const msg::date::GoTo&) {
		               const date_picker::Mode pick_mode =
		                   mode == msg::DateMode::Week ? date_picker::Mode::Week : date_picker::Mode::Month;
		               if (const auto d =
		                       date_picker::pick(pick_mode, ctx.day, platform::today(), week_begins(ctx.settings))) {
			               ctx.day = *d;
		               }
	               },
	               [this, &ctx](const msg::date::WeekHeader& wh) { week_->header_mouse(ctx, wh.mouse); },
	               [this, &ctx](const msg::date::WeekGrid& wg) { week_->grid_mouse(ctx, wg.mouse); },
	               [this, &ctx](const msg::date::MonthGrid& mg) {
		               if (const auto next = month_->mouse(ctx, mg.mouse)) {
			               switch_mode(ctx, *next);
		               }
	               },
	               [this, &ctx](const msg::date::MonthKey& k) {
		               std::string typed;
		               text::append_utf8(typed, k.ch);
		               const std::int64_t day = ctx.day;
		               event_dialog::edit(ctx, std::nullopt, day, std::nullopt, typed);
	               },
	           },
	           message);
}

std::optional<std::int64_t> DateView::calendar_day(int x, int y) const {
	const int cw = cal_->w() / 7;
	const int ch = cal_->h() / 7;
	if (cw == 0 || ch == 0 || !cal_->visible()) {
		return std::nullopt;
	}
	const int lx = x - cal_->x();
	const int ly = y - cal_->y();
	if (lx < 0 || lx >= cal_->w() || ly < 0 || ly >= cal_->h()) {
		return std::nullopt;
	}
	const int row = ly / ch - 1;
	if (row < 0 || row > 5) {
		return std::nullopt;
	}
	const int col = std::min(lx / cw, 6);
	const auto& p = *pane_;
	return detail::calendar_start(p.day, p.begins) + (row * 7 + col);
}

void DateView::seek_address(const view::Ctx& ctx) {
	const std::string needle = text::to_lowercase(look_->value());
	if (needle.empty()) {
		return;
	}
	const bool by = address::by_company(ctx.settings);
	const auto found = std::find_if(pane_addrs_.begin(), pane_addrs_.end(), [&](const model::Address& a) {
		return text::to_lowercase(a.list_name(by)).starts_with(needle);
	});
	if (found != pane_addrs_.end()) {
		const int i = static_cast<int>(found - pane_addrs_.begin());
		addr_list_->select_all_rows(0);
		static_cast<void>(addr_list_->select_row(i, 1));
		addr_list_->row_position(i);
		addr_list_->redraw();
	}
}

void DateView::end_typing_ctx(view::Ctx& ctx, bool save) {
	if (!typing_) {
		return;
	}
	Typing t = std::move(*typing_);
	typing_.reset();
	const std::string title = t.field.text();
	t.field.close();
	if (save && !title.empty()) {
		add_event(ctx, title, t.day, t.min, t.untimed);
	}
	sync_model();
}

void DateView::end_title_edit_ctx(view::Ctx& ctx, bool save) {
	if (!title_edit_) {
		return;
	}
	TitleEdit t = std::move(*title_edit_);
	title_edit_.reset();
	const std::string text = t.field.text();
	const Occ occ = t.occ;
	t.field.close();
	if (save && !text.empty() && text != occ.event.title) {
		change_occurrence(ctx.store, occ, CHANGE_TEXT, [&text](model::Event& e) { e.title = text; });
	}
	sync_model();
}

void DateView::add_event(view::Ctx& ctx, std::string_view title, std::int64_t day, std::int32_t min, bool untimed) {
	model::Event e;
	e.title = std::string(title);
	e.date = day;
	e.untimed = untimed;
	e.start = untimed ? 8 * 60 : min;
	e.end = untimed ? 9 * 60 : min + 60;
	apply_alarm_preset(e, ctx.settings);
	ctx.store.save_event(e);
	selected_id_ = e.id;
}

void DateView::field_event(view::Ctx& ctx, msg::FieldEv f) {
	if (mode == msg::DateMode::Week) {
		week_->field_event(ctx, f);
		return;
	}
	switch (f) {
	case msg::FieldEv::Enter:
		end_typing_ctx(ctx, true);
		end_title_edit_ctx(ctx, true);
		break;
	case msg::FieldEv::Escape:
		end_typing_ctx(ctx, false);
		end_title_edit_ctx(ctx, false);
		break;
	case msg::FieldEv::Lost: {
		// A focus loss saves, unless the focus went to a dialog of the field itself.
		Fl_Widget* focused = Fl::focus();
		if (typing_ && static_cast<Fl_Widget*>(typing_->field.input) != focused) {
			end_typing_ctx(ctx, true);
		}
		if (title_edit_ && static_cast<Fl_Widget*>(title_edit_->field.input) != focused) {
			end_title_edit_ctx(ctx, true);
		}
		break;
	}
	case msg::FieldEv::Double:
		// A double click in the field of an empty hour opens Edit Event, as on the hour.
		if (typing_ && !typing_->untimed) {
			const std::int64_t day = typing_->day;
			const std::int32_t min = typing_->min;
			end_typing_ctx(ctx, false);
			event_dialog::edit(ctx, std::nullopt, day, min, "");
		}
		break;
	}
}

void DateView::start_typing(view::Ctx& ctx, std::int32_t min, bool untimed) {
	end_typing_ctx(ctx, true);
	selected_id_.reset();
	edit_b_->deactivate();
	TypeField field = TypeField::open(day_group_, 0, 0, 50, "", s_);
	typing_ = Typing{.field = std::move(field), .day = ctx.day, .min = min, .untimed = untimed};
	sync_model();
}

void DateView::edit_title(Occ occ, Rect r, bool header) {
	const int wx = header ? header_->x() : grid_->x();
	const int wy = header ? header_->y() : grid_->y() - scroller_.offset();
	const int x = title_x(r, occ.event);
	const int y = title_top(r, header);
	TypeField field =
	    TypeField::open(day_group_, wx + x, wy + y, std::max(r.x + r.w - BAR_W - x, 20), occ.event.title, s_);
	const int n = static_cast<int>(std::strlen(field.input->value()));
	field.input->insert_position(n, 0);
	title_edit_ = TitleEdit{std::move(field), std::move(occ)};
	sync_model();
}

std::pair<std::int32_t, std::int32_t> DateView::local(bool header, int x, int y) const {
	if (header) {
		return {x - header_->x(), y - header_->y()};
	}
	return {x - grid_->x(), y - grid_->y() + scroller_.offset()};
}

std::int32_t DateView::width_of(bool header) const {
	return header ? header_->w() : grid_->w();
}

std::optional<std::pair<Occ, Rect>> DateView::box_at(bool header, std::int32_t lx, std::int32_t ly) const {
	for (const auto& [i, r, h] : layout_day(occs_, width_of(header))) {
		if (h == header && r.contains(lx, ly)) {
			return std::pair<Occ, Rect>{occs_[i], r};
		}
	}
	return std::nullopt;
}

std::optional<Rect> DateView::sel_box() const {
	for (const auto& [i, r, h] : layout_day(occs_, grid_->w())) {
		if (!h && selected_id_ && occs_[i].event.id == *selected_id_) {
			return r;
		}
	}
	return std::nullopt;
}

void DateView::set_cursor(Fl_Cursor c) const {
	if (Fl_Window* w = grid_->window()) {
		w->cursor(c);
	}
}

void DateView::day_mouse(view::Ctx& ctx, bool header, msg::Mouse m) {
	const auto lp = local(header, m.x, m.y);
	const std::int32_t lx = lp.first;
	const std::int32_t ly = lp.second;
	// True when the point is on the blue bar under the selected box of the grid.
	const auto on_bar = [&]() {
		if (header) {
			return false;
		}
		const auto r = sel_box();
		return r.has_value() && on_resize_bar(*r, lx, ly);
	};

	if (const auto* wheel = std::get_if<msg::mouse::Wheel>(&m.kind)) {
		scroller_.wheel(wheel->lines);
		handle(msg::DateMsg{msg::date::Scrolled{}}, ctx);
		return;
	}
	if (std::holds_alternative<msg::mouse::Move>(m.kind)) {
		set_cursor(on_bar() ? FL_CURSOR_NS : FL_CURSOR_DEFAULT);
		return;
	}
	if (std::holds_alternative<msg::mouse::Press>(m.kind)) {
		suppress_click_ = false;
		if (header) {
			static_cast<void>(header_->take_focus());
		} else {
			static_cast<void>(grid_->take_focus());
		}
		end_typing_ctx(ctx, true);
		end_title_edit_ctx(ctx, true);
		if (m.right) {
			// The menu of the box under a right click. Empty space has no menu, as in Palm Desktop.
			if (const auto hit = box_at(header, lx, ly)) {
				selected_id_ = hit->first.event.id;
				edit_b_->activate();
				sync_model();
				box_action(ctx, hit->first);
			}
			return;
		}
		// The blue bar under the selected box resizes it, so it is tested before the box.
		if (!header) {
			const auto r = sel_box();
			const auto occ = selected_occ();
			if (r && occ && on_resize_bar(*r, lx, ly)) {
				drag_ = Drag(*occ, true, false, 0, {lx, ly});
				return;
			}
		}
		const auto hit = box_at(header, lx, ly);
		if (!hit) {
			return;
		}
		const Occ occ = hit->first;
		const Rect r = hit->second;
		if (on_handle(r, lx, ly) || marker_at(r, occ.event, lx, ly, header)) {
			return;
		}
		drag_ = Drag(occ, false, header, ly - r.y, {lx, ly});
		return;
	}
	if (std::holds_alternative<msg::mouse::Drag>(m.kind)) {
		if (!drag_) {
			return;
		}
		Drag d = std::move(*drag_);
		drag_.reset();
		if (d.resize) {
			set_cursor(FL_CURSOR_NS);
		}
		if (!d.moved && std::max(std::abs(lx - d.origin.first), std::abs(ly - d.origin.second)) <= 4) {
			drag_ = std::move(d);
			return;
		}
		d.moved = true;
		model_->outline = update_drag(d, m.x, m.y, lx, ly);
		drag_ = std::move(d);
		grid_->redraw();
		return;
	}
	if (std::holds_alternative<msg::mouse::Release>(m.kind)) {
		set_cursor(on_bar() ? FL_CURSOR_NS : FL_CURSOR_DEFAULT);
		if (drag_) {
			Drag d = std::move(*drag_);
			drag_.reset();
			model_->outline.reset();
			grid_->redraw();
			if (d.moved) {
				suppress_click_ = true;
				static_cast<void>(update_drag(d, m.x, m.y, lx, ly));
				const model::Event e = d.occ.event;
				if (!d.resize) {
					// A moved box is deselected, as in Palm Desktop 3.1.1.
					selected_id_.reset();
					edit_b_->deactivate();
					sync_model();
				}
				if (d.drop_day) {
					const std::int64_t day = *d.drop_day;
					change_occurrence(ctx.store, d.occ, CHANGE_TEXT, [day](model::Event& x) { x.date = day; });
				} else if (d.resize) {
					if (d.new_end != e.end) {
						const std::int32_t end = d.new_end;
						change_occurrence(ctx.store, d.occ, CHANGE_TEXT, [end](model::Event& x) { x.end = end; });
					}
				} else if (!e.untimed && d.new_start != e.start) {
					const std::int32_t len = e.end - e.start;
					const std::int32_t start = d.new_start;
					change_occurrence(ctx.store, d.occ, CHANGE_TEXT, [start, len](model::Event& x) {
						x.start = start;
						x.end = start + len;
					});
				}
				return;
			}
		}
		if (suppress_click_ || m.right) {
			suppress_click_ = false;
			return;
		}
		click(ctx, header, lx, ly, m.double_click);
	}
}

void DateView::click(view::Ctx& ctx, bool header, std::int32_t lx, std::int32_t ly, bool double_click) {
	if (const auto hit = box_at(header, lx, ly)) {
		const Occ occ = hit->first;
		const Rect r = hit->second;
		selected_id_ = occ.event.id;
		edit_b_->activate();
		sync_model();
		const auto marker = marker_at(r, occ.event, lx, ly, header);
		if (double_click && on_handle(r, lx, ly)) {
			event_dialog::edit(ctx, occ.event, occ.day, std::nullopt, "");
		} else if (double_click) {
			// A single click selects the box; a double click on the title edits it in place.
			edit_title(occ, r, header);
		} else if (marker == Marker::Alarm) {
			edit_alarm(ctx.store, occ);
		} else if (marker == Marker::Note) {
			edit_note(ctx.store, occ);
		}
		return;
	}
	if (double_click) {
		return;
	}
	if (header) {
		start_typing(ctx, 0, true);
	} else {
		start_typing(ctx, std::clamp(min_at(ly) / 60 * 60, 0, 23 * 60), false);
	}
}

void DateView::box_action(view::Ctx& ctx, const Occ& occ) {
	const auto action = event_menu();
	if (!action) {
		return;
	}
	switch (*action) {
	case date_shared::BoxAction::Delete:
		if (delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, occ)) {
			selected_id_.reset();
		}
		break;
	case date_shared::BoxAction::Note:
		edit_note(ctx.store, occ);
		break;
	case date_shared::BoxAction::Alarm:
		edit_alarm(ctx.store, occ);
		break;
	case date_shared::BoxAction::Edit:
		event_dialog::edit(ctx, occ.event, occ.day, std::nullopt, "");
		break;
	}
}

std::optional<Rect> DateView::update_drag(Drag& d, int x, int y, std::int32_t /*lx*/, std::int32_t /*ly*/) const {
	const model::Event& e = d.occ.event;
	if (d.resize) {
		d.drop_day.reset();
	} else {
		d.drop_day = calendar_day(x, y);
	}
	if (d.drop_day || d.header) {
		return std::nullopt;
	}
	// The grid point, even when the drag started on the header.
	const int gy = y - grid_->y() + scroller_.offset();
	const int w = grid_->w();
	if (d.resize) {
		d.new_end = std::min(std::max(e.start + 30, round_half(min_at(gy))), 1440);
		return outline_box(e.start, d.new_end, w);
	}
	if (!e.untimed) {
		const std::int32_t dur = e.end - e.start;
		d.new_start = std::min(std::max(round_half(min_at(gy - d.grab)), 0), 1440 - dur);
		return outline_box(d.new_start, d.new_start + dur, w);
	}
	return std::nullopt;
}

void DateView::pane_mouse(view::Ctx& ctx, msg::Mouse m) {
	const bool address = pane_->address;
	PaneList* list = address ? addr_list_ : todo_list_;
	const int lx = m.x - list->x();
	const int ly = m.y - list->y();
	if (std::holds_alternative<msg::mouse::Press>(m.kind)) {
		const auto hit = list->cell_under_cursor();
		const int row = hit ? hit->first : -1;
		pane_press_ = std::tuple<std::int32_t, std::int32_t, std::int32_t, bool>{lx, ly, row, false};
		return;
	}
	if (std::holds_alternative<msg::mouse::Drag>(m.kind)) {
		if (!pane_press_) {
			return;
		}
		const auto [px, py, row, dragging] = *pane_press_;
		if (!dragging && std::max(std::abs(lx - px), std::abs(ly - py)) <= 4) {
			return;
		}
		pane_press_ = std::tuple<std::int32_t, std::int32_t, std::int32_t, bool>{px, py, row, true};
		// A To Do item or an address dragged to the Day grid becomes an event: the outline shows it.
		const auto over = drop_target(m.x, m.y);
		model_->drop_box.reset();
		if (over && !over->first) {
			const std::int32_t start = drop_min(over->second);
			model_->drop_box = outline_box(start, start + 60, grid_->w());
		}
		grid_->redraw();
		return;
	}
	if (std::holds_alternative<msg::mouse::Release>(m.kind)) {
		if (!pane_press_) {
			return;
		}
		const std::int32_t row = std::get<2>(*pane_press_);
		const bool dragging = std::get<3>(*pane_press_);
		pane_press_.reset();
		model_->drop_box.reset();
		grid_->redraw();
		if (row < 0) {
			return;
		}
		const auto row_index = static_cast<std::size_t>(row);
		if (dragging) {
			std::optional<std::string> title;
			if (address) {
				if (row_index < pane_addrs_.size()) {
					title = event_title_of(pane_addrs_[row_index]);
				}
			} else if (row_index < pane_todos_.size()) {
				title = pane_todos_[row_index].title;
			}
			const auto over = drop_target(m.x, m.y);
			if (title && over) {
				const std::string trimmed(text::trim(*title));
				if (!trimmed.empty()) {
					end_typing_ctx(ctx, true);
					end_title_edit_ctx(ctx, true);
					add_event(ctx, trimmed, ctx.day, drop_min(over->second), over->first);
				}
			}
			return;
		}
		if (address) {
			if (m.double_click && row_index < pane_addrs_.size()) {
				address_dialog::edit(ctx, pane_addrs_[row_index], category::UNFILED);
			}
		} else {
			todo_click(ctx, row_index, lx, m.double_click);
		}
	}
}

std::optional<std::pair<bool, std::int32_t>> DateView::drop_target(int x, int y) const {
	const auto inside = [x, y](const Fl_Widget* w) {
		return x >= w->x() && x < w->x() + w->w() && y >= w->y() && y < w->y() + w->h();
	};
	if (mode != msg::DateMode::Day) {
		return std::nullopt;
	}
	if (inside(header_)) {
		return std::pair<bool, std::int32_t>{true, 0};
	}
	if (inside(grid_)) {
		return std::pair<bool, std::int32_t>{false, y - grid_->y() + scroller_.offset()};
	}
	return std::nullopt;
}

void DateView::todo_click(view::Ctx& ctx, std::size_t row, std::int32_t lx, bool double_click) {
	if (row >= pane_todos_.size()) {
		return;
	}
	model::Todo t = pane_todos_[row];
	const std::int32_t scroll_w = todo_list_->rows() * ROW_H > todo_list_->h() - 4 ? 17 : 0;
	const auto [pri, due] = pane_parts(*pane_, todo_list_->w() - 4 - scroll_w);
	const std::int64_t today = platform::today();
	if (lx < 20) {
		// The check box.
		todo::mark_done(t, !t.done, ctx.settings, today);
		ctx.store.save_todo(t);
	} else if (!double_click && pri && lx >= pri->first && lx < pri->second) {
		const std::array<std::string_view, 5> levels{"1", "2", "3", "4", "5"};
		if (const auto i = popup(levels)) {
			t.priority = static_cast<std::int32_t>(*i) + 1;
			ctx.store.save_todo(t);
		}
	} else if (!double_click && due && lx >= due->first && lx < due->second) {
		if (const auto pick = popup(todo::DUE_NAMES)) {
			if (const auto d = todo::due_for(*pick, t.due, today, ctx.settings)) {
				t.due = *d;
				ctx.store.save_todo(t);
			}
		}
	} else if (double_click) {
		ctx.s.send(msg::ShowTodo{t.id});
	}
}

} // namespace palm::date
