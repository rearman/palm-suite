// The small modal dialogs of desktop-rs/app/src/dialogs.rs: the delete question, a name prompt and List By. Each one
// opens, runs its own event loop until it closes, and returns the answer, as a Swing modal dialog does. The message
// boxes, the prompt and About follow the Palm Desktop 3.1.1 dialogs of Windows.
#pragma once

#include <FL/Enumerations.H>

#include <cstddef>
#include <functional>
#include <initializer_list>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

class Fl_Box;
class Fl_Button;
class Fl_Window;

namespace palm::settings {
class Settings;
}

namespace palm::dialogs {

// The icon at the left of a message box.
enum class Icon { Question, Warning, Info, None };

// Makes `win` the main window that the dialogs center over. Set by set_main at start-up.
void set_main(Fl_Window* win);

// Moves `win` so that it is centered over the main window, or over the largest shown window when the main window is
// not shown. The position is kept inside the work area of the screen that holds the anchor.
void center(Fl_Window* win);

// Shows a modal window centered on the main window, with a close box, for a dialog that runs its own loop.
void show_modal(Fl_Window* win);

// Runs a modal window until it closes. The window is centered first, then shown, and then given the close box that
// FLTK leaves off a modal window.
void run_modal(Fl_Window* win);

// Asks before a record is deleted, with `title` on its title bar, OK and Cancel, as Palm Desktop 3.1.1's "Delete
// Memo Pad Items". True on OK, and true at once when `no_confirm` is set (Shift+Delete) or the "confirmDelete" setting
// is off.
bool confirm_delete_titled(const settings::Settings& settings, bool no_confirm, std::string_view title,
                           std::string_view question);

// Shows `text` with `icon` and one OK button, as a message box does.
void message(std::string_view title, Icon icon, std::string_view text);

// Asks `text` with `icon` at the left and one button for each of `buttons`, Enter pressing `default_button`. The index
// of the button pressed, or None when the dialog is closed (by Escape, the close box or Cancel). The layout is that of
// a Windows message box: the icon at (11, 11), the text at x 53, buttons of 75 x 23 with 6 px between them, and an 11
// px margin.
std::optional<std::size_t> ask_icon(std::string_view title, Icon icon, std::string_view text,
                                    std::initializer_list<std::string_view> buttons, std::size_t default_button);

// The prompt of Palm Desktop 3.1.1, such as New Category: `label` above a text field holding `initial`, all of it
// selected, and OK and Cancel. The trimmed text on OK, None on Cancel, on the close box and on a blank.
std::optional<std::string> prompt_titled(std::string_view title, std::string_view label, std::string_view initial);

// The About box of Palm Desktop: the logo, the name in bold, the version and one OK button.
void about();

// The title of a group box whose top-left corner is (x, y), drawn over its top edge with the panel gray behind it, as
// a Windows group box has it. Call it after the group's end(), so the title draws on top.
Fl_Box* group_title(int x, int y, std::string_view text);

// A field label at (x, y, w, h), aligned by `align`, with '&' before the letter of its Alt key. FLTK underlines that
// letter on a button only, so the label is a button without edges that takes no focus.
Fl_Button* key_label(std::string_view text, int x, int y, int w, int h, Fl_Align align);

// The List By form of the Address Book and the Memo Pad: one radio for each choice in a titled group, then OK and
// Cancel. The index of the chosen radio on OK, None on Cancel.
std::optional<std::size_t> list_by(std::string_view heading, std::initializer_list<std::string_view> choices,
                                   std::size_t selected);

// A push button of the dialogs, 75 x 23 at (x, y). The default button has a 1 px black outline, drawn as Palm Desktop
// 3.1.1 draws it: a black frame behind the button, which sits 1 px inside it. The other buttons show no dotted focus
// rectangle.
Fl_Button* push_button(int x, int y, std::string_view label, bool is_default);

// Draws the 32 x 32 message box icon at (x, y): a blue disc with a white "?" or "i", or a yellow warning triangle with
// a black outline and a black "!".
void draw_icon(Icon icon, int x, int y);

namespace detail {

// The lines of `text` word-wrapped to `max_w`, where `measure` gives the width of a string in pixels. A newline in
// `text` starts a new line. Exposed for test_dialogs.cpp.
std::vector<std::string> wrap_lines(std::string_view text, int max_w,
                                    const std::function<int(std::string_view)>& measure);

} // namespace detail

} // namespace palm::dialogs
