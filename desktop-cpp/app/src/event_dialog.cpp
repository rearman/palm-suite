// The Edit Event dialog, the Change Repeat and Select Time dialogs, and the parsers of their time and date fields, as
// desktop-rs/app/src/event_dialog.rs. The Rust closures of the buttons become static callbacks that set the action of
// the dialog, and the keys of a window and the drawing of a strip are subclasses.
#include "palm/event_dialog.hpp"

#include "palm/combo.hpp"
#include "palm/date_picker.hpp"
#include "palm/date_shared.hpp"
#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/note_dialog.hpp"
#include "palm/platform.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Hold_Browser.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_Output.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Spinner.H>
#include <FL/Fl_Text_Buffer.H>
#include <FL/Fl_Text_Display.H>
#include <FL/Fl_Window.H>
#include <FL/fl_ask.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <charconv>
#include <cstddef>
#include <cstdint>
#include <format>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::event_dialog {
namespace {

// The text of an untimed start or end field.
constexpr std::string_view NO_TIME = "None";

// True for an ASCII digit.
bool is_digit(char c) {
	return c >= '0' && c <= '9';
}

// True when `t` is one or more ASCII digits.
bool all_digits(std::string_view t) {
	return !t.empty() && std::ranges::all_of(t, is_digit);
}

// The value of a string of ASCII digits. The callers check the digits first.
std::int64_t num_of(std::string_view t) {
	std::int64_t v = 0;
	std::from_chars(t.data(), t.data() + t.size(), v);
	return v;
}

// True when a time field says no time: blank or "None".
bool is_no_time(std::string_view field) {
	const std::string_view t = text::trim(field);
	return t.empty() || text::eq_ignore_ascii_case(t, NO_TIME);
}

// The first line of `s`, without its line break, as Rust's str::lines gives it.
std::string_view first_line(std::string_view s) {
	std::string_view line = s.substr(0, s.find('\n'));
	if (line.ends_with('\r')) {
		line.remove_suffix(1);
	}
	return line;
}

// Shows a warning in the Palm Desktop message box.
void warn(std::string_view message_text) {
	dialogs::message("Palm Desktop", dialogs::Icon::Warning, message_text);
}

// Shows or hides `w`.
void set_shown(Fl_Widget* w, bool on) {
	if (on) {
		w->show();
	} else {
		w->hide();
	}
}

// True for a day in both the 4th and the last week of its month. The monthly repeat by day reads the week from its
// first date, so such a day asks which of the two is meant.
bool in_4th_and_last_week(std::int64_t day) {
	return model::week_index(day) == 4 && model::is_last_week(day);
}

// The text of the Repeat button.
const char* repeat_name(const model::Event& e) {
	switch (e.repeat) {
	case model::repeat::DAY:
		return "Daily...";
	case model::repeat::WEEK:
		return "Weekly...";
	case model::repeat::MONTH_DAY:
	case model::repeat::MONTH_DATE:
		return "Monthly...";
	case model::repeat::YEAR:
		return "Yearly...";
	default:
		return "None...";
	}
}

// The badge of a question box: the Question icon at the top left of the box.
class QuestionBadge : public Fl_Box {
public:
	using Fl_Box::Fl_Box;

