// The accelerator underlines of mnemonics.hpp. The state and the originals are file-static: every call runs on the
// UI thread, as the Rust runs its thread-local state there.
#include "palm/mnemonics.hpp"

#include "palm/text.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Menu_.H>
#include <FL/Fl_Menu_Bar.H>
#include <FL/Fl_Menu_Button.H>
#include <FL/Fl_Menu_Item.H>
#include <FL/Fl_Tabs.H>
#include <FL/Fl_Widget.H>
#include <FL/Fl_Window.H>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <new>
#include <string>
#include <unordered_map>
#include <utility>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace palm::mnemonics {

namespace {

// Whether the underlines are shown. They start hidden, so the first refresh hides them.
bool shown = false;

// The label each widget and menu item had before it was stripped, keyed by its address. A menu item is keyed by
// its menu and its stripped label, because FLTK moves the items of a menu in memory when items are added or
// removed, so an item's own address does not last.
std::unordered_map<std::uintptr_t, std::string>& originals() {
	static std::unordered_map<std::uintptr_t, std::string> map;
	return map;
}

// The text of a label without its accelerator '&', and the accelerator letter.
struct Scanned {
	std::string text;
	char32_t accel;
};

// Splits a label into its text without the accelerator '&' and the accelerator letter. A '&' followed by a space
// is not an accelerator, and it stays in the text. FLTK underlines one letter per label, so the first accelerator
// counts. The letter after the '&' stays in the text: only the '&' goes.
std::optional<Scanned> scan(std::string_view label) {
	std::string text;
	text.reserve(label.size());
	std::optional<char32_t> accel;
	std::size_t i = 0;
	while (i < label.size()) {
		if (label[i] != '&') {
			text.push_back(label[i]);
			++i;
			continue;
		}
		if (i + 1 < label.size() && label[i + 1] == '&') {
			text += "&&";
			i += 2;
			continue;
		}
		if (i + 1 < label.size()) {
			const palm::text::Decoded next = palm::text::decode_at(label, i + 1);
			if (!palm::text::is_white_space(next.cp)) {
				if (!accel) {
					accel = next.cp;
				}
				++i;
				continue;
			}
		}
		text.push_back('&');
		++i;
	}
	if (!accel) {
		return std::nullopt;
	}
	return Scanned{std::move(text), *accel};
}

// Shows or hides the underlines. A change of state applies at once.
void set_shown(bool on) {
	if (std::exchange(shown, on) != on) {
		refresh();
	}
}

// The event handler: Alt and F10 show the underlines, and a mouse press hides them. FLTK calls this handler only
// for events that no widget handled, so a press on a widget does not reach it on every platform; the Windows
// system handler covers that case. It returns 0, so it never eats an event.
int on_event(int event) {
	switch (event) {
	case FL_KEYDOWN: {
		const int key = Fl::event_key();
		if (key == FL_Alt_L || key == FL_Alt_R || key == FL_F + 10) {
			set_shown(true);
		}
		break;
	}
	case FL_PUSH:
		set_shown(false);
		break;
	default:
		break;
	}
	return 0;
}

#ifdef _WIN32
// Sees every message before FLTK does. It returns 0, so FLTK still handles the message.
int system_event(void* event, void*) {
	if (!event) {
		return 0;
	}
	const MSG& msg = *static_cast<const MSG*>(event);
	switch (msg.message) {
	case WM_LBUTTONDOWN:
	case WM_RBUTTONDOWN:
	case WM_MBUTTONDOWN:
		set_shown(false);
		break;
	case WM_KEYDOWN:
	case WM_SYSKEYDOWN:
		if (msg.wParam == VK_MENU || msg.wParam == VK_F10) {
			set_shown(true);
		}
		break;
	default:
		break;
	}
	return 0;
}
#endif

// The accelerator letter in lower case, which FLTK matches as the keyboard sends it.
int shortcut_key(char32_t accel) {
	const char32_t lower =
	    accel < 128 ? static_cast<char32_t>(palm::text::ascii_lower(static_cast<char>(accel))) : accel;
	return static_cast<int>(lower);
}

// Records the original of a widget or menu item under its key and returns it. Labels without an accelerator are
// not kept.
std::string remember(std::uintptr_t key, std::string_view current) {
	auto& map = originals();
	std::optional<std::string_view> stored;
	if (const auto it = map.find(key); it != map.end()) {
		stored = it->second;
	}
	std::string original = pick_original(stored, current);
	if (accelerator(original)) {
		map[key] = original;
	} else {
		map.erase(key);
	}
	return original;
}

// Gives a button Alt+letter as its shortcut when the button has none, so the shortcut works while the label has
// no '&'. A button keeps any shortcut it already has.
void button_shortcut(Fl_Button& button, std::string_view original) {
	const std::optional<char32_t> accel = accelerator(original);
	if (!accel) {
		return;
	}
	if (button.shortcut() == 0) {
		button.shortcut(FL_ALT | shortcut_key(*accel));
	}
}

// Sets the label of a widget to its state. A button also gets Alt+letter as its shortcut when it has none.
void refresh_widget(Fl_Widget& widget, bool on) {
	// FLTK draws '&' as an underline only on buttons and on the tabs of a Tabs; any other label, such as a frame
	// that shows a record, draws '&' as itself and is left alone.
	const bool is_tab = widget.parent() && dynamic_cast<Fl_Tabs*>(widget.parent());
	auto* button = dynamic_cast<Fl_Button*>(&widget);
	if (!button && !is_tab) {
		return;
	}
	const auto key = reinterpret_cast<std::uintptr_t>(&widget);
	const std::string current = widget.label() ? widget.label() : "";
	const std::string original = remember(key, current);
	const std::string wanted = target(original, on);
	if (wanted != current) {
		widget.copy_label(wanted.c_str());
	}
	if (!on && button) {
		button_shortcut(*button, original);
	}
}

// Sets the label of a menu item in C memory. The menus are built with add(), so FLTK owns their labels and frees
// them with free() when it removes an item. The new label comes from malloc, and the old one is freed once here,
// for the same reason. A menu whose labels are static strings is not supported, as in the Rust.
void set_item_label(Fl_Menu_Item& item, std::string_view label) {
	char* copy = static_cast<char*>(std::malloc(label.size() + 1));
	if (!copy) {
		throw std::bad_alloc();
	}
	std::memcpy(copy, label.data(), label.size());
	copy[label.size()] = '\0';
	const char* old = item.label();
	item.label(copy);
	if (old) {
		std::free(const_cast<char*>(old));
	}
}

// The key of a menu item's original label: its menu and its label without the '&'. A hash, as in the Rust, so two
// keys can collide in theory.
std::uintptr_t menu_key(std::uintptr_t menu, std::string_view current) {
	const std::optional<std::string> stripped = strip(current);
	const std::string_view plain = stripped ? std::string_view(*stripped) : current;
	std::size_t h = std::hash<std::string_view>{}(plain);
	h ^= std::hash<std::uintptr_t>{}(menu) + 0x9e3779b97f4a7c15ULL + (h << 6) + (h >> 2);
	return static_cast<std::uintptr_t>(h);
}

// Sets the labels of the items of a menu to their state.
void refresh_menu(Fl_Menu_& menu, bool on) {
	const auto menu_addr = reinterpret_cast<std::uintptr_t>(static_cast<Fl_Widget*>(&menu));
	const int count = menu.size();
	for (int i = 0; i < count; ++i) {
		// FLTK's menu() accessor is const, but the items belong to the menu, and set_item_label changes only the
		// label, as FLTK's own menu code does.
		auto* item = const_cast<Fl_Menu_Item*>(menu.menu() + i);
		if (!item->label()) {
			continue;
		}
		const std::string current = item->label();
		const std::string original = remember(menu_key(menu_addr, current), current);
		const std::string wanted = target(original, on);
		if (wanted != current) {
			set_item_label(*item, wanted);
		}
	}
}

// Walks a group and everything in it: its widgets, its menus and the groups inside it.
void refresh_group(Fl_Group& group, bool on) {
	const int count = group.children();
	for (int i = 0; i < count; ++i) {
		Fl_Widget* child = group.child(i);
		if (!child) {
			continue;
		}
		refresh_widget(*child, on);
		if (auto* bar = dynamic_cast<Fl_Menu_Bar*>(child)) {
			refresh_menu(*bar, on);
		}
		// The items of a Choice are data, such as category names, and have no accelerators to hide.
		if (auto* menu = dynamic_cast<Fl_Menu_Button*>(child)) {
			refresh_menu(*menu, on);
		}
		if (auto* sub = dynamic_cast<Fl_Group*>(child)) {
			refresh_group(*sub, on);
		}
	}
}

// Drops the originals of the items of a menu. The keys are the ones refresh_menu stores.
void forget_menu(Fl_Menu_& menu) {
	const auto menu_addr = reinterpret_cast<std::uintptr_t>(static_cast<Fl_Widget*>(&menu));
	const int count = menu.size();
	for (int i = 0; i < count; ++i) {
		const Fl_Menu_Item* item = menu.menu() + i;
		if (item->label()) {
			originals().erase(menu_key(menu_addr, item->label()));
		}
	}
}

// Drops the originals of a widget, its menu items, and everything in it.
void forget_widget(Fl_Widget& widget) {
	originals().erase(reinterpret_cast<std::uintptr_t>(&widget));
	if (auto* menu = dynamic_cast<Fl_Menu_*>(&widget)) {
		forget_menu(*menu);
	}
	if (auto* group = dynamic_cast<Fl_Group*>(&widget)) {
		const int count = group->children();
		for (int i = 0; i < count; ++i) {
			if (Fl_Widget* child = group->child(i)) {
				forget_widget(*child);
			}
		}
	}
}

} // namespace

