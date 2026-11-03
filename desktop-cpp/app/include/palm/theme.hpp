// The Palm Desktop look, as desktop-rs/app/src/theme.rs: the Windows classic colors, the Palm Desktop fonts and the
// 3.1.1 icons. The fonts fall back to Liberation Sans, which has the widths of Arial, where the Windows fonts are
// missing.
#pragma once

#include <FL/Enumerations.H>

#include <memory>
#include <string_view>

class Fl_PNG_Image;
class Fl_RGB_Image;

namespace palm::theme {

// The Windows 3D face gray that Swing's classic look reads on Windows 10 and 11.
inline const Fl_Color PANEL = fl_rgb_color(240, 240, 240);
// The 3D shadow gray of a raised cell.
inline const Fl_Color SHADOW = fl_rgb_color(160, 160, 160);
// The Windows application workspace gray around the Date view, as in Palm Desktop.
inline const Fl_Color WORKSPACE = fl_rgb_color(171, 171, 171);
inline const Fl_Color SELECT = fl_rgb_color(0, 120, 215);

// The height of a menu item in device-independent pixels, as a Windows 11 menu item.
inline constexpr int MENU_ITEM_H = 22;

// The Palm Desktop fonts by role. FL_HELVETICA is Microsoft Sans Serif, the dialog font, so every widget without its
// own font uses it.
struct Fonts {
	// Microsoft Sans Serif, the dialog font (8 pt, 11 px).
	Fl_Font sans;
	Fl_Font sans_bold;
	// Arial, the view, title, and caption font.
	Fl_Font arial;
	Fl_Font arial_bold;
	// Segoe UI, the launcher font.
	Fl_Font segoe;
};

// The fonts of install(). Throws std::runtime_error when install() has not run.
const Fonts& fonts();

// Sets the colors and the fonts. Call it once, after the app starts and before any window is built.
void install();

// The Palm Desktop 3.1.1 bitmap `name`, an icon of icons/<name>.png. Throws std::runtime_error for an unknown name.
std::unique_ptr<Fl_PNG_Image> icon(std::string_view name);

// The inactive form of an icon, as Swing's GrayFilter makes it for a disabled button: the gray value of each pixel,
// brightened halfway to white, with its alpha kept.
std::unique_ptr<Fl_RGB_Image> gray_icon(std::string_view name);

} // namespace palm::theme
