// The Sync Options dialog, as desktop-rs/app/src/sync_dialog.rs. Each button and the window send one msg::Msg; the
// dialog keeps no state beyond its widgets and the flag of the saved token.
#include "palm/sync_dialog.hpp"

#include "palm/dialogs.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Secret_Input.H>
#include <FL/Fl_Widget.H>

#include <array>
#include <cstddef>
#include <initializer_list>
#include <memory>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::sync_dialog {

namespace {

// The gray of the note under the token field.
const Fl_Color NOTE_GRAY = fl_rgb_color(110, 110, 110);

// The size of a button of this dialog, which is wider than the 75 px of the dialogs' push buttons, as in Rust.
constexpr int BUTTON_W = 78;
constexpr int BUTTON_H = 23;

// One button: its label, and the function that makes its message.
struct Button {
	const char* label;
	msg::Msg (*make)();
};

// The four buttons, from left to right. Close is last, and the window sends its message too.
const std::array<Button, 4> BUTTONS{{
    {"Set &Up", []() -> msg::Msg { return msg::SyncDialogSetUp{}; }},
    {"Sync &Now", []() -> msg::Msg { return msg::SyncDialogSyncNow{}; }},
    {"Turn &Off", []() -> msg::Msg { return msg::SyncDialogTurnOff{}; }},
    {"Close", []() -> msg::Msg { return msg::SyncDialogClose{}; }},
}};

// Set Up needs a token (typed, or saved on this computer) and a passphrase, and stays off while it runs.
bool can_set_up(const Fl_Input* token, const Fl_Input* pass, bool saved_token, bool busy) {
	const bool has_token = !std::string_view(token->value()).empty() || saved_token;
	return !busy && has_token && !std::string_view(pass->value()).empty();
}

void set_active(Fl_Widget* w, bool on) {
	if (on) {
		w->activate();
	} else {
		w->deactivate();
	}
}

} // namespace

std::unique_ptr<SyncDialog> SyncDialog::open(msg::Sender sender, std::string_view server, bool saved_token,
                                             bool sync_on, std::string_view status) {
	return std::unique_ptr<SyncDialog>(new SyncDialog(std::move(sender), server, saved_token, sync_on, status));
}

SyncDialog::SyncDialog(msg::Sender sender, std::string_view server_text, bool saved, bool sync_on,
                       std::string_view status_text)
    : saved_token_(saved) {
	// The presses are made first, so each button has its callback data before it is built.
	presses_.reserve(BUTTONS.size());
	for (const Button& b : BUTTONS) {
		presses_.push_back(Press{sender, b.make});
	}

	win_ = std::make_unique<Fl_Double_Window>(380, 200, "Sync Options");
	// Each label belongs to its field, as in Rust: its Alt letter focuses the field, and the underline always shows,
	// since Fl_Input and Fl_Secret_Input set SHORTCUT_LABEL in their constructors.
	server = new Fl_Input(84, 14, 270, 22, "S&erver:");
	server->value(std::string(server_text).c_str());
	token = new Fl_Secret_Input(84, 44, 270, 22, "&Token:");
	pass = new Fl_Secret_Input(84, 74, 270, 22, "P&assphrase:");
	if (saved) {
		token->tooltip("Leave it empty to keep the token saved on this computer.");
		auto* note = new Fl_Box(84, 98, 270, 16, "A token is saved here; an empty Token keeps it.");
		note->labelcolor(NOTE_GRAY);
		note->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	}
	status_ = new Fl_Box(84, 118, 280, 20);
	status_->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	status_->copy_label(std::string(status_text).c_str());

	std::array<Fl_Button*, BUTTONS.size()> made{};
	for (std::size_t i = 0; i < BUTTONS.size(); ++i) {
		// A plain button keeps keyboard focus, so Tab reaches it, as the Rust Button does.
		Fl_Button* b = new Fl_Button(32 + static_cast<int>(i) * 84, 160, BUTTON_W, BUTTON_H, BUTTONS[i].label);
		b->callback(on_press, &presses_[i]);
		made[i] = b;
	}
	set_up_ = made[0];
	if (!sync_on) {
		made[1]->hide();
		made[2]->hide();
	}
	win_->callback(on_press, &presses_[BUTTONS.size() - 1]);
	win_->end();
	dialogs::show_modal(win_.get());

	for (Fl_Input* input : {static_cast<Fl_Input*>(token), static_cast<Fl_Input*>(pass)}) {
		input->when(FL_WHEN_CHANGED);
		input->callback(on_edit, this);
	}
	update_set_up(false);
}

SyncDialog::~SyncDialog() = default;

void SyncDialog::set_busy(bool busy) {
	if (busy) {
		status_->copy_label("Please wait.");
		status_->redraw_label();
	}
	update_set_up(busy);
}

void SyncDialog::set_status(std::string_view text) {
	status_->copy_label(std::string(text).c_str());
	status_->redraw_label();
}

void SyncDialog::close() {
	win_->hide();
}

void SyncDialog::update_set_up(bool busy) {
	set_active(set_up_, can_set_up(token, pass, saved_token_, busy));
}

void SyncDialog::on_press(Fl_Widget*, void* data) {
	auto* press = static_cast<Press*>(data);
	press->sender.send(press->make());
}

void SyncDialog::on_edit(Fl_Widget*, void* data) {
	static_cast<SyncDialog*>(data)->update_set_up(false);
}

} // namespace palm::sync_dialog
