// Device-pixel drawing and the Windows classic edges, as desktop-rs/app/src/draw.rs.
#include "palm/draw.hpp"

#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Menu_.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Widget.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cmath>
#include <vector>

namespace palm::draw {
namespace {

// The logical clips of the open Clip guards, innermost last.
std::vector<Rect>& clips() {
	static thread_local std::vector<Rect> stack;
	return stack;
}

// A logical coordinate in device pixels at scale `s`.
int device(float s, int v) {
	return static_cast<int>(std::lround(static_cast<float>(v) * s));
}

// A logical rectangle in device pixels at scale `s`.
Rect device_rect(float s, int x, int y, int w, int h) {
	const int x1 = device(s, x);
	const int y1 = device(s, y);
	return {x1, y1, device(s, x + w) - x1, device(s, y + h) - y1};
}

// Draws rings of a box from the outside in, in device pixels: each ring has its top-left and its bottom-right
// color. Then fills the inside with `fill` when it is set.
void rings(int x, int y, int w, int h, std::initializer_list<std::pair<Fl_Color, Fl_Color>> edges,
           std::optional<Fl_Color> fill) {
	const Dev d = Dev::begin();
	const Rect r = d.rect(x, y, w, h);
	x = r.x;
	y = r.y;
	w = r.w;
	h = r.h;
	const int k = d.lw();
	for (const auto& [top_left, bottom_right] : edges) {
		if (w <= 0 || h <= 0) {
			return;
		}
		fl_color(bottom_right);
		fl_rectf(x, y + h - k, w, k);
		fl_rectf(x + w - k, y, k, h);
		fl_color(top_left);
		fl_rectf(x, y, w - k, k);
		fl_rectf(x, y, k, h - k);
		x += k;
		y += k;
		w -= 2 * k;
		h -= 2 * k;
	}
	if (fill && w > 0 && h > 0) {
		fl_rectf(x, y, w, h, *fill);
	}
}

// A raised button or panel. FLTK passes the fill color, already grayed for an inactive widget.
void up_box(int x, int y, int w, int h, Fl_Color c) {
	rings(x, y, w, h, {{HIGHLIGHT, DARK_SHADOW}, {LIGHT, SHADOW}}, c);
}

void up_frame(int x, int y, int w, int h, Fl_Color) {
	rings(x, y, w, h, {{HIGHLIGHT, DARK_SHADOW}, {LIGHT, SHADOW}}, std::nullopt);
}

void down_box(int x, int y, int w, int h, Fl_Color c) {
	rings(x, y, w, h, {{SHADOW, HIGHLIGHT}, {DARK_SHADOW, LIGHT}}, c);
}

void down_frame(int x, int y, int w, int h, Fl_Color) {
	rings(x, y, w, h, {{SHADOW, HIGHLIGHT}, {DARK_SHADOW, LIGHT}}, std::nullopt);
}

void thin_up_box(int x, int y, int w, int h, Fl_Color c) {
	rings(x, y, w, h, {{HIGHLIGHT, SHADOW}}, c);
}

void thin_down_box(int x, int y, int w, int h, Fl_Color c) {
	rings(x, y, w, h, {{SHADOW, HIGHLIGHT}}, c);
}

// The etched groove of a status bar or a group: a shadow line, then a highlight line inside it.
void engraved_box(int x, int y, int w, int h, Fl_Color c) {
	rings(x, y, w, h, {{SHADOW, HIGHLIGHT}, {HIGHLIGHT, SHADOW}}, c);
}

// The popup frame of a Windows menu: a 1 px gray border around a white face. FLTK passes the menu's own color,
// which is gray in a scheme, so the face is white here.
void menu_box(int x, int y, int w, int h, Fl_Color) {
	rings(x, y, w, h, {{SHADOW, SHADOW}}, FL_WHITE);
}

// The ink of a widget: black, or the gray SHADOW when the widget is inactive.
Fl_Color ink(bool active) {
	return active ? FL_BLACK : SHADOW;
}

// The side of a check box and a radio button in logical pixels, as Windows draws them at 100%.
constexpr int BOX = 13;
constexpr int RADIO = 12;

// The text of a widget label, empty when the label is unset.
std::string_view label_of(const Fl_Widget* w) {
	return w->label() ? std::string_view(w->label()) : std::string_view();
}

// Draws `text` with FLTK's fl_draw, as Rust's draw::draw_text, which skips a one-byte text at size -1.
void draw_text(std::string_view text, int x, int y) {
	if (fl_size() == -1 && text.size() == 1) {
		return;
	}
	const std::string s(text);
	fl_draw(s.c_str(), x, y);
}

// Draws `text` on one line with its center at `cy`, the accelerator letter underlined. The baseline comes from the
// font height, so the line is centered on `cy`.
void label_at(std::string_view text, Fl_Font font, int size, int x, int cy, Fl_Color c) {
	const auto [plain, accel] = accel_split(text);
	fl_font(font, size);
	const int h = fl_height();
	const int base = cy - h / 2 + h - fl_descent();
	fl_color(c);
	draw_text(plain, x, base);
	if (!accel) {
		return;
	}
	const std::size_t i = *accel;
	const std::string_view view(plain);
	// The accelerator letter is one code point; "&" is ASCII, so the bytes after it end at the next code point.
	const std::size_t len = i < view.size() ? palm::text::decode_at(view, i).len : 0;
	const int before = text_w(view.substr(0, i));
	const int under = text_w(view.substr(i, len));
	const Dev d = Dev::begin();
	const Rect u = d.rect(x + before, base + 1, under, 0);
	fl_rectf(u.x, u.y, u.w, d.lw());
}

// The check mark at (x, y), its 7x7 box, in device pixels.
void tick(int x, int y, Fl_Color c) {
	const Dev d = Dev::begin();
	fl_color(c);
	for (const auto& [dx, dy, w, h] : tick_rects()) {
		const Rect r = d.rect(x + dx, y + dy, w, h);
		fl_rectf(r.x, r.y, r.w, r.h);
	}
}

// The sunken 13x13 box of a check button, then the mark and the label.
void draw_check(Fl_Check_Button* b) {
	const int x = b->x();
	const int y = b->y();
	const int h = b->h();
	const bool on = b->active();
	const int by = y + (h - BOX) / 2;
	const Fl_Color fill = on ? FL_WHITE : theme::PANEL;
	down_box(x, by, BOX, BOX, fill);
	if (b->value()) {
		tick(x + 3, by + 3, ink(on));
	}
	label_at(label_of(b), b->labelfont(), b->labelsize(), x + 17, y + h / 2, ink(on));
}

// A disk in device pixels, its top-left half (45 to 225 degrees) in `top_left`, its bottom-right half in
// `bottom_right`.
void half_disk(int x, int y, int w, int h, Fl_Color top_left, Fl_Color bottom_right) {
	fl_color(top_left);
	fl_pie(x, y, w, h, 45.0, 225.0);
	fl_color(bottom_right);
	fl_pie(x, y, w, h, 225.0, 405.0);
}

// The two-tone ring of a radio button at (x, y), 12 px wide, and its white face. The outer ring splits on the
// diagonal: SHADOW on the top-left half, HIGHLIGHT on the bottom-right half; the inner ring uses DARK_SHADOW and
// LIGHT.
void radio_box(int x, int y, Fl_Color fill) {
	const Dev d = Dev::begin();
	const Rect r = d.rect(x, y, RADIO, RADIO);
	const int k = d.lw();
	half_disk(r.x, r.y, r.w, r.h, SHADOW, HIGHLIGHT);
	half_disk(r.x + k, r.y + k, r.w - 2 * k, r.h - 2 * k, DARK_SHADOW, LIGHT);
	fl_color(fill);
	fl_pie(r.x + 2 * k, r.y + 2 * k, r.w - 4 * k, r.h - 4 * k, 0.0, 360.0);
}

// The 4x4 dot of a set radio button, centered in its 12 px box.
void radio_dot(int x, int y, Fl_Color c) {
	const Dev d = Dev::begin();
	const Rect r = d.rect(x + 4, y + 4, 4, 4);
	fl_color(c);
	fl_pie(r.x, r.y, r.w, r.h, 0.0, 360.0);
}

// The radio ring, the dot when set, and the label.
void draw_radio(Fl_Round_Button* b) {
	const int x = b->x();
	const int y = b->y();
	const int h = b->h();
	const bool on = b->active();
	const int by = y + (h - RADIO) / 2;
	const Fl_Color fill = on ? FL_WHITE : theme::PANEL;
	radio_box(x, by, fill);
	if (b->value()) {
		radio_dot(x, by, ink(on));
	}
	label_at(label_of(b), b->labelfont(), b->labelsize(), x + 17, y + h / 2, ink(on));
}

// Draws the arrow in its 4x7 box at (x, y), in device pixels.
void arrow_glyph(int x, int y, bool right, Fl_Color c) {
	const Dev d = Dev::begin();
	fl_color(c);
	for (const auto& [dx, dy, len] : arrow_rows(right)) {
		const Rect r = d.rect(x + dx, y + dy, len, 1);
		fl_rectf(r.x, r.y, r.w, r.h);
	}
}

// The raised push button with a black triangle centered in it, pointing right or left. The triangle is gray when
// the button is inactive. The button is drawn down while it is pressed.
void draw_arrow(Fl_Button* b, bool right) {
	const int x = b->x();
	const int y = b->y();
	const int w = b->w();
	const int h = b->h();
	const Fl_Boxtype frame = b->value() ? FL_DOWN_BOX : FL_UP_BOX;
	fl_draw_box(frame, x, y, w, h, b->color());
	arrow_glyph(x + (w - 4) / 2, y + (h - 7) / 2, right, ink(b->active()));
}

// The check box whose draw is the Windows classic check.
class CheckBox : public Fl_Check_Button {
public:
	using Fl_Check_Button::Fl_Check_Button;

