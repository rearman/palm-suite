// The offscreen capture of `--shot`, for the screenshots of each phase of the port, and the steps of `--shot-open`,
// which open menus, lists and dialogs before the capture.
//
// A step posts mouse events to the app's own window, as the OS would deliver a click, so the menu or dialog opens
// through its real handler. It sends no OS input: the real pointer stays where it is. The posting needs Win32 or X11;
// on Linux the shot run forces X11 (`FLTK_BACKEND=x11`), since Wayland takes no posted events. The run drops all real
// keyboard and mouse input, so the user's pointer cannot disturb it.
#pragma once

#include <cstdint>
#include <expected>
#include <filesystem>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

class Fl_Window;

namespace palm::shot {

// A left press and release at a point in logical pixels from the top left of the main window.
struct Click {
	int x;
	int y;
	bool operator==(const Click&) const = default;
};

// A right press and release.
struct RightClick {
	int x;
	int y;
	bool operator==(const RightClick&) const = default;
};

// Two left clicks at one point, a double click.
struct DoubleClick {
	int x;
	int y;
	bool operator==(const DoubleClick&) const = default;
};

// The pointer moves to the point, as a hover.
struct Move {
	int x;
	int y;
	bool operator==(const Move&) const = default;
};

// One step of `--shot-open`: a mouse event at a point in logical pixels from the top left of the main window.
using Step = std::variant<Click, RightClick, DoubleClick, Move>;

// Reads the steps of `--shot-open`: `click:X,Y`, `rclick:X,Y`, `dclick:X,Y` and `move:X,Y`, separated by `;`.
std::expected<std::vector<Step>, std::string> parse_steps(std::string_view text);

// Draws `main` and the windows shown over it (dialogs, menus, lists) offscreen at the display scale, each at its place
// relative to `main`, on a black ground that covers them all, and writes it to `path` as a binary PPM. The error is
// the OS error text when the file cannot be written.
std::expected<void, std::string> save(Fl_Window& main, const std::filesystem::path& path);

// Ends the program at once with `code`, from inside an FLTK callback. It runs no exit handler: FLTK's crash when they
// run inside its event loop (an access violation on Windows). The OS frees the store lock.
[[noreturn]] void exit(int code);

// The device pixel that FLTK reads back as logical pixel `v` at scale `s`: FLTK truncates `device / s`.
int device(int v, float s);

// Posts the mouse events of `step` to the window that takes them: the shown window that holds the pointer grab (a
// menu), else the modal one (a dialog), else `main`. The grab of a menu bar's menu is a window that is never shown;
// FLTK passes events from any window of the app to it. `time` is the event time in ms, for the click counts of X11.
void post(Fl_Window& main, const Step& step, std::uint32_t time);

// Makes the shot run drop all real keyboard and mouse input, so only the posted steps reach it. A menu holds the
// pointer, so without this, the user's mouse anywhere on the desktop would move its highlight or close it.
void ignore_real_input();

} // namespace palm::shot
