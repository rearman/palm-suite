// The Date view, as desktop-rs/app/src/date.rs: the helpers and the widget subclasses of the Day view, the right
// pane, and the tabs on the right edge. The methods of DateView are in date_view.cpp.
//
// A Rust closure set on a widget is the draw() or handle(int) of its subclass. Each draw() first runs the FLTK
// draw of its class, as fltk-rs runs its default draw before the closure, and each handle(int) runs FLTK's own
// handler first, as fltk-rs does.

#include "date_internal.hpp"

#include "palm/address.hpp"
#include "palm/date_picker.hpp"
#include "palm/date_shared.hpp"
#include "palm/days.hpp"
#include "palm/draw.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/platform.hpp"
#include "palm/records.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"
#include "palm/todo.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_Scrollbar.H>
#include <FL/Fl_Table_Row.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <variant>
#include <vector>

namespace palm::date {

namespace {

// The message of a Date Book event.
msg::Msg date_msg(msg::DateMsg m) {
	return msg::Msg{msg::Date{std::move(m)}};
}

// The message of the field that types a new event or a title.
msg::Msg field_msg(msg::FieldEv e) {
	return date_msg(msg::date::Field{e});
}

// A vertical scroll bar that keeps the message it sends, so its callback needs no other state.
class ScrollBar : public Fl_Scrollbar {
public:
	ScrollBar(int x, int y, int h, msg::Sender s, msg::Msg (*make)())
	    : Fl_Scrollbar(x, y, Fl::scrollbar_size(), h), s_(std::move(s)), make_(make) {}

