// The Week view, as desktop-rs/app/src/week.rs and WeekView.kt: seven day columns with the untimed row above a time
// grid, and the row of the step buttons, Go to, the title and Today that the Month view shares.

#include "palm/week.hpp"

#include "palm/date.hpp"
#include "palm/date_picker.hpp"
#include "palm/date_shared.hpp"
#include "palm/days.hpp"
#include "palm/draw.hpp"
#include "palm/event_dialog.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/platform.hpp"
#include "palm/theme.hpp"
#include "palm/view.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Scrollbar.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <format>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

namespace palm::week {

namespace {

using date_shared::Occ;
using date_shared::Rect;

constexpr std::int32_t HOUR_H = 38;
constexpr std::int32_t HEAD_H = 20;
constexpr std::int32_t UNTIMED_LINE = 19;
constexpr std::int32_t MIN_UNTIMED_H = 20;
constexpr std::int32_t MIN_OF_DAY = 1440;

// The Msg of a message of the Date Book.
msg::Msg date_msg(msg::DateMsg m) {
	return msg::Msg{msg::Date{std::move(m)}};
}

// The Msg that a scroll bar or a resize of the grid sends.
msg::Msg scroll_msg() {
	return date_msg(msg::date::Scrolled{});
}

// The length of an event in minutes, 30 when it has no end.
std::int32_t dur_of(const model::Event& e) {
	return e.end > e.start ? e.end - e.start : 30;
}

// The height of a box for an event of `min` minutes, as the grid draws it.
std::int32_t box_h(std::int32_t min) {
	return std::max(min * HOUR_H / 60 - date_shared::SEL_H, 12);
}

// `min` rounded to the nearest 30 minutes.
std::int32_t round30(std::int32_t min) {
	return static_cast<std::int32_t>(std::round(static_cast<double>(min) / 30.0) * 30.0);
}

// The width of one day column of a grid `w` px wide.
std::int32_t col_w(std::int32_t w) {
	return std::max((w - date::TIME_W) / 7, 1);
}

// The shift of content row `y` from the grid's top: the untimed strip stays put, the hours move by `offset`.
std::int32_t row_shift(std::int32_t untimed_h, std::int32_t offset, std::int32_t y) {
	return y < untimed_h ? 0 : -offset;
}

// The content row under row `sy` of the grid, the inverse of `row_shift`.
std::int32_t content_row(std::int32_t untimed_h, std::int32_t offset, std::int32_t sy) {
	return sy < untimed_h ? sy : sy + offset;
}

// True when the event of `o` is the selected one of the model.
bool is_selected(const detail::WeekModel& m, const Occ& o) {
	return m.selected && m.selected->first == o.event.id && m.selected->second == o.day;
}

// The navigation row. It owns the sender that its buttons send through, so the callbacks need no copy.
class NavRow : public Fl_Group {
public:
	NavRow(int x, int y, int w, int h, msg::Sender s) : Fl_Group(x, y, w, h), sender(std::move(s)) {}