	void draw() override {
		Fl_Box::draw();
		dialogs::draw_icon(dialogs::Icon::Question, x(), y());
	}
};

// The answer of the Monthly Repeat question: the radio that is set and the window that OK closes.
struct QuestionAnswer {
	Fl_Window* win;
	Fl_Round_Button* last;
	std::optional<std::size_t> answer;
};

void monthly_ok(Fl_Widget*, void* data) {
	auto* q = static_cast<QuestionAnswer*>(data);
	q->answer = q->last->value() ? std::size_t{1} : std::size_t{0};
	q->win->hide();
}

// The Monthly Repeat question: "4th week" (Some(0)) or "Last week" (Some(1)), or None when the box is closed. The
// question has a Question icon, two radios and one OK button; "Last week" is set at first when `last` is true.
std::optional<std::size_t> monthly_week_question(bool last) {
	auto win = std::make_unique<Fl_Double_Window>(230, 130, "Monthly Repeat");
	new QuestionBadge(11, 11, 32, 32);
	auto* question = new Fl_Box(53, 14, 166, 34, "Does this event occur on the 4th or last week of each month?");
	question->align(FL_ALIGN_LEFT | FL_ALIGN_TOP | FL_ALIGN_INSIDE | FL_ALIGN_WRAP);
	auto* fourth = draw::radio(53, 60, 80, 20, "&4th week");
	fourth->value(!last);
	auto* last_r = draw::radio(140, 60, 80, 20, "&Last week");
	last_r->value(last);
	auto* ok = dialogs::push_button((230 - 75) / 2, 92, "OK", true);
	ok->shortcut(FL_Enter);
	QuestionAnswer answer{win.get(), last_r, std::nullopt};
	ok->callback(monthly_ok, &answer);
	win->callback([](Fl_Widget* w, void*) { w->hide(); });
	win->end();
	dialogs::run_modal(win.get());
	return answer.answer;
}

// The images that the buttons of a dialog show. A button does not own its image, so the dialog keeps them alive.
using Images = std::vector<std::unique_ptr<Fl_PNG_Image>>;

// A 22 x 22 icon button at (x, y) with the bitmap `png` and the tooltip `tip`.
Fl_Button* icon_button(int x, int y, std::string_view png, const char* tip, Images& keep) {
	auto image = theme::icon(png);
	auto* b = new Fl_Button(x, y, 22, 22);
	b->image(image.get());
	b->tooltip(tip);
	keep.push_back(std::move(image));
	return b;
}

// A widget callback that sets the pending action of a dialog. `slot` is the answer of the dialog and `act` the action
// of the widget that holds this record.
template <typename A> struct Send {
	std::optional<A>* slot;
	A act;
};

// The callback of a button: it sets the action in its Send record.
template <typename A> void send_act(Fl_Widget*, void* data) {
	auto* s = static_cast<Send<A>*>(data);
	*s->slot = s->act;
}

// The callback of a field label: a click moves the focus to `target`.
void focus_pressed(Fl_Widget*, void* data) {
	static_cast<Fl_Widget*>(data)->take_focus();
}

// A field label of the Edit Event form at height y, in the 6 px left column, that moves the focus to `target`.
Fl_Button* focus_label(std::string_view text_label, int y, Fl_Widget* target) {
	Fl_Button* l = dialogs::key_label(text_label, 6, y, 60, 22, FL_ALIGN_RIGHT);
	l->callback(focus_pressed, target);
	return l;
}

// What the Edit Event buttons ask for.
enum class Act {
	Ok,
	Clock,
	Calendar,
	Note,
	Repeat,
};

// The Edit Event window. Enter outside the description is OK, as the default button of the Swing form.
class EditWindow : public Fl_Double_Window {
public:
	EditWindow(int w, int h, const char* title) : Fl_Double_Window(w, h, title) {}

	int handle(int e) override {
		const int base = Fl_Window::handle(e);
		if (e == FL_SHORTCUT && (Fl::event_key() == FL_Enter || Fl::event_key() == FL_KP_Enter)) {
			*act = Act::Ok;
			return 1;
		}
		return base;
	}

	std::optional<Act>* act = nullptr;
};

// What the Change Repeat controls ask for.
enum class RepeatKind {
	Ok,
	Kind,
	End,
	Toggle,
	Refresh,
};

// A Change Repeat action: the kind (the repeat type of a radio, the day of a cell) and its value.
struct RepeatAct {
	RepeatKind kind;
	int value = 0;
};

// The seven day cells of Change Repeat in week order, in 1 px black lines. A click turns a day on or off.
class DayStrip : public Fl_Box {
public:
	DayStrip(int x, int y, int w, int h) : Fl_Box(x, y, w, h) {}

	void draw() override {
		Fl_Box::draw();
		fl_rectf(x(), y(), w(), h(), FL_BLACK);
		const int cw = (w() - 1) / 7;
		for (int c = 0; c < 7; ++c) {
			const int day = static_cast<int>((static_cast<std::uint32_t>(c) + begins) % 7);
			const bool on = (mask & (1 << day)) != 0;
			const int cx = x() + 1 + c * cw;
			fl_rectf(cx, y() + 1, cw - 1, h() - 2, on ? theme::SELECT : FL_WHITE);
			fl_font(theme::fonts().sans, 11);
			fl_color(on ? FL_WHITE : FL_BLACK);
			fl_draw(std::string(model::DOW_LETTER[static_cast<std::size_t>(day)]).c_str(), cx, y(), cw - 1, h(),
			        FL_ALIGN_CENTER);
		}
	}

	int handle(int e) override {
		const int base = Fl_Box::handle(e);
		if (e != FL_PUSH) {
			return base;
		}
		const int c = std::clamp((Fl::event_x() - x()) * 7 / std::max(w(), 1), 0, 6);
		*act = RepeatAct{RepeatKind::Toggle, (c + static_cast<int>(begins)) % 7};
		return 1;
	}

	// The day mask to draw, the first day of the week, and the dialog's answer.
	int mask = 0;
	std::uint32_t begins = 0;
	std::optional<RepeatAct>* act = nullptr;
};

// The Change Repeat repeat that a preview of `e` gets: the kind, the interval, the end and the day mask, with the rest
// of `e` kept. A monthly repeat by day keeps only its LAST_WEEK flag.
model::Event repeat_preview(const model::Event& e, std::int32_t kind, std::int32_t every, std::int64_t end,
                            std::int32_t mask) {
	const bool on = kind != model::repeat::NONE;
	model::Event p = e;
	p.repeat = kind;
	p.every = every;
	p.repeat_end = on ? end : -1;
	if (!on) {
		p.week_days = 0;
	} else if (kind == model::repeat::MONTH_DAY) {
		p.week_days = e.week_days & model::repeat::LAST_WEEK;
	} else if (mask == 0) {
		p.week_days = static_cast<std::int32_t>(1U << days::dow(e.date));
	} else {
		p.week_days = mask;
	}
	return p;
}

// The Select Time actions.
enum class TimeAct {
	Start,
	End,
	Hour,
	Minute,
	NoTime,
	Ok,
};

// The Select Time window. Alt+S and Alt+E check the start and the end time, as the mnemonics of the Swing radios.
class TimeWindow : public Fl_Double_Window {
public:
	TimeWindow(int w, int h, const char* title) : Fl_Double_Window(w, h, title) {}

