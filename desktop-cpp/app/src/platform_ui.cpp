#include "palm/platform_ui.hpp"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

#include "palm/text.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Window.H>
#include <FL/platform.H>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <format>
#include <limits>
#include <vector>

namespace palm::platform_ui {

std::string format_placement(const Placement& p) {
	return std::format("{},{},{},{},{}", p.box.x, p.box.y, p.box.w, p.box.h, p.maximized ? 1 : 0);
}

std::optional<Placement> parse_placement(std::string_view text) {
	const std::vector<std::string_view> parts = text::split(text, ',');
	if (parts.size() != 5) {
		return std::nullopt;
	}
	std::array<int, 5> n{};
	for (std::size_t i = 0; i < n.size(); ++i) {
		const std::optional<int> v = text::parse_i32(parts[i]);
		if (!v) {
			return std::nullopt;
		}
		n[i] = *v;
	}
	const Box box{n[0], n[1], n[2], n[3]};
	// The far edges must fit an int too, as a Windows RECT holds them.
	constexpr std::int64_t max = std::numeric_limits<int>::max();
	if (box.w <= 0 || box.h <= 0 || std::int64_t{box.x} + box.w > max || std::int64_t{box.y} + box.h > max ||
	    (n[4] != 0 && n[4] != 1)) {
		return std::nullopt;
	}
	return Placement{box, n[4] == 1};
}

void BoxKeeper::note(const Box& box) {
	if (boxes_[0] != box) {
		boxes_[1] = boxes_[0];
		boxes_[0] = box;
	}
}

std::optional<Box> BoxKeeper::restored(const Box& current) const {
	for (const std::optional<Box>& box : boxes_) {
		if (box && *box != current) {
			return box;
		}
	}
	return std::nullopt;
}

#ifdef _WIN32
// The handle is only passed to user32, which checks it; a stale handle makes the calls fail with no effect.
void add_close_box(Fl_Window* win) {
	const HWND hwnd = fl_xid(win);
	if (!hwnd) {
		return;
	}
	const LONG_PTR style = GetWindowLongPtrW(hwnd, GWL_STYLE);
	SetWindowLongPtrW(hwnd, GWL_STYLE, style | WS_SYSMENU);
	SetWindowPos(hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
}

// The window needs its handle ere Windows can place it, so it shows first and moves after. Windows moves a box that
// lies wholly off every screen onto one, so a monitor that is gone leaves the window in sight.
void show_placed(Fl_Window* win, const std::optional<Placement>& saved) {
	win->show();
	const HWND hwnd = fl_xid(win);
	if (!saved || !hwnd) {
		win->maximize();
		return;
	}
	const Box& b = saved->box;
	WINDOWPLACEMENT wp{};
	wp.length = sizeof(wp);
	wp.showCmd = saved->maximized ? SW_SHOWMAXIMIZED : SW_SHOWNORMAL;
	wp.ptMinPosition = POINT{-1, -1};
	wp.ptMaxPosition = POINT{-1, -1};
	wp.rcNormalPosition = RECT{b.x, b.y, b.x + b.w, b.y + b.h};
	if (!SetWindowPlacement(hwnd, &wp)) {
		win->maximize();
	}
}

// FLTK sets a window's position on WM_MOVE and its maximized flag on the WM_SIZE after it, so its own x, y, w, and h
// give the maximized box of a maximized window. Windows keeps the box the window restores to, which is the one to save.
std::optional<Placement> placement(Fl_Window* win, const BoxKeeper& /*kept*/) {
	const HWND hwnd = fl_xid(win);
	if (!hwnd) {
		return std::nullopt;
	}
	WINDOWPLACEMENT wp{};
	wp.length = sizeof(wp);
	if (!GetWindowPlacement(hwnd, &wp)) {
		return std::nullopt;
	}
	const bool maximized =
	    wp.showCmd == SW_SHOWMAXIMIZED || (wp.showCmd == SW_SHOWMINIMIZED && (wp.flags & WPF_RESTORETOMAXIMIZED) != 0);
	const RECT& r = wp.rcNormalPosition;
	return Placement{Box{static_cast<int>(r.left), static_cast<int>(r.top), static_cast<int>(r.right - r.left),
	                     static_cast<int>(r.bottom - r.top)},
	                 maximized};
}
#else
// Elsewhere the window manager draws the close box, so there is nothing to add.
void add_close_box(Fl_Window* /*win*/) {}

namespace {

// Whether the session is Wayland. The Linux build of FLTK runs on Wayland when a compositor is there and on X11 else.
bool on_wayland() {
#ifdef FLTK_USE_WAYLAND
	Fl::open_display();
	return fl_wl_display() != nullptr;
#else
	return false;
#endif
}

// Whether 64 by 64 units of the box lie in the work area of one screen, so the window can be seen and grabbed. A
// heuristic: it does not check that the window manager's title bar, if any, is in sight. A box that fails opens
// maximized instead, as a box on a monitor that is gone would.
bool on_a_screen(const Box& b) {
	constexpr int MIN_SEEN = 64;
	for (int i = 0; i < Fl::screen_count(); ++i) {
		int sx = 0;
		int sy = 0;
		int sw = 0;
		int sh = 0;
		Fl::screen_work_area(sx, sy, sw, sh, i);
		const int w = std::min(b.x + b.w, sx + sw) - std::max(b.x, sx);
		const int h = std::min(b.y + b.h, sy + sh) - std::max(b.y, sy);
		if (w >= MIN_SEEN && h >= MIN_SEEN) {
			return true;
		}
	}
	return false;
}

// How far right and down of its asked position the window manager put the window at start. A manager that adds a frame
// puts the frame at the asked position, so the window lands a frame's width and title's height off; without this the
// saved position would creep by that much each session. cwm adds no frame, so it is 0 there.
int frame_dx = 0;
int frame_dy = 0;

// The largest offset taken for a frame. A heuristic: a larger offset means the manager put the window where it chose,
// so no offset is taken off.
constexpr int MAX_FRAME = 64;

} // namespace

// On X11 a box set ere show() goes out with the USPosition hint, which window managers (cwm among them) honor. The app
// waits for the window to map ere it measures the frame or maximizes, since a window manager may ignore the state
// message of an unmapped window. Wayland gives a client no position, so there the window opens maximized as before.
void show_placed(Fl_Window* win, const std::optional<Placement>& saved) {
	const bool x11 = !on_wayland();
	const bool place = x11 && saved && on_a_screen(saved->box);
	if (place) {
		const Box& b = saved->box;
		win->resize(b.x, b.y, b.w, b.h);
	}
	win->show();
	if (x11) {
		win->wait_for_expose();
	}
	if (place) {
		const int dx = win->x() - saved->box.x;
		const int dy = win->y() - saved->box.y;
		if (dx >= 0 && dx <= MAX_FRAME && dy >= 0 && dy <= MAX_FRAME) {
			frame_dx = dx;
			frame_dy = dy;
		}
	}
	if (!place || saved->maximized) {
		win->maximize();
	}
}

// X11 keeps no box for a maximized window to restore to, so a maximized window gives the box that `kept` noted ere it
// was maximized. The position saved is the one to ask for, less the frame measured at start.
std::optional<Placement> placement(Fl_Window* win, const BoxKeeper& kept) {
	if (!win->shown() || on_wayland()) {
		return std::nullopt;
	}
	const Box now{win->x(), win->y(), win->w(), win->h()};
	Box box = win->maximize_active() ? kept.restored(now).value_or(now) : now;
	box.x -= frame_dx;
	box.y -= frame_dy;
	return Placement{box, win->maximize_active() != 0};
}
#endif

} // namespace palm::platform_ui