	msg::Sender sender;
};

void step_back(Fl_Widget*, void* data) {
	static_cast<NavRow*>(data)->sender.send(date_msg(msg::date::Step{-1}));
}

void go_to(Fl_Widget*, void* data) {
	static_cast<NavRow*>(data)->sender.send(date_msg(msg::date::GoTo{}));
}

void step_fwd(Fl_Widget*, void* data) {
	static_cast<NavRow*>(data)->sender.send(date_msg(msg::date::Step{1}));
}

void today_pressed(Fl_Widget*, void* data) {
	static_cast<NavRow*>(data)->sender.send(date_msg(msg::date::Today{}));
}

// The root group of the Week view, with the edge under the navigation row. The group stays current after this call,
// so the navigation row, the scroll bar and the frames are its children.
Fl_Group* make_root(int x, int y, int w, int h) {
	auto* root = new Fl_Group(x, y, w, h);
	(new Fl_Box(x, y + NAV_H, w, h - NAV_H))->box(FL_BORDER_BOX);
	return root;
}

// The model of a new view: the untimed strip at its minimum, and the clock format of the system.
std::shared_ptr<detail::WeekModel> make_model() {
	auto m = std::make_shared<detail::WeekModel>();
	m->untimed_h = MIN_UNTIMED_H;
	m->h24 = platform::uses_24_hours();
	return m;
}

// The day names over the columns on the panel gray, with no lines between them. The selected day fills blue with
// white text; today is red and bold, unless it is the selected day.
void draw_header(std::int32_t x, std::int32_t y, std::int32_t w, std::int32_t h, const detail::WeekModel& m) {
	const theme::Fonts& fonts = theme::fonts();
	fl_rectf(x, y, w, h, theme::PANEL);
	const std::int32_t cw = col_w(w);
	for (std::int32_t i = 0; i < 7; ++i) {
		const std::int32_t cx = x + date::TIME_W + i * cw;
		const std::int64_t day = m.start + i;
		const bool sel = day == m.day;
		const bool today = day == m.today;
		if (sel) {
			fl_rectf(cx, y, cw, h, theme::SELECT);
		}
		const std::string text =
		    std::format("{} {}", model::DOW_SHORT[date_shared::dow_index(static_cast<std::size_t>(i), m.begins)],
		                palm::days::ymd(day).day);
		fl_font(today ? fonts.sans_bold : fonts.sans, 11);
		if (sel) {
			fl_color(FL_WHITE);
		} else if (today) {
			fl_color(FL_RED);
		} else {
			fl_color(FL_BLACK);
		}
		fl_draw(text.c_str(), cx, y, cw, h, FL_ALIGN_CENTER);
	}
	fl_line(x + date::TIME_W, y + h - 1, x + w, y + h - 1);
}

// The grid. The untimed strip stays at the top; the hours scroll below it, clipped to their area, with the time column
// of raised cells, the day column lines, and the boxes, the selected one last.
void draw_grid(std::int32_t x, std::int32_t y, std::int32_t w, std::int32_t h, const detail::WeekModel& m) {
	const std::int32_t top = y - m.offset;
	const std::int32_t hours_y = y + m.untimed_h;
	const std::int32_t cw = col_w(w);
	palm::draw::Clip clip(x, y, w, h);
	fl_rectf(x, y, w, h, FL_WHITE);
	std::vector<std::pair<Rect, Occ>> hits = m.hits(w);
	std::stable_sort(hits.begin(), hits.end(), [&m](const auto& a, const auto& b) {
		return is_selected(m, a.second) < is_selected(m, b.second);
	});
	{
		palm::draw::Clip hours(x, hours_y, w, std::max(h - m.untimed_h, 0));
		fl_color(date_shared::HOUR_LINE);
		for (std::int32_t hour = 1; hour <= 24; ++hour) {
			const std::int32_t hy = top + m.hour_y(hour);
			fl_line(x, hy, x + w, hy);
		}
		for (std::int32_t hour = 0; hour < 24; ++hour) {
			const std::int32_t hy = top + m.hour_y(hour);
			if (hy > y + h || hy + HOUR_H < y) {
				continue;
			}
			date_shared::raised_cell(x, hy, date::TIME_W, HOUR_H);
			date_shared::draw_hour_label(hour * 60, date::TIME_W, x, hy + HOUR_H / 2, m.h24);
		}
		fl_color(FL_BLACK);
		for (std::int32_t i = 0; i <= 7; ++i) {
			fl_line(x + date::TIME_W + i * cw, top + m.hour_y(0), x + date::TIME_W + i * cw, top + m.hour_y(24));
		}
		for (const auto& [r, o] : hits) {
			if (!o.event.untimed) {
				date_shared::paint_event(r.moved(x, top), o.event, is_selected(m, o), false);
			}
		}
		if (m.typing && m.typing->y >= m.untimed_h) {
			date_shared::draw_box(m.typing->moved(x, top), date_shared::Fill::White, true);
		}
		if (m.outline && m.outline->y >= m.untimed_h) {
			date_shared::draw_outline(m.outline->moved(x, top));
		}
	}
	// The untimed strip: its own time cell and day column lines, then its rows, none of it scrolled.
	{
		palm::draw::Clip strip(x, y, w, m.untimed_h);
		date_shared::raised_cell(x, y, date::TIME_W, m.untimed_h);
		date_shared::draw_diamond(x + date::TIME_W / 2, y + m.untimed_h / 2);
		fl_color(FL_BLACK);
		for (std::int32_t i = 0; i <= 7; ++i) {
			fl_line(x + date::TIME_W + i * cw, y, x + date::TIME_W + i * cw, hours_y);
		}
		for (const auto& [r, o] : hits) {
			if (o.event.untimed) {
				date_shared::paint_event(r.moved(x, y), o.event, is_selected(m, o), true);
			}
		}
		if (m.typing && m.typing->y < m.untimed_h) {
			date_shared::draw_box(m.typing->moved(x, y), date_shared::Fill::White, true);
		}
		if (m.outline && m.outline->y < m.untimed_h) {
			date_shared::draw_outline(m.outline->moved(x, y));
		}
	}
	// The first hour line is black: it parts the untimed strip from the hours, and stays put.
	fl_color(FL_BLACK);
	fl_line(x + date::TIME_W, hours_y, x + w, hours_y);
}

} // namespace

Fl_Box* nav(int x, int y, int w, msg::Sender s) {
	// The row is its own group with the title as its resizable widget, so a wider window widens the title and the
	// buttons keep the 3.1.1 sizes: arrows 18x20, Go to and Today 75x20.
	auto* row = new NavRow(x, y, w, 26, std::move(s));
	Fl_Button* back = palm::draw::arrow(x + 2, y + 4, 18, 20, false);
	back->callback(step_back, row);
	Fl_Button* go = new Fl_Button(x + 20, y + 4, 75, 20, "&Go to");
	go->callback(go_to, row);
	Fl_Button* fwd = palm::draw::arrow(x + 95, y + 4, 18, 20, true);
	fwd->callback(step_fwd, row);
	Fl_Button* today_b = new Fl_Button(x + w - 77, y + 4, 75, 20, "T&oday");
	today_b->callback(today_pressed, row);
	auto* title = new Fl_Box(x + 117, y + 4, w - 198, 20);
	title->box(FL_BORDER_BOX);
	title->color(FL_WHITE);
	title->labelfont(theme::fonts().arial_bold);
	title->labelsize(13);
	row->resizable(title);
	row->end();
	return title;
}

namespace detail {

std::int32_t WeekModel::hour_y(std::int32_t hour) const {
	return untimed_h + hour * HOUR_H;
}

std::int32_t WeekModel::line_y(std::int32_t min) const {
	return untimed_h + min * HOUR_H / 60;
}

std::int32_t WeekModel::min_of_y(std::int32_t y) const {
	return (y - untimed_h) * 60 / HOUR_H;
}

std::vector<std::pair<Rect, Occ>> WeekModel::hits(std::int32_t w) const {
	const std::int32_t cw = col_w(w);
	std::vector<std::pair<Rect, Occ>> out;
	for (std::size_t i = 0; i < days.size(); ++i) {
		const std::int32_t x = date::TIME_W + static_cast<std::int32_t>(i) * cw + 1;
		std::int32_t untimed_n = 0;
		for (const Occ& o : days[i]) {
			const model::Event& e = o.event;
			if (e.untimed) {
				out.emplace_back(Rect::new_rect(x, 2 + untimed_n * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2), o);
				++untimed_n;
			} else {
				// A record without an end gets 30 minutes.
				const std::int32_t s = std::clamp(e.start, 0, MIN_OF_DAY - 1);
				const std::int32_t end = e.end > s ? std::min(e.end, MIN_OF_DAY) : std::min(s + 30, MIN_OF_DAY);
				const std::int32_t y = untimed_h + s * HOUR_H / 60;
				out.emplace_back(Rect::new_rect(x, y, cw - 3, box_h(end - s)), o);
			}
		}
	}
	return out;
}

Drag::Drag(Occ occ_in, Rect rect_in, std::pair<std::int32_t, std::int32_t> origin_in, bool resize_in)
    : occ(std::move(occ_in)), rect(rect_in), origin(origin_in), resize(resize_in) {
	dur = dur_of(occ.event);
	new_day = occ.day;
	new_start = occ.event.start;
	new_end = new_start + dur;
}

WeekHeaderFrame::WeekHeaderFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<WeekModel> model)
    : date::MouseFrame(x, y, w, h, s, [](msg::Mouse m) { return date_msg(msg::date::WeekHeader{m}); }),
      model_(std::move(model)) {}

void WeekHeaderFrame::draw() {
	draw_header(x(), y(), w(), h(), *model_);
}

WeekGridFrame::WeekGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<WeekModel> model)
    : date::MouseFrame(x, y, w, h, s, [](msg::Mouse m) { return date_msg(msg::date::WeekGrid{m}); }),
      model_(std::move(model)), sender_(std::move(s)) {}

