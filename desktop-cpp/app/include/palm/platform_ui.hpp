// The FLTK-dependent platform services of the desktop app: the close box that FLTK leaves off a modal window.
#pragma once

class Fl_Window;

namespace palm::platform_ui {

// Adds the close (X) box to a window that FLTK gave none. FLTK adds WS_SYSMENU only to a window that is not modal, and
// every dialog is modal, so a dialog has a title bar without the box. The style bit is set on the live window, and the
// frame is redrawn. No maximize or minimize box is added. Elsewhere the window manager draws the close box, so this
// does nothing.
void add_close_box(Fl_Window* win);

} // namespace palm::platform_ui