	msg::Sender s_;
	msg::Msg (*make_)();
};

} // namespace

std::optional<msg::Mouse> mouse_of(int ev) {
	msg::MouseKind kind = msg::mouse::Press{};
	switch (ev) {
	case FL_PUSH:
		kind = msg::mouse::Press{};
		break;
	case FL_DRAG:
		kind = msg::mouse::Drag{};
		break;
	case FL_RELEASE:
		kind = msg::mouse::Release{};
		break;
	case FL_MOVE:
		kind = msg::mouse::Move{};
		break;
	case FL_MOUSEWHEEL: {
		// FLTK counts a positive dy as a turn down, which is the Rust MouseWheel::Down.
		const int dy = Fl::event_dy();
		kind = msg::mouse::Wheel{dy > 0 ? 1 : (dy < 0 ? -1 : 0)};
		break;
	}
	default:
		return std::nullopt;
	}
	return msg::Mouse{kind, Fl::event_x(), Fl::event_y(), Fl::event_button() == FL_RIGHT_MOUSE,
	                  Fl::event_clicks() != 0};
}

MouseFrame::MouseFrame(int x, int y, int w, int h, msg::Sender s, std::function<msg::Msg(msg::Mouse)> wrap)
    : Fl_Box(x, y, w, h), s_(std::move(s)), wrap_(std::move(wrap)) {}

int MouseFrame::handle(int event) {
	const int ret = Fl_Box::handle(event);
	if (const auto m = mouse_of(event)) {
		s_.send(wrap_(*m));
		return 1;
	}
	// Enter keeps the Move events coming.
	return (event == FL_ENTER || event == FL_LEAVE) ? 1 : ret;
}

Scroller::Scroller(int x, int y, int h, msg::Sender s, msg::Msg (*make)())
    : bar(new ScrollBar(x, y, h, std::move(s), make)) {
	bar->type(FL_VERTICAL);
	bar->linesize(10);
	bar->callback(
	    [](Fl_Widget* w, void*) {
		    auto* sb = static_cast<ScrollBar*>(w);
		    sb->s_.send(sb->make_());
	    },
	    nullptr);
}

int Scroller::offset() const {
	return bar->value();
}

void Scroller::set_range(int view, int total) {
	detail::set_bar_range(*bar, view, total);
	if (total - view > 0 && pending_) {
		scroll_to(*pending_);
		pending_.reset();
	}
}

void Scroller::scroll_to(int y) {
	bar->value(std::clamp(y, 0, static_cast<int>(bar->maximum())));
}

void Scroller::scroll_later(int y) {
	pending_ = y;
}

void Scroller::wheel(int lines) {
	scroll_to(bar->value() + lines * 3 * 10);
}

TypeInput::TypeInput(int x, int y, int w, int h, msg::Sender s) : Fl_Input(x, y, w, h), s_(std::move(s)) {}

int TypeInput::handle(int event) {
	// FLTK's own handler runs first. Enter is sent here, as the Rust Enter-key callback is: the field sets no
	// callback, so FLTK's handler sends nothing.
	const int ret = Fl_Input::handle(event);
	switch (event) {
	case FL_KEYDOWN:
		if (Fl::event_key() == FL_Escape) {
			s_.send(field_msg(msg::FieldEv::Escape));
			return 1;
		}
		if (Fl::event_key() == FL_Enter || Fl::event_key() == FL_KP_Enter) {
			s_.send(field_msg(msg::FieldEv::Enter));
		}
		return ret;
	case FL_UNFOCUS:
		s_.send(field_msg(msg::FieldEv::Lost));
		return ret;
	case FL_PUSH:
		if (Fl::event_button() == FL_RIGHT_MOUSE) {
			text_edit::popup(text_edit::Field{static_cast<Fl_Input*>(this)});
			return 1;
		}
		if (Fl::event_clicks() != 0) {
			s_.send(field_msg(msg::FieldEv::Double));
			return 1;
		}
		return ret;
	default:
		return ret;
	}
}

TypeField TypeField::open(Fl_Group* g, int x, int y, int w, std::string_view text, msg::Sender s) {
	g->begin();
	auto* input = new TypeInput(0, 0, 20, 17, std::move(s));
	g->end();
	Fl_Group::current(nullptr);
	input->box(FL_FLAT_BOX);
	input->color(FL_WHITE);
	// The font of the box titles, so the text keeps its size while it is edited.
	input->textfont(theme::fonts().sans);
	input->textsize(11);
	input->value(std::string(text).c_str());
	input->when(FL_WHEN_ENTER_KEY_ALWAYS);
	input->take_focus();
	TypeField field{input};
	field.place(x, y, w);
	return field;
}

void TypeField::place(int x, int y, int w) {
	input->resize(x - 1, y, std::max(w, 20) + 1, date_shared::title_line_h());
	input->redraw();
}

std::string TypeField::text() const {
	const std::string value = input->value();
	return std::string(text::trim(value));
}

void TypeField::close() {
	Fl_Group* parent = input->parent();
	Fl::delete_widget(input);
	if (parent != nullptr) {
		parent->redraw();
	}
}

namespace detail {

namespace {

// The message of a click on the right pane's list.
msg::Msg pane_list_msg(msg::Mouse m) {
	return date_msg(msg::date::PaneList{m});
}

// The sorted boxes of the header (`header` true) or the grid, as layout_day gives them. The selected box comes last,
// so its blue bar is on top; the sort is stable.
std::vector<std::tuple<std::size_t, date_shared::Rect>> sorted_boxes(const DayModel& m, std::int32_t width,
                                                                     bool header) {
	std::vector<std::tuple<std::size_t, date_shared::Rect>> boxes;
	for (const auto& [i, r, is_header] : layout_day(m.occs, width)) {
		if (is_header == header) {
			boxes.emplace_back(i, r);
		}
	}
	const auto selected = [&m](std::size_t i) { return m.selected && *m.selected == m.occs[i].event.id; };
	std::stable_sort(boxes.begin(), boxes.end(), [&selected](const auto& a, const auto& b) {
		return selected(std::get<0>(a)) < selected(std::get<0>(b));
	});
	return boxes;
}

} // namespace

void set_bar_range(Fl_Scrollbar& bar, std::int32_t view, std::int32_t total) {
	const std::int32_t max = std::max(total - view, 0);
	bar.bounds(0.0, static_cast<double>(max));
	const float shown = static_cast<float>(view) / static_cast<float>(std::max(total, 1));
	bar.slider_size(static_cast<double>(std::min(shown, 1.0F)));
	bar.value(std::clamp(bar.value(), 0, max));
}

std::int32_t y_of(std::int32_t min) {
	return min * HALF / 30;
}

std::int32_t min_at(std::int32_t y) {
	return y * 30 / HALF;
}

std::int32_t drop_min(std::int32_t y) {
	return std::clamp(min_at(y) / 30 * 30, 0, 23 * 60);
}

std::int32_t round_half(std::int32_t min) {
	return (min + 15) / 30 * 30;
}

date_shared::Rect hour_box(std::int32_t min, std::int32_t width) {
	const std::int32_t top = y_of(min);
	return date_shared::Rect::new_rect(TIME_W + 2, top + 2, width - TIME_W - 4, y_of(min + 60) - top - 3);
}

date_shared::Rect outline_box(std::int32_t start, std::int32_t end, std::int32_t width) {
	const std::int32_t top = y_of(start);
	return date_shared::Rect::new_rect(TIME_W + 3, top + 1, width - TIME_W - 5,
	                                   std::max(y_of(end), top + HALF) - top - 2);
}

std::vector<std::tuple<std::size_t, date_shared::Rect, bool>> layout_day(std::span<const date_shared::Occ> occs,
                                                                         std::int32_t width) {
	std::vector<std::tuple<std::size_t, date_shared::Rect, bool>> out;
	std::int32_t row = 0;
	for (std::size_t i = 0; i < occs.size(); ++i) {
		if (occs[i].event.untimed) {
			out.emplace_back(
			    i, date_shared::Rect::new_rect(TIME_W + 2, row * UNTIMED_H + 1, width - TIME_W - 4, UNTIMED_H - 2),
			    true);
			++row;
		}
	}
	std::vector<std::size_t> timed;
	for (std::size_t i = 0; i < occs.size(); ++i) {
		if (!occs[i].event.untimed) {
			timed.push_back(i);
		}
	}
	// The column of each event: the first column that is free at its start.
	std::vector<std::int32_t> col_end;
	std::vector<std::size_t> col(timed.size(), 0);
	for (std::size_t k = 0; k < timed.size(); ++k) {
		const auto& e = occs[timed[k]].event;
		auto it = std::find_if(col_end.begin(), col_end.end(), [&e](std::int32_t end) { return end <= e.start; });
		std::size_t c = 0;
		if (it == col_end.end()) {
			col_end.push_back(0);
			c = col_end.size() - 1;
		} else {
			c = static_cast<std::size_t>(it - col_end.begin());
		}
		col_end[c] = e.end;
		col[k] = c;
	}
	const std::int32_t area_w = width - TIME_W - 2;
	for (std::size_t k = 0; k < timed.size(); ++k) {
		const auto& e = occs[timed[k]].event;
		// The column count of an event: the widest column among the events it overlaps.
		std::size_t n = col[k] + 1;
		for (std::size_t j = 0; j < timed.size(); ++j) {
			const auto& p = occs[timed[j]].event;
			if (e.start < p.end && p.start < e.end) {
				n = std::max(n, col[j] + 1);
			}
		}
		const std::int32_t col_w = area_w / static_cast<std::int32_t>(n);
		const std::int32_t top = y_of(e.start);
		const std::int32_t bottom = std::max(y_of(e.end), top + HALF);
		// Palm Desktop keeps a 2 px gap between the box and the grid lines.
		out.emplace_back(timed[k],
		                 date_shared::Rect::new_rect(TIME_W + 2 + static_cast<std::int32_t>(col[k]) * col_w, top + 2,
		                                             col_w - 2, bottom - top - 3),
		                 false);
	}
	return out;
}

std::string event_title_of(const model::Address& a) {
	const address::Row row = address::row_of(a, false);
	if (row.number.empty()) {
		return row.name;
	}
	return row.name + " " + row.number;
}

std::optional<std::size_t> records_selected(records::RecordTable& t, std::size_t n) {
	const auto rows = records::selected_rows(t, n);
	if (rows.empty()) {
		return std::nullopt;
	}
	return rows.front();
}

std::optional<std::size_t> popup(std::span<const std::string_view> labels) {
	Fl_Menu_Button mb(0, 0, 0, 0);
	draw::style_menu(&mb);
	mb.type(Fl_Menu_Button::POPUP3);
	for (const auto label : labels) {
		mb.add(std::string(label).c_str());
	}
	if (mb.popup() == nullptr) {
		return std::nullopt;
	}
	const int v = mb.value();
	if (v < 0) {
		return std::nullopt;
	}
	return static_cast<std::size_t>(v);
}

bool pane_event(records::RecordTable& t, int ev, msg::Sender s) {
	const auto m = mouse_of(ev);
	if (!m) {
		return ev == FL_FOCUS || ev == FL_UNFOCUS;
	}
	if (std::holds_alternative<msg::mouse::Press>(m->kind)) {
		t.take_focus();
		s.send(pane_list_msg(*m));
		return false;
	}
	if (std::holds_alternative<msg::mouse::Drag>(m->kind)) {
		s.send(pane_list_msg(*m));
		return true;
	}
	if (std::holds_alternative<msg::mouse::Release>(m->kind)) {
		s.send(pane_list_msg(*m));
		return false;
	}
	return false;
}

std::pair<Span, Span> pane_parts(const PaneModel& p, std::int32_t w) {
	Span pri = std::nullopt;
	Span due = std::nullopt;
	if (p.show_pri) {
		pri = std::pair{26, 38};
	}
	if (p.show_due) {
		due = std::pair{w - 4 - 32 - 45, w - 4 - 32};
	}
	return {pri, due};
}

void draw_pane_todo(const PaneTodo& r, const PaneModel& p, bool selected,
                    std::tuple<std::int32_t, std::int32_t, std::int32_t, std::int32_t> box,
                    std::pair<Fl_PNG_Image&, Fl_PNG_Image&> icons) {
	const auto& fonts = theme::fonts();
	const auto [x, y, w, h] = box;
	fl_rectf(x, y, w, h, FL_WHITE);
	todo::draw_check(x + 4, y + (h - 13) / 2, r.todo.done);
	fl_font(fonts.sans, 11);
	const int base = y + (h - fl_height()) / 2 + fl_height() - fl_descent();
	const auto [pri, due] = pane_parts(p, w);
	int tx = x + 21;
	if (pri) {
		fl_color(FL_BLACK);
		fl_draw(std::to_string(r.todo.priority).c_str(), x + pri->first, base);
		tx = x + 39;
	}
	const int tail = x + w - 4 - 32 - (p.show_due ? 45 : 0);
	std::string flat = r.todo.title;
	std::replace(flat.begin(), flat.end(), '\n', ' ');
	const std::string title = draw::fit(flat, tail - tx - 4, draw::text_w);
	if (selected) {
		fl_rectf(tx - 1, y, draw::text_w(title) + 3, h, theme::SELECT);
	}
	if (selected) {
		fl_color(r.overdue ? FL_YELLOW : FL_WHITE);
	} else {
		fl_color(r.overdue ? FL_RED : FL_BLACK);
	}
	fl_draw(title.c_str(), tx, base);
	if (due && r.todo.due >= 0) {
		// The due text keeps its "!" apart, so the plain date and the bold "!" are drawn in turn.
		const std::size_t end = r.due.find_last_not_of('!');
		const std::string_view plain =
		    end == std::string::npos ? std::string_view{} : std::string_view(r.due).substr(0, end + 1);
		// An overdue due date stays black, with a bold black "!", as in Palm Desktop 3.1.1.
		fl_color(FL_BLACK);
		fl_draw(std::string(plain).c_str(), x + due->first, base);
		if (plain.size() < r.due.size()) {
			const int at = x + due->first + draw::text_w(plain);
			fl_font(fonts.sans_bold, 11);
			fl_draw("!", at, base);
		}
	}
	int ix = x + w - 4 - 32;
	const std::array<std::pair<bool, Fl_PNG_Image*>, 2> marks = {
	    std::pair{!r.todo.note.empty(), &icons.first},
	    std::pair{r.todo.is_private, &icons.second},
	};
	for (const auto& [on, img] : marks) {
		if (on) {
			img->draw(ix, y + (h - img->h()) / 2, img->w(), img->h());
			ix += img->w();
		}
	}
}

std::int64_t calendar_start(std::int64_t day, std::uint32_t begins) {
	const auto d = days::ymd(day);
	return date_picker::week_start_of(days::epoch_day(d.year, d.month, 1), begins);
}

void draw_calendar(const Fl_Box& f, const PaneModel& p) {
	const auto& fonts = theme::fonts();
	const int cw = f.w() / 7;
	const int ch = f.h() / 7;
	fl_rectf(f.x(), f.y(), f.w(), f.h(), FL_WHITE);
	fl_rectf(f.x(), f.y(), f.w(), ch, theme::PANEL);
	for (int c = 0; c < 7; ++c) {
		fl_font(fonts.sans_bold, 11);
		fl_color(FL_BLACK);
		const std::string letter(model::DOW_LETTER[(static_cast<std::uint32_t>(c) + p.begins) % 7]);
		fl_draw(letter.c_str(), f.x() + c * cw, f.y(), cw, ch, FL_ALIGN_CENTER);
	}
	const std::int64_t start = calendar_start(p.day, p.begins);
	const std::uint32_t month = days::ymd(p.day).month;
	for (int i = 0; i < 42; ++i) {
		const std::int64_t d = start + i;
		const int x = f.x() + (i % 7) * cw;
		const int y = f.y() + (i / 7 + 1) * ch;
		const bool sel = d == p.day;
		const bool today = d == p.today;
		Fl_Color color = days::ymd(d).month == month ? FL_BLACK : fl_rgb_color(128, 128, 128);
		if (today) {
			color = FL_RED;
		}
		if (sel) {
			fl_rectf(x, y, cw, ch, theme::SELECT);
			if (!today) {
				color = FL_WHITE;
			}
		}
		fl_font(today ? fonts.sans_bold : fonts.sans, 11);
		fl_color(color);
		const std::string num = std::to_string(days::ymd(d).day);
		fl_draw(num.c_str(), x, y, cw, ch, FL_ALIGN_CENTER);
	}
}

void paint_box(date_shared::Rect r, const model::Event& e, const DayModel& m, bool centered) {
	const bool selected = m.selected && *m.selected == e.id;
	if (m.editing && *m.editing == e.id) {
		date_shared::draw_box(r, date_shared::Fill::White, selected);
	} else {
		date_shared::paint_event(r, e, selected, centered);
	}
}

void draw_header(const Fl_Box& f, const DayModel& m) {
	const int x = f.x();
	const int y = f.y();
	const int w = f.w();
	const int h = f.h();
	const draw::Clip clip(x, y, w, h);
	fl_rectf(x, y, w, h, FL_WHITE);
	date_shared::raised_cell(x, y, TIME_W, h);
	date_shared::draw_diamond(x + TIME_W / 2, y + h / 2);
	fl_color(FL_BLACK);
	fl_line(x + TIME_W, y + h - 1, x + w, y + h - 1);
	fl_line(x + TIME_W, y, x + TIME_W, y + h);
	for (const auto& [i, r] : sorted_boxes(m, w, true)) {
		paint_box(r.moved(x, y), m.occs[i].event, m, true);
	}
	if (m.typing && m.typing->first) {
		date_shared::draw_box(m.typing->second.moved(x, y), date_shared::Fill::White, true);
	}
}

void draw_grid(const Fl_Box& f, const DayModel& m) {
	const int x = f.x();
	const int y = f.y();
	const int w = f.w();
	const int h = f.h();
	const int top = y - m.offset;
	const draw::Clip clip(x, y, w, h);
	fl_rectf(x, y, w, h, FL_WHITE);
	for (int hour = 0; hour < 24; ++hour) {
		const int hy = top + y_of(hour * 60);
		if (hy > y + h || hy + 2 * HALF < y) {
			continue;
		}
		date_shared::raised_cell(x, hy, TIME_W, y_of((hour + 1) * 60) - y_of(hour * 60));
		fl_color(date_shared::HOUR_LINE);
		fl_line(x + TIME_W, hy, x + w, hy);
		date_shared::draw_hour_label(hour * 60, TIME_W, x, hy + HALF, m.h24);
	}
	fl_color(date_shared::HOUR_LINE);
	fl_line(x + TIME_W, top + y_of(24 * 60), x + w, top + y_of(24 * 60));
	fl_color(FL_BLACK);
	fl_line(x + TIME_W, y, x + TIME_W, y + h);
	for (const auto& [i, r] : sorted_boxes(m, w, false)) {
		paint_box(r.moved(x, top), m.occs[i].event, m, false);
	}
	if (m.typing && !m.typing->first) {
		date_shared::draw_box(m.typing->second.moved(x, top), date_shared::Fill::White, true);
	}
	for (const auto& r : {m.outline, m.drop_box}) {
		if (r) {
			date_shared::draw_outline(r->moved(x, top));
		}
	}
}

std::int32_t tab_len(msg::DateMode mode) {
	fl_font(theme::fonts().sans, 11);
	return draw::text_w(tab_caption(mode)) + 28;
}

std::string_view tab_caption(msg::DateMode mode) {
	switch (mode) {
	case msg::DateMode::Day:
		return "Day";
	case msg::DateMode::Week:
		return "Week";
	case msg::DateMode::Month:
		return "Month";
	}
	return "Day";
}

void draw_tabs(const Fl_Box& f, msg::DateMode mode) {
	const int x = f.x();
	const int y = f.y();
	const int h = f.h();
	fl_rectf(x, y, f.w(), h, theme::WORKSPACE);
	// The right edge of the body: white at the top, shadow gray, black at the bottom.
	fl_rectf(x, y, 1, 1, draw::HIGHLIGHT);
	fl_rectf(x, y + 1, 1, h - 2, theme::SHADOW);
	fl_rectf(x, y + h - 1, 1, 1, FL_BLACK);
	fl_rectf(x + 1, y, 1, h, FL_BLACK);
	std::int32_t top = 2;
	std::vector<std::tuple<msg::DateMode, std::int32_t, std::int32_t>> placed;
	for (const auto m : {msg::DateMode::Day, msg::DateMode::Week, msg::DateMode::Month}) {
		const std::int32_t len = tab_len(m);
		placed.emplace_back(m, top, len);
		top += len;
	}
	// The selected tab last, so it lies over its neighbors.
	std::stable_sort(placed.begin(), placed.end(), [mode](const auto& a, const auto& b) {
		return (std::get<0>(a) == mode) < (std::get<0>(b) == mode);
	});
	for (const auto& [m, t, len] : placed) {
		const bool sel = m == mode;
		const int tx = sel ? x : x + 1;
		const int ty = sel ? y + t - 2 : y + t;
		const int tw = sel ? 24 : 21;
		const int th = sel ? len + 4 : len;
		fl_rectf(tx, ty, tw - 2, th - 2, theme::PANEL);
		fl_color(draw::HIGHLIGHT);
		fl_line(sel ? tx : tx + 1, ty, tx + tw - 4, ty);
		fl_color(theme::SHADOW);
		fl_point(tx + tw - 3, ty);
		fl_line(tx + tw - 2, ty + 1, tx + tw - 2, ty + th - 3);
		fl_line(sel ? tx + 2 : tx + 1, ty + th - 3, tx + tw - 2, ty + th - 3);
		fl_color(FL_BLACK);
		fl_line(tx + tw - 1, ty + 3, tx + tw - 1, ty + th - 3);
		fl_line(sel ? tx + 2 : tx, ty + th - 2, tx + tw - 2, ty + th - 2);
		const std::string_view cap = tab_caption(m);
		fl_font(theme::fonts().sans, 11);
		const int cw = draw::text_w(cap);
		// The caption reads from the top down; its first letter is the Alt key.
		const int cx = tx + (tw - 2) / 2 - (fl_height() - fl_descent() * 2) / 2;
		const int cy = ty + (th - 2 - cw) / 2;
		const std::string text(cap);
		fl_draw(-90, text.c_str(), cx, cy);
		const int lw = draw::text_w(cap.substr(0, 1));
		fl_line(cx - 1, cy, cx - 1, cy + lw - 1);
	}
}

Drag::Drag(date_shared::Occ o, bool r, bool h, std::int32_t g, std::pair<std::int32_t, std::int32_t> org)
    : occ(std::move(o)), resize(r), header(h), grab(g), origin(org), new_start(occ.event.start),
      new_end(occ.event.end) {}

BodyFrame::BodyFrame(int x, int y, int w, int h) : Fl_Box(x, y, w, h) {}

void BodyFrame::draw() {
	Fl_Box::draw();
	// The raised bevel without its right side; the tab strip draws that edge.
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect r = d.rect(x(), y(), w(), h());
	const int k = d.lw();
	fl_rectf(r.x, r.y, r.w, k, draw::HIGHLIGHT);
	fl_rectf(r.x, r.y, k, r.h - k, draw::HIGHLIGHT);
	fl_rectf(r.x, r.y + r.h - 2 * k, r.w, k, theme::SHADOW);
	fl_rectf(r.x, r.y + r.h - k, r.w, k, FL_BLACK);
}

TitleFrame::TitleFrame(int x, int y, int w, int h, msg::Sender s) : Fl_Box(x, y, w, h), s_(std::move(s)) {}

int TitleFrame::handle(int event) {
	const int ret = Fl_Box::handle(event);
	if (event == FL_PUSH && Fl::event_clicks() != 0) {
		s_.send(date_msg(msg::date::TitleDouble{}));
		return 1;
	}
	return ret;
}

DayHeaderFrame::DayHeaderFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<DayModel> model)
    : MouseFrame(x, y, w, h, std::move(s), [](msg::Mouse m) { return date_msg(msg::date::Day{true, m}); }),
      model_(std::move(model)) {}

void DayHeaderFrame::draw() {
	Fl_Box::draw();
	draw_header(*this, *model_);
}

DayGridFrame::DayGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<DayModel> model)
    : MouseFrame(x, y, w, h, std::move(s), [](msg::Mouse m) { return date_msg(msg::date::Day{false, m}); }),
      model_(std::move(model)) {}