	int handle(int e) override {
		const int base = Fl_Window::handle(e);
		if (e != FL_SHORTCUT || !(Fl::event_state() & FL_ALT)) {
			return base;
		}
		const int k = Fl::event_key();
		const int key = (k >= 'A' && k <= 'Z') ? k - 'A' + 'a' : k;
		if (key == 's') {
			start_r->value(1);
			end_r->value(0);
			*act = TimeAct::Start;
		} else if (key == 'e') {
			end_r->value(1);
			start_r->value(0);
			*act = TimeAct::End;
		} else {
			return base;
		}
		return 1;
	}

	std::optional<TimeAct>* act = nullptr;
	Fl_Round_Button* start_r = nullptr;
	Fl_Round_Button* end_r = nullptr;
};

// The Purge Events answer: whether OK was pressed, and the window that the buttons close.
struct PurgeAnswer {
	Fl_Window* win;
	bool go;
};

void purge_ok(Fl_Widget*, void* data) {
	auto* a = static_cast<PurgeAnswer*>(data);
	a->go = true;
	a->win->hide();
}

} // namespace

std::optional<std::int32_t> parse_time(std::string_view field) {
	// Spaces and case do not count. The filter keeps the code points that are not White_Space.
	std::string filtered;
	for (std::size_t i = 0; i < field.size();) {
		const text::Decoded d = text::decode_at(field, i);
		if (!text::is_white_space(d.cp)) {
			filtered.append(field.substr(i, d.len));
		}
		i += d.len;
	}
	const std::string s = text::to_lowercase(filtered);
	std::size_t digits_end = 0;
	while (digits_end < s.size() && (is_digit(s[digits_end]) || s[digits_end] == ':')) {
		++digits_end;
	}
	const std::string_view num = std::string_view(s).substr(0, digits_end);
	const std::string_view suffix = std::string_view(s).substr(digits_end);
	if (!(suffix.empty() || suffix == "a" || suffix == "am" || suffix == "p" || suffix == "pm")) {
		return std::nullopt;
	}
	std::int64_t h = 0;
	std::int64_t m = 0;
	const std::size_t colon = num.find(':');
	if (colon != std::string_view::npos) {
		const std::string_view hs = num.substr(0, colon);
		const std::string_view ms = num.substr(colon + 1);
		if (!((hs.size() == 1 || hs.size() == 2) && ms.size() == 2 && all_digits(hs) && all_digits(ms))) {
			return std::nullopt;
		}
		h = num_of(hs);
		m = num_of(ms);
	} else if (all_digits(num) && num.size() <= 2) {
		h = num_of(num);
	} else if (all_digits(num) && num.size() <= 4) {
		h = num_of(num.substr(0, num.size() - 2));
		m = num_of(num.substr(num.size() - 2));
	} else {
		return std::nullopt;
	}
	if (m > 59) {
		return std::nullopt;
	}
	std::int64_t hour = 0;
	if (suffix.empty()) {
		hour = h;
	} else if (h < 1 || h > 12) {
		return std::nullopt;
	} else if (suffix.starts_with('a')) {
		hour = h % 12;
	} else {
		hour = h % 12 + 12;
	}
	if (hour > 23) {
		return std::nullopt;
	}
	return static_cast<std::int32_t>(hour * 60 + m);
}

std::optional<std::int64_t> parse_short_date(std::string_view field) {
	const std::vector<std::string_view> parts = text::split(text::trim(field), '/');
	if (parts.size() != 3) {
		return std::nullopt;
	}
	const std::string_view m = parts[0];
	const std::string_view d = parts[1];
	const std::string_view y = parts[2];
	const auto ok = [](std::string_view t, std::size_t lo, std::size_t hi) {
		return t.size() >= lo && t.size() <= hi && std::ranges::all_of(t, is_digit);
	};
	if (!ok(m, 1, 2) || !ok(d, 1, 2) || !(y.size() == 2 || y.size() == 4) || !ok(y, 2, 4)) {
		return std::nullopt;
	}
	const auto month = static_cast<std::uint32_t>(num_of(m));
	const auto dom = static_cast<std::uint32_t>(num_of(d));
	const std::int64_t yy = num_of(y);
	std::int64_t year = 0;
	if (y.size() == 4) {
		year = yy;
	} else if (yy >= 70) {
		year = 1900 + yy;
	} else {
		year = 2000 + yy;
	}
	if (month < 1 || month > 12 || dom < 1 || dom > days::month_length(year, month)) {
		return std::nullopt;
	}
	return days::epoch_day(year, month, dom);
}

