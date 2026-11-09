// The main window, as MainFrame.kt and desktop-rs/app/src/window.rs: the menus, the toolbar, the launcher bar on the
// left, the four views in the middle and the status bar at the bottom. Every control sends a msg::Msg; the main loop
// does the work.
#pragma once

#include "palm/msg.hpp"
#include "palm/platform_ui.hpp"
#include "palm/text_edit.hpp"

#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Image.H>

#include <list>
#include <memory>
#include <string_view>
#include <utility>
#include <vector>

class Fl_Box;
class Fl_Button;
class Fl_Group;
class Fl_Image;
class Fl_Menu_Bar;
class Fl_Radio_Button;

namespace palm::window {

// The message of one menu item, toolbar button, launcher button or the window close. A widget keeps the address of its
// command as its user data, so the list that holds the commands keeps each one at a fixed address.
struct Command {
	msg::Sender sender;
	msg::Msg msg;
};

// The View menu item of each view, by its path. It has a check mark while its view shows.
using ViewItems = std::vector<std::pair<msg::View, const char*>>;

// Menu items that apply to some views only, by their path. The other views gray them.
using OnlyIn = std::vector<std::pair<const char*, std::vector<msg::View>>>;

// Which record items of the Edit menu apply to the selection, as main.rs reports them.
struct RecordState {
	// Delete and Select All. The Rust field `delete` is a C++ keyword, so it takes the module name.
	bool delete_record = true;
	bool select_all = true;
	// Edit Event... and Delete Event.
	bool event = true;
};

// The main window. It sends the window events to the main loop, and it owns the commands and the images of its
// widgets, so both live as long as the window.
class MainWindow : public Fl_Double_Window {
public:
	MainWindow(msg::Sender s);

	// FLTK's own handler runs first, then the window sends its messages, as the Rust closure of the handle does.
	int handle(int event) override;

	// FLTK's own resize runs first. Then the box of a shown window that is not maximized is noted in `boxes`. FLTK's
	// position() also comes here.
	void resize(int x, int y, int w, int h) override;

	msg::Sender sender;
	// The window's last boxes while not maximized, for the placement saved at exit on X11.
	platform_ui::BoxKeeper boxes;
	// The commands of the menu bar, the toolbar, the launcher bar and the close box.
	std::list<Command> commands;
	// The commands of the Edit menu, which is built again for each view.
	std::list<Command> edit_commands;
	// The images of the widgets. A widget uses its image without owning it.
	std::vector<std::unique_ptr<Fl_Image>> images;
};

// The main window and its widgets. Each view places itself in its group, from view_group.
class Ui {
public:
	// Builds the main window, and sends the messages of its controls through `s`.
	static Ui build(msg::Sender s);

	// Shows the view and checks its launcher button and View menu item, and grays the menu items that belong to other
	// views. The Edit menu takes the items of the view.
	void show_view(msg::View view);

	// The group of one view, for the view to build its widgets in.
	Fl_Group* view_group(msg::View view) const;

	void set_status(std::string_view text);
	void set_position(std::string_view text);

	// Grays the Edit items and toolbar buttons that do not apply to the focus owner.
	void set_edit_state(const text_edit::EditState& s);

	// Grays Delete and Select All when no record is selected, and Edit Event... and Delete Event when no event is
	// selected, as Palm Desktop 3.1.1 does.
	void set_record_state(bool delete_record, bool select_all, bool event);

	// The toolbar shows the private-record mark while private records are hidden.
	void set_private_hidden(bool hidden);

	MainWindow* win = nullptr;

private:
	void update_menus(msg::View view);
	void build_edit_menu();
	void set_tool(std::string_view name, bool on);

	Fl_Menu_Bar* bar = nullptr;
	std::vector<std::pair<msg::View, Fl_Radio_Button*>> launchers;
	std::vector<std::pair<msg::View, Fl_Group*>> views;
	Fl_Box* status = nullptr;
	Fl_Box* position = nullptr;
	Fl_Box* private_icon = nullptr;
	ViewItems view_items;
	OnlyIn only_in;
	// The toolbar buttons whose state changes: "cut", "copy", "paste", "undo" and "new".
	std::vector<std::pair<const char*, Fl_Button*>> tools;
	// The view on show. The Edit menu holds the items of this view.
	msg::View view = msg::View::Date;
	text_edit::EditState edit;
	RecordState record;
};

} // namespace palm::window
