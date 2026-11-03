// The small modal dialogs of desktop-rs/app/src/dialogs.rs, on FLTK. Each one opens, runs its own event loop until it
// closes, and returns the answer. A dialog keeps its answer in a state struct that its window holds as user data, so a
// button callback (which gets only its widget) can reach it.
#include "palm/dialogs.hpp"

#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/platform_ui.hpp"
#include "palm/settings.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <functional>
#include <initializer_list>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::dialogs {
namespace {

// The text size of the dialogs, in pixels (MS Sans Serif 8 pt at the Windows display scale).
constexpr int TEXT_SIZE = 11;
// The size of a dialog button.
constexpr int BUTTON_W = 75;
constexpr int BUTTON_H = 23;
// The space between two buttons, and the margin at the edges of a message box.
constexpr int BUTTON_GAP = 6;
constexpr int MARGIN = 11;
// The message box icon, and the left edge and top of its text when there is an icon.
constexpr int ICON_SIZE = 32;
constexpr int TEXT_LEFT = 53;
constexpr int TEXT_TOP = 14;
// The widest and the narrowest message box, in pixels.
constexpr int BOX_MAX_W = 420;
constexpr int BOX_MIN_W = 230;
// The version that the About box shows: the version of desktop-rs/app/Cargo.toml, which the Rust build reads. The C++
// port has no version of its own yet.
constexpr std::string_view VERSION = "0.0.0";

// The main window, which the dialogs center over. Set by set_main at start-up.
thread_local Fl_Window* main_window = nullptr;

// The answer of an ask_icon box.
struct AskState {
	std::optional<std::size_t> answer;
};

// The answer of a prompt_titled box, and its text field.
struct PromptState {
	Fl_Input* input = nullptr;
	std::optional<std::string> answer;
};

// The answer of a list_by box, and its radios in the order of the choices.
struct ListState {
	std::vector<Fl_Round_Button*> radios;
	std::optional<std::size_t> answer;
};

// The 32 x 32 message box icon as a widget, drawn by draw_icon.
class IconBadge : public Fl_Box {
public:
	IconBadge(int x, int y, Icon kind) : Fl_Box(x, y, ICON_SIZE, ICON_SIZE), kind_(kind) {}

	void draw() override {
		Fl_Box::draw();
		draw_icon(kind_, x(), y());
	}

private:
	Icon kind_;
};

// The black frame behind the default button of push_button.
class Outline : public Fl_Box {
public:
	using Fl_Box::Fl_Box;

	void draw() override {
		Fl_Box::draw();
		fl_color(FL_BLACK);
		fl_rectf(x(), y(), w(), h());
	}
};

// Closes the window that holds `w`. A window with no parent is its own top window.
void hide_window(Fl_Widget* w, void*) {
	w->top_window()->hide();
}

// A message box button: the index of the button is its callback argument.
void ask_pressed(Fl_Widget* w, long index) {
	Fl_Window* win = w->top_window();
	static_cast<AskState*>(win->user_data())->answer = static_cast<std::size_t>(index);
	win->hide();
}

// The OK button of prompt_titled: the trimmed text, unless it is blank.
void prompt_ok(Fl_Widget* w, void*) {
	Fl_Window* win = w->top_window();
	auto* state = static_cast<PromptState*>(win->user_data());
	const std::string text(text::trim(state->input->value()));
	if (!text.empty()) {
		state->answer = text;
	}
	win->hide();
}

// The OK button of list_by: the index of the first radio that is set, if any.
void list_ok(Fl_Widget* w, void*) {
	Fl_Window* win = w->top_window();
	auto* state = static_cast<ListState*>(win->user_data());
	state->answer.reset();
	for (std::size_t i = 0; i < state->radios.size(); ++i) {
		if (state->radios[i]->value()) {
			state->answer = i;
			break;
		}
	}
	win->hide();
}

// The width of `text` in the current font, with no symbols drawn, as Rust's draw::measure(text, false) gives it.
int measure(std::string_view text) {
	int w = 0;
	int h = 0;
	const std::string s(text);
	fl_measure(s.c_str(), w, h, 0);
	return w;
}

// The lines of a message joined with newlines.
std::string join(const std::vector<std::string>& lines) {
	std::string out;
	for (std::size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) {
			out += '\n';
		}
		out += lines[i];
	}
	return out;
}