void DayGridFrame::draw() {
	Fl_Box::draw();
	draw_grid(*this, *model_);
}

MonthButton::MonthButton(int x, int y, int w, int h, const char* label) : Fl_Button(x, y, w, h, label) {}

void MonthButton::draw() {
	Fl_Button::draw();
	// The black lines of the month grid: right and bottom of each button.
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect r = d.rect(x(), y(), w(), h());
	const int k = d.lw();
	fl_rectf(r.x + r.w - k, r.y, k, r.h, FL_BLACK);
	fl_rectf(r.x, r.y + r.h - k, r.w, k, FL_BLACK);
}

MonthEdgeFrame::MonthEdgeFrame(int x, int y, int w, int h) : Fl_Box(x, y, w, h) {}

void MonthEdgeFrame::draw() {
	Fl_Box::draw();
	// The black top and left edge of the first row and column of the month buttons.
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect r = d.rect(x(), y(), w(), h());
	const int k = d.lw();
	fl_rectf(r.x, r.y, r.w, k, FL_BLACK);
	fl_rectf(r.x, r.y, k, r.h, FL_BLACK);
}

CalendarFrame::CalendarFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<PaneModel> pane)
    : MouseFrame(x, y, w, h, std::move(s), [](msg::Mouse m) { return date_msg(msg::date::Calendar{m}); }),
      pane_(std::move(pane)) {}

