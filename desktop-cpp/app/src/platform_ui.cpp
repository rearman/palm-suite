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

#include <FL/Fl_Window.H>
#include <FL/platform.H>

namespace palm::platform_ui {

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
#else
// Elsewhere the window manager draws the close box, so there is nothing to add.
void add_close_box(Fl_Window* /*win*/) {}
#endif

} // namespace palm::platform_ui