std::string full_date(std::int64_t day) {
	const days::Ymd d = days::ymd(day);
	return std::format("{}/{}/{}", d.month, d.day, d.year);
}

void edit(view::Ctx& ctx, std::optional<model::Event> orig, std::int64_t day, std::optional<std::int32_t> start_min,
          std::string_view title) {
	const std::int32_t start = start_min.value_or(8 * 60);
	model::Event work;
	if (orig) {
		work = *orig;
	} else {
		work.title = std::string(title);
		work.date = day;
		work.start = start;
		work.end = start + 60;
		work.untimed = !start_min.has_value();
		date_shared::apply_alarm_preset(work, ctx.settings);
	}
	const bool h24 = ctx.h24;

	// The answers of the buttons, and the storage that the window needs: the images, the description and the sends.
	std::optional<Act> act;
	Images images;
	Fl_Text_Buffer desc_buf;
	Send<Act> clock_send{&act, Act::Clock};
	Send<Act> cal_send{&act, Act::Calendar};
	Send<Act> note_send{&act, Act::Note};
	Send<Act> repeat_send{&act, Act::Repeat};
	Send<Act> ok_send{&act, Act::Ok};

	auto win = std::make_unique<EditWindow>(258, 262, "Edit Event");
	win->act = &act;

	// The description: a multi-line field, selected on open so the first key replaces it.
	auto* desc = new text_edit::Editor(70, 4, 182, 50);
	desc->buffer(&desc_buf);
	desc->wrap_mode(Fl_Text_Display::WRAP_AT_BOUNDS, 0);
	desc->scrollbar_align(FL_ALIGN_RIGHT);
	focus_label("De&scription:", 4, desc);
	desc_buf.text(work.title.c_str());
	if (orig) {
		desc_buf.select(0, desc_buf.length());
	} else {
		// A title typed in the Month view goes on after its first key.
		desc->insert_position(desc_buf.length());
	}

	// The time fields and the Clock button.
	auto* start_f = new text_edit::Input(70, 60, 60, 22);
	auto* end_f = new text_edit::Input(158, 60, 60, 22);
	focus_label("T&ime:", 60, start_f);
	auto* to = dialogs::key_label("&to", 134, 60, 22, 22, FL_ALIGN_CENTER);
	to->callback(focus_pressed, static_cast<Fl_Widget*>(end_f));
	auto* clock = icon_button(226, 60, "clock", "Set time", images);
	clock->callback(send_act<Act>, &clock_send);
	const auto show_time = [h24](Fl_Input* s, Fl_Input* e, const model::Event& t) {
		const std::string st = t.untimed ? std::string(NO_TIME) : model::time(t.start, h24);
		const std::string en = t.untimed ? std::string(NO_TIME) : model::time(t.end, h24);
		s->value(st.c_str());
		e->value(en.c_str());
	};
	show_time(start_f, end_f, work);

	// The date field, with the name of its day, and the Calendar button.
	auto* date_f = new text_edit::Input(70, 90, 80, 22);
	date_f->value(full_date(day).c_str());
	focus_label("D&ate:", 90, date_f);
	auto* day_name = new Fl_Box(154, 90, 68, 22);
	day_name->box(FL_DOWN_BOX);
	day_name->copy_label(std::string(model::DOW_LONG[days::dow(day)]).c_str());
	auto* cal = icon_button(226, 90, "calendar", "Choose date", images);
	cal->callback(send_act<Act>, &cal_send);

	// The note: its first line shows, and the Note button opens the Note Editor.
	auto* note_f = new Fl_Output(70, 120, 152, 22);
	note_f->color(theme::PANEL);
	note_f->value(std::string(first_line(work.note)).c_str());
	focus_label("Not&e:", 120, note_f);
	auto* note_b = icon_button(226, 120, "note", "Edit note", images);
	note_b->callback(send_act<Act>, &note_send);

	// The Repeat button, which opens Change Repeat.
	auto* repeat_b = new Fl_Button(70, 150, 90, 22);
	repeat_b->callback(send_act<Act>, &repeat_send);
	focus_label("&Repeat:", 150, repeat_b);

	// The alarm: its count and unit show only while Alarm is checked.
	auto* alarm = draw::check(70, 180, 18, 22, "");
	alarm->value(work.alarm);
	focus_label("A&larm:", 180, alarm);
	auto* amount = date_shared::count_spinner(92, 180, 44, 22, work.alarm_advance, 1);
	auto* unit = new combo::Choice(140, 180, 112, 22);
	for (const std::string_view u : date_shared::ALARM_UNITS) {
		unit->add(std::string(u).c_str());
	}
	unit->value(std::clamp(work.alarm_unit, 0, 2));
	draw::style_menu(unit);
	const std::array<Fl_Widget*, 2> alarm_parts{amount, unit};

	// The private check box.
	auto* private_b = draw::check(70, 210, 18, 22, "");
	private_b->value(work.is_private);
	focus_label("&Private:", 210, private_b);

	// OK and Cancel. The Repeat text and its tooltip follow the event.
	auto* ok = new Fl_Button(51, 234, 75, 23, "OK");
	ok->callback(send_act<Act>, &ok_send);
	auto* cancel = new Fl_Button(132, 234, 75, 23, "Cancel");
	cancel->callback([](Fl_Widget* w, void*) { w->top_window()->hide(); });
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);

	const auto show_repeat = [](Fl_Button* b, const model::Event& e) {
		b->label(repeat_name(e));
		if (e.repeats()) {
			const std::string tip = date_shared::describe_desktop(e);
			b->copy_tooltip(tip.c_str());
		} else {
			b->tooltip("");
		}
	};
	show_repeat(repeat_b, work);
	dialogs::show_modal(win.get());
	desc->take_focus();
	while (win->shown()) {
		Fl::wait();
		for (Fl_Widget* w : alarm_parts) {
			set_shown(w, alarm->value() != 0);
		}
		// The day name follows the date field.
		if (const auto d = parse_short_date(date_f->value())) {
			const std::string name(model::DOW_LONG[days::dow(*d)]);
			const char* current = day_name->label();
			if (!current || std::string_view(current) != name) {
				day_name->copy_label(name.c_str());
			}
		}
		if (!act) {
			continue;
		}
		const Act a = *act;
		act.reset();
		switch (a) {
		case Act::Clock: {
			// Select Time works on a copy of the time fields; the fields take the copy on OK or No Time.
			const auto s = parse_time(start_f->value());
			model::Event t = work;
			t.untimed = !s.has_value();
			t.start = s.value_or(8 * 60);
			t.end = t.untimed ? t.start + 60 : parse_time(end_f->value()).value_or(std::min(t.start + 60, 1439));
			if (select_time(t, h24)) {
				show_time(start_f, end_f, t);
			}
			break;
		}
		case Act::Calendar: {
			const std::int64_t from = parse_short_date(date_f->value()).value_or(day);
			if (const auto d = date_picker::pick(date_picker::Mode::Day, from, platform::today(),
			                                     date_shared::week_begins(ctx.settings))) {
				date_f->value(full_date(*d).c_str());
			}
			break;
		}
		case Act::Note:
			if (const auto note = note_dialog::edit("Note Editor", work.note)) {
				note_f->value(std::string(first_line(*note)).c_str());
				work.note = *note;
			}
			break;
		case Act::Repeat:
			if (change_repeat(work, ctx.settings)) {
				show_repeat(repeat_b, work);
			}
			break;
		case Act::Ok: {
			const auto new_day = parse_short_date(date_f->value());
			if (!new_day) {
				warn("Enter a date as M/D/YY.");
				continue;
			}
			const bool timed = !is_no_time(start_f->value());
			const auto s = parse_time(start_f->value());
			std::optional<std::int32_t> en;
			if (is_no_time(end_f->value())) {
				if (s) {
					en = std::min(*s + 60, 1439);
				}
			} else {
				en = parse_time(end_f->value());
			}
			if (timed) {
				if (!s || !en) {
					warn("Invalid time entered.");
					continue;
				}
				if (*en < *s) {
					warn("The end time is before the start time.");
					continue;
				}
				work.start = *s;
				work.end = *en;
			}
			work.untimed = !timed;
			work.title = std::string(text::trim(text_edit::buffer_text(desc_buf)));
			work.alarm = alarm->value() != 0;
			work.alarm_advance = static_cast<std::int32_t>(amount->value());
			work.alarm_unit = std::max(0, unit->value());
			work.is_private = private_b->value() != 0;
			if (work.repeat == model::repeat::MONTH_DAY && in_4th_and_last_week(*new_day)) {
				// The first date alone cannot tell the 4th week from the last, so the answer goes into the LAST_WEEK
				// flag of the rule.
				const auto pick = monthly_week_question((work.week_days & model::repeat::LAST_WEEK) != 0);
				if (!pick) {
					continue;
				}
				if (*pick == 0) {
					work.week_days &= ~model::repeat::LAST_WEEK;
				} else {
					work.week_days |= model::repeat::LAST_WEEK;
				}
			}
			if (!orig) {
				work.date = *new_day;
				ctx.store.save_event(work);
			} else if (*orig == work && *new_day == day) {
				// The occurrence is unchanged, so nothing is saved.
			} else {
				model::Event w = work;
				w.date = *new_day;
				if (!orig->repeats()) {
					ctx.store.save_event(w);
				} else {
					const date_shared::Occ occ{*orig, day};
					if (!date_shared::save_occurrence(ctx.store, occ, w, date_shared::CHANGE_TEXT)) {
						continue;
					}
				}
			}
			win->hide();
			break;
		}
		}
	}
}

