// The main window, as desktop-rs/app/src/window.rs. A Rust closure of a widget becomes a callback that gets the Command
// of the widget as its user data; the Rust handle closure of the window is MainWindow::handle.
#include "palm/window.hpp"

#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Image.H>
#include <FL/Fl_Menu_Bar.H>
#include <FL/Fl_Menu_Item.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/Fl_Radio_Button.H>
#include <FL/Fl_Window.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <optional>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>

namespace palm::window {

namespace {

constexpr int W = 1000;
constexpr int H = 700;
constexpr int MENU_H = 20;
constexpr int TOOL_H = 30;
constexpr int STATUS_H = 22;
constexpr int LAUNCHER_W = 72;
// A launcher button is 57 x 44 px, and the next one starts 51 px lower, as Palm Desktop 3.1.1.
constexpr int LAUNCHER_BTN_W = 57;
constexpr int LAUNCHER_BTN_H = 44;
constexpr int LAUNCHER_STEP = 51;

// Ctrl with a letter key.
int ctrl(char c) {
	return FL_CTRL | c;
}

// The item at `path`, or nullptr. FLTK returns it const, but its flags change in place, as they do in mnemonics.cpp.
Fl_Menu_Item* find_menu_item(Fl_Menu_Bar* bar, const char* path) {
	return const_cast<Fl_Menu_Item*>(bar->find_item(path));
}

// Sends the message of the command that the widget's user data names.
void fire(Fl_Widget*, void* data) {
	const auto* c = static_cast<const Command*>(data);
	c->sender.send(c->msg);
}

// The close box and File > Exit send Exit. FLTK runs the callback of a window on Escape too, and that sends nothing.
void on_close(Fl_Widget*, void* data) {
	if (Fl::event() == FL_CLOSE) {
		fire(nullptr, data);
	}
}

// Keeps a command in `list` and returns it at its fixed address.
Command* command(std::list<Command>& list, const msg::Sender& s, msg::Msg m) {
	return &list.emplace_back(Command{s, std::move(m)});
}

// Keeps an image for the window, which the widget uses without owning it.
template <typename T> T* keep(MainWindow* w, std::unique_ptr<T> img) {
	T* p = img.get();
	w->images.push_back(std::move(img));
	return p;
}

// Adds the item `path`, with its shortcut and flags, that sends `m` when chosen.
void add_item(Fl_Menu_Bar* bar, std::list<Command>& commands, const msg::Sender& s, const char* path, int key,
              int flags, msg::Msg m) {
	bar->add(path, key, fire, command(commands, s, std::move(m)), flags);
}

// The Edit submenu of one view, as Palm Desktop 3.1.1: the text commands, Delete and Select All, the items of the view,
// then Find... at the end.
void edit_menu(Fl_Menu_Bar* bar, std::list<Command>& commands, const msg::Sender& s, msg::View view) {
	const int normal = 0;
	const int divider = FL_MENU_DIVIDER;
	auto item = [&](const char* path, int key, int flags, msg::Msg m) {
		add_item(bar, commands, s, path, key, flags, std::move(m));
	};

	item("&Edit/&Undo", ctrl('z'), divider, msg::Edit{msg::EditCmd::Undo});
	item("&Edit/Cu&t", ctrl('x'), normal, msg::Edit{msg::EditCmd::Cut});
	item("&Edit/&Copy", ctrl('c'), normal, msg::Edit{msg::EditCmd::Copy});
	item("&Edit/&Paste", ctrl('v'), normal, msg::Edit{msg::EditCmd::Paste});
	item("&Edit/&Delete", FL_Delete, divider, msg::Edit{msg::EditCmd::Delete});
	item("&Edit/Select &All", ctrl('a'), divider, msg::Edit{msg::EditCmd::SelectAll});
	switch (view) {
	case msg::View::Date:
		item("&Edit/&New Event...", ctrl('n'), normal, msg::Edit{msg::EditCmd::New});
		item("&Edit/&Edit Event...", 0, normal, msg::Date{msg::date::Edit{}});
		item("&Edit/De&lete Event", 0, divider, msg::Edit{msg::EditCmd::Delete});
		break;
	case msg::View::Todo:
		item("&Edit/&New To Do", ctrl('n'), divider, msg::Edit{msg::EditCmd::New});
		break;
	case msg::View::Memo:
		item("&Edit/&New Memo", ctrl('n'), divider, msg::Edit{msg::EditCmd::New});
		break;
	case msg::View::Address:
		item("&Edit/&New Address...", ctrl('n'), normal, msg::Edit{msg::EditCmd::New});
		item("&Edit/&Dial...", ctrl('i'), divider, msg::Address{msg::address::Dial{}});
		break;
	}
	item("&Edit/&Find...", ctrl('f'), normal, msg::Find{});
}

// Grays the Edit items that do not apply: the text commands by the Edit state, and Delete, Select All and the event
// items by the record state.
void apply_edit_state(Fl_Menu_Bar* bar, const text_edit::EditState& edit, const RecordState& record) {
	const std::array<std::pair<const char*, bool>, 8> items = {{
	    {"&Edit/&Undo", edit.undo},
	    {"&Edit/Cu&t", edit.cut},
	    {"&Edit/&Copy", edit.copy},
	    {"&Edit/&Paste", edit.paste},
	    {"&Edit/&Delete", record.delete_record},
	    {"&Edit/Select &All", record.select_all},
	    {"&Edit/&Edit Event...", record.event},
	    {"&Edit/De&lete Event", record.event},
	}};
	for (const auto& [path, on] : items) {
		if (Fl_Menu_Item* item = find_menu_item(bar, path)) {
			if (on) {
				item->activate();
			} else {
				item->deactivate();
			}
		}
	}
}

// The menu bar of MainFrame.kt. The Edit submenu comes from edit_menu. Items whose feature comes in a later phase of
// the port are grayed.
std::tuple<Fl_Menu_Bar*, ViewItems, OnlyIn> menu_bar(MainWindow* w) {
	auto* bar = new Fl_Menu_Bar(0, 0, W, MENU_H);
	bar->box(FL_FLAT_BOX);
	bar->textsize(11);
	auto item = [&](const char* path, int key, int flags, msg::Msg m) {
		add_item(bar, w->commands, w->sender, path, key, flags, std::move(m));
	};
	const int normal = 0;
	const int divider = FL_MENU_DIVIDER;
	const int later = FL_MENU_INACTIVE;

	item("&File/&Save All", ctrl('s'), divider, msg::SaveAll{});
	item("&File/&Import...", 0, normal, msg::Import{});
	item("&File/&Export...", 0, divider, msg::Export{});
	bar->add("&File/Page Set&up...", 0, nullptr, nullptr, later);
	item("&File/&Print...", ctrl('p'), divider, msg::Print{});
	item("&File/E&xit", 0, normal, msg::Exit{});
	// The Edit submenu of the start view; Ui::show_view rebuilds it for each view.
	edit_menu(bar, w->edit_commands, w->sender, msg::View::Date);

	const std::array<std::tuple<msg::View, const char*, int>, 4> views = {{
	    {msg::View::Date, "&View/&Date Book", FL_F + 2},
	    {msg::View::Address, "&View/&Address Book", FL_F + 3},
	    {msg::View::Todo, "&View/T&o Do List", FL_F + 4},
	    {msg::View::Memo, "&View/&Memo Pad", FL_F + 5},
	}};
	for (std::size_t i = 0; i < views.size(); ++i) {
		const auto [view, path, key] = views[i];
		const int flags = i == 3 ? (FL_MENU_TOGGLE | FL_MENU_DIVIDER) : FL_MENU_TOGGLE;
		item(path, key, flags, msg::ShowView{view});
	}
	item("&View/&Show Private Records", 0, FL_MENU_TOGGLE, msg::SetShowPrivate{true});
	item("&View/&Hide Private Records", 0, FL_MENU_TOGGLE | FL_MENU_DIVIDER, msg::SetShowPrivate{false});
	item("&View/Sh&ow...", 0, normal, msg::Todo{msg::todo::Show{}});
	item("&View/&List By...", 0, normal, msg::ListBy{});

	item("&Tools/&Categories...", 0, divider, msg::Categories{});
	item("&Tools/Custom &Field Labels...", 0, divider, msg::CustomLabels{});
	// Purge names what it deletes: the events of the Date Book, the completed items of the To Do List.
	item("&Tools/&Purge Events...", 0, divider, msg::Purge{});
	item("&Tools/&Purge Completed To Do's...", 0, divider, msg::Purge{});
	item("&Tools/&Options...", 0, normal, msg::Options{});

	item("&Sync/&Options...", 0, normal, msg::SyncOptions{});
	item("&Sync/Sync &Now", 0, normal, msg::SyncNow{});

	item("&Help/&About Palm Desktop...", 0, normal, msg::About{});

	ViewItems view_items;
	for (const auto& [v, path, key] : views) {
		view_items.emplace_back(v, path);
	}
	OnlyIn only_in = {
	    {"&View/Sh&ow...", {msg::View::Todo}},
	    {"&View/&List By...", {msg::View::Memo, msg::View::Address}},
	    {"&Tools/&Categories...", {msg::View::Todo, msg::View::Memo, msg::View::Address}},
	    {"&Tools/Custom &Field Labels...", {msg::View::Address}},
	    {"&Tools/&Purge Events...", {msg::View::Date}},
	    {"&Tools/&Purge Completed To Do's...", {msg::View::Todo}},
	};
	return {bar, std::move(view_items), std::move(only_in)};
}

// The toolbar of MainFrame.kt. Returns the private-record mark at its right end, and the buttons whose state changes.
std::pair<Fl_Box*, std::vector<std::pair<const char*, Fl_Button*>>> toolbar(MainWindow* w) {
	auto* g = new Fl_Group(0, MENU_H, W, TOOL_H);
	g->box(FL_FLAT_BOX);
	int x = 2;
	std::vector<std::pair<const char*, Fl_Button*>> tools;
	auto add = [&](const char* png, const char* tip, std::optional<msg::Msg> m, bool gap) {
		auto* b = new Fl_Button(x, MENU_H + 3, 24, 24);
		b->box(FL_UP_BOX);
		b->down_box(FL_DOWN_BOX);
		b->image(keep(w, theme::icon(png)));
		b->deimage(keep(w, theme::gray_icon(png)));
		b->tooltip(tip);
		b->clear_visible_focus();
		if (m) {
			b->callback(fire, command(w->commands, w->sender, *m));
		} else {
			// The feature comes in a later phase of the port.
			b->deactivate();
		}
		x += 24 + (gap ? 10 : 0);
		tools.emplace_back(png, b);
	};
	add("save", "Save All", msg::SaveAll{}, false);
	add("print", "Print", msg::Print{}, true);
	add("cut", "Cut", msg::Edit{msg::EditCmd::Cut}, false);
	add("copy", "Copy", msg::Edit{msg::EditCmd::Copy}, false);
	add("paste", "Paste", msg::Edit{msg::EditCmd::Paste}, false);
	add("undo", "Undo", msg::Edit{msg::EditCmd::Undo}, true);
	add("find", "Find", msg::Find{}, true);
	add("new", "New Item", msg::Edit{msg::EditCmd::New}, false);
	auto* spacer = new Fl_Box(x, MENU_H, W - x - 30, TOOL_H);
	auto* mark = new Fl_Box(W - 26, MENU_H + 3, 24, 24);
	mark->image(keep(w, theme::icon("private")));
	mark->tooltip("Private records are hidden");
	g->resizable(spacer);
	g->end();
	const std::array<std::string_view, 5> kept = {"cut", "copy", "paste", "undo", "new"};
	std::erase_if(tools,
	              [&](const auto& t) { return std::ranges::find(kept, std::string_view(t.first)) == kept.end(); });
	return {mark, std::move(tools)};
}

// A launcher button: the 24 px app icon over its name. It has no Alt key: F2 to F5 from the View menu select the views,
// as in Palm Desktop.
Fl_Radio_Button* launcher_button(MainWindow* w, int y, const char* label, const char* png, msg::View view) {
	auto* b = new Fl_Radio_Button(7, y, LAUNCHER_BTN_W, LAUNCHER_BTN_H, label);
	b->labelfont(theme::fonts().segoe);
	b->labelsize(12);
	b->box(FL_UP_BOX);
	b->down_box(FL_DOWN_BOX);
	b->selection_color(fl_rgb_color(250, 250, 250));
	b->image(keep(w, theme::icon(png)));
	b->align(FL_ALIGN_INSIDE | FL_ALIGN_CENTER);
	b->clear_visible_focus();
	b->callback(fire, command(w->commands, w->sender, msg::ShowView{view}));
	return b;
}

// The area of one view; each view builds itself in its group.
Fl_Group* view_group(msg::View view, int x, int y, int w, int h) {
	auto* g = new Fl_Group(x, y, w, h);
	g->box(FL_FLAT_BOX);
	g->color(view == msg::View::Date ? theme::WORKSPACE : theme::PANEL);
	g->end();
	g->hide();
	return g;
}

// The status bar: the status text at the left, and the position box (the date or "3 of 12") at the right.
std::pair<Fl_Box*, Fl_Box*> status_bar() {
	auto* g = new Fl_Group(0, H - STATUS_H, W, STATUS_H);
	g->box(FL_ENGRAVED_BOX);
	auto* status = new Fl_Box(5, H - STATUS_H + 2, W - 110, STATUS_H - 4, "Ready");
	status->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	status->labelsize(12);
	auto* position = new Fl_Box(W - 98, H - STATUS_H + 2, 90, 17);
	position->box(FL_THIN_DOWN_BOX);
	position->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	position->labelsize(12);
	g->resizable(status);
	g->end();
	return {status, position};
}

} // namespace

MainWindow::MainWindow(msg::Sender s) : Fl_Double_Window(W, H, "Palm Desktop"), sender(std::move(s)) {}

int MainWindow::handle(int event) {
	const int result = Fl_Double_Window::handle(event);
	switch (event) {
	case FL_FOCUS:
		sender.send(msg::SyncIfStale{});
		break;
	case FL_PUSH:
	case FL_RELEASE:
	case FL_KEYUP:
		sender.send(msg::EditState{});
		break;
	case FL_KEYDOWN:
		if (Fl::event_key() == FL_Delete && (Fl::event_state() & FL_SHIFT) != 0) {
			sender.send(msg::Edit{msg::EditCmd::DeleteNoConfirm});
		}
		break;
	default:
		break;
	}
	return result;
}

void MainWindow::resize(int x, int y, int w, int h) {
	Fl_Double_Window::resize(x, y, w, h);
	if (shown() && visible() && !maximize_active() && !fullscreen_active()) {
		boxes.note(platform_ui::Box{this->x(), this->y(), this->w(), this->h()});
	}
}

Ui Ui::build(msg::Sender s) {
	auto* win = new MainWindow(s);
	win->color(theme::PANEL);
	win->size_range(640, 480, 0, 0);
	win->icon(keep(win, theme::icon("app48")));

	auto [bar, view_items, only_in] = menu_bar(win);
	draw::style_menu(bar);
	auto [private_icon, tools] = toolbar(win);
	private_icon->hide();

	auto* launcher = new Fl_Group(0, MENU_H + TOOL_H, LAUNCHER_W, H - MENU_H - TOOL_H - STATUS_H);
	launcher->box(FL_UP_BOX);
	std::vector<std::pair<msg::View, Fl_Radio_Button*>> launchers;
	int y = launcher->y() + 6;
	const std::array<std::tuple<msg::View, const char*, const char*>, 4> buttons = {{
	    {msg::View::Date, "Date", "date"},
	    {msg::View::Address, "Address", "address"},
	    {msg::View::Todo, "To Do", "todo"},
	    {msg::View::Memo, "Memo", "memo"},
	}};
	for (const auto& [view, label, png] : buttons) {
		launchers.emplace_back(view, launcher_button(win, y, label, png, view));
		y += LAUNCHER_STEP;
	}
	// The empty space below the buttons takes the height of a taller window.
	auto* spacer = new Fl_Box(0, y, LAUNCHER_W, launcher->h() - (y - launcher->y()));
	launcher->resizable(spacer);
	launcher->end();

	const int cx = LAUNCHER_W;
	const int cy = MENU_H + TOOL_H;
	const int cw = W - LAUNCHER_W;
	const int ch = H - MENU_H - TOOL_H - STATUS_H;
	auto* center = new Fl_Group(cx, cy, cw, ch);
	center->box(FL_FLAT_BOX);
	center->color(theme::PANEL);
	// A 3 px margin, then the raised bevel of the official window.
	auto* bevel = new Fl_Box(cx + 3, cy + 3, cw - 6, ch - 6);
	bevel->box(FL_UP_BOX);
	std::vector<std::pair<msg::View, Fl_Group*>> views;
	for (const msg::View v : msg::ALL) {
		views.emplace_back(v, palm::window::view_group(v, cx + 5, cy + 5, cw - 10, ch - 10));
	}
	center->resizable(bevel);
	center->end();

	auto [status, position] = status_bar();
	win->resizable(center);
	win->end();
	dialogs::set_main(win);
	win->callback(on_close, command(win->commands, s, msg::Exit{}));

	Ui ui;
	ui.win = win;
	ui.bar = bar;
	ui.launchers = std::move(launchers);
	ui.views = std::move(views);
	ui.status = status;
	ui.position = position;
	ui.private_icon = private_icon;
	ui.view_items = std::move(view_items);
	ui.only_in = std::move(only_in);
	ui.tools = std::move(tools);
	ui.build_edit_menu();
	return ui;
}

void Ui::show_view(msg::View v) {
	for (auto& [view_id, g] : views) {
		if (view_id == v) {
			g->show();
		} else {
			g->hide();
		}
	}
	for (auto& [view_id, b] : launchers) {
		b->value(view_id == v);
	}
	view = v;
	// The menu items are found by their paths, which hold '&', and the Edit menu is built again.
	mnemonics::with_originals([&] { update_menus(v); });
	win->redraw();
}

Fl_Group* Ui::view_group(msg::View v) const {
	for (const auto& [view_id, g] : views) {
		if (view_id == v) {
			return g;
		}
	}
	throw std::runtime_error("every view has a group");
}

void Ui::set_status(std::string_view text) {
	std::string escaped;
	for (const char c : text) {
		escaped += c;
		if (c == '@') {
			escaped += '@';
		}
	}
	status->copy_label(escaped.c_str());
}

void Ui::set_position(std::string_view text) {
	const std::string label(text);
	position->copy_label(label.c_str());
}

void Ui::set_edit_state(const text_edit::EditState& s) {
	edit = s;
	mnemonics::with_originals([&] { apply_edit_state(bar, edit, record); });
	const std::array<std::pair<const char*, bool>, 4> tool_states = {{
	    {"undo", s.undo},
	    {"cut", s.cut},
	    {"copy", s.copy},
	    {"paste", s.paste},
	}};
	for (const auto& [name, on] : tool_states) {
		set_tool(name, on);
	}
}

void Ui::set_record_state(bool delete_record, bool select_all, bool event) {
	record = RecordState{delete_record, select_all, event};
	mnemonics::with_originals([&] { apply_edit_state(bar, edit, record); });
}

void Ui::set_private_hidden(bool hidden) {
	if (hidden) {
		private_icon->show();
	} else {
		private_icon->hide();
	}
	const std::array<std::pair<const char*, bool>, 2> items = {{
	    {"&View/&Show Private Records", !hidden},
	    {"&View/&Hide Private Records", hidden},
	}};
	mnemonics::with_originals([&] {
		for (const auto& [path, on] : items) {
			if (Fl_Menu_Item* item = find_menu_item(bar, path)) {
				if (on) {
					item->set();
				} else {
					item->clear();
				}
			}
		}
	});
	win->redraw();
}

// Checks the View item of `v`, grays the items of other views and builds the Edit menu of `v`.
void Ui::update_menus(msg::View v) {
	for (const auto& [item_view, path] : view_items) {
		if (Fl_Menu_Item* item = find_menu_item(bar, path)) {
			if (item_view == v) {
				item->set();
			} else {
				item->clear();
			}
		}
	}
	for (const auto& [path, views_in] : only_in) {
		if (Fl_Menu_Item* item = find_menu_item(bar, path)) {
			if (std::find(views_in.begin(), views_in.end(), v) != views_in.end()) {
				item->activate();
			} else {
				item->deactivate();
			}
		}
	}
	build_edit_menu();
}

// Clears the Edit submenu and adds the items of the view on show, so no item is hidden. The Edit state and the record
// state carry over.
void Ui::build_edit_menu() {
	const int idx = bar->find_index("&Edit");
	if (bar->clear_submenu(idx) != 0) {
		throw std::runtime_error("the Edit menu exists");
	}
	win->edit_commands.clear();
	edit_menu(bar, win->edit_commands, win->sender, view);
	apply_edit_state(bar, edit, record);
}

void Ui::set_tool(std::string_view name, bool on) {
	for (const auto& [n, b] : tools) {
		if (name == n) {
			if (on) {
				b->activate();
			} else {
				b->deactivate();
			}
		}
	}
}

} // namespace palm::window