void CalendarFrame::draw() {
	Fl_Box::draw();
	draw_calendar(*this, *pane_);
}

SwitchFrame::SwitchFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<PaneModel> pane)
    : Fl_Box(x, y, w, h), s_(std::move(s)), pane_(std::move(pane)) {}

void SwitchFrame::draw() {
	Fl_Box::draw();
	const bool address = pane_->address;
	const int half = (w() - 4) / 2;
	const std::array<std::pair<const char*, bool>, 2> segments = {{{"Address", address}, {"To Do", !address}}};
	for (std::size_t i = 0; i < segments.size(); ++i) {
		const auto [label, on] = segments[i];
		const int sx = x() + 2 + static_cast<int>(i) * half;
		fl_rectf(sx, y() + 2, half, h() - 4, on ? theme::SELECT : FL_WHITE);
		fl_font(theme::fonts().sans, 11);
		fl_color(on ? FL_WHITE : FL_BLACK);
		fl_draw(label, sx, y() + 2, half, h() - 4, FL_ALIGN_CENTER);
	}
}

int SwitchFrame::handle(int event) {
	const int ret = Fl_Box::handle(event);
	if (event == FL_PUSH && Fl::event_button() == FL_LEFT_MOUSE) {
		s_.send(date_msg(msg::date::Pane{Fl::event_x() < x() + w() / 2}));
		return 1;
	}
	return ret;
}