void WeekGridFrame::draw() {
	draw_grid(x(), y(), w(), h(), *model_);
}

void WeekGridFrame::resize(int x, int y, int w, int h) {
	date::MouseFrame::resize(x, y, w, h);
	sender_.send(scroll_msg());
}

} // namespace detail

WeekView::WeekView(int x, int y, int w, int h, msg::Sender s)
    : root(make_root(x, y, w, h)), s_(s), title_(nav(x, y, w, s)),
      scroller_(x + w - 1 - Fl::scrollbar_size(), y + NAV_H + 1 + HEAD_H, h - NAV_H - 2 - HEAD_H, s, scroll_msg),
      model_(make_model()) {
	const std::int32_t gy = y + NAV_H;
	const std::int32_t sb = Fl::scrollbar_size();
	header_ = new detail::WeekHeaderFrame(x + 1, gy + 1, w - 2 - sb, HEAD_H, s, model_);
	grid_ = new detail::WeekGridFrame(x + 1, gy + 1 + HEAD_H, w - 2 - sb, h - NAV_H - 2 - HEAD_H, s, model_);
	auto* grow = new Fl_Box(x + 200, gy + 40, w - 300, h - NAV_H - 80);
	root->resizable(grow);
	root->end();
	root->hide();
}

void WeekView::focus() {
	grid_->take_focus();
}

