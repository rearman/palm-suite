//! What a view handler gets from the app: the store, the settings and the shared view state.

use crate::msg::Msg;
use crate::settings::Settings;
use crate::store::DesktopStore;
use fltk::app::Sender;

/// The left and right mouse buttons as app::event_button() numbers them. app::event_mouse_button() panics
/// when the last event was a key (FLTK then reports a key code as the button), so code reads the number.
pub const LEFT: i32 = 1;
pub const RIGHT: i32 = 3;

pub struct Ctx<'a> {
	pub store: &'a mut DesktopStore,
	pub settings: &'a mut Settings,
	pub show_private: bool,
	/// Set while Shift+Delete runs, so a delete does not ask.
	pub no_confirm: bool,
	/// The selected day of the Date Book, as an epoch day. The views move it.
	pub day: &'a mut i64,
	/// True when the system shows the time in 24-hour form.
	pub h24: bool,
	pub s: Sender<Msg>,
}

impl Ctx<'_> {
	/// Saves a view setting; a failure shows in the status bar on the next save of the store.
	pub fn set(&mut self, key: &str, value: &str) {
		let _ = self.settings.set(key, value);
	}
}
