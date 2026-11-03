// What a view handler gets from the app: the store, the settings and the shared view state, as view.rs.
#pragma once

#include "palm/msg.hpp"
#include "palm/settings.hpp"
#include "palm/store.hpp"

#include <cstdint>
#include <string_view>

namespace palm::view {

// The left and right mouse buttons as Fl::event_button() numbers them. Fl::event_mouse_button() panics in Rust
// when the last event was a key (FLTK then reports a key code as the button), so code reads the number.
inline constexpr int LEFT = 1;
inline constexpr int RIGHT = 3;

struct Ctx {
	store::DesktopStore& store;
	settings::Settings& settings;
	bool show_private;
	// Set while Shift+Delete runs, so a delete does not ask.
	bool no_confirm;
	// The selected day of the Date Book, as an epoch day. The views move it.
	std::int64_t& day;
	// True when the system shows the time in 24-hour form.
	bool h24;
	msg::Sender s;

	// Saves a view setting; a failure shows in the status bar on the next save of the store.
	void set(std::string_view key, std::string_view value);
};

} // namespace palm::view