PaneList::PaneList(int x, int y, int w, int h, std::shared_ptr<PaneModel> pane, bool address, msg::Sender s)
    : records::RecordTable(x, y, w, h), pane_(std::move(pane)), address_(address), s_(std::move(s)),
      note_(theme::icon("note")), key_(theme::icon("private")) {}

PaneList::~PaneList() = default;

void PaneList::draw_cell(TableContext context, int row, int col, int x, int y, int w, int h) {
	(void)col;
	switch (context) {
	case CONTEXT_STARTPAGE:
		records::fit_column(*this, ROW_H);
		if (address_) {
			widest_ = address::widest_number(pane_->addrs);
		}
		break;
	case CONTEXT_CELL: {
		if (row < 0) {
			break;
		}
		const draw::Clip clip = draw::Clip::cell(this, x, y, w, h);
		const bool selected = row_selected(row) != 0;
		const auto idx = static_cast<std::size_t>(row);
		if (address_) {
			if (idx < pane_->addrs.size()) {
				address::draw_row(pane_->addrs[idx], selected, draw::Rect{x, y, w, h}, widest_);
			}
		} else if (idx < pane_->todos.size()) {
			draw_pane_todo(pane_->todos[idx], *pane_, selected, std::tuple{x, y, w, h},
			               std::pair<Fl_PNG_Image&, Fl_PNG_Image&>{*note_, *key_});
		}
		break;
	}
	default:
		break;
	}
}