	void draw() override { draw_check(this); }
};

// The radio button whose draw is the Windows classic radio.
class RadioButton : public Fl_Round_Button {
public:
	using Fl_Round_Button::Fl_Round_Button;

	void draw() override { draw_radio(this); }
};

// The arrow push button, drawn as its triangle.
class ArrowButton : public Fl_Button {
public:
	ArrowButton(int x, int y, int w, int h, bool right) : Fl_Button(x, y, w, h, nullptr), right_(right) {}

	void draw() override { draw_arrow(this, right_); }

private:
	bool right_;
};

} // namespace

Clip::Clip(int x, int y, int w, int h) {
	fl_push_clip(x, y, w, h);
	clips().push_back({x, y, w, h});
}

Clip::~Clip() {
	clips().pop_back();
	fl_pop_clip();
}

Clip Clip::cell(const Fl_Widget* t, int x, int y, int w, int h) {
	const int tx = t->x() + 2;
	const int ty = t->y() + 2;
	const int tr = t->x() + t->w() - 2;
	const int tb = t->y() + t->h() - 2;
	const int x0 = std::max(x, tx);
	const int y0 = std::max(y, ty);
	const int x1 = std::min(x + w, tr);
	const int y1 = std::min(y + h, tb);
	return Clip(x0, y0, std::max(x1 - x0, 0), std::max(y1 - y0, 0));
}

Dev::Dev(float scale, float saved, int pushed) : s(scale), saved_(saved), pushed_(pushed) {}

Dev Dev::begin() {
	const float saved = fl_override_scale();
	// The Cairo driver (Linux) applies the override only on the screen. On an offscreen or print surface a second
	// call still returns the scale; then draw in logical pixels and let Cairo scale.
	const float s = fl_override_scale() == 1.0f ? saved : 1.0f;
	if (s == 1.0f) {
		return Dev(s, saved, 0);
	}
	// No clip in logical pixels may stay on top while the scale is 1: a push or a pop inside would select it at the
	// wrong size.
	fl_push_no_clip();
	if (clips().empty()) {
		return Dev(s, saved, 1);
	}
	const Rect& c = clips().back();
	const Rect r = device_rect(s, c.x, c.y, c.w, c.h);
	fl_push_clip(r.x, r.y, r.w, r.h);
	return Dev(s, saved, 2);
}

Dev::~Dev() {
	for (int i = 0; i < pushed_; ++i) {
		fl_pop_clip();
	}
	fl_restore_scale(saved_);
	// The pops selected the logical clip at scale 1; select it again at the scale.
	if (pushed_ > 0) {
		fl_restore_clip();
	}
}

int Dev::p(int v) const {
	return device(s, v);
}

int Dev::lw() const {
	return std::max(static_cast<int>(std::floor(s)), 1);
}

Rect Dev::rect(int x, int y, int w, int h) const {
	return device_rect(s, x, y, w, h);
}

int text_w(std::string_view s) {
	if (s.empty()) {
		return 0;
	}
	return static_cast<int>(std::ceil(fl_width(s.data(), static_cast<int>(s.size()))));
}

std::string fit(std::string_view text, int w, const std::function<int(std::string_view)>& measure) {
	if (measure(text) <= w) {
		return std::string(text);
	}
	// The byte offset of each code point, so a cut falls between code points as Rust's chars() does.
	std::vector<std::size_t> starts;
	for (std::size_t i = 0; i < text.size();) {
		starts.push_back(i);
		i += palm::text::decode_at(text, i).len;
	}
	std::size_t n = starts.size();
	while (true) {
		const std::size_t end = n < starts.size() ? starts[n] : text.size();
		std::string cut = std::string(text.substr(0, end)) + "...";
		if (n == 0 || measure(cut) <= w) {
			return cut;
		}
		--n;
	}
}

std::string escape_at(std::string_view text) {
	std::string out;
	for (const char c : text) {
		out += c;
		if (c == '@') {
			out += '@';
		}
	}
	return out;
}

std::string escape_label(std::string_view s) {
	std::string out;
	for (const char c : s) {
		if (c == '&' || c == '@') {
			out.push_back(c);
		}
		out.push_back(c);
	}
	return out;
}

void install_classic_boxes() {
	Fl::set_boxtype(FL_UP_BOX, up_box, 2, 2, 4, 4);
	Fl::set_boxtype(FL_UP_FRAME, up_frame, 2, 2, 4, 4);
	Fl::set_boxtype(FL_DOWN_BOX, down_box, 2, 2, 4, 4);
	Fl::set_boxtype(FL_DOWN_FRAME, down_frame, 2, 2, 4, 4);
	Fl::set_boxtype(FL_THIN_UP_BOX, thin_up_box, 1, 1, 2, 2);
	Fl::set_boxtype(FL_THIN_DOWN_BOX, thin_down_box, 1, 1, 2, 2);
	Fl::set_boxtype(FL_ENGRAVED_BOX, engraved_box, 2, 2, 4, 4);
	Fl::set_boxtype(MENU_FRAME, menu_box, 1, 1, 2, 2);
}

void style_menu(Fl_Menu_* m) {
	m->menu_box(MENU_FRAME);
	m->textfont(FL_HELVETICA);
	m->textsize(11);
	m->textcolor(FL_BLACK);
	m->selection_color(theme::SELECT);
}

Fl_Check_Button* check(int x, int y, int w, int h, std::string_view label) {
	auto* b = new CheckBox(x, y, w, h, nullptr);
	b->copy_label(std::string(label).c_str());
	b->box(FL_NO_BOX);
	b->clear_visible_focus();
	return b;
}

Fl_Round_Button* radio(int x, int y, int w, int h, std::string_view label) {
	auto* b = new RadioButton(x, y, w, h, nullptr);
	b->copy_label(std::string(label).c_str());
	b->type(FL_RADIO_BUTTON);
	b->box(FL_NO_BOX);
	b->clear_visible_focus();
	return b;
}

Fl_Button* arrow(int x, int y, int w, int h, bool right) {
	auto* b = new ArrowButton(x, y, w, h, right);
	b->box(FL_UP_BOX);
	b->clear_visible_focus();
	return b;
}

std::pair<std::string, std::optional<std::size_t>> accel_split(std::string_view label) {
	// '&' is ASCII, so a multi-byte code point never holds one; the bytes copy through unchanged.
	std::string text;
	std::optional<std::size_t> accel;
	for (std::size_t i = 0; i < label.size(); ++i) {
		const char c = label[i];
		if (c != '&') {
			text.push_back(c);
		} else if (i + 1 < label.size() && label[i + 1] == '&') {
			++i;
			text.push_back('&');
		} else if (!accel && i + 1 < label.size()) {
			accel = text.size();
		}
	}
	return {text, accel};
}

std::array<std::tuple<int, int, int, int>, 7> tick_rects() {
	// The row of the mark in each of the 7 columns.
	constexpr std::array<int, 7> rows{3, 4, 5, 4, 3, 2, 1};
	std::array<std::tuple<int, int, int, int>, 7> runs{};
	for (std::size_t c = 0; c < rows.size(); ++c) {
		runs[c] = {static_cast<int>(c), rows[c] - 1, 1, 3};
	}
	return runs;
}

std::array<std::tuple<int, int, int>, 7> arrow_rows(bool right) {
	constexpr std::array<int, 7> lengths{1, 2, 3, 4, 3, 2, 1};
	std::array<std::tuple<int, int, int>, 7> rows{};
	for (std::size_t r = 0; r < lengths.size(); ++r) {
		const int len = lengths[r];
		const int dx = right ? 0 : 4 - len;
		rows[r] = {dx, static_cast<int>(r), len};
	}
	return rows;
}

} // namespace palm::draw