bool change_repeat(model::Event& e, const settings::Settings& settings) {
	const std::uint32_t begins = date_shared::week_begins(settings);
	std::int32_t kind = e.repeat;
	std::int32_t month_by = e.repeat == model::repeat::MONTH_DAY ? model::repeat::MONTH_DAY : model::repeat::MONTH_DATE;
	// Bits 0 to 6 are the week days; a monthly repeat by day keeps its LAST_WEEK flag in bit 7.
	std::int32_t mask =
	    (e.week_days & 0x7F) == 0 ? static_cast<std::int32_t>(1U << days::dow(e.date)) : (e.week_days & 0x7F);
	std::int64_t end = e.repeat_end;

	// The answers of the controls, and the storage that the window needs.
	std::optional<RepeatAct> act;
	Images images;
	Send<RepeatAct> refresh_send{&act, {RepeatKind::Refresh, 0}};
	Send<RepeatAct> end_send{&act, {RepeatKind::End, 0}};
	Send<RepeatAct> ok_send{&act, {RepeatKind::Ok, 0}};
	constexpr std::array<std::pair<const char*, std::int32_t>, 5> kinds{{
	    {"&None", model::repeat::NONE},
	    {"&Daily", model::repeat::DAY},
	    {"&Weekly", model::repeat::WEEK},
	    {"&Monthly", model::repeat::MONTH_DATE},
	    {"&Yearly", model::repeat::YEAR},
	}};
	std::array<Send<RepeatAct>, 5> kind_sends{};
	for (std::size_t i = 0; i < kinds.size(); ++i) {
		kind_sends[i] = Send<RepeatAct>{&act, {RepeatKind::Kind, kinds[i].second}};
	}

	auto win = std::make_unique<Fl_Double_Window>(263, 222, "Change Repeat");

	// The five repeat types, one radio each. The radios of one window are exclusive.
	for (std::size_t i = 0; i < kinds.size(); ++i) {
		const auto [label, k] = kinds[i];
		auto* r = draw::radio(10, 16 + 24 * static_cast<int>(i), 72, 20, label);
		const bool on = k == model::repeat::MONTH_DATE
		                    ? (kind == model::repeat::MONTH_DATE || kind == model::repeat::MONTH_DAY)
		                    : kind == k;
		r->value(on);
		r->callback(send_act<RepeatAct>, &kind_sends[i]);
	}
	auto* hint = new Fl_Box(96, 40, 157, 56, "Click one of the buttons to the left to set a repeat interval.");
	hint->align(FL_ALIGN_LEFT | FL_ALIGN_TOP | FL_ALIGN_INSIDE | FL_ALIGN_WRAP);

	// The Every count and its unit, the End on date with its calendar button, the Days strip and the By choice.
	auto* every_l = dialogs::key_label("&Every:", 96, 18, 46, 17, FL_ALIGN_RIGHT);
	auto* every = date_shared::count_spinner(144, 16, 32, 21, e.every, 1);
	every->callback(send_act<RepeatAct>, &refresh_send);
	auto* every_unit = new Fl_Box(192, 18, 66, 17);
	every_unit->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	auto* end_l = dialogs::key_label("End &on:", 96, 44, 46, 17, FL_ALIGN_RIGHT);
	auto* end_f = new Fl_Output(144, 42, 80, 21);
	end_f->color(theme::PANEL);
	auto* cal = new Fl_Button(224, 42, 20, 20);
	auto calendar_image = theme::icon("calendar");
	cal->image(calendar_image.get());
	images.push_back(std::move(calendar_image));
	cal->callback(send_act<RepeatAct>, &end_send);
	// The popup of End on: a hidden menu button at the calendar button, in the Windows menu look.
	auto* end_menu = new Fl_Menu_Button(cal->x(), cal->y() + cal->h(), 0, 0);
	end_menu->add("No End Date");
	end_menu->add("Date...");
	draw::style_menu(end_menu);
	end_menu->hide();
	auto* days_l = dialogs::key_label("Day&s:", 96, 70, 46, 17, FL_ALIGN_RIGHT);
	auto* strip = new DayStrip(144, 69, 99, 18);
	strip->mask = mask;
	strip->begins = begins;
	strip->act = &act;
	auto* by_l = dialogs::key_label("&By:", 96, 70, 46, 17, FL_ALIGN_RIGHT);
	auto* by = new combo::Choice(144, 68, 70, 21);
	by->add("Day");
	by->add("Date");
	by->value(month_by == model::repeat::MONTH_DAY ? 0 : 1);
	draw::style_menu(by);
	by->callback(send_act<RepeatAct>, &refresh_send);

	// The summary of the repeat, and OK and Cancel.
	auto* summary = new Fl_Box(10, 136, 243, 52);
	summary->box(FL_DOWN_BOX);
	summary->color(theme::PANEL);
	summary->align(FL_ALIGN_LEFT | FL_ALIGN_TOP | FL_ALIGN_INSIDE | FL_ALIGN_WRAP);
	auto* ok = new Fl_Button(10, 194, 68, 23, "OK");
	ok->shortcut(FL_Enter);
	ok->callback(send_act<RepeatAct>, &ok_send);
	auto* cancel = new Fl_Button(82, 194, 68, 23, "Cancel");
	cancel->callback([](Fl_Widget* w, void*) { w->top_window()->hide(); });
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);

	// Shows or hides the controls of the kind and the day mask, and writes the summary.
	const auto refresh = [&](std::int32_t cur_kind, std::int64_t cur_end, std::int32_t cur_mask) {
		const bool on = cur_kind != model::repeat::NONE;
		const auto n = static_cast<std::int32_t>(every->value());
		const bool monthly = cur_kind == model::repeat::MONTH_DATE || cur_kind == model::repeat::MONTH_DAY;
		set_shown(hint, !on);
		set_shown(every_l, on);
		set_shown(every, on);
		set_shown(every_unit, on);
		set_shown(end_l, on);
		set_shown(end_f, on);
		set_shown(cal, on);
		set_shown(days_l, cur_kind == model::repeat::WEEK);
		set_shown(strip, cur_kind == model::repeat::WEEK);
		set_shown(by, monthly);
		set_shown(by_l, monthly);
		const char* unit = "Month";
		if (cur_kind == model::repeat::DAY) {
			unit = "Day";
		} else if (cur_kind == model::repeat::WEEK) {
			unit = "Week";
		} else if (cur_kind == model::repeat::YEAR) {
			unit = "Year";
		}
		every_unit->copy_label(std::format("{}{}", unit, n == 1 ? "" : "s").c_str());
		const std::string end_text = cur_end < 0 ? std::string("No End Date") : model::short_date(cur_end);
		end_f->value(end_text.c_str());
		const std::string text_summary =
		    on ? date_shared::describe_desktop(repeat_preview(e, cur_kind, n, cur_end, cur_mask)) : std::string();
		summary->copy_label(text_summary.c_str());
		strip->mask = cur_mask;
		strip->redraw();
		win->redraw();
	};
	refresh(kind, end, mask);
	dialogs::show_modal(win.get());
	while (win->shown()) {
		Fl::wait();
		if (!act) {
			continue;
		}
		const RepeatAct a = *act;
		act.reset();
		switch (a.kind) {
		case RepeatKind::Kind:
			kind = a.value == model::repeat::MONTH_DATE ? month_by : a.value;
			break;
		case RepeatKind::Refresh:
			month_by = by->value() == 0 ? model::repeat::MONTH_DAY : model::repeat::MONTH_DATE;
			if (kind == model::repeat::MONTH_DATE || kind == model::repeat::MONTH_DAY) {
				kind = month_by;
			}
			break;
		case RepeatKind::Toggle:
			mask ^= 1 << a.value;
			break;
		case RepeatKind::End: {
			const Fl_Menu_Item* pick = end_menu->popup();
			const char* pick_label = pick ? pick->label() : nullptr;
			if (pick_label && std::string_view(pick_label) == "No End Date") {
				end = -1;
			} else if (pick_label) {
				const std::int64_t from = end >= 0 ? end : e.date;
				if (const auto d = date_picker::pick(date_picker::Mode::Day, from, platform::today(), begins)) {
					if (*d < e.date) {
						warn("The end date is before the first date.");
					} else {
						end = *d;
					}
				}
			}
			break;
		}
		case RepeatKind::Ok: {
			const model::Event w = repeat_preview(e, kind, static_cast<std::int32_t>(every->value()), end, mask);
			e.repeat = w.repeat;
			e.every = w.every;
			e.repeat_end = w.repeat_end;
			e.week_days = w.week_days;
			win->hide();
			return true;
		}
		}
		refresh(kind, end, mask);
	}
	return false;
}