int PaneList::handle(int event) {
	// FLTK's own handler runs first; the pane's events then see the event, and their result joins FLTK's.
	const int ret = Fl_Table_Row::handle(event);
	return ret | (pane_event(*this, event, s_) ? 1 : 0);
}

TabsFrame::TabsFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<msg::DateMode> tab_mode)
    : Fl_Box(x, y, w, h), s_(std::move(s)), tab_mode_(std::move(tab_mode)) {}

void TabsFrame::draw() {
	Fl_Box::draw();
	draw_tabs(*this, *tab_mode_);
}

int TabsFrame::handle(int event) {
	const int ret = Fl_Box::handle(event);
	switch (event) {
	case FL_PUSH: {
		if (Fl::event_button() != FL_LEFT_MOUSE) {
			return ret;
		}
		const int py = Fl::event_y() - y();
		std::int32_t top = 2;
		for (const auto mode : {msg::DateMode::Day, msg::DateMode::Week, msg::DateMode::Month}) {
			const std::int32_t len = tab_len(mode);
			if (py >= top && py < top + len) {
				s_.send(Fl::event_clicks() != 0 ? date_msg(msg::date::Today{}) : date_msg(msg::date::Mode{mode}));
			}
			top += len;
		}
		return 1;
	}
	case FL_SHORTCUT: {
		// Alt+D, Alt+W and Alt+M, as in Palm Desktop.
		if (!Fl::event_state(FL_ALT)) {
			return ret;
		}
		int key = Fl::event_key();
		if (key >= 'A' && key <= 'Z') {
			key += 'a' - 'A';
		}
		switch (key) {
		case 'd':
			s_.send(date_msg(msg::date::Mode{msg::DateMode::Day}));
			return 1;
		case 'w':
			s_.send(date_msg(msg::date::Mode{msg::DateMode::Week}));
			return 1;
		case 'm':
			s_.send(date_msg(msg::date::Mode{msg::DateMode::Month}));
			return 1;
		default:
			return ret;
		}
	}
	default:
		return ret;
	}
}

