// Palm Desktop for Windows and Linux, after Palm Desktop 3.1.1, as desktop-rs/app/src/main.rs.
//
// `--shot out.ppm` draws the window offscreen at the display scale (FLTK_SCALING_FACTOR sets it), saves it and exits.
// It shows the device-pixel drawing on Windows only, as in the spike.
#include "palm/address.hpp"
#include "palm/address_dialog.hpp"
#include "palm/category.hpp"
#include "palm/date.hpp"
#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/event_dialog.hpp"
#include "palm/find.hpp"
#include "palm/memo.hpp"
#include "palm/mnemonics.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/options.hpp"
#include "palm/platform.hpp"
#include "palm/printing.hpp"
#include "palm/records.hpp"
#include "palm/runner.hpp"
#include "palm/settings.hpp"
#include "palm/shot.hpp"
#include "palm/store.hpp"
#include "palm/sync.hpp"
#include "palm/sync_dialog.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"
#include "palm/theme.hpp"
#include "palm/todo.hpp"
#include "palm/transfer.hpp"
#include "palm/view.hpp"
#include "palm/window.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Secret_Input.H>
#include <FL/Fl_Table_Row.H>
#include <FL/Fl_Widget.H>
#include <FL/Fl_Window.H>

#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <iostream>
#include <limits>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

namespace {

// The sync runs this long after the last local change, so one action that writes several records syncs once.
constexpr double CHANGE_DELAY = 1.0;
constexpr double HOUR = 3600.0;

// What the focus owner is, for the Edit commands: a text field, a record list, or nothing.
enum class TargetKind { Text, MemoList, AddressList, TodoList, None };

// The target of an Edit command. A text field holds its Field; the lists hold none.
struct Target {
	TargetKind kind = TargetKind::None;
	std::optional<palm::text_edit::Field> field;
};

// The `--shot` capture: the window to draw and the file to write.
struct ShotJob {
	Fl_Window* win;
	std::string out;
};

// The start-up failures: an alert, then exit, as Rust's fail.
[[noreturn]] void fail(const std::string& text) {
	palm::dialogs::message("Palm Desktop", palm::dialogs::Icon::Warning, text);
	std::exit(1);
}

// The timers send their messages through a Sender that the App holds, so no callback touches the app state.
void send_sync_request(void* data) {
	static_cast<palm::msg::Sender*>(data)->send(palm::msg::SyncRequest{});
}

// The hourly sync: it sends a request, then runs again in an hour.
void hourly_sync(void* data) {
	send_sync_request(data);
	Fl::repeat_timeout(HOUR, hourly_sync, data);
}

// The `--shot` timer: it saves the capture, then ends the program.
void save_shot(void* data) {
	auto* job = static_cast<ShotJob*>(data);
	if (auto r = palm::shot::save(*job->win, job->out); !r) {
		std::cerr << "shot: " << r.error() << '\n';
	}
	Fl::hide_all_windows();
}

// The app state, as the App struct of main.rs. main() builds the views into it after the window exists.
struct App {
	App(palm::store::LockFile lock_file, palm::store::DesktopStore desk, palm::settings::Settings prefs,
	    palm::window::Ui window_ui, palm::msg::Sender s, bool h24_value);
	~App();
	App(const App&) = delete;
	App& operator=(const App&) = delete;

	// The views and their drawing read the store, the settings and the window through these members.
	palm::store::LockFile lock_;
	palm::store::DesktopStore store;
	palm::settings::Settings settings;
	palm::sync::DesktopSync sync;
	std::unique_ptr<palm::sync_dialog::SyncDialog> dialog;
	// The view on show.
	palm::msg::View view = palm::msg::View::Date;
	// The selected day of the Date views, as an epoch day. Starts on today.
	std::int64_t day;
	// The day the views show.
	std::int64_t shown_day = std::numeric_limits<std::int64_t>::min();
	bool h24;
	bool show_private;
	palm::msg::Sender sender;
	// The sender that the timers use. It lives as long as the App.
	std::unique_ptr<palm::msg::Sender> timer_sender;
	// The last save error shown, so one fault gives one alert.
	std::optional<std::string> shown_save_error;
	// The store version the views show.
	std::uint64_t shown_version = std::numeric_limits<std::uint64_t>::max();
	std::unique_ptr<palm::memo::MemoView> memo;
	std::unique_ptr<palm::address::AddressView> address;
	std::unique_ptr<palm::todo::TodoView> todo;
	std::unique_ptr<palm::date::DateView> date;
	// The window is deleted in ~App, before the views, as the Rust drop order does.
	palm::window::Ui ui;