// The window that is shown and has the largest area, the last one of equal area as Rust's max_by_key gives. Null when
// no window is shown.
Fl_Window* largest_shown() {
	Fl_Window* best = nullptr;
	for (Fl_Window* w = Fl::first_window(); w; w = Fl::next_window(w)) {
		if (!w->shown()) {
			continue;
		}
		if (!best || w->w() * w->h() >= best->w() * best->h()) {
			best = w;
		}
	}
	return best;
}

} // namespace

void set_main(Fl_Window* win) {
	main_window = win;
}

void center(Fl_Window* win) {
	Fl_Window* anchor = main_window && main_window->shown() ? main_window : largest_shown();
	int ax = 0;
	int ay = 0;
	int aw = 0;
	int ah = 0;
	if (anchor) {
		ax = anchor->x();
		ay = anchor->y();
		aw = anchor->w();
		ah = anchor->h();
	} else {
		Fl::screen_work_area(ax, ay, aw, ah, Fl::screen_num(win->x(), win->y()));
	}
	int sx = 0;
	int sy = 0;
	int sw = 0;
	int sh = 0;
	Fl::screen_work_area(sx, sy, sw, sh, Fl::screen_num(ax + aw / 2, ay + ah / 2));
	const int x = std::clamp(ax + (aw - win->w()) / 2, sx, std::max(sx + sw - win->w(), sx));
	const int y = std::clamp(ay + (ah - win->h()) / 2, sy, std::max(sy + sh - win->h(), sy));
	win->position(x, y);
}

void show_modal(Fl_Window* win) {
	center(win);
	win->set_modal();
	win->show();
	platform_ui::add_close_box(win);
	// The labels of the new window hide their underlines until Alt, as the main window's do.
	mnemonics::refresh();
}

void run_modal(Fl_Window* win) {
	show_modal(win);
	while (win->shown()) {
		Fl::wait();
	}
	mnemonics::forget(win);
}

bool confirm_delete_titled(const settings::Settings& settings, bool no_confirm, std::string_view title,
                           std::string_view question) {
	if (no_confirm || !settings.get_bool("confirmDelete", true)) {
		return true;
	}
	return ask_icon(title, Icon::Question, question, {"OK", "Cancel"}, 0) == std::optional<std::size_t>(0);
}

void message(std::string_view title, Icon icon, std::string_view text) {
	ask_icon(title, icon, text, {"OK"}, 0);
}

std::optional<std::size_t> ask_icon(std::string_view title, Icon icon, std::string_view text,
                                    std::initializer_list<std::string_view> buttons, std::size_t default_button) {
	fl_font(FL_HELVETICA, TEXT_SIZE);
	const int left = icon == Icon::None ? MARGIN : TEXT_LEFT;
	const int max_text = BOX_MAX_W - left - MARGIN;
	const std::vector<std::string> lines = detail::wrap_lines(text, max_text, measure);
	int text_w = 0;
	for (const std::string& line : lines) {
		text_w = std::max(text_w, measure(line));
	}
	text_w = std::min(text_w, max_text);
	const int text_h = static_cast<int>(lines.size()) * fl_height();
	const int n = static_cast<int>(buttons.size());
	const int buttons_w = n * BUTTON_W + std::max(n - 1, 0) * BUTTON_GAP;
	const int w = detail::message_width(left, text_w, buttons_w);
	const int text_bottom = TEXT_TOP + text_h;
	const int content_bottom = icon == Icon::None ? text_bottom : std::max(text_bottom, MARGIN + ICON_SIZE);
	const int by = content_bottom + MARGIN;
	const int h = by + BUTTON_H + MARGIN;

	AskState state;
	Fl_Double_Window win(w, h);
	mnemonics::ForgetOnExit forget_win(win);
	win.copy_label(std::string(title).c_str());
	if (icon != Icon::None) {
		new IconBadge(MARGIN, MARGIN, icon);
	}
	auto* question = new Fl_Box(left, TEXT_TOP, text_w, text_h);
	question->copy_label(draw::escape_at(join(lines)).c_str());
	question->align(FL_ALIGN_LEFT | FL_ALIGN_TOP | FL_ALIGN_INSIDE);
	question->labelfont(FL_HELVETICA);
	question->labelsize(TEXT_SIZE);
	const int x0 = (w - buttons_w) / 2;
	std::size_t i = 0;
	for (const std::string_view label : buttons) {
		const bool is_default = i == default_button;
		Fl_Button* b = push_button(x0 + static_cast<int>(i) * (BUTTON_W + BUTTON_GAP), by, label, is_default);
		if (is_default) {
			b->shortcut(FL_Enter);
		}
		b->callback(ask_pressed, static_cast<long>(i));
		++i;
	}
	// Escape and the close box both run this callback, and it closes the box with no answer.
	win.callback(hide_window);
	win.user_data(&state);
	win.end();
	run_modal(&win);
	return state.answer;
}

