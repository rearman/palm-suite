// The Month view, as desktop-rs/app/src/month.rs and MonthView.kt: a grid of six weeks, with the events of each day in
// its cell. The grid draws in MonthGridFrame::draw() and takes its events in MonthGridFrame::handle(int), where the
// Rust closures of the Frame stood.
#include "palm/month.hpp"

#include "palm/date.hpp"
#include "palm/date_picker.hpp"
#include "palm/date_shared.hpp"
#include "palm/days.hpp"
#include "palm/draw.hpp"
#include "palm/event_dialog.hpp"
#include "palm/model.hpp"
#include "palm/platform.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"
#include "palm/view.hpp"
#include "palm/week.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <format>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>

namespace palm::month {

namespace {

// The height of the day name row.
constexpr int NAME_H = 18;
constexpr int NUM_PAD = 2;
constexpr int WEEKS = 6;
// The triangle that marks a cell with more events than fit.
constexpr int MORE_W = 7;
constexpr int MORE_H = 4;

// Shows the Month cell menu at the pointer; the index of the pick.
std::optional<std::size_t> cell_menu() {
	Fl_Menu_Button mb(0, 0, 0, 0);
	mb.type(Fl_Menu_Button::POPUP3);
	for (const char* label : {"&Day View", "&Week View", "&New Event..."}) {
		mb.add(label);
	}
	// A divider after Week View (item 1), as the Rust's mb.at(1) sets it.
	mb.mode(1, FL_MENU_DIVIDER);
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

// The text of an event line: an asterisk for an untimed event, the start time for a timed one.
std::string line_text(const date_shared::Occ& o, bool h24) {
	if (o.event.untimed) {
		return "* " + o.event.title;
	}
	return date_shared::time_label(o.event.start, h24) + " " + o.event.title;
}

// Whether a code point is a control character, as Rust's char::is_control.
bool is_control(char32_t c) {
	return c <= 0x1F || (c >= 0x7F && c <= 0x9F);
}

// The grid lines: one black device pixel line on each edge of the six weeks, drawn once for all cells, so two cells
// never draw the same edge twice.
void draw_grid(int x, int y, int cw, int ch) {
	const auto d = draw::Dev::begin();
	const int top = y + NAME_H;
	const int bottom = y + NAME_H + WEEKS * ch;
	const int left = x;
	const int right = x + 7 * cw;
	const int dt = d.p(top);
	const int db = d.p(bottom);
	const int dl = d.p(left);
	const int dr = d.p(right);
	const int lw = d.lw();
	fl_color(FL_BLACK);
	for (int c = 0; c <= 7; ++c) {
		fl_rectf(d.p(left + c * cw), dt, lw, db - dt);
	}
	for (int r = 0; r <= WEEKS; ++r) {
		fl_rectf(dl, d.p(top + r * ch), dr - dl, lw);
	}
}

} // namespace

namespace detail {

MonthGridFrame::MonthGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<MonthModel> model)
    : Fl_Box(x, y, w, h), s_(std::move(s)), model_(std::move(model)) {}

void MonthGridFrame::draw() {
	const auto& fonts = theme::fonts();
	const int x = this->x();
	const int y = this->y();
	const int w = this->w();
	const int h = this->h();
	draw::Clip clip(x, y, w, h);
	fl_rectf(x, y, w, h, FL_WHITE);
	const int cw = w / 7;
	const int ch = (h - NAME_H) / WEEKS;
	// The day name row.
	fl_rectf(x, y, w, NAME_H, theme::PANEL);
	fl_font(fonts.sans, 11);
	fl_color(FL_BLACK);
	for (int c = 0; c < 7; ++c) {
		const std::string name(model::DOW_LONG[date_shared::dow_index(static_cast<std::size_t>(c), model_->begins)]);
		fl_draw(name.c_str(), x + c * cw, y, cw, NAME_H, FL_ALIGN_CENTER);
	}
	if (model_->days.empty()) {
		return;
	}
	const std::uint32_t shown = days::ymd(model_->day).month;
	fl_font(fonts.sans, 11);
	const int number_h = fl_height() + NUM_PAD;
	fl_font(fonts.arial, 12);
	const int line_h = fl_height();
	const auto key = theme::icon("private");
	for (int i = 0; i < 7 * WEEKS; ++i) {
		const int rx = x + (i % 7) * cw;
		const int ry = y + NAME_H + (i / 7) * ch;
		const std::int64_t day = model_->start + i;
		const bool other = days::ymd(day).month != shown;
		draw::Clip cell(rx, ry, cw, ch);
		fl_rectf(rx, ry, cw, ch, other ? theme::PANEL : FL_WHITE);
		// The date number at the top right. The selected day fills with the select color; today is red and bold, on
		// the fill too.
		const bool today = day == model_->today;
		fl_font(today ? fonts.sans_bold : fonts.sans, 11);
		const std::string num = std::to_string(days::ymd(day).day);
		const int nw = draw::text_w(num);
		const int nx = rx + cw - nw - 3;
		Fl_Color color = FL_BLACK;
		if (day == model_->day) {
			fl_rectf(nx - 2, ry + 1, nw + 4, fl_height(), theme::SELECT);
			color = today ? FL_RED : FL_WHITE;
		} else if (today) {
			color = FL_RED;
		} else if (other) {
			color = fl_rgb_color(128, 128, 128);
		}
		fl_color(color);
		fl_draw(num.c_str(), nx, ry + fl_height() - fl_descent() + 1);
		// The event lines that fit. A black triangle at the top left shows the day has more events.
		const int room = std::max(0, (ch - number_h) / std::max(1, line_h));
		const auto& list = model_->days[static_cast<std::size_t>(i)];
		fl_font(fonts.arial, 12);
		const int asc = fl_height() - fl_descent();
		int ly = ry + number_h;
		const std::size_t fits = std::min(list.size(), static_cast<std::size_t>(room));
		for (std::size_t k = 0; k < fits; ++k) {
			const auto& o = list[k];
			int lx = rx + 3;
			if (o.event.is_private) {
				key->draw(lx, ly, key->w(), key->h());
				lx += 13;
			}
			fl_font(fonts.arial, 12);
			fl_color(FL_BLACK);
			const std::string line = draw::fit(line_text(o, model_->h24), rx + cw - lx - 2, draw::text_w);
			fl_draw(line.c_str(), lx, ly + asc);
			ly += line_h;
		}
		if (list.size() > static_cast<std::size_t>(room)) {
			const int tx = rx + 2;
			const int ty = ry + 2;
			fl_color(FL_BLACK);
			fl_polygon(tx, ty, tx + MORE_W, ty, tx + MORE_W / 2, ty + MORE_H);
		}
	}
	draw_grid(x, y, cw, ch);
}

int MonthGridFrame::handle(int event) {
	// FLTK's own handler runs first, as in fltk-rs; the result below is the one the Rust closure gave.
	Fl_Box::handle(event);
	if (event == FL_KEYDOWN) {
		// A printable key starts a new event on the selected day, titled with the key.
		const std::string_view typed(Fl::event_text(), static_cast<std::size_t>(Fl::event_length()));
		if (typed.empty()) {
			return 0;
		}
		const text::Decoded decoded = text::decode_at(typed, 0);
		const bool one_char = decoded.len == typed.size();
		if (one_char && !is_control(decoded.cp) && !Fl::event_state(FL_CTRL) && !Fl::event_state(FL_ALT)) {
			s_.send(msg::Msg{msg::Date{msg::DateMsg{msg::date::MonthKey{decoded.cp}}}});
			return 1;
		}
		return 0;
	}
	if (const auto m = date::mouse_of(event)) {
		s_.send(msg::Msg{msg::Date{msg::DateMsg{msg::date::MonthGrid{*m}}}});
		return 1;
	}
	return (event == FL_FOCUS || event == FL_UNFOCUS) ? 1 : 0;
}

} // namespace detail

MonthView::MonthView(int x, int y, int w, int h, msg::Sender s) : model_(std::make_shared<detail::MonthModel>()) {
	root = new Fl_Group(x, y, w, h);
	title_ = week::nav(x, y, w, s);
	model_->h24 = platform::uses_24_hours();
	grid_ = new detail::MonthGridFrame(x, y + week::NAV_H, w, h - week::NAV_H, s, model_);
	root->resizable(grid_);
	root->end();
	root->hide();
}

void MonthView::focus() {
	grid_->take_focus();
}

void MonthView::refresh(view::Ctx& ctx) {
	const days::Ymd d = days::ymd(ctx.day);
	const std::uint32_t begins = date_shared::week_begins(ctx.settings);
	const std::int64_t start = date_picker::week_start_of(days::epoch_day(d.year, d.month, 1), begins);
	model_->start = start;
	model_->day = ctx.day;
	model_->today = platform::today();
	model_->begins = begins;
	model_->days.clear();
	model_->days.reserve(7 * WEEKS);
	for (int i = 0; i < 7 * WEEKS; ++i) {
		model_->days.push_back(date_shared::visible_events(ctx.store, start + i, ctx.show_private));
	}
	title_->copy_label(
	    std::format("{}, {}", date_shared::MONTH_LONG[static_cast<std::size_t>(d.month - 1)], d.year).c_str());
	grid_->redraw();
}

std::optional<std::int64_t> MonthView::cell_at(int x, int y) const {
	const int lx = x - grid_->x();
	const int ly = y - grid_->y();
	const int cw = grid_->w() / 7;
	const int ch = (grid_->h() - NAME_H) / WEEKS;
	if (ly < NAME_H || cw == 0 || ch == 0) {
		return std::nullopt;
	}
	const int row = std::min((ly - NAME_H) / ch, WEEKS - 1);
	const int col = std::min(lx / cw, 6);
	return static_cast<std::int64_t>(row * 7 + col);
}

std::optional<msg::DateMode> MonthView::mouse(view::Ctx& ctx, msg::Mouse m) {
	if (!std::holds_alternative<msg::mouse::Press>(m.kind)) {
		return std::nullopt;
	}
	grid_->take_focus();
	const auto i = cell_at(m.x, m.y);
	if (!i) {
		return std::nullopt;
	}
	const std::int64_t day = model_->start + *i;
	ctx.day = day;
	if (m.right) {
		const std::optional<std::size_t> pick = cell_menu();
		if (pick && *pick == 0) {
			return msg::DateMode::Day;
		}
		if (pick && *pick == 1) {
			return msg::DateMode::Week;
		}
		if (pick) {
			event_dialog::edit(ctx, std::nullopt, day, std::nullopt, "");
		}
		return std::nullopt;
	}
	if (m.double_click) {
		return msg::DateMode::Day;
	}
	return std::nullopt;
}

} // namespace palm::month