Fl_Input* WeekView::field() const {
	if (typing_) {
		return typing_->field.input;
	}
	if (title_edit_) {
		return title_edit_->first.input;
	}
	return nullptr;
}

void WeekView::scroll_to_hour(std::int32_t hour) {
	scroller_.scroll_later(hour * HOUR_H);
}

std::optional<Occ> WeekView::selected_occ() const {
	if (!selected_) {
		return std::nullopt;
	}
	return find(selected_->event.id, selected_->day);
}

std::optional<Occ> WeekView::find(std::int64_t id, std::int64_t day) const {
	for (const auto& col : model_->days) {
		for (const Occ& o : col) {
			if (o.event.id == id && o.day == day) {
				return o;
			}
		}
	}
	return std::nullopt;
}

void WeekView::refresh(view::Ctx& ctx) {
	const std::uint32_t begins = date_shared::week_begins(ctx.settings);
	const std::int64_t start = date_picker::week_start_of(ctx.day, begins);
	// A new week saves the field first, so its text is not lost.
	if (typing_ && model_->start != start) {
		end_typing(ctx, true);
	}
	const bool new_week = model_->start != start;
	std::vector<std::vector<Occ>> week_days;
	week_days.reserve(7);
	for (std::int64_t i = 0; i < 7; ++i) {
		week_days.push_back(date_shared::visible_events(ctx.store, start + i, ctx.show_private));
	}
	std::int32_t most = 0;
	for (const auto& day : week_days) {
		std::int32_t untimed_n = 0;
		for (const Occ& o : day) {
			if (o.event.untimed) {
				++untimed_n;
			}
		}
		most = std::max(most, untimed_n);
	}
	std::int32_t untimed_h = std::max(most * UNTIMED_LINE + 4, MIN_UNTIMED_H);
	// An open untimed field keeps its line.
	if (typing_ && !typing_->min) {
		untimed_h = std::max(untimed_h, (typing_->line + 1) * UNTIMED_LINE + 4);
	}
	model_->start = start;
	model_->day = ctx.day;
	model_->today = platform::today();
	model_->begins = begins;
	model_->days = std::move(week_days);
	model_->untimed_h = untimed_h;
	if (selected_) {
		selected_ = find(selected_->event.id, selected_->day);
	}
	const auto long_date = [](std::int64_t d) {
		const palm::days::Ymd y = palm::days::ymd(d);
		return std::format("{} {}, {}", date_shared::MONTH_LONG[y.month - 1], y.day, y.year);
	};
	const std::string label = std::format("{} - {} Week {}", long_date(start), long_date(start + 6),
	                                      date_shared::week_of_year(start, begins == 1));
	title_->copy_label(label.c_str());
	// A new week opens at the work-day start hour, as Palm Desktop does.
	if (new_week) {
		scroll_to_hour(date_shared::work_day(ctx.settings));
	}
	sync();
}

