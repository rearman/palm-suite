//! What a view handler gets from the app: the store, the settings and the shared view state.

use crate::msg::Msg;
use crate::settings::Settings;
use crate::store::DesktopStore;
use fltk::app::Sender;

pub struct Ctx<'a> {
	pub store: &'a mut DesktopStore,
	pub settings: &'a mut Settings,
	pub show_private: bool,
	/// Set while Shift+Delete runs, so a delete does not ask.
	pub no_confirm: bool,
	pub s: Sender<Msg>,
}

impl Ctx<'_> {
	/// Saves a view setting; a failure shows in the status bar on the next save of the store.
	pub fn set(&mut self, key: &str, value: &str) {
		let _ = self.settings.set(key, value);
	}
}