void install() {
	Fl::add_handler(on_event);
#ifdef _WIN32
	Fl::add_system_handler(system_event, nullptr);
#endif
}

void refresh() {
	for (Fl_Window* win = Fl::first_window(); win; win = Fl::next_window(win)) {
		refresh_group(*win, shown);
	}
}

void forget(Fl_Widget* root) {
	if (root) {
		forget_widget(*root);
	}
}

namespace detail {

bool begin_originals() {
	const bool before = std::exchange(shown, true);
	if (!before) {
		refresh();
	}
	return before;
}

void end_originals(bool before) {
	shown = before;
	if (!before) {
		refresh();
	}
}

} // namespace detail

std::optional<std::string> strip(std::string_view label) {
	const std::optional<Scanned> s = scan(label);
	if (!s) {
		return std::nullopt;
	}
	return s->text;
}

std::optional<char32_t> accelerator(std::string_view label) {
	const std::optional<Scanned> s = scan(label);
	if (!s) {
		return std::nullopt;
	}
	return s->accel;
}

std::string target(std::string_view original, bool on) {
	if (!on) {
		if (std::optional<std::string> stripped = strip(original)) {
			return *stripped;
		}
	}
	return std::string(original);
}

std::string pick_original(std::optional<std::string_view> stored, std::string_view current) {
	if (stored && (*stored == current || strip(*stored) == current)) {
		return std::string(*stored);
	}
	return std::string(current);
}

} // namespace palm::mnemonics