	// The view context of the App, built from its members, so a view member can be used beside it.
	palm::view::Ctx make_ctx();
	// Routes one message to its view, then refreshes what the message changed.
	void handle(palm::msg::Msg msg);
	// Reads the store again in every view.
	void refresh_views();
	// The focus owner, for the Edit commands.
	Target focus_target() const;
	void update_edit_state();
	// Tells the window which record commands apply.
	void update_record_state(const Target& target);
	// The Edit menu and toolbar: the focused text field takes the text commands; the view takes the rest.
	void edit(palm::msg::EditCmd cmd);
	// Shows a record that Find found in its view, selected.
	void go_to(const palm::runner::Record& r);
	// Edit > New and the New toolbar button in the current view.
	void new_record();
	void apply(std::vector<palm::sync::Effect> effects);
	void close_dialog();
	void alert(std::string_view text);
	void save_setting(std::string_view key, std::string_view value);
	// A failed save of the data file gives one alert, and the status bar keeps the error.
	void check_save();
	void show_view(palm::msg::View v);
	// The selected date in the Date Book, and the position in the other views.
	void update_position();
};

App::App(palm::store::LockFile lock_file, palm::store::DesktopStore desk, palm::settings::Settings prefs,
         palm::window::Ui window_ui, palm::msg::Sender s, bool h24_value)
    : lock_(std::move(lock_file)), store(std::move(desk)), settings(std::move(prefs)), sync(s, h24_value),
      day(palm::platform::today()), h24(h24_value), show_private(settings.get_bool("showPrivate", false)), sender(s),
      timer_sender(std::make_unique<palm::msg::Sender>(s)), ui(std::move(window_ui)) {}

App::~App() {
	delete ui.win;
	ui.win = nullptr;
}

palm::view::Ctx App::make_ctx() {
	return palm::view::Ctx{store, settings, show_private, false, day, h24, sender};
}

void App::handle(palm::msg::Msg msg) {
	std::visit(
	    palm::msg::Overload{
	        [this](palm::msg::Memo& m) {
		        auto ctx = make_ctx();
		        memo->handle(std::move(m.event), ctx);
	        },
	        [this](palm::msg::Address& m) {
		        auto ctx = make_ctx();
		        address->handle(m.event, ctx);
	        },
	        [this](palm::msg::Todo& m) {
		        auto ctx = make_ctx();
		        todo->handle(std::move(m.event), ctx);
	        },
	        [this](palm::msg::Date& m) {
		        auto ctx = make_ctx();
		        date->handle(std::move(m.event), ctx);
	        },
	        [this](palm::msg::Import&) {
		        auto ctx = make_ctx();
		        palm::transfer::import(ctx);
	        },
	        [this](palm::msg::Export&) {
		        const bool addresses = view == palm::msg::View::Address;
		        auto ctx = make_ctx();
		        if (auto status = palm::transfer::export_transfer(ctx, addresses)) {
			        ui.set_status(*status);
		        }
	        },
	        [this](palm::msg::Print&) {
		        const auto ctx = make_ctx();
		        std::pair<std::string, std::vector<std::string>> out;
		        switch (view) {
		        case palm::msg::View::Date:
			        out = date->print_lines(ctx);
			        break;
		        case palm::msg::View::Address:
			        out = address->print_lines(ctx);
			        break;
		        case palm::msg::View::Todo:
			        out = todo->print_lines(ctx);
			        break;
		        case palm::msg::View::Memo:
			        out = memo->print_lines();
			        break;
		        }
		        palm::printing::print(out.first, out.second);
	        },
	        [this](palm::msg::Find&) {
		        const auto ctx = make_ctx();
		        if (auto r = palm::find::show(ctx)) {
			        go_to(*r);
		        }
	        },
	        [this](palm::msg::Options&) {
		        if (palm::options::show(settings, h24)) {
			        refresh_views();
		        }
	        },
	        [this](palm::msg::ShowTodo& m) {
		        show_view(palm::msg::View::Todo);
		        save_setting("view", palm::msg::key(palm::msg::View::Todo));
		        auto ctx = make_ctx();
		        todo->select(ctx, m.id);
	        },
	        [this](palm::msg::Purge&) {
		        switch (view) {
		        case palm::msg::View::Todo:
			        if (palm::dialogs::ask_icon("Purge Completed To Do Items", palm::dialogs::Icon::Question,
			                                    "Purge completed To Do item(s)?", {"OK", "Cancel"},
			                                    std::size_t{0}) == std::size_t{0}) {
				        const std::size_t n = store.purge_todos();
				        palm::dialogs::message("Palm Desktop", palm::dialogs::Icon::Info,
				                               std::format("{} To Do items purged.", n));
			        }
			        break;
		        case palm::msg::View::Date: {
			        auto ctx = make_ctx();
			        palm::event_dialog::purge_events(ctx);
			        break;
		        }
		        default:
			        break;
		        }
	        },
	        [this](palm::msg::Edit& m) { edit(m.cmd); },
	        [](palm::msg::EditState&) {},
	        [this](palm::msg::EditCategories& m) { palm::category::edit_categories(store, m.app); },
	        [this](palm::msg::Categories&) {
		        switch (view) {
		        case palm::msg::View::Memo:
			        palm::category::edit_categories(store, 1);
			        break;
		        case palm::msg::View::Address:
			        palm::category::edit_categories(store, 2);
			        break;
		        case palm::msg::View::Todo:
		        case palm::msg::View::Date:
			        palm::category::edit_categories(store, 0);
			        break;
		        }
	        },
	        [this](palm::msg::ListBy&) {
		        auto ctx = make_ctx();
		        switch (view) {
		        case palm::msg::View::Memo:
			        memo->list_by(ctx);
			        break;
		        case palm::msg::View::Address:
			        address->list_by(ctx);
			        break;
		        default:
			        break;
		        }
	        },
	        [this](palm::msg::CustomLabels&) {
		        auto ctx = make_ctx();
		        if (palm::address_dialog::custom_field_labels(ctx)) {
			        refresh_views();
		        }
	        },
	        [this](palm::msg::SplitMoved& m) { save_setting(m.key, std::to_string(m.width)); },
	        [this](palm::msg::ShowView& m) {
		        show_view(m.view);
		        save_setting("view", palm::msg::key(m.view));
	        },
	        [this](palm::msg::SetShowPrivate& m) {
		        show_private = m.on;
		        ui.set_private_hidden(!m.on);
		        save_setting("showPrivate", m.on ? "true" : "false");
		        refresh_views();
	        },
	        [this](palm::msg::SaveAll&) {
		        store.persist();
		        if (!store.save_error) {
			        ui.set_status("Saved.");
		        }
	        },
	        [this](palm::msg::SyncNow&) { apply(sync.sync_now(store, settings, palm::sync::Report::Menu)); },
	        [this](palm::msg::SyncRequest&) { apply(sync.request(store, settings)); },
	        [this](palm::msg::SyncIfStale&) { apply(sync.request_if_stale(store, settings)); },
	        [this](palm::msg::Changed&) {
		        // A new local change moves the pending sync to one second after this change.
		        Fl::remove_timeout(send_sync_request, timer_sender.get());
		        Fl::add_timeout(CHANGE_DELAY, send_sync_request, timer_sender.get());
	        },
	        [this](palm::msg::SyncAnswer& m) { apply(sync.answer(store, settings, std::move(m.answer))); },
	        [this](palm::msg::SyncOptions&) {
		        if (dialog) {
			        return;
		        }
		        const std::string status = palm::sync::DesktopSync::is_on(settings) ? settings.get("lastSync", "")
		                                                                            : std::string("Sync is off.");
		        const bool saved_token = !settings.get("token", "").empty();
		        dialog =
		            palm::sync_dialog::SyncDialog::open(sender, palm::sync::DesktopSync::server(settings), saved_token,
		                                                palm::sync::DesktopSync::is_on(settings), status);
	        },
	        [this](palm::msg::SyncDialogSetUp&) {
		        if (!dialog) {
			        return;
		        }
		        dialog->set_busy(true);
		        const std::string typed(palm::text::trim(dialog->token->value()));
		        const std::string token = typed.empty() ? settings.get("token", "") : typed;
		        sync.set_up(std::string(palm::text::trim(dialog->server->value())), token, dialog->pass->value());
	        },
	        [this](palm::msg::SetUpDone& m) {
		        apply(sync.finish_set_up(store, settings, std::move(m.result), m.server, m.token));
	        },
	        [this](palm::msg::SyncDialogSyncNow&) {
		        apply(sync.sync_now(store, settings, palm::sync::Report::Dialog));
	        },
	        [this](palm::msg::SyncDialogTurnOff&) {
		        const std::string ask = "Turn off sync on this computer? Your records stay here.";
		        if (palm::dialogs::ask_icon("Palm Desktop", palm::dialogs::Icon::Question, ask, {"Turn Off", "Cancel"},
		                                    std::size_t{0}) == std::size_t{0}) {
			        if (auto r = palm::sync::DesktopSync::turn_off(settings); !r) {
				        alert(std::format("The settings were not saved: {}", r.error()));
			        }
			        close_dialog();
		        }
	        },
	        [this](palm::msg::SyncDialogClose&) { close_dialog(); },
	        [](palm::msg::About&) { palm::dialogs::about(); },
	        [](palm::msg::Exit&) { Fl::hide_all_windows(); },
	    },
	    msg);
	check_save();
	if (store.version != shown_version || day != shown_day) {
		refresh_views();
	}
	update_edit_state();
	palm::mnemonics::refresh();
}

void App::refresh_views() {
	shown_version = store.version;
	auto ctx = make_ctx();
	memo->refresh(ctx);
	address->refresh(ctx);
	todo->refresh(ctx);
	date->refresh(ctx);
	shown_day = day;
	shown_version = store.version;
	update_position();
}

Target App::focus_target() const {
	Fl_Widget* f = Fl::focus();
	if (f == nullptr) {
		return {};
	}
	if (f == memo->editor()) {
		return {TargetKind::Text, palm::text_edit::Field{memo->editor()}};
	}
	if (f == todo->editor()) {
		return {TargetKind::Text, palm::text_edit::Field{todo->editor()}};
	}
	if (Fl_Input* in = date->field_widget(); in != nullptr && f == in) {
		return {TargetKind::Text, palm::text_edit::Field{in}};
	}
	if (f == date->look()) {
		return {TargetKind::Text, palm::text_edit::Field{static_cast<Fl_Input*>(date->look())}};
	}
	if (f == address->look()) {
		return {TargetKind::Text, palm::text_edit::Field{static_cast<Fl_Input*>(address->look())}};
	}
	if (f == memo->table()) {
		return {TargetKind::MemoList, {}};
	}
	if (f == address->table()) {
		return {TargetKind::AddressList, {}};
	}
	if (f == todo->table()) {
		return {TargetKind::TodoList, {}};
	}
	return {};
}

void App::update_edit_state() {
	const Target target = focus_target();
	palm::text_edit::EditState s;
	switch (target.kind) {
	case TargetKind::Text:
		s = palm::text_edit::state(*target.field);
		break;
	case TargetKind::MemoList:
		s = memo->list_edit_state();
		break;
	case TargetKind::AddressList:
		s = address->list_edit_state();
		break;
	case TargetKind::TodoList:
		s = todo->list_edit_state();
		break;
	case TargetKind::None:
		break;
	}
	ui.set_edit_state(s);
	update_record_state(target);
	update_position();
}

// Tells the window which record commands apply: Delete when the view has a selected record or the focused field has a
// text selection, Select All when the view has records, and the Event items when the Date Book has a selected event.
// DateView exposes no accessor for its selection or its records yet, so the Date Book passes true for all three.
void App::update_record_state(const Target& target) {
	const bool text_selected =
	    target.kind == TargetKind::Text && palm::text_edit::state(*target.field).delete_text_edit;
	bool del = false;
	bool all = false;
	bool event = false;
	switch (view) {
	case palm::msg::View::Memo: {
		const auto s = memo->list_edit_state();
		del = s.delete_text_edit;
		all = s.select_all;
		break;
	}
	case palm::msg::View::Address: {
		const auto s = address->list_edit_state();
		del = s.delete_text_edit;
		all = s.select_all;
		break;
	}
	case palm::msg::View::Todo: {
		const auto s = todo->list_edit_state();
		del = s.delete_text_edit;
		all = s.select_all;
		break;
	}
	case palm::msg::View::Date: {
		const bool on = date->has_selected_event();
		del = on;
		all = on;
		event = on;
		break;
	}
	}
	ui.set_record_state(del || text_selected, all, event);
}

void App::edit(palm::msg::EditCmd cmd) {
	using palm::msg::EditCmd;
	const Target target = focus_target();
	if (target.kind == TargetKind::Text) {
		palm::text_edit::TextAction a = palm::text_edit::TextAction::Undo;
		switch (cmd) {
		case EditCmd::Undo:
			a = palm::text_edit::TextAction::Undo;
			break;
		case EditCmd::Cut:
			a = palm::text_edit::TextAction::Cut;
			break;
		case EditCmd::Copy:
			a = palm::text_edit::TextAction::Copy;
			break;
		case EditCmd::Paste:
			a = palm::text_edit::TextAction::Paste;
			break;
		case EditCmd::Delete:
			a = palm::text_edit::TextAction::Delete;
			break;
		case EditCmd::SelectAll:
			a = palm::text_edit::TextAction::SelectAll;
			break;
		// Shift+Delete in a text field keeps its own delete.
		case EditCmd::DeleteNoConfirm:
			return;
		case EditCmd::New:
			new_record();
			return;
		}
		palm::text_edit::run(*target.field, a);
		return;
	}
	const bool list = target.kind == TargetKind::MemoList || target.kind == TargetKind::AddressList ||
	                  target.kind == TargetKind::TodoList;
	std::optional<palm::records::ListAction> action;
	switch (cmd) {
	case EditCmd::New:
		new_record();
		return;
	case EditCmd::SelectAll:
		action = palm::records::action::SelectAll{};
		break;
	case EditCmd::Delete:
	case EditCmd::DeleteNoConfirm:
		action = palm::records::action::Delete{};
		break;
	case EditCmd::Cut:
		if (list) {
			action = palm::records::action::Cut{};
		}
		break;
	case EditCmd::Copy:
		if (list) {
			action = palm::records::action::Copy{};
		}
		break;
	case EditCmd::Paste:
		if (list) {
			action = palm::records::action::Paste{};
		}
		break;
	case EditCmd::Undo:
		return;
	}
	if (!action) {
		return;
	}
	auto ctx = make_ctx();
	ctx.no_confirm = cmd == EditCmd::DeleteNoConfirm;
	switch (view) {
	case palm::msg::View::Memo:
		memo->list_action(*action, ctx);
		break;
	case palm::msg::View::Address:
		address->list_action(*action, ctx);
		break;
	case palm::msg::View::Todo:
		todo->list_action(*action, ctx);
		break;
	// The Date Book deletes the selected event; it has no record clipboard.
	case palm::msg::View::Date:
		if (std::holds_alternative<palm::records::action::Delete>(*action)) {
			date->delete_selected(ctx);
		}
		break;
	}
}

void App::go_to(const palm::runner::Record& r) {
	std::optional<palm::msg::View> v;
	if (std::holds_alternative<palm::model::Event>(r)) {
		v = palm::msg::View::Date;
	} else if (std::holds_alternative<palm::model::Todo>(r)) {
		v = palm::msg::View::Todo;
	} else if (std::holds_alternative<palm::model::Memo>(r)) {
		v = palm::msg::View::Memo;
	} else if (std::holds_alternative<palm::model::Address>(r)) {
		v = palm::msg::View::Address;
	}
	if (!v) {
		return;
	}
	show_view(*v);
	save_setting("view", palm::msg::key(*v));
	auto ctx = make_ctx();
	if (const auto* e = std::get_if<palm::model::Event>(&r)) {
		date->select(ctx, *e);
	} else if (const auto* t = std::get_if<palm::model::Todo>(&r)) {
		todo->select(ctx, t->id);
	} else if (const auto* m = std::get_if<palm::model::Memo>(&r)) {
		memo->select(ctx, m->id);
	} else if (const auto* ad = std::get_if<palm::model::Address>(&r)) {
		address->select(ctx, ad->id);
	}
}

void App::new_record() {
	auto ctx = make_ctx();
	switch (view) {
	case palm::msg::View::Memo:
		memo->new_record(ctx);
		break;
	case palm::msg::View::Address:
		address->new_record(ctx);
		break;
	case palm::msg::View::Todo:
		todo->new_record(ctx);
		break;
	case palm::msg::View::Date:
		date->new_record(ctx);
		break;
	}
}

void App::apply(std::vector<palm::sync::Effect> effects) {
	for (const auto& e : effects) {
		std::visit(palm::msg::Overload{
		               [this](const palm::sync::Status& s) { ui.set_status(s.text); },
		               // The views come in later phases; the position box is the one view state so far.
		               [this](const palm::sync::Refresh&) { update_position(); },
		               [this](const palm::sync::Alert& a) { alert(a.text); },
		               [this](const palm::sync::DialogDone& d) {
			               if (!d.error) {
				               close_dialog();
				               ui.set_status("Sync done.");
				               return;
			               }
			               if (dialog) {
				               dialog->set_busy(false);
				               dialog->set_status(settings.get("lastSync", ""));
			               }
			               alert(*d.error);
		               },
		           },
		           e);
	}
}

void App::close_dialog() {
	if (dialog) {
		dialog->close();
		dialog.reset();
	}
}

void App::alert(std::string_view text) {
	palm::dialogs::message("Palm Desktop", palm::dialogs::Icon::Warning, text);
}

void App::save_setting(std::string_view key, std::string_view value) {
	if (auto r = settings.set(key, value); !r) {
		ui.set_status(std::format("The settings were not saved: {}", r.error()));
	}
}

void App::check_save() {
	if (store.save_error != shown_save_error) {
		shown_save_error = store.save_error;
		if (shown_save_error) {
			const std::string text = std::format("Palm Desktop could not save your records: {}", *shown_save_error);
			ui.set_status(text);
			alert(text);
		}
	}
}

void App::show_view(palm::msg::View v) {
	view = v;
	ui.show_view(v);
	switch (v) {
	case palm::msg::View::Memo:
		memo->focus();
		break;
	case palm::msg::View::Address:
		address->focus();
		break;
	case palm::msg::View::Todo:
		todo->focus();
		break;
	case palm::msg::View::Date:
		date->focus();
		break;
	}
	update_edit_state();
}

void App::update_position() {
	std::string text;
	switch (view) {
	case palm::msg::View::Date: {
		const palm::days::Ymd d = palm::days::ymd(palm::platform::today());
		text = std::format("{}/{}/{}", d.month, d.day, d.year);
		break;
	}
	case palm::msg::View::Memo:
		text = memo->position();
		break;
	case palm::msg::View::Address:
		text = address->position();
		break;
	case palm::msg::View::Todo:
		text = todo->position();
		break;
	}
	ui.set_position(text);
}

} // namespace

