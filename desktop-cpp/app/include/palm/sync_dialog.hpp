// The Sync Options dialog of desktop-rs/app/src/sync_dialog.rs (SyncDialog in Dialogs.kt): the palmsync server, the
// device token and the passphrase. Set Up and Turn Off close the dialog when they finish; Close leaves the sync
// settings as they are.
#pragma once

#include "palm/msg.hpp"

#include <memory>
#include <string_view>
#include <vector>

class Fl_Button;
class Fl_Box;
class Fl_Double_Window;
class Fl_Input;
class Fl_Secret_Input;
class Fl_Widget;

namespace palm::sync_dialog {

// The dialog. open() shows it as a modal window that the main loop serves, and each button sends its msg::Msg
// through the sender that open() takes. The fields are public, as the Rust fields are.
class SyncDialog {
public:
	// Opens the dialog. When `saved_token` is true an empty Token field keeps the saved token. Sync Now and Turn Off
	// show only when `sync_on` is true.
	static std::unique_ptr<SyncDialog> open(msg::Sender sender, std::string_view server, bool saved_token, bool sync_on,
	                                        std::string_view status);

	SyncDialog(const SyncDialog&) = delete;
	SyncDialog& operator=(const SyncDialog&) = delete;
	~SyncDialog();

	// Set Up runs: the button stays off until it ends. Busy also sets the status to "Please wait.".
	void set_busy(bool busy);

	// Shows `text` in the status line.
	void set_status(std::string_view text);

	// Hides the window. The dialog stays until it is destroyed, as the Rust window does until it is dropped.
	void close();

	Fl_Input* server = nullptr;
	Fl_Secret_Input* token = nullptr;
	Fl_Secret_Input* pass = nullptr;

private:
	// A button of the dialog: the sender, and the function that makes the message it sends (Rust's MakeMsg).
	struct Press {
		msg::Sender sender;
		msg::Msg (*make)();
	};

	SyncDialog(msg::Sender sender, std::string_view server_text, bool saved, bool sync_on,
	           std::string_view status_text);

	// Turns Set Up on when a token and a passphrase are typed (or a token is saved), and the dialog is not busy.
	void update_set_up(bool busy);

	// The callbacks of the buttons and the window, and of the two fields.
	static void on_press(Fl_Widget* w, void* data);
	static void on_edit(Fl_Widget* w, void* data);

	// The presses are held in a vector that is filled once, so the pointers given to the callbacks stay valid.
	std::vector<Press> presses_;
	bool saved_token_;
	Fl_Box* status_ = nullptr;
	Fl_Button* set_up_ = nullptr;
	std::unique_ptr<Fl_Double_Window> win_;
};

} // namespace palm::sync_dialog
