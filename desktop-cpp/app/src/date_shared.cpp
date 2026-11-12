// The parts the Day, Week, and Month views share, as desktop-rs/app/src/date_shared.rs, on FLTK. Drawing is in device
// pixels through draw::Dev, and the dialogs run their own loops, as in Rust.
#include "palm/date_shared.hpp"

#include "palm/combo.hpp"
#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/note_dialog.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_Spinner.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cstdlib>
#include <format>
#include <initializer_list>
#include <memory>
#include <set>
#include <stdexcept>
#include <utility>

namespace palm::date_shared {
namespace {

// The outline of a moved or dragged event, as in Palm Desktop.
const Fl_Color DRAG_NAVY = fl_rgb_color(0, 0, 128);

// Rust's rem_euclid for a positive divisor: the remainder in [0, b).
constexpr std::int64_t rem_euclid(std::int64_t a, std::int64_t b) {
	return ((a % b) + b) % b;
}

// The text of `parts` joined by one space, the empty parts left out, as the Rust words helper of paint and describe.
std::string words(std::initializer_list<std::string_view> parts) {
	std::string out;
	for (const std::string_view p : parts) {
		if (p.empty()) {
			continue;
		}
		if (!out.empty()) {
			out += ' ';
		}
		out += p;
	}
	return out;
}

// The marker icon of kind `m`, an icon of icons/<name>.png.
std::unique_ptr<Fl_PNG_Image> marker_icon(Marker m) {
	static constexpr std::array<std::string_view, 4> names = {"repeat", "alarm", "note", "private"};
	return theme::icon(names[static_cast<std::size_t>(m)]);
}

// The size of the marker icons (all are 13 px square or smaller).
std::pair<int, int> marker_size(Marker m) {
	const auto img = marker_icon(m);
	return {img->w(), img->h()};
}

// The top of the marker icons of a box: centered in an untimed header row, else 2 px below the top.
int marker_y(Rect r, int ih, bool centered) {
	return centered ? r.y + (r.h - ih) / 2 : r.y + 2;
}

// A new event's copy of the occurrence: the series without its repeat, for a change of the current occurrence.
model::Event single(const model::Event& e, std::int64_t day) {
	model::Event s = e;
	s.id = 0;
	s.uid.clear();
	s.date = day;
	s.repeat = model::repeat::NONE;
	s.every = 1;
	s.repeat_end = -1;
	s.week_days = 0;
	s.exceptions.clear();
	return s;
}

// The state of the Alarm Settings dialog, which its callbacks reach through their data pointer.
struct AlarmState {
	Fl_Window* win = nullptr;
	Fl_Check_Button* on = nullptr;
	Fl_Spinner* amount = nullptr;
	combo::Choice* unit = nullptr;
	bool saved = false;
};

// Shows the count and the units while Alarm is checked.
void alarm_set_on(Fl_Spinner* amount, Fl_Choice* unit, bool on) {
	if (on) {
		amount->show();
		unit->show();
	} else {
		amount->hide();
		unit->hide();
	}
}

// The Alarm check box: the count and the units follow it.
void alarm_toggled(Fl_Widget* w, void* data) {
	auto* st = static_cast<AlarmState*>(data);
	alarm_set_on(st->amount, st->unit, static_cast<Fl_Check_Button*>(w)->value() != 0);
}

// Alt+A on the "Alarm:" label toggles the check box.
void alarm_label_pressed(Fl_Widget*, void* data) {
	auto* st = static_cast<AlarmState*>(data);
	st->on->value(!st->on->value());
	st->on->do_callback();
}

// OK of Alarm Settings: the dialog saves its answer when the window closes.
void alarm_ok(Fl_Widget*, void* data) {
	auto* st = static_cast<AlarmState*>(data);
	st->saved = true;
	st->win->hide();
}

} // namespace

Rect Rect::new_rect(int x, int y, int w, int h) {
	return Rect{x, y, w, h};
}

bool Rect::contains(int px, int py) const {
	return px >= x && px < x + w && py >= y && py < y + h;
}

Rect Rect::moved(int dx, int dy) const {
	return new_rect(x + dx, y + dy, w, h);
}

std::vector<Occ> visible_events(const store::DesktopStore& store, std::int64_t day, bool show_private) {
	std::vector<Occ> out;
	for (const model::Event* e : store.day_events(day)) {
		if (show_private || !e->is_private) {
			out.push_back(Occ{*e, day});
		}
	}
	return out;
}

std::string time_label(std::int32_t min, bool h24) {
	const std::int32_t h = min / 60 % 24;
	const std::int32_t m = min % 60;
	if (h24) {
		return std::format("{}:{:02}", h, m);
	}
	return std::format("{}:{:02}{}", (h + 11) % 12 + 1, m, h < 12 ? "a" : "p");
}

std::uint32_t week_begins(const settings::Settings& settings) {
	return static_cast<std::uint32_t>(std::clamp(settings.get_int("weekStart", 0), 0, 1));
}

std::int32_t work_day(const settings::Settings& settings) {
	return std::clamp(settings.get_int("workDay", 8), 0, 23);
}

std::size_t dow_index(std::size_t col, std::uint32_t begins) {
	return (col + begins) % 7;
}

void apply_alarm_preset(model::Event& e, const settings::Settings& settings) {
	if (!settings.get_bool("alarmPreset", false)) {
		return;
	}
	e.alarm = true;
	e.alarm_advance = std::max(1, settings.get_int("alarmPresetAmount", 5));
	e.alarm_unit = std::clamp(settings.get_int("alarmPresetUnit", 0), 0, 2);
}

std::vector<Marker> markers(const model::Event& e) {
	std::vector<Marker> m;
	if (e.repeats()) {
		m.push_back(Marker::Repeat);
	}
	if (e.alarm) {
		m.push_back(Marker::Alarm);
	}
	if (!e.note.empty()) {
		m.push_back(Marker::Note);
	}
	if (e.is_private) {
		m.push_back(Marker::Private);
	}
	return m;
}

void draw_box(Rect r, Fill fill, bool selected) {
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect b = d.rect(r.x, r.y, r.w, r.h);
	const int x = b.x;
	const int y = b.y;
	const int w = b.w;
	const int h = b.h;
	if (w <= 0 || h <= 0) {
		return;
	}
	fl_rectf(x, y, w, h, FL_WHITE);
	if (fill == Fill::Event) {
		// The yellow and white dither of Palm Desktop, one device pixel per dot: white, then the yellow pixels whose
		// x + y is even. Each row draws 1 x 1 rectangles, so no pixel is antialiased.
		// Cost: about w * h / 2 rectangles per redraw; a full-day box of 200 x 800 px is about 80000.
		// Upgrade path: a cached RGB image of the checker, drawn with fl_draw_image, if redraws slow down.
		fl_color(FL_YELLOW);
		for (int py = y; py < y + h; ++py) {
			int px = ((x + py) % 2 + 2) % 2 == 0 ? x : x + 1;
			for (; px < x + w; px += 2) {
				fl_rectf(px, py, 1, 1);
			}
		}
	}
	const int k = d.lw();
	const int bx = d.p(r.x + r.w - BAR_W);
	fl_rectf(bx, y, x + w - bx, h, selected ? fl_rgb_color(128, 128, 128) : fl_rgb_color(192, 192, 192));
	fl_color(FL_BLACK);
	const std::array<std::array<int, 4>, 5> edges = {{
	    {x, y, w, k},
	    {x, y + h - k, w, k},
	    {x, y, k, h},
	    {x + w - k, y, k, h},
	    {bx, y, k, h},
	}};
	for (const auto& [rx, ry, rw, rh] : edges) {
		fl_rectf(rx, ry, rw, rh);
	}
	if (selected) {
		const draw::Rect s = d.rect(r.x, r.y + r.h, r.w, SEL_H);
		fl_rectf(s.x, s.y, s.w, s.h, theme::SELECT);
	}
}

void paint_event(Rect r, const model::Event& e, bool selected, bool centered) {
	draw_box(r, Fill::Event, selected);
	// The title stops at the bar.
	const draw::Clip clip(r.x, r.y, std::max(0, r.w - BAR_W), r.h);
	int x = r.x + 3;
	for (const Marker m : markers(e)) {
		const auto img = marker_icon(m);
		const int iw = img->w();
		const int ih = img->h();
		img->draw(x, marker_y(r, ih, centered), iw, ih);
		x += iw + 2;
	}
	// The title is Helvetica 11 (MS Sans Serif 8), word-wrapped to the box left of the bar: the first line starts after
	// the marker icons, the others at the left edge. Lines past the bottom are cut by the clip.
	const int lh = title_line_h();
	fl_color(FL_BLACK);
	const int asc = fl_height() - fl_descent();
	const int right = r.x + r.w - BAR_W - 2;
	const std::vector<std::string> lines = wrap_words(e.title, right - x, right - (r.x + 3), draw::text_w);
	const int top = centered ? r.y + (r.h - static_cast<int>(lines.size()) * lh) / 2 : title_top(r, false);
	for (std::size_t i = 0; i < lines.size(); ++i) {
		const int lx = i == 0 ? x : r.x + 3;
		fl_draw(lines[i].c_str(), lx, top + asc + static_cast<int>(i) * lh);
	}
}

std::vector<std::string> wrap_words(std::string_view text, int first_w, int rest_w,
                                    const std::function<int(std::string_view)>& measure) {
	std::vector<std::string> lines;
	std::string cur;
	for (const std::string_view part : text::split_whitespace(text)) {
		std::string word(part);
		while (true) {
			const int limit = lines.empty() ? first_w : rest_w;
			const std::string candidate = cur.empty() ? word : cur + " " + word;
			if (measure(candidate) <= limit) {
				cur = candidate;
				break;
			}
			if (!cur.empty()) {
				lines.push_back(std::move(cur));
				cur.clear();
				continue;
			}
			// A word wider than the line: cut it after the last character that fits, at least one character.
			std::size_t cut = word.empty() ? 0 : text::decode_at(word, 0).len;
			for (std::size_t i = 0; i < word.size();) {
				const std::size_t end = i + text::decode_at(word, i).len;
				if (measure(std::string_view(word).substr(0, end)) > limit) {
					break;
				}
				cut = end;
				i = end;
			}
			lines.push_back(word.substr(0, cut));
			word = word.substr(cut);
			if (word.empty()) {
				break;
			}
		}
	}
	if (!cur.empty()) {
		lines.push_back(cur);
	}
	return lines;
}

int title_x(Rect r, const model::Event& e) {
	int x = r.x + 3;
	for (const Marker m : markers(e)) {
		x += marker_size(m).first + 2;
	}
	return x;
}

std::optional<Marker> marker_at(Rect r, const model::Event& e, int px, int py, bool centered) {
	int x = r.x + 3;
	for (const Marker m : markers(e)) {
		const auto [iw, ih] = marker_size(m);
		if (Rect::new_rect(x, marker_y(r, ih, centered), iw, ih).contains(px, py)) {
			return m;
		}
		x += iw + 2;
	}
	return std::nullopt;
}

bool on_handle(Rect r, int px, int py) {
	return r.contains(px, py) && px >= r.x + r.w - BAR_W;
}

bool on_resize_bar(Rect r, int px, int py) {
	return px >= r.x && px < r.x + r.w && py >= r.y + r.h && py < r.y + r.h + SEL_H + 2;
}

void draw_outline(Rect r) {
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect b = d.rect(r.x, r.y, r.w, r.h);
	const int t = 3 * d.lw();
	fl_color(DRAG_NAVY);
	const std::array<std::array<int, 4>, 4> edges = {{
	    {b.x, b.y, b.w, t},
	    {b.x, b.y + b.h - t, b.w, t},
	    {b.x, b.y, t, b.h},
	    {b.x + b.w - t, b.y, t, b.h},
	}};
	for (const auto& [rx, ry, rw, rh] : edges) {
		fl_rectf(rx, ry, std::max(rw, 0), std::max(rh, 0));
	}
}

void raised_cell(int x, int y, int w, int h) {
	const draw::Dev d = draw::Dev::begin();
	const draw::Rect b = d.rect(x, y, w, h);
	const int k = d.lw();
	fl_rectf(b.x, b.y, b.w, b.h, theme::PANEL);
	fl_rectf(b.x, b.y, b.w, k, draw::HIGHLIGHT);
	fl_rectf(b.x, b.y + b.h - k, b.w, k, theme::SHADOW);
}

void draw_diamond(int cx, int cy) {
	fl_color(FL_BLACK);
	for (int dy = -3; dy <= 3; ++dy) {
		const int half = 3 - std::abs(dy);
		fl_line(cx - half, cy + dy, cx + half, cy + dy);
	}
}

bool hour_label_shows(int mid, int top) {
	fl_font(theme::fonts().sans, 11);
	return mid - fl_height() / 2 >= top;
}

void draw_hour_label(std::int32_t min, int w, int x, int mid, bool h24, bool suffix) {
	fl_font(theme::fonts().sans, 11);
	fl_color(FL_BLACK);
	std::string label = time_label(min, h24);
	if (!h24 && !suffix) {
		label.pop_back();
	}
	const int asc = fl_height() - fl_descent();
	const int desc = fl_descent();
	fl_draw(label.c_str(), x + w - 10 - draw::text_w(label), mid + (asc - desc) / 2);
	fl_line(x + w - 7, mid, x + w - 3, mid);
}

std::optional<bool> ask_repeat_scope(std::string_view text) {
	const auto answer =
	    dialogs::ask_icon("Repeating Event", dialogs::Icon::Question, text, {"&Current", "&All", "Cancel"}, 1);
	if (!answer) {
		return std::nullopt;
	}
	switch (*answer) {
	case 0:
		return true;
	case 1:
		return false;
	default:
		return std::nullopt;
	}
}

bool save_occurrence(store::DesktopStore& store, const Occ& occ, model::Event work, std::string_view text) {
	if (!occ.event.repeats()) {
		store.save_event(work);
		return true;
	}
	const auto scope = ask_repeat_scope(text);
	if (!scope) {
		return false;
	}
	if (*scope) {
		model::Event series = occ.event;
		series.exceptions.insert(occ.day);
		store.save_event(series);
		const std::int64_t day = work.date;
		model::Event one = single(work, day);
		store.save_event(one);
	} else {
		const std::int64_t shift = days::wrapping_sub(work.date, occ.day);
		work.date = days::wrapping_add(occ.event.date, shift);
		if (work.repeat_end >= 0) {
			work.repeat_end = days::wrapping_add(work.repeat_end, shift);
		}
		std::set<std::int64_t> moved;
		for (const std::int64_t d : work.exceptions) {
			moved.insert(days::wrapping_add(d, shift));
		}
		work.exceptions = std::move(moved);
		store.save_event(work);
	}
	return true;
}

bool change_occurrence(store::DesktopStore& store, const Occ& occ, std::string_view text,
                       const std::function<void(model::Event&)>& change) {
	model::Event copy = occ.event;
	copy.date = occ.day;
	change(copy);
	return save_occurrence(store, occ, std::move(copy), text);
}

bool delete_occurrence(store::DesktopStore& store, const settings::Settings& settings, bool no_confirm,
                       const Occ& occ) {
	if (occ.event.repeats()) {
		const auto scope = ask_repeat_scope("Delete the current event only or all occurrences?");
		if (!scope) {
			return false;
		}
		if (*scope) {
			model::Event series = occ.event;
			series.exceptions.insert(occ.day);
			store.save_event(series);
		} else {
			store.delete_event(occ.event.id);
		}
		return true;
	}
	const bool confirm = !no_confirm && settings.get_bool("confirmDelete", true);
	if (confirm && dialogs::ask_icon("Delete Date Book Items", dialogs::Icon::Question,
	                                 "Delete the selected Date Book item(s)?", {"OK", "Cancel"}, 0) != std::size_t{0}) {
		return false;
	}
	store.delete_event(occ.event.id);
	return true;
}

Fl_Spinner* count_spinner(int x, int y, int w, int h, int value, int min) {
	auto* s = new Fl_Spinner(x, y, w, h);
	s->range(static_cast<double>(min), 99999.0);
	s->step(1.0);
	s->value(static_cast<double>(std::max(value, min)));
	return s;
}

bool alarm_dialog(model::Event& e) {
	AlarmState st;
	Fl_Double_Window win(227, 89);
	win.copy_label("Alarm Settings");
	st.win = &win;
	// The check box at the right of its "Alarm:" label, as Palm Desktop 3.1.1 lays it out.
	st.on = draw::check(47, 19, 13, 13, "");
	st.on->value(e.alarm);
	Fl_Button* label = dialogs::key_label("&Alarm:", 19, 17, 26, 18, FL_ALIGN_LEFT);
	st.amount = count_spinner(71, 17, 36, 18, e.alarm_advance, 1);
	st.unit = new combo::Choice(115, 17, 102, 18);
	for (const std::string_view u : ALARM_UNITS) {
		st.unit->add(std::string(u).c_str());
	}
	st.unit->value(std::clamp(e.alarm_unit, 0, 2));
	draw::style_menu(st.unit);
	// OK and Cancel at the bottom, 75 x 23 each, as in 3.1.1.
	Fl_Button* ok = new Fl_Button(26, 54, 75, 23, "&OK");
	Fl_Button* cancel = new Fl_Button(108, 54, 75, 23, "Cancel");
	ok->shortcut(FL_Enter);
	ok->callback(alarm_ok, &st);
	cancel->callback([](Fl_Widget* w, void*) { w->top_window()->hide(); });
	win.end();
	alarm_set_on(st.amount, st.unit, e.alarm);
	st.on->callback(alarm_toggled, &st);
	label->callback(alarm_label_pressed, &st);
	dialogs::run_modal(&win);
	if (!st.saved) {
		return false;
	}
	e.alarm = st.on->value() != 0;
	e.alarm_advance = static_cast<int>(st.amount->value());
	e.alarm_unit = std::max(0, st.unit->value());
	return true;
}

void edit_note(store::DesktopStore& store, const Occ& occ) {
	const auto text = note_dialog::edit("Note Editor", occ.event.note);
	if (!text) {
		return;
	}
	change_occurrence(store, occ, "Change the note for the current event only or for all occurrences?",
	                  [&text](model::Event& e) { e.note = *text; });
}

void edit_alarm(store::DesktopStore& store, const Occ& occ) {
	model::Event work = occ.event;
	if (!alarm_dialog(work)) {
		return;
	}
	change_occurrence(store, occ, "Change the alarm for the current event only or for all occurrences?",
	                  [&work](model::Event& e) {
		                  e.alarm = work.alarm;
		                  e.alarm_advance = work.alarm_advance;
		                  e.alarm_unit = work.alarm_unit;
	                  });
}

std::optional<BoxAction> event_menu() {
	Fl_Menu_Button mb(0, 0, 0, 0);
	mb.type(Fl_Menu_Button::POPUP3);
	for (const char* label : {"&Delete", "&Note...", "&Alarm...", "&Edit..."}) {
		mb.add(label);
	}
	// A divider after Delete and after Alarm, as in Palm Desktop 3.1.1.
	for (const int i : {0, 2}) {
		mb.mode(i, FL_MENU_DIVIDER);
	}
	draw::style_menu(&mb);
	if (!mb.popup()) {
		return std::nullopt;
	}
	const int v = mb.value();
	if (v < 0) {
		return std::nullopt;
	}
	switch (v) {
	case 0:
		return BoxAction::Delete;
	case 1:
		return BoxAction::Note;
	case 2:
		return BoxAction::Alarm;
	default:
		return BoxAction::Edit;
	}
}

int title_line_h() {
	fl_font(FL_HELVETICA, 11);
	return fl_height();
}

int title_top(Rect r, bool centered) {
	if (centered) {
		return r.y + (r.h - title_line_h()) / 2;
	}
	return r.y + 1;
}

void draw_centered(std::string_view s, int x, int y, int w, int h) {
	const std::string text = draw::escape_at(s);
	fl_draw(text.c_str(), x, y, w, h, FL_ALIGN_CENTER);
}

std::string describe_desktop(const model::Event& e) {
	if (e.repeat == model::repeat::NONE) {
		return {};
	}
	const std::int32_t n = std::max<std::int32_t>(e.every, 1);
	const std::string freq = n == 1 ? std::string() : n == 2 ? std::string("other") : model::ordinal(n);
	const days::Ymd d = days::ymd(e.date);
	std::string base;
	switch (e.repeat) {
	case model::repeat::DAY:
		base = words({"Every", freq, "day"});
		break;
	case model::repeat::WEEK: {
		const std::int32_t mask = e.week_days == 0 ? (1 << static_cast<int>(days::dow(e.date))) : e.week_days;
		std::vector<std::size_t> picked;
		for (std::size_t i = 0; i < 7; ++i) {
			if ((mask & (1 << static_cast<int>(i))) != 0) {
				picked.push_back(i);
			}
		}
		if (picked.size() == 1) {
			base = words({"Every", freq, model::DOW_LONG[picked[0]]});
		} else {
			// Rust panics here on no day, as names.len() - 1 underflows; a thrown runtime_error keeps that outcome.
			if (picked.empty()) {
				throw std::runtime_error("describe_desktop: a weekly repeat with no day");
			}
			// "Mon, and Tue" for two days, "Mon, Tue, and Wed" for three: Rust's names[..n-1].join(", ") then ", and ".
			std::string names;
			for (std::size_t k = 0; k + 1 < picked.size(); ++k) {
				names += std::string(model::DOW_SHORT[picked[k]]) + ", ";
			}
			names += "and " + std::string(model::DOW_SHORT[picked.back()]);
			const std::string weekly = "week on " + names;
			base = words({"Every", freq, weekly});
		}
		break;
	}
	case model::repeat::MONTH_DAY: {
		const std::uint32_t w = model::month_week(e);
		const std::string week = w == 5 ? std::string("last") : model::ordinal(static_cast<std::int32_t>(w));
		base =
		    std::format("The {} {} of {}", week, model::DOW_LONG[days::dow(e.date)], words({"every", freq, "month"}));
		break;
	}
	case model::repeat::MONTH_DATE:
		base = std::format("The {} of {}", model::ordinal(static_cast<std::int32_t>(d.day)),
		                   words({"every", freq, "month"}));
		break;
	case model::repeat::YEAR:
		base = std::format("{} {} {}", MONTH_LONG[d.month - 1], d.day, words({"every", freq, "year"}));
		break;
	default:
		break;
	}
	if (e.repeat_end >= 0) {
		return std::format("{} until {}", base, model::short_date(e.repeat_end));
	}
	return base;
}

std::string date_title(std::int64_t day) {
	const days::Ymd d = days::ymd(day);
	return std::format("{}, {} {}, {}", model::DOW_LONG[days::dow(day)], MONTH_LONG[d.month - 1], d.day, d.year);
}

std::int64_t week_of_year(std::int64_t day, bool monday) {
	const days::Ymd d = days::ymd(day);
	const std::int64_t doy = days::wrapping_add(days::wrapping_sub(day, days::epoch_day(d.year, 1, 1)), 1);
	const std::int64_t sow_iso = monday ? 1 : 7;
	const std::int64_t min_days = monday ? 4 : 1;
	const std::int64_t iso_dow = static_cast<std::int64_t>((days::dow(day) + 6) % 7) + 1;
	const std::int64_t dow1 = rem_euclid(iso_dow - sow_iso, 7) + 1;
	const std::int64_t week_start = rem_euclid(doy - dow1, 7);
	const std::int64_t offset = week_start + 1 > min_days ? 7 - week_start : -week_start;
	return (7 + offset + (doy - 1)) / 7;
}

} // namespace palm::date_shared
