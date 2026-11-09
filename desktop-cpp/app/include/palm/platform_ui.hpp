// The FLTK-dependent platform services of the desktop app: the close box that FLTK leaves off a modal window, and the
// size and position of the main window from one session to the next.
#pragma once

#include <array>
#include <optional>
#include <string>
#include <string_view>

class Fl_Window;

namespace palm::platform_ui {

// Adds the close (X) box to a window that FLTK gave none. FLTK adds WS_SYSMENU only to a window that is not modal, and
// every dialog is modal, so a dialog has a title bar without the box. The style bit is set on the live window, and the
// frame is redrawn. No maximize or minimize box is added. Elsewhere the window manager draws the close box, so this
// does nothing.
void add_close_box(Fl_Window* win);

// A window's box: its position and size. On Windows it is the frame in device pixels and workspace coordinates, as
// WINDOWPLACEMENT gives it; on X11 it is the window in FLTK units, as Fl_Window gives it.
struct Box {
	int x = 0;
	int y = 0;
	int w = 0;
	int h = 0;

	bool operator==(const Box&) const = default;
};

// The box a window restores to when it is not maximized, and whether it is maximized. A minimized window counts by the
// state it restores to.
struct Placement {
	Box box;
	bool maximized = false;

	bool operator==(const Placement&) const = default;
};

// The settings text of a placement: "x,y,w,h,maximized", with maximized as 0 or 1.
std::string format_placement(const Placement& p);

// The placement that format_placement wrote, or none when the text is not five whole numbers, gives a box with no
// width or height, or ends in a field other than 0 or 1. The text comes from the settings file, which a person may
// edit.
std::optional<Placement> parse_placement(std::string_view text);

// The last two boxes of a window while it was not maximized. X11 keeps no box for a maximized window to restore to, so
// the main window notes each of its boxes here. A window manager may send the maximized size ere it sets the maximized
// state, so that size can be the newest box noted; the box to restore is the newest one other than the current box.
class BoxKeeper {
public:
	// Notes the window's box after a move or resize while it is not maximized.
	void note(const Box& box);

	// The box to restore a window to whose box is now `current`, or none when no other box was noted.
	std::optional<Box> restored(const Box& current) const;

private:
	std::array<std::optional<Box>, 2> boxes_;
};

// Shows the main window at `saved` and maximizes it when `saved` says so. With nothing saved, on Wayland (which gives a
// client no position), and on X11 when the saved box lies on no screen, it shows the window maximized.
void show_placed(Fl_Window* win, const std::optional<Placement>& saved);

// The placement of the shown main window, to save at exit: on Windows as Windows keeps it, on X11 from the window and
// `kept`. None on Wayland, or when the window has no handle.
std::optional<Placement> placement(Fl_Window* win, const BoxKeeper& kept);

} // namespace palm::platform_ui
