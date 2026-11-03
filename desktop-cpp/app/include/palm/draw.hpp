// Device-pixel drawing and the Windows classic edges, as desktop-rs/app/src/draw.rs. FLTK maps each logical pixel
// to 1 or 2 device pixels at 125% and 150%, so its 1 px lines come out uneven. While a Dev lives, FLTK draws at
// scale 1: positions are logical values times the scale, rounded, and each Palm line is a whole number of device
// pixels wide.
#pragma once

#include <FL/Enumerations.H>

#include <array>
#include <cstddef>
#include <functional>
#include <initializer_list>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>

class Fl_Button;
class Fl_Check_Button;
class Fl_Menu_;
class Fl_Round_Button;
class Fl_Widget;

namespace palm::draw {

// A rectangle: x, y, w, h.
struct Rect {
	int x;
	int y;
	int w;
	int h;
};

// The Windows system colors that Swing's classic look reads on Windows 10 and 11.
inline const Fl_Color HIGHLIGHT = fl_rgb_color(255, 255, 255);
inline const Fl_Color LIGHT = fl_rgb_color(227, 227, 227);
inline const Fl_Color SHADOW = fl_rgb_color(160, 160, 160);
inline const Fl_Color DARK_SHADOW = fl_rgb_color(105, 105, 105);

// A clip in logical pixels for as long as the guard lives. FLTK keeps its clips in logical pixels and selects them
// at the scale of the moment of a push or a pop, so under the override of a Dev they come out at scale 1. A Dev
// therefore clips to the innermost Clip in device pixels of its own.
class Clip {
public:
	Clip(int x, int y, int w, int h);
	~Clip();
	Clip(const Clip&) = delete;
	Clip& operator=(const Clip&) = delete;

	// A clip of a table cell (x, y, w, h), cut to the inside of the edge of its table `t`: the table clips its
	// cells, and a Dev does not see that clip.
	static Clip cell(const Fl_Widget* t, int x, int y, int w, int h);
};

// Device-pixel drawing for custom views and edges.
class Dev {
public:
	// Device pixels per logical pixel for the coordinates: the display scale, or 1 where the override has no
	// effect.
	float s;

	// The scale of the coordinates, the scale to restore at the end, and the clips pushed in device pixels.
	static Dev begin();
	~Dev();
	Dev(const Dev&) = delete;
	Dev& operator=(const Dev&) = delete;

	// A logical coordinate in device pixels.
	int p(int v) const;

	// The width of a 1 px Palm line, in device pixels: 1 below scale 2, then whole multiples.
	int lw() const;

	// A logical rectangle in device pixels.
	Rect rect(int x, int y, int w, int h) const;

private:
	Dev(float scale, float saved, int pushed);

	float saved_;
	int pushed_;
};

// The width of `s` in the current font, in whole pixels.
int text_w(std::string_view s);

// `text` with each '@' doubled, as a FLTK label or fl_draw takes it.
std::string escape_at(std::string_view text);

// `s` with each '&' and each '@' doubled, so that FLTK shows the characters of a name as typed.
std::string escape_label(std::string_view s);

// `text` cut to `w` px with "..." at its end, as Palm Desktop cuts a list column. `measure` gives the width of a
// text in pixels.
std::string fit(std::string_view text, int w, const std::function<int(std::string_view)>& measure);

// Replaces FLTK's raised, sunken, and etched boxes with the Windows classic edges. The edge widths stay 2 px (1 px
// for the thin boxes), so the widget insets FLTK computes are unchanged.
void install_classic_boxes();

// The popup frame of a menu box type: FL_FREE_BOXTYPE, the first unused index in FLTK 1.4.
inline constexpr Fl_Boxtype MENU_FRAME = FL_FREE_BOXTYPE;

// Makes the popup of a menu, choice or menu button look like a Windows menu: the bordered white popup, Helvetica
// (Microsoft Sans Serif) 11 px black text and the Windows selection blue.
void style_menu(Fl_Menu_* m);

// A check box at (x, y) with `label`: a 13x13 sunken box centered on the height of the widget, a 7x7 check mark
// when set, and the label 17 px from the left. The default drawing is replaced, so no focus rectangle is drawn.
// The caller owns the widget, as a group owns its children.
Fl_Check_Button* check(int x, int y, int w, int h, std::string_view label);

// A round radio button at (x, y) with `label`, drawn as the Windows classic radio.
Fl_Round_Button* radio(int x, int y, int w, int h, std::string_view label);

// A push button at (x, y) with a triangle arrow, replacing an FLTK "@>" or "@<" glyph button.
Fl_Button* arrow(int x, int y, int w, int h, bool right);

// The accelerator split of a label: the text with each "&&" as one "&", and the byte index in that text of the
// letter after the first lone "&", or no index when the label has no accelerator.
std::pair<std::string, std::optional<std::size_t>> accel_split(std::string_view label);

// The 7x7 check mark as pixel runs (x, y, w, h) in its box.
std::array<std::tuple<int, int, int, int>, 7> tick_rects();

// The pixel rows of a 4 px wide, 7 px tall triangle, as (x offset, y, length) in its box.
std::array<std::tuple<int, int, int>, 7> arrow_rows(bool right);

} // namespace palm::draw