std::optional<std::string> prompt_titled(std::string_view title, std::string_view label, std::string_view initial) {
	constexpr int w = 201;
	constexpr int h = 89;
	PromptState state;
	Fl_Double_Window win(w, h);
	mnemonics::ForgetOnExit forget_win(win);
	win.copy_label(std::string(title).c_str());
	auto* caption = new Fl_Box(MARGIN, 8, w - 2 * MARGIN, 14);
	caption->copy_label(draw::escape_at(label).c_str());
	caption->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	caption->labelfont(FL_HELVETICA);
	caption->labelsize(TEXT_SIZE);
	auto* input = new Fl_Input(MARGIN, 24, w - 2 * MARGIN, 20);
	const std::string value(initial);
	input->value(value.c_str());
	// The text is selected, with the insert point at its end.
	input->insert_position(static_cast<int>(value.size()), 0);
	input->take_focus();
	state.input = input;
	const int by = 58;
	Fl_Button* ok = push_button(w - MARGIN - 2 * BUTTON_W - BUTTON_GAP, by, "OK", true);
	ok->shortcut(FL_Enter);
	ok->callback(prompt_ok);
	Fl_Button* cancel = push_button(w - MARGIN - BUTTON_W, by, "Cancel", false);
	cancel->callback(hide_window);
	win.callback(hide_window);
	win.user_data(&state);
	win.end();
	run_modal(&win);
	return state.answer;
}

void about() {
	constexpr int w = 280;
	constexpr int h = 110;
	const auto logo = theme::icon("app48");
	Fl_Double_Window win(w, h);
	mnemonics::ForgetOnExit forget_win(win);
	win.copy_label("About Palm Desktop");
	auto* image = new Fl_Box(MARGIN, MARGIN, logo->w(), logo->h());
	image->image(logo.get());
	auto* name = new Fl_Box(72, MARGIN, w - 72 - MARGIN, 20);
	name->copy_label("Palm Desktop");
	name->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	name->labelfont(theme::fonts().sans_bold);
	name->labelsize(14);
	auto* version = new Fl_Box(72, 36, w - 72 - MARGIN, 16);
	version->copy_label((std::string("Version ") + std::string(VERSION)).c_str());
	version->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	version->labelfont(FL_HELVETICA);
	version->labelsize(TEXT_SIZE);
	Fl_Button* ok = push_button(w - MARGIN - BUTTON_W, h - MARGIN - BUTTON_H, "OK", true);
	ok->shortcut(FL_Enter);
	ok->callback(hide_window);
	win.callback(hide_window);
	win.end();
	run_modal(&win);
}

Fl_Box* group_title(int x, int y, std::string_view text) {
	fl_font(FL_HELVETICA, TEXT_SIZE);
	const int title_w = measure(text) + 4;
	auto* title = new Fl_Box(x + 8, y - 7, title_w, 14);
	title->copy_label(draw::escape_at(text).c_str());
	title->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	title->labelfont(FL_HELVETICA);
	title->labelsize(TEXT_SIZE);
	title->box(FL_FLAT_BOX);
	title->color(theme::PANEL);
	return title;
}

Fl_Button* key_label(std::string_view text, int x, int y, int w, int h, Fl_Align align) {
	auto* b = new Fl_Button(x, y, w, h);
	b->copy_label(std::string(text).c_str());
	b->box(FL_NO_BOX);
	b->down_box(FL_NO_BOX);
	b->align(align | FL_ALIGN_INSIDE);
	b->clear_visible_focus();
	return b;
}

