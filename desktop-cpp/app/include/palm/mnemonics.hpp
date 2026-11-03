// Accelerator underlines that show only while Alt is held, as Windows and Palm Desktop 3.1.1 draw them.
//
// FLTK always draws the underline under the accelerator letter of a label ('&' in the label). While the
// underlines are hidden, refresh strips the '&' from every label, and it puts the '&' back while they are
// shown. Alt or F10 shows them; the next mouse press hides them.
#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>

class Fl_Widget;

namespace palm::mnemonics {

// Installs the handlers that show the underlines on Alt or F10 and hide them on a mouse press.
void install();

// Applies the current state to every widget of every shown window and to every menu item. Labels that already
// match the state are left alone, so a call with no change only walks the tree.
void refresh();

// Drops the stored originals of root, of every widget under it, and of every menu item of a menu under it. Call it
// just before root is destroyed: a later widget at the same address would otherwise inherit an old original.
void forget(Fl_Widget* root);

// Calls forget on its root when the scope that owns it ends. Declare it after the window, so it runs before the window
// and its widgets are destroyed.
class ForgetOnExit {
public:
	explicit ForgetOnExit(Fl_Widget& root) : root_(root) {}
	~ForgetOnExit() { forget(&root_); }
	ForgetOnExit(const ForgetOnExit&) = delete;
	ForgetOnExit& operator=(const ForgetOnExit&) = delete;

private:
	Fl_Widget& root_;
};

namespace detail {

// Sets the state that with_originals runs under and returns the state before it.
bool begin_originals();

// Restores the state that begin_originals returned.
void end_originals(bool shown);

} // namespace detail

// Runs f with every label in its original form, '&' included, then puts the state back. Code that finds menu
// items by their path ("&Edit/&Undo") or adds items to a menu runs inside it, so the paths match. The state is
// restored when f throws too.
template <typename F> std::invoke_result_t<F> with_originals(F&& f) {
	struct Restore {
		bool shown;
		~Restore() { detail::end_originals(shown); }
	} restore{detail::begin_originals()};
	return std::forward<F>(f)();
}

// The label rules, exposed for the tests. Each one reads a label and needs no FLTK widget.

// Removes the accelerator '&' from a label. None when the label has no accelerator. "&&" is a literal '&' and
// stays as it is.
std::optional<std::string> strip(std::string_view label);

// The accelerator letter of a label, or None when the label has none.
std::optional<char32_t> accelerator(std::string_view label);

// The label to show now: the original while the underlines are shown, its stripped form while hidden.
std::string target(std::string_view original, bool shown);

// Chooses the original label. The stored original stays, unless the current label is neither the original nor
// its stripped form: code has then set a new label, which becomes the original.
std::string pick_original(std::optional<std::string_view> stored, std::string_view current);

} // namespace palm::mnemonics