bool select_time(model::Event& t, bool h24) {
	std::optional<TimeAct> act;
	Send<TimeAct> start_send{&act, TimeAct::Start};
	Send<TimeAct> end_send{&act, TimeAct::End};
	Send<TimeAct> notime_send{&act, TimeAct::NoTime};
	Send<TimeAct> hour_send{&act, TimeAct::Hour};
	Send<TimeAct> minute_send{&act, TimeAct::Minute};
	Send<TimeAct> ok_send{&act, TimeAct::Ok};

	auto win = std::make_unique<TimeWindow>(207, 220, "Select Time");
	win->act = &act;
	auto* start_l = new Fl_Box(8, 8, 80, 17, "Start time:");
	start_l->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	auto* start_r = draw::radio(8, 26, 18, 22, "");
	start_r->value(1);
	auto* start_show = new Fl_Output(28, 26, 60, 22);
	start_show->color(theme::PANEL);
	auto* end_l = new Fl_Box(8, 54, 80, 17, "End time:");
	end_l->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	auto* end_r = draw::radio(8, 72, 18, 22, "");
	auto* end_show = new Fl_Output(28, 72, 60, 22);
	end_show->color(theme::PANEL);
	auto* no_time = new Fl_Button(8, 102, 80, 23, "&No Time");
	start_r->callback(send_act<TimeAct>, &start_send);
	end_r->callback(send_act<TimeAct>, &end_send);
	no_time->callback(send_act<TimeAct>, &notime_send);
	win->start_r = start_r;
	win->end_r = end_r;

	auto* hours = new Fl_Hold_Browser(96, 8, 48, 172);
	for (int h = 0; h < 24; ++h) {
		hours->add(std::format("{:02}", h24 ? h : (h + 11) % 12 + 1).c_str());
	}
	auto* minutes = new Fl_Hold_Browser(150, 8, 48, 172);
	for (int m = 0; m < 12; ++m) {
		minutes->add(std::format("{:02}", m * 5).c_str());
	}
	hours->callback(send_act<TimeAct>, &hour_send);
	minutes->callback(send_act<TimeAct>, &minute_send);

	auto* ok = new Fl_Button(30, 188, 68, 23, "OK");
	ok->shortcut(FL_Enter);
	ok->callback(send_act<TimeAct>, &ok_send);
	auto* cancel = new Fl_Button(104, 188, 68, 23, "Cancel");
	cancel->callback([](Fl_Widget* w, void*) { w->top_window()->hide(); });
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);

	// Shows the start or the end time, and selects its hour and minute in the lists. No Time clears both lists.
	const auto refresh = [&](const model::Event& cur, bool edit_start) {
		start_show->value(cur.untimed ? std::string(NO_TIME).c_str() : model::time(cur.start, h24).c_str());
		end_show->value(cur.untimed ? std::string(NO_TIME).c_str() : model::time(cur.end, h24).c_str());
		if (cur.untimed) {
			// No hour or minute is chosen, and the hours start at 08 at the top of the list.
			if (hours->value() > 0) {
				hours->select(hours->value(), 0);
			}
			if (minutes->value() > 0) {
				minutes->select(minutes->value(), 0);
			}
			hours->topline(9);
			return;
		}
		const std::int32_t active = edit_start ? cur.start : cur.end;
		hours->select(active / 60 + 1);
		minutes->select(active % 60 / 5 + 1);
	};
	bool edit_start = true;
	refresh(t, edit_start);
	dialogs::show_modal(win.get());
	while (win->shown()) {
		Fl::wait();
		if (!act) {
			continue;
		}
		const TimeAct a = *act;
		act.reset();
		const std::int32_t active = edit_start ? t.start : t.end;
		// Puts `at` on the checked time. A new start moves the end; a new end before the start is refused.
		const auto place = [&](std::int32_t at) {
			if (edit_start) {
				const std::int32_t len = std::max(t.end - t.start, 0);
				t.start = at;
				t.end = std::min(at + len, 1439);
			} else if (at >= t.start) {
				t.end = at;
			} else {
				fl_beep();
			}
			t.untimed = false;
		};
		switch (a) {
		case TimeAct::Start:
			edit_start = true;
			break;
		case TimeAct::End:
			edit_start = false;
			break;
		case TimeAct::Hour:
			if (hours->value() > 0) {
				place((hours->value() - 1) * 60 + active % 60);
			}
			break;
		case TimeAct::Minute:
			if (minutes->value() > 0) {
				place(active / 60 * 60 + (minutes->value() - 1) * 5);
			}
			break;
		case TimeAct::NoTime:
			t.untimed = true;
			win->hide();
			return true;
		case TimeAct::Ok:
			win->hide();
			return true;
		}
		refresh(t, edit_start);
	}
	return false;
}