void WeekView::sync() {
	const std::int32_t untimed_h = model_->untimed_h;
	// The hours scroll under the untimed strip, so their bar starts below it.
	const std::int32_t hours_h = std::max(grid_->h() - untimed_h, 1);
	const std::int32_t total = model_->hour_y(24) - untimed_h + 1;
	scroller_.set_range(hours_h, total);
	Fl_Scrollbar* b = scroller_.bar;
	b->resize(b->x(), grid_->y() + untimed_h, b->w(), hours_h);
	std::optional<Rect> typing_rect;
	if (typing_) {
		const std::int32_t cw = col_w(grid_->w());
		const std::int32_t x = date::TIME_W + static_cast<std::int32_t>(typing_->col) * cw + 1;
		if (!typing_->min) {
			typing_rect = Rect::new_rect(x, 2 + typing_->line * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2);
		} else {
			typing_rect = Rect::new_rect(x, untimed_h + *typing_->min * HOUR_H / 60, cw - 3, box_h(60));
		}
	}
	model_->offset = scroller_.offset();
	if (selected_) {
		model_->selected = std::pair{selected_->event.id, selected_->day};
	} else {
		model_->selected.reset();
	}
	model_->typing = typing_rect;
	if (typing_ && typing_rect) {
		const std::int32_t top = grid_->y() + row_shift(untimed_h, scroller_.offset(), typing_rect->y);
		typing_->field.place(grid_->x() + typing_rect->x + 3,
		                     top + date_shared::title_top(*typing_rect, typing_rect->y < untimed_h),
		                     typing_rect->w - date_shared::BAR_W - 4);
	}
	header_->redraw();
	grid_->redraw();
	scroller_.bar->redraw();
}

void WeekView::scrolled() {
	sync();
	root->redraw();
}

void WeekView::set_cursor(Fl_Cursor c) const {
	if (Fl_Window* w = grid_->window()) {
		w->cursor(c);
	}
}

std::optional<Rect> WeekView::sel_rect() const {
	const std::optional<Occ> sel = selected_occ();
	if (!sel) {
		return std::nullopt;
	}
	for (const auto& [r, o] : model_->hits(grid_->w())) {
		if (o == *sel && !o.event.untimed) {
			return r;
		}
	}
	return std::nullopt;
}

std::optional<std::pair<Rect, Occ>> WeekView::hit_at(std::int32_t lx, std::int32_t ly) const {
	const auto hits = model_->hits(grid_->w());
	for (auto it = hits.rbegin(); it != hits.rend(); ++it) {
		if (it->first.contains(lx, ly)) {
			return *it;
		}
	}
	return std::nullopt;
}

void WeekView::header_mouse(view::Ctx& ctx, msg::Mouse m) {
	if (!std::holds_alternative<msg::mouse::Press>(m.kind) || m.right) {
		return;
	}
	const std::int32_t lx = m.x - header_->x();
	if (lx < date::TIME_W) {
		return;
	}
	const std::int64_t start = model_->start;
	ctx.day = start + std::clamp((lx - date::TIME_W) / col_w(header_->w()), 0, 6);
}