std::optional<std::size_t> list_by(std::string_view heading, std::initializer_list<std::string_view> choices,
                                   std::size_t selected) {
	const int n = static_cast<int>(choices.size());
	const int w = 198;
	const int h = 94 + 22 * n;
	ListState state;
	Fl_Double_Window win(w, h);
	mnemonics::ForgetOnExit forget_win(win);
	win.copy_label("List By");
	// The group box: its top edge at y 18, the title drawn over that edge, the radios 14 px below it.
	const int gx = 11;
	const int gy = 18;
	const int gw = w - 22;
	const int gh = 22 * n + 30;
	auto* group = new Fl_Group(gx, gy, gw, gh);
	group->box(FL_ENGRAVED_BOX);
	std::size_t i = 0;
	for (const std::string_view c : choices) {
		Fl_Round_Button* r = draw::radio(gx + 9, gy + 14 + 22 * static_cast<int>(i), gw - 18, 20, c);
		r->value(i == selected);
		state.radios.push_back(r);
		++i;
	}
	group->end();
	group_title(gx, gy, heading);
	const int by = h - MARGIN - BUTTON_H;
	const int cancel_x = w - MARGIN - BUTTON_W;
	Fl_Button* ok = push_button(cancel_x - BUTTON_GAP - BUTTON_W, by, "OK", true);
	ok->shortcut(FL_Enter);
	ok->callback(list_ok);
	Fl_Button* cancel = push_button(cancel_x, by, "Cancel", false);
	cancel->callback(hide_window);
	win.callback(hide_window);
	win.user_data(&state);
	win.end();
	run_modal(&win);
	return state.answer;
}

Fl_Button* push_button(int x, int y, std::string_view label, bool is_default) {
	const int inset = is_default ? 1 : 0;
	if (is_default) {
		new Outline(x, y, BUTTON_W, BUTTON_H);
	}
	auto* b = new Fl_Button(x + inset, y + inset, BUTTON_W - 2 * inset, BUTTON_H - 2 * inset);
	b->copy_label(std::string(label).c_str());
	if (is_default) {
		b->take_focus();
	} else {
		b->clear_visible_focus();
	}
	return b;
}

namespace {

// A filled disc that fills the icon box at (x, y).
void disc(int x, int y, Fl_Color color) {
	fl_color(color);
	fl_pie(x, y, ICON_SIZE, ICON_SIZE, 0.0, 360.0);
}

// A bold glyph centered in the 32 x 32 icon box at (x, y), in the top 28 px of it.
void glyph(int x, int y, const char* text, Fl_Color color) {
	fl_color(color);
	fl_font(FL_HELVETICA_BOLD, 22);
	fl_draw(text, x, y, ICON_SIZE, ICON_SIZE - 4, FL_ALIGN_CENTER);
}

} // namespace

void draw_icon(Icon icon, int x, int y) {
	const Fl_Color blue = fl_rgb_color(0, 120, 215);
	switch (icon) {
	case Icon::None:
		break;
	case Icon::Question:
		disc(x, y, blue);
		glyph(x, y, "?", FL_WHITE);
		break;
	case Icon::Info:
		disc(x, y, blue);
		glyph(x, y, "i", FL_WHITE);
		break;
	case Icon::Warning: {
		const std::array<std::pair<int, int>, 3> points{{{x + 16, y + 1}, {x + 31, y + 30}, {x + 1, y + 30}}};
		fl_color(fl_rgb_color(255, 200, 0));
		fl_begin_polygon();
		for (const auto& [px, py] : points) {
			fl_vertex(px, py);
		}
		fl_end_polygon();
		fl_color(FL_BLACK);
		fl_begin_loop();
		for (const auto& [px, py] : points) {
			fl_vertex(px, py);
		}
		fl_end_loop();
		glyph(x, y + 4, "!", FL_BLACK);
		break;
	}
	}
}

namespace detail {

std::vector<std::string> wrap_lines(std::string_view text, int max_w,
                                    const std::function<int(std::string_view)>& measure) {
	std::vector<std::string> out;
	for (const std::string_view para : text::split(text, '\n')) {
		std::string line;
		for (const std::string_view word : text::split(para, ' ')) {
			std::string candidate = line.empty() ? std::string(word) : line + " " + std::string(word);
			if (line.empty() || measure(candidate) <= max_w) {
				line = std::move(candidate);
			} else {
				out.push_back(std::exchange(line, std::string(word)));
			}
		}
		out.push_back(line);
	}
	return out;
}

int message_width(int left, int text_w, int buttons_w) {
	return std::clamp(std::max(left + text_w + MARGIN, buttons_w + 2 * MARGIN), BOX_MIN_W, BOX_MAX_W);
}

} // namespace detail

} // namespace palm::dialogs