void Place::apply(date_shared::Rect g) {
	const int cx = g.x;
	const int cy = g.y;
	const int cw = g.w;
	const int ch = g.h;
	const int dw = std::max(0, cw - PANE_W - 11);
	const int rows = model->untimed_rows;
	const int hh = rows * UNTIMED_H + date_shared::SEL_H;
	const int gy = cy + TITLE_H + TITLE_GAP;
	const int gh = std::max(0, ch - (gy - cy) - 33);
	const int sb = Fl::scrollbar_size();
	const int view = std::max(0, gh - 2 - hh);
	title->resize(cx, cy, dw, TITLE_H);
	edge->resize(cx, gy, dw, gh);
	header->resize(cx + 1, gy + 1, std::max(0, dw - 2), hh);
	grid->resize(cx + 1, gy + 1 + hh, std::max(0, dw - 2 - sb), view);
	bar->resize(cx + dw - 1 - sb, gy + 1 + hh, sb, view);
	set_bar_range(*bar, view, y_of(24 * 60) + 1);
	model->offset = bar->value();
	const int by = cy + ch - 28;
	today->resize(cx + dw - 228, by, BTN_W, BTN_H);
	edit->resize(cx + dw - 152, by, BTN_W, BTN_H);
	new_date->resize(cx + dw - 76, by, BTN_W, BTN_H);

	const int px = cx + cw - PANE_W;
	back->resize(px, cy + 2, 13, 20);
	year->resize(px + 14, cy + 2, PANE_W - 28, 20);
	fwd->resize(px + PANE_W - 13, cy + 2, 13, 20);
	const int bw = PANE_W / 6;
	for (std::size_t m = 0; m < months.size(); ++m) {
		const int c = static_cast<int>(m % 6);
		const int r = static_cast<int>(m / 6);
		months[m]->resize(px + c * bw, cy + 26 + r * MONTH_H, bw, MONTH_H);
	}
	month_edge->resize(px, cy + 26, PANE_W, 2 * MONTH_H);
	cal_edge->resize(px, cy + 60, PANE_W, 130);
	cal->resize(px + 2, cy + 62, PANE_W - 4, 126);
	const int hy = cy + 194;
	switch_date->resize(px, hy, 117, 18);
	todo_cat->resize(px + 121, hy, PANE_W - 121, 18);
	addr_cat->resize(px + 121, hy, PANE_W - 121, 18);
	const int ly = hy + 22;
	const int list_h = std::max(0, ch - (ly - cy));
	todo_list->resize(px, ly, PANE_W, list_h);
	addr_list->resize(px, ly, PANE_W, std::max(0, list_h - 24));
	look_label->resize(px, cy + ch - 20, 44, 20);
	look->resize(px + 48, cy + ch - 20, PANE_W - 48, 20);
}

} // namespace detail

} // namespace palm::date
