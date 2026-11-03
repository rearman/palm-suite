// The Palm Desktop date dialog of DatePicker.kt, as desktop-rs/app/src/date_picker.rs: Select Date, Select Week, or
// Select Month. The year with its arrows, the twelve month buttons, and (except for a month) a calendar of six weeks.
#include "palm/date_picker.hpp"

#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/model.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <limits>
#include <string>

namespace palm::date_picker {

namespace {

// The state that the widgets share, as the Rust Rc<Cell>s do. It lives in pick(), declared before the window, so it
// outlives the widgets that the window deletes.
struct State {
	Mode mode;
	std::int64_t today;
	std::uint32_t begins;
	std::int64_t sel;
	// The answer once OK, Cancel, Today, Enter, or Escape gives it: true on OK or Today, false on Cancel.
	std::optional<bool> done;
};

// The calendar: a header row of day letters, then six weeks. The cells are drawn from the selection.
class Calendar : public Fl_Box {
public:
	Calendar(int px, int py, int pw, int ph, State* st) : Fl_Box(px, py, pw, ph), st_(st) {}

	void draw() override;
	int handle(int e) override;

private:
	State* st_;
};

// A month button: a flat face, with a gray outline drawn over it. A press moves the selection to the same day of the
// chosen month.
class MonthButton : public Fl_Button {
public:
	MonthButton(int px, int py, State* st, int m) : Fl_Button(px, py, 32, 18), st_(st), m_(m) {
		copy_label(std::string(model::MONTH_SHORT[static_cast<std::size_t>(m)]).c_str());
		box(FL_FLAT_BOX);
		down_box(FL_FLAT_BOX);
		selection_color(theme::SELECT);
		clear_visible_focus();
		callback(&MonthButton::pressed);
	}

	void draw() override;

private:
	static void pressed(Fl_Widget* w, void*);

	State* st_;
	int m_;
};

// The dialog window. Its handle reads the keys: the arrows move by a day or a week, Page Up and Page Down by a month,
// Enter is OK, and Escape is Cancel.
class Picker : public Fl_Double_Window {
public:
	Picker(int pw, int ph, const char* title, State* st) : Fl_Double_Window(pw, ph, title), st_(st) {}

