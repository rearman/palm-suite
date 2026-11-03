// The Options dialog of Dialogs.kt: the General tab (the startup view and the delete question) and the Date Book tab
// (the work day, the first day of the week and the alarm preset), as desktop-rs/app/src/options.rs.

#include "palm/options.hpp"

#include "palm/combo.hpp"
#include "palm/date_shared.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/settings.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Spinner.H>
#include <FL/Fl_Tabs.H>
#include <FL/Fl_Window.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <utility>

namespace palm::options {

namespace {

// The startup views: the key of each in settings, then its name in the menu.
constexpr std::array<std::pair<std::string_view, std::string_view>, 4> START_VIEWS = {{
    {"date", "Date Book"},
    {"address", "Address Book"},
    {"todo", "To Do List"},
    {"memo", "Memo Pad"},
}};

// Moves the keyboard focus to `target`, which a key label stands for.
void take_focus_cb(Fl_Widget*, void* target) {
	static_cast<Fl_Widget*>(target)->take_focus();
}

// The widgets that the alarm preset shows and hides.
struct PresetRow {
	Fl_Spinner* amount = nullptr;
	Fl_Choice* unit = nullptr;
};

// The answer of the dialog: whether OK was pressed, and the window that the buttons close.
struct Answer {
	Fl_Window* win = nullptr;
	bool saved = false;
};

// Shows the amount and the unit of the alarm preset when `on`, and hides them otherwise.
void show_preset(bool on, Fl_Spinner* amount, Fl_Choice* unit) {
	if (on) {
		amount->show();
		unit->show();
	} else {
		amount->hide();
		unit->hide();
	}
}

// The alarm preset check box changed: shows or hides its amount and unit.
void preset_changed(Fl_Widget* w, void* data) {
	const auto* row = static_cast<const PresetRow*>(data);
	show_preset(static_cast<Fl_Check_Button*>(w)->value() != 0, row->amount, row->unit);
}

} // namespace

bool show(settings::Settings& settings, bool h24) {
	// The answer and the preset rows are declared before the window, so the window is deleted first.
	Answer answer;
	PresetRow preset_row;
	// The 3.1.1 Options dialog is 361 x 275 px client (367 x 299 outer) on every tab.
	auto win = std::make_unique<Fl_Double_Window>(361, 275, "Options");
	answer.win = win.get();
	auto* tabs = new Fl_Tabs(8, 8, 345, 229);
	const auto page = [](const char* label) {
		auto* g = new Fl_Group(8, 30, 345, 207, label);
		g->box(FL_UP_BOX);
		return g;
	};

	Fl_Group* general = page("&General");
	auto* start = new palm::combo::Choice(133, 48, 197, 22);
	for (const auto& view : START_VIEWS) {
		start->add(std::string(view.second).c_str());
	}
	draw::style_menu(start);
	const std::string cur = settings.get("startView", "date");
	const auto found =
	    std::find_if(START_VIEWS.begin(), START_VIEWS.end(), [&cur](const auto& view) { return view.first == cur; });
	start->value(found == START_VIEWS.end() ? 0 : static_cast<int>(found - START_VIEWS.begin()));
	auto* start_l = dialogs::key_label("&Startup application:", 20, 48, 100, 22, FL_ALIGN_RIGHT);
	start_l->callback(take_focus_cb, start);
	auto* confirm = draw::check(28, 88, 280, 22, "&Confirm before deleting records");
	confirm->value(settings.get_bool("confirmDelete", true));
	general->end();

	Fl_Group* date = page("Date &Book");
	auto* frame = new Fl_Group(20, 40, 321, 108, "Date and Time");
	frame->box(FL_ENGRAVED_BOX);
	frame->align(FL_ALIGN_TOP_LEFT | FL_ALIGN_INSIDE);
	auto* work = new palm::combo::Choice(142, 58, 175, 22);
	for (int h = 0; h < 24; ++h) {
		work->add(date_shared::time_label(h * 60, h24).c_str());
	}
	draw::style_menu(work);
	work->value(date_shared::work_day(settings));
	auto* week = new palm::combo::Choice(142, 86, 175, 22);
	week->add("Sunday|Monday");
	draw::style_menu(week);
	week->value(static_cast<int>(date_shared::week_begins(settings)));
	auto* preset = draw::check(142, 114, 18, 22, "");
	preset->value(settings.get_bool("alarmPreset", false));
	auto* amount = date_shared::count_spinner(164, 114, 50, 22, settings.get_int("alarmPresetAmount", 5), 1);
	auto* unit = new palm::combo::Choice(218, 114, 115, 22);
	unit->add("Minutes|Hours|Days");
	draw::style_menu(unit);
	unit->value(std::clamp(settings.get_int("alarmPresetUnit", 0), 0, 2));
	const auto bind = [](std::string_view text, int y, Fl_Widget* target) {
		auto* l = dialogs::key_label(text, 30, y, 100, 22, FL_ALIGN_RIGHT);
		l->callback(take_focus_cb, target);
	};
	bind("Work &day begins:", 58, work);
	bind("&Week begins:", 86, week);
	bind("Alarm &preset:", 114, preset);
	frame->end();
	date->end();
	tabs->end();

	auto* ok = new Fl_Button(196, 245, 75, 23, "OK");
	ok->shortcut(FL_Enter);
	auto* cancel = new Fl_Button(278, 245, 75, 23, "Cancel");
	ok->callback(
	    [](Fl_Widget*, void* data) {
		    auto* a = static_cast<Answer*>(data);
		    a->saved = true;
		    a->win->hide();
	    },
	    &answer);
	cancel->callback([](Fl_Widget*, void* data) { static_cast<Answer*>(data)->win->hide(); }, &answer);
	win->end();

	// The amount and the unit show only when the alarm preset is checked.
	show_preset(preset->value() != 0, amount, unit);
	preset_row.amount = amount;
	preset_row.unit = unit;
	preset->callback(preset_changed, &preset_row);

	dialogs::run_modal(win.get());
	if (!answer.saved) {
		return false;
	}
	const auto start_key = START_VIEWS[static_cast<std::size_t>(std::max(start->value(), 0))].first;
	// A failed save is ignored, as in the Rust port.
	static_cast<void>(settings.set("startView", start_key));
	static_cast<void>(settings.set_bool("confirmDelete", confirm->value() != 0));
	static_cast<void>(settings.set_int("workDay", std::max(work->value(), 0)));
	static_cast<void>(settings.set_int("weekStart", std::max(week->value(), 0)));
	static_cast<void>(settings.set_bool("alarmPreset", preset->value() != 0));
	static_cast<void>(settings.set_int("alarmPresetAmount", static_cast<int>(amount->value())));
	static_cast<void>(settings.set_int("alarmPresetUnit", std::max(unit->value(), 0)));
	return true;
}

} // namespace palm::options