void WeekView::grid_mouse(view::Ctx& ctx, msg::Mouse m) {
	const std::int32_t untimed_h = model_->untimed_h;
	const std::int32_t lx = m.x - grid_->x();
	const std::int32_t ly = content_row(untimed_h, scroller_.offset(), m.y - grid_->y());
	if (const auto* wheel = std::get_if<msg::mouse::Wheel>(&m.kind)) {
		scroller_.wheel(wheel->lines);
		scrolled();
		return;
	}
	if (std::holds_alternative<msg::mouse::Move>(m.kind)) {
		const std::optional<Rect> r = sel_rect();
		const bool on = r && date_shared::on_resize_bar(*r, lx, ly);
		set_cursor(on ? FL_CURSOR_NS : FL_CURSOR_DEFAULT);
		return;
	}
	if (std::holds_alternative<msg::mouse::Press>(m.kind)) {
		suppress_click_ = false;
		grid_->take_focus();
		if (lx < date::TIME_W) {
			return;
		}
		if (m.right) {
			// The menu of a box. Empty space has no menu, as in Palm Desktop.
			close_saving(ctx);
			const auto hit = hit_at(lx, ly);
			selected_.reset();
			if (hit) {
				selected_ = hit->second;
			}
			sync();
			if (hit) {
				box_action(ctx, hit->second);
			}
			return;
		}
		const std::optional<Rect> r = sel_rect();
		const std::optional<Occ> sel = selected_occ();
		if (r && sel && date_shared::on_resize_bar(*r, lx, ly)) {
			drag_.emplace(*sel, *r, std::pair{lx, ly}, true);
			return;
		}
		const auto hit = hit_at(lx, ly);
		if (!hit) {
			return;
		}
		const Rect br = hit->first;
		const Occ occ = hit->second;
		if (date_shared::on_handle(br, lx, ly) || date_shared::marker_at(br, occ.event, lx, ly, occ.event.untimed)) {
			return;
		}
		selected_ = occ;
		drag_.emplace(occ, br, std::pair{lx, ly}, false);
		sync();
		return;
	}
	if (std::holds_alternative<msg::mouse::Drag>(m.kind)) {
		if (!drag_) {
			return;
		}
		detail::Drag d = std::move(*drag_);
		drag_.reset();
		if (d.resize) {
			set_cursor(FL_CURSOR_NS);
		}
		if (!d.moved && std::abs(lx - d.origin.first) <= 4 && std::abs(ly - d.origin.second) <= 4) {
			drag_.emplace(std::move(d));
			return;
		}
		d.moved = true;
		const Rect outline = update_drag(d, lx, ly);
		model_->outline = outline;
		drag_.emplace(std::move(d));
		grid_->redraw();
		return;
	}
	// The release: a moved box is saved, else the click acts.
	const std::optional<Rect> r = sel_rect();
	const bool on = r && date_shared::on_resize_bar(*r, lx, ly);
	set_cursor(on ? FL_CURSOR_NS : FL_CURSOR_DEFAULT);
	if (drag_) {
		detail::Drag d = std::move(*drag_);
		drag_.reset();
		model_->outline.reset();
		grid_->redraw();
		if (d.moved) {
			suppress_click_ = true;
			save_drag(ctx, d);
			return;
		}
	}
	if (suppress_click_ || m.right) {
		suppress_click_ = false;
		return;
	}
	click(ctx, lx, ly, m.double_click);
}