	int handle(int e) override;

private:
	State* st_;
};

void ok_pressed(Fl_Widget*, void* data) {
	static_cast<State*>(data)->done = true;
}

void cancel_pressed(Fl_Widget*, void* data) {
	static_cast<State*>(data)->done = false;
}

void today_pressed(Fl_Widget*, void* data) {
	State* st = static_cast<State*>(data);
	st->sel = st->today;
	st->done = true;
}

void back_pressed(Fl_Widget*, void* data) {
	State* st = static_cast<State*>(data);
	st->sel = add_months(st->sel, -12);
}

void fwd_pressed(Fl_Widget*, void* data) {
	State* st = static_cast<State*>(data);
	st->sel = add_months(st->sel, 12);
}

void MonthButton::pressed(Fl_Widget* w, void*) {
	MonthButton* b = static_cast<MonthButton*>(w);
	const days::Ymd d = days::ymd(b->st_->sel);
	b->st_->sel = add_months(b->st_->sel, static_cast<std::int64_t>(b->m_) + 1 - static_cast<std::int64_t>(d.month));
}

void MonthButton::draw() {
	Fl_Button::draw();
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect r = d.rect(x(), y(), w(), h());
	fl_color(theme::SHADOW);
	fl_rect(r.x, r.y, r.w, r.h);
}

void Calendar::draw() {
	Fl_Box::draw();
	const std::int64_t s = st_->sel;
	const std::int64_t start = detail::calendar_start(s, st_->begins);
	const std::int64_t week = week_start_of(s, st_->begins);
	const std::uint32_t month = days::ymd(s).month;
	const float cw = static_cast<float>(w()) / 7.0F;
	const float ch = static_cast<float>(h()) / 7.0F;
	const theme::Fonts& fonts = theme::fonts();
	fl_color(FL_WHITE);
	fl_rectf(x(), y(), w(), h());
	for (int r = 0; r < 7; ++r) {
		for (int c = 0; c < 7; ++c) {
			const int cx = x() + static_cast<int>(static_cast<float>(c) * cw);
			const int cy = y() + static_cast<int>(static_cast<float>(r) * ch);
			const int cell_w = x() + static_cast<int>(static_cast<float>(c + 1) * cw) - cx;
			const int cell_h = y() + static_cast<int>(static_cast<float>(r + 1) * ch) - cy;
			if (r == 0) {
				fl_font(fonts.sans_bold, 11);
				fl_color(FL_BLACK);
				const std::string letter(model::DOW_LETTER[(static_cast<std::uint32_t>(c) + st_->begins) % 7]);
				fl_draw(letter.c_str(), cx, cy, cell_w, cell_h, FL_ALIGN_CENTER);
				continue;
			}
			const std::int64_t d = days::wrapping_add(start, static_cast<std::int64_t>((r - 1) * 7 + c));
			bool picked = false;
			switch (st_->mode) {
			case Mode::Week:
				picked = d >= week && d < days::wrapping_add(week, 7);
				break;
			case Mode::Month:
				picked = false;
				break;
			case Mode::Day:
				picked = d == s;
				break;
			}
			if (picked) {
				fl_color(theme::SELECT);
				fl_rectf(cx, cy, cell_w, cell_h);
			}
			// In a week, today is bold red; in a day, a red box is drawn around it below.
			const bool week_today = st_->mode == Mode::Week && d == st_->today;
			const std::string label = std::to_string(days::ymd(d).day);
			fl_font(week_today ? fonts.sans_bold : fonts.sans, 11);
			if (week_today) {
				fl_color(FL_RED);
			} else if (picked) {
				fl_color(FL_WHITE);
			} else if (days::ymd(d).month != month) {
				fl_color(fl_rgb_color(128, 128, 128));
			} else {
				fl_color(FL_BLACK);
			}
			fl_draw(label.c_str(), cx, cy, cell_w, cell_h, FL_ALIGN_CENTER);
			if (st_->mode == Mode::Day && d == st_->today) {
				// A 1 px red box around the number, drawn on the blue fill when today is selected.
				int tw = 0;
				int th = 0;
				fl_measure(label.c_str(), tw, th, 0);
				const int bx = cx + (cell_w - tw) / 2 - 2;
				const int by = cy + (cell_h - th) / 2 - 1;
				fl_color(FL_RED);
				fl_rect(bx, by, tw + 4, th + 2);
			}
		}
	}
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect r = d.rect(x(), y(), w(), h());
	fl_color(theme::SHADOW);
	fl_rect(r.x, r.y, r.w, r.h);
}

int Calendar::handle(int e) {
	// FLTK's own handler runs first, as fltk-rs does before the Rust closure. The closure takes only a push.
	const int base = Fl_Box::handle(e);
	if (e != FL_PUSH) {
		return base;
	}
	const float cw = static_cast<float>(w()) / 7.0F;
	const float ch = static_cast<float>(h()) / 7.0F;
	const auto c = static_cast<std::int64_t>(static_cast<float>(Fl::event_x() - x()) / cw);
	const auto r = static_cast<std::int64_t>(static_cast<float>(Fl::event_y() - y()) / ch);
	if (c < 0 || c >= 7 || r < 1 || r >= 7) {
		return base | 1;
	}
	st_->sel = days::wrapping_add(detail::calendar_start(st_->sel, st_->begins), (r - 1) * 7 + c);
	if (Fl::event_clicks() != 0) {
		st_->done = true;
	}
	return base | 1;
}

int Picker::handle(int e) {
	// FLTK's own handler runs first, as fltk-rs does before the Rust closure; the closure result is OR'd with it.
	const int base = Fl_Window::handle(e);
	if (e != FL_KEYDOWN && e != FL_SHORTCUT) {
		return base;
	}
	const std::int64_t s = st_->sel;
	std::int64_t next = s;
	switch (Fl::event_key()) {
	case FL_Left:
		next = days::wrapping_sub(s, 1);
		break;
	case FL_Right:
		next = days::wrapping_add(s, 1);
		break;
	case FL_Up:
		next = days::wrapping_sub(s, 7);
		break;
	case FL_Down:
		next = days::wrapping_add(s, 7);
		break;
	case FL_Page_Up:
		next = add_months(s, -1);
		break;
	case FL_Page_Down:
		next = add_months(s, 1);
		break;
	case FL_Enter:
	case FL_KP_Enter:
		st_->done = true;
		return base | 1;
	case FL_Escape:
		st_->done = false;
		return base | 1;
	default:
		return base;
	}
	st_->sel = next;
	return base | 1;
}

} // namespace

std::int64_t week_start_of(std::int64_t day, std::uint32_t begins) {
	return days::wrapping_sub(day, static_cast<std::int64_t>((days::dow(day) + 7 - begins) % 7));
}

std::int64_t add_months(std::int64_t day, std::int64_t months) {
	const days::Ymd d = days::ymd(day);
	const std::int64_t n = days::wrapping_add(
	    days::wrapping_add(days::wrapping_mul(d.year, 12), static_cast<std::int64_t>(d.month) - 1), months);
	// Rust's div_euclid and rem_euclid: the remainder is never negative, so a negative n rounds the quotient down.
	std::int64_t y = n / 12;
	std::int64_t m0 = n % 12;
	if (m0 < 0) {
		m0 += 12;
		y -= 1;
	}
	const auto m = static_cast<std::uint32_t>(m0) + 1;
	return days::epoch_day(y, m, std::min(d.day, days::month_length(y, m)));
}

namespace detail {

std::int64_t calendar_start(std::int64_t day, std::uint32_t begins) {
	const days::Ymd d = days::ymd(day);
	return week_start_of(days::epoch_day(d.year, d.month, 1), begins);
}

} // namespace detail

std::optional<std::int64_t> pick(Mode mode, std::int64_t day, std::int64_t today, std::uint32_t begins) {
	const char* title = "Select Date";
	if (mode == Mode::Week) {
		title = "Select Week";
	} else if (mode == Mode::Month) {
		title = "Select Month";
	}
	// The client heights of Palm Desktop 3.1.1 at 100%: 232 for a calendar, 102 for a month.
	const int h = mode == Mode::Month ? 102 : 232;
	// The state is declared before the window, so the window's widgets are deleted before it.
	State st{mode, today, begins, day, std::nullopt};
	Picker win(210, h, title, &st);
	const theme::Fonts& fonts = theme::fonts();

	Fl_Button* back = draw::arrow(6, 6, 20, 20, false);
	back->callback(back_pressed, &st);
	Fl_Box* year = new Fl_Box(30, 6, 150, 20);
	year->box(FL_DOWN_BOX);
	year->labelfont(fonts.sans_bold);
	Fl_Button* fwd = draw::arrow(184, 6, 20, 20, true);
	fwd->callback(fwd_pressed, &st);

	std::array<MonthButton*, 12> months{};
	for (int m = 0; m < 12; ++m) {
		const int col = m % 6;
		const int row = m / 6;
		months[static_cast<std::size_t>(m)] = new MonthButton(6 + col * 33, 32 + row * 20, &st, m);
	}

	Calendar* cal = new Calendar(6, 76, 198, 122, &st);
	if (mode == Mode::Month) {
		cal->hide();
	}

	const int by = h - 29;
	Fl_Button* ok = new Fl_Button(6, by, 64, 23, "OK");
	Fl_Button* cancel = new Fl_Button(73, by, 64, 23, "Cancel");
	const char* today_label = "&Today";
	if (mode == Mode::Week) {
		today_label = "&This Week";
	} else if (mode == Mode::Month) {
		today_label = "&This Month";
	}
	Fl_Button* today_b = new Fl_Button(140, by, 64, 23, today_label);
	ok->callback(ok_pressed, &st);
	cancel->callback(cancel_pressed, &st);
	today_b->callback(today_pressed, &st);
	win.end();
	mnemonics::ForgetOnExit forget_win(win);

	// Redraw the year, the months and the calendar when the selection moved. The first pass sees a value that no
	// selection holds, so it draws them all.
	std::int64_t shown = std::numeric_limits<std::int64_t>::min();
	dialogs::show_modal(&win);
	while (win.shown()) {
		Fl::wait();
		if (st.done) {
			const bool answer = *st.done;
			st.done.reset();
			win.hide();
			return answer ? std::optional<std::int64_t>(st.sel) : std::nullopt;
		}
		if (shown != st.sel) {
			shown = st.sel;
			const days::Ymd d = days::ymd(st.sel);
			year->copy_label(std::to_string(d.year).c_str());
			for (std::size_t m = 0; m < months.size(); ++m) {
				const bool on = static_cast<std::uint32_t>(m) + 1 == d.month;
				months[m]->color(on ? theme::SELECT : theme::PANEL);
				months[m]->labelcolor(on ? FL_WHITE : FL_BLACK);
				months[m]->redraw();
			}
			year->redraw();
			cal->redraw();
		}
	}
	return std::nullopt;
}

} // namespace palm::date_picker