void purge_events(view::Ctx& ctx) {
	constexpr std::array<std::pair<const char*, std::int64_t>, 4> spans{{
	    {"1 Week", 7},
	    {"2 Weeks", 14},
	    {"3 Weeks", 21},
	    {"1 Month", 30},
	}};
	auto win = std::make_unique<Fl_Double_Window>(399, 141, "Purge Events");
	new QuestionBadge(11, 11, 32, 32);
	dialogs::key_label("&Delete events older than:", 102, 16, 120, 22, FL_ALIGN_LEFT);
	auto* span = new combo::Choice(234, 16, 72, 22);
	for (const auto& item : spans) {
		span->add(item.first);
	}
	span->value(0);
	draw::style_menu(span);
	auto* ok = dialogs::push_button(121, 107, "OK", true);
	ok->shortcut(FL_Enter);
	auto* cancel = dialogs::push_button(202, 107, "Cancel", false);
	PurgeAnswer answer{win.get(), false};
	ok->callback(purge_ok, &answer);
	cancel->callback([](Fl_Widget* w, void*) { w->top_window()->hide(); });
	win->end();
	dialogs::run_modal(win.get());
	if (!answer.go) {
		return;
	}
	const auto idx = static_cast<std::size_t>(std::max(span->value(), 0));
	const std::size_t n = ctx.store.purge_events(platform::today() - spans[idx].second);
	dialogs::message("Palm Desktop", dialogs::Icon::Info, std::format("{} events purged.", n));
}

} // namespace palm::event_dialog