Rect WeekView::update_drag(detail::Drag& d, std::int32_t lx, std::int32_t ly) const {
	const detail::WeekModel& m = *model_;
	const std::int32_t cw = col_w(grid_->w());
	const std::int32_t col = std::clamp((lx - date::TIME_W) / cw, 0, 6);
	if (d.resize) {
		const std::int32_t start = d.occ.event.start;
		d.new_end = std::min(std::max(round30(m.min_of_y(ly)), start + 30), MIN_OF_DAY);
		const std::int32_t top = m.line_y(start);
		return Rect::new_rect(d.rect.x, top + 1, d.rect.w, std::max(m.line_y(d.new_end), top + HOUR_H / 2) - top - 2);
	}
	if (d.occ.event.untimed) {
		d.new_day = m.start + col;
		return Rect::new_rect(date::TIME_W + col * cw + 1, d.rect.y, cw - 3, d.rect.h);
	}
	d.new_day = m.start + col;
	const std::int32_t grab = d.origin.second - d.rect.y;
	d.new_start = std::min(std::max(round30(m.min_of_y(ly - grab)), 0), MIN_OF_DAY - d.dur);
	d.new_end = d.new_start + d.dur;
	const std::int32_t top = m.line_y(d.new_start);
	return Rect::new_rect(d.rect.x + lx - d.origin.first, top + 1, d.rect.w, m.line_y(d.new_end) - top - 2);
}

void WeekView::save_drag(view::Ctx& ctx, const detail::Drag& d) {
	const Occ& occ = d.occ;
	const model::Event& e = d.occ.event;
	if (d.resize) {
		if (d.new_end != e.end) {
			date_shared::change_occurrence(ctx.store, occ, date_shared::CHANGE_TEXT,
			                               [&d](model::Event& x) { x.end = d.new_end; });
		}
	} else if (e.untimed) {
		if (d.new_day != occ.day) {
			date_shared::change_occurrence(ctx.store, occ, date_shared::CHANGE_TEXT,
			                               [&d](model::Event& x) { x.date = d.new_day; });
		}
	} else if (d.new_day != occ.day || d.new_start != e.start) {
		date_shared::change_occurrence(ctx.store, occ, date_shared::CHANGE_TEXT, [&d](model::Event& x) {
			x.date = d.new_day;
			x.start = d.new_start;
			x.end = d.new_start + d.dur;
		});
	}
}

void WeekView::click(view::Ctx& ctx, std::int32_t lx, std::int32_t ly, bool double_click) {
	if (lx < date::TIME_W) {
		return;
	}
	if (double_click) {
		end_title_edit(ctx, false);
		end_typing(ctx, false);
	} else {
		close_saving(ctx);
	}
	const auto col = static_cast<std::size_t>(std::clamp((lx - date::TIME_W) / col_w(grid_->w()), 0, 6));
	const std::int64_t start = model_->start;
	ctx.day = start + static_cast<std::int64_t>(col);
	const auto hit = hit_at(lx, ly);
	if (!hit) {
		selected_.reset();
		const std::int32_t untimed_h = model_->untimed_h;
		std::optional<std::int32_t> min;
		if (ly >= untimed_h) {
			min = std::clamp((ly - untimed_h) / HOUR_H, 0, 23) * 60;
		}
		if (double_click) {
			event_dialog::edit(ctx, std::nullopt, ctx.day, min, "");
			return;
		}
		std::int32_t line = 0;
		for (const Occ& o : model_->days[col]) {
			if (o.event.untimed) {
				++line;
			}
		}
		start_typing(ctx, col, min, line);
		return;
	}
	const Rect r = hit->first;
	const Occ occ = hit->second;
	const auto marker = date_shared::marker_at(r, occ.event, lx, ly, occ.event.untimed);
	selected_ = occ;
	sync();
	if (double_click && date_shared::on_handle(r, lx, ly)) {
		event_dialog::edit(ctx, occ.event, occ.day, std::nullopt, "");
	} else if (double_click) {
		start_title_edit(r, occ);
	} else if (marker == date_shared::Marker::Alarm) {
		date_shared::edit_alarm(ctx.store, occ);
	} else if (marker == date_shared::Marker::Note) {
		date_shared::edit_note(ctx.store, occ);
	}
}

void WeekView::box_action(view::Ctx& ctx, const Occ& occ) {
	const std::optional<date_shared::BoxAction> act = date_shared::event_menu();
	if (!act) {
		return;
	}
	switch (*act) {
	case date_shared::BoxAction::Delete:
		date_shared::delete_occurrence(ctx.store, ctx.settings, ctx.no_confirm, occ);
		break;
	case date_shared::BoxAction::Note:
		date_shared::edit_note(ctx.store, occ);
		break;
	case date_shared::BoxAction::Alarm:
		date_shared::edit_alarm(ctx.store, occ);
		break;
	case date_shared::BoxAction::Edit:
		event_dialog::edit(ctx, occ.event, occ.day, std::nullopt, "");
		break;
	}
}

