//! Sync > Options, as SyncDialog in Dialogs.kt: the palmsync server, the device token and the passphrase.
//! Set Up and Turn Off close the dialog when they finish; Close leaves the sync settings as they are.

use crate::dialogs;
use crate::msg::Msg;
use fltk::app::Sender;
use fltk::button::Button;
use fltk::enums::{Align, CallbackTrigger, Color};
use fltk::frame::Frame;
use fltk::input::{Input, SecretInput};
use fltk::prelude::*;
use fltk::window::Window;

/// A function that makes the message of one button.
type MakeMsg = fn() -> Msg;

pub struct SyncDialog {
	win: Window,
	pub server: Input,
	pub token: SecretInput,
	pub pass: SecretInput,
	status: Frame,
	set_up: Button,
	saved_token: bool,
	busy: bool,
}

/// Set Up needs a token (typed, or saved on this computer) and a passphrase, and stays off while it runs.
fn can_set_up(token: &SecretInput, pass: &SecretInput, saved_token: bool, busy: bool) -> bool {
	!busy && (!token.value().is_empty() || saved_token) && !pass.value().is_empty()
}

fn set_active(b: &mut Button, on: bool) {
	if on {
		b.activate();
	} else {
		b.deactivate();
	}
}

impl SyncDialog {
	/// Opens the dialog. When `saved_token` is true an empty Token field keeps the saved token.
	pub fn open(s: Sender<Msg>, server: &str, saved_token: bool, sync_on: bool, status: &str) -> SyncDialog {
		let mut win = Window::default().with_size(380, 200).with_label("Sync Options");
		let mut server_in = Input::new(84, 14, 270, 22, "S&erver:");
		server_in.set_value(server);
		let mut token = SecretInput::new(84, 44, 270, 22, "&Token:");
		let pass = SecretInput::new(84, 74, 270, 22, "P&assphrase:");
		if saved_token {
			token.set_tooltip("Leave it empty to keep the token saved on this computer.");
			let mut note = Frame::new(84, 98, 270, 16, "A token is saved here; an empty Token keeps it.");
			note.set_label_color(Color::from_rgb(110, 110, 110));
			note.set_align(Align::Left | Align::Inside);
		}
		let mut status_frame = Frame::new(84, 118, 280, 20, None);
		status_frame.set_align(Align::Left | Align::Inside);
		status_frame.set_label(status);

		let buttons: [(&str, MakeMsg); 4] = [
			("Set &Up", || Msg::SyncDialogSetUp),
			("Sync &Now", || Msg::SyncDialogSyncNow),
			("Turn &Off", || Msg::SyncDialogTurnOff),
			("Close", || Msg::SyncDialogClose),
		];
		let mut made = Vec::new();
		for (i, (label, msg)) in buttons.into_iter().enumerate() {
			let mut b = Button::new(32 + i as i32 * 84, 160, 78, 23, None);
			b.set_label(label);
			b.set_callback(move |_| s.send(msg()));
			made.push(b);
		}
		let mut set_up = made[0].clone();
		if !sync_on {
			made[1].hide();
			made[2].hide();
		}
		win.set_callback(move |_| s.send(Msg::SyncDialogClose));
		win.end();
		dialogs::show_modal(&mut win);

		for mut input in [token.clone(), pass.clone()] {
			let (token, pass, mut set_up) = (token.clone(), pass.clone(), set_up.clone());
			input.set_trigger(CallbackTrigger::Changed);
			input.set_callback(move |_| set_active(&mut set_up, can_set_up(&token, &pass, saved_token, false)));
		}
		set_active(&mut set_up, can_set_up(&token, &pass, saved_token, false));
		SyncDialog {
			win,
			server: server_in,
			token,
			pass,
			status: status_frame,
			set_up,
			saved_token,
			busy: false,
		}
	}

	/// Set Up runs: the button stays off until it ends.
	pub fn set_busy(&mut self, busy: bool) {
		self.busy = busy;
		if busy {
			self.status.set_label("Please wait.");
		}
		set_active(
			&mut self.set_up,
			can_set_up(&self.token, &self.pass, self.saved_token, busy),
		);
	}

	pub fn set_status(&mut self, text: &str) {
		self.status.set_label(text);
	}

	pub fn close(&mut self) {
		self.win.hide();
	}
}