int main(int argc, char** argv) {
	const std::vector<std::string> args(argv, argv + argc);
	// Fl::lock() runs once, before any thread starts that sends a message.
	Fl::lock();
	palm::theme::install();
	auto channel = palm::msg::channel();
	palm::msg::Sender sender = channel.first;
	palm::msg::Receiver receiver = channel.second;

	const std::filesystem::path dir = palm::settings::config_dir();
	auto lock = palm::store::lock(dir);
	if (!lock) {
		fail(lock.error());
	}
	const std::filesystem::path path = dir / palm::store::FILE_NAME;
	auto opened = palm::store::DesktopStore::open(path);
	if (!opened) {
		fail(
		    std::format("Palm Desktop could not read {}: {}\nThe file stays as it is.", path.string(), opened.error()));
	}
	palm::store::DesktopStore desk = std::move(*opened);
	desk.on_change = [sender] { sender.send(palm::msg::Changed{}); };
	palm::settings::Settings settings = palm::settings::Settings::open(dir);
	const bool h24 = palm::platform::uses_24_hours();

	palm::window::Ui ui = palm::window::Ui::build(sender);
	App a(std::move(*lock), std::move(desk), std::move(settings), std::move(ui), sender, h24);

	Fl_Group* g = a.ui.view_group(palm::msg::View::Memo);
	g->begin();
	a.memo =
	    std::make_unique<palm::memo::MemoView>(g->x() + 6, g->y() + 6, g->w() - 12, g->h() - 12, sender, a.settings);
	g->resizable(a.memo->root);
	g->end();
	g = a.ui.view_group(palm::msg::View::Address);
	g->begin();
	a.address = std::make_unique<palm::address::AddressView>(g->x() + 6, g->y() + 6, g->w() - 12, g->h() - 12, sender,
	                                                         a.settings);
	g->resizable(a.address->root);
	g->end();
	g = a.ui.view_group(palm::msg::View::Todo);
	g->begin();
	a.todo =
	    std::make_unique<palm::todo::TodoView>(g->x() + 6, g->y() + 6, g->w() - 12, g->h() - 12, sender, a.settings);
	g->resizable(a.todo->root);
	g->end();
	g = a.ui.view_group(palm::msg::View::Date);
	g->begin();
	a.date = std::make_unique<palm::date::DateView>(g->x(), g->y(), g->w(), g->h(), sender, a.settings);
	g->resizable(a.date->root);
	g->end();
	palm::mnemonics::install();
	a.ui.set_private_hidden(!a.show_private);

	// The startView setting wins. Without it, the view of the last session opens.
	std::string start = a.settings.get("startView", "");
	if (start.empty()) {
		start = a.settings.get("view", "date");
	}
	a.refresh_views();
	a.show_view(palm::msg::from_key(start).value_or(palm::msg::View::Date));
	a.ui.win->show();
	palm::mnemonics::refresh();
	std::optional<std::size_t> shot_at;
	for (std::size_t i = 0; i < args.size(); ++i) {
		if (args[i] == "--shot") {
			shot_at = i;
			break;
		}
	}
	if (!shot_at) {
		a.ui.win->maximize();
	}

	// Sync now when sync is on, then every hour, 1 s after each local change, and when the window comes to the front
	// after a minute without a sync.
	sender.send(palm::msg::SyncRequest{});
	Fl::add_timeout(HOUR, hourly_sync, a.timer_sender.get());

	std::optional<ShotJob> shot;
	if (shot_at) {
		std::string out = *shot_at + 1 < args.size() ? args[*shot_at + 1] : std::string("shot.ppm");
		shot.emplace(ShotJob{a.ui.win, std::move(out)});
		Fl::add_timeout(1.0, save_shot, &*shot);
	}

	// The loop ends when no window is shown, as the Rust loop ends when app.wait() returns false.
	while (Fl::wait()) {
		while (auto m = receiver.recv()) {
			a.handle(std::move(*m));
		}
	}
	return 0;
}