void WeekView::close_saving(view::Ctx& ctx) {
	end_title_edit(ctx, true);
	end_typing(ctx, true);
}

void WeekView::start_typing(view::Ctx& ctx, std::size_t col, std::optional<std::int32_t> min, std::int32_t line) {
	end_typing(ctx, true);
	const std::int64_t start = model_->start;
	if (!min) {
		model_->untimed_h = std::max(model_->untimed_h, (line + 1) * UNTIMED_LINE + 4);
	}
	date::TypeField field = date::TypeField::open(root, 0, 0, 50, "", s_);
	typing_ = detail::Typing{field, start + static_cast<std::int64_t>(col), col, min, line};
	sync();
}

void WeekView::end_typing(view::Ctx& ctx, bool save) {
	if (!typing_) {
		return;
	}
	detail::Typing t = std::move(*typing_);
	typing_.reset();
	const std::string title = t.field.text();
	const std::int64_t day = t.day;
	const std::optional<std::int32_t> min = t.min;
	t.field.close();
	sync();
	if (!save || title.empty()) {
		return;
	}
	model::Event e;
	e.title = title;
	e.date = day;
	e.untimed = !min;
	e.start = min.value_or(8 * 60);
	e.end = min ? *min + 60 : 9 * 60;
	date_shared::apply_alarm_preset(e, ctx.settings);
	ctx.store.save_event(e);
	selected_ = Occ{e, day};
}

void WeekView::start_title_edit(Rect r, Occ occ) {
	const std::int32_t x = date_shared::title_x(r, occ.event);
	const std::int32_t shift = row_shift(model_->untimed_h, scroller_.offset(), r.y);
	const std::int32_t top = grid_->y() + shift;
	date::TypeField field =
	    date::TypeField::open(root, grid_->x() + x, top + date_shared::title_top(r, occ.event.untimed),
	                          r.x + r.w - date_shared::BAR_W - x, occ.event.title, s_);
	const auto n = static_cast<int>(std::strlen(field.input->value()));
	field.input->insert_position(n, n);
	field.input->mark(0);
	title_edit_ = std::pair{field, occ};
}

void WeekView::end_title_edit(view::Ctx& ctx, bool save) {
	if (!title_edit_) {
		return;
	}
	std::pair<date::TypeField, Occ> taken = std::move(*title_edit_);
	title_edit_.reset();
	const std::string text = taken.first.text();
	taken.first.close();
	grid_->redraw();
	if (save && !text.empty() && text != taken.second.event.title) {
		date_shared::change_occurrence(ctx.store, taken.second, date_shared::CHANGE_TEXT,
		                               [&text](model::Event& e) { e.title = text; });
	}
}

void WeekView::field_event(view::Ctx& ctx, msg::FieldEv f) {
	switch (f) {
	case msg::FieldEv::Enter:
		close_saving(ctx);
		break;
	case msg::FieldEv::Escape:
		end_title_edit(ctx, false);
		end_typing(ctx, false);
		break;
	case msg::FieldEv::Lost: {
		const Fl_Widget* focused = Fl::focus();
		if (typing_ && static_cast<const Fl_Widget*>(typing_->field.input) != focused) {
			end_typing(ctx, true);
		}
		if (title_edit_ && static_cast<const Fl_Widget*>(title_edit_->first.input) != focused) {
			end_title_edit(ctx, true);
		}
		break;
	}
	case msg::FieldEv::Double:
		// A double click in the field opens the event dialog, as on an empty hour.
		if (typing_) {
			const std::int64_t day = typing_->day;
			const std::optional<std::int32_t> min = typing_->min;
			end_typing(ctx, false);
			event_dialog::edit(ctx, std::nullopt, day, min, "");
		}
		break;
	}
}

} // namespace palm::week
