// The To Do List view, as TodoPanel.kt and TodoMenus.kt: the table of items with its category filter and the Show
// and New buttons on the left, and the details of the selected item on the right. The rules at the end (shown, order,
// due label, completion) are the ones the To Do part of the Day view follows too, as desktop-rs/app/src/todo.rs.
#pragma once

#include "palm/category.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/records.hpp"
#include "palm/settings.hpp"
#include "palm/store.hpp"
#include "palm/text_edit.hpp"
#include "palm/view.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

class Fl_Button;
class Fl_Check_Button;
class Fl_Group;
class Fl_Output;
class Fl_PNG_Image;
class Fl_RGB_Image;
class Fl_Round_Button;
class Fl_Table_Row;
class Fl_Text_Buffer;

namespace palm::todo {

using Settings = settings::Settings;

// The Due menu choices of a To Do, in menu order.
inline constexpr std::array<std::string_view, 5> DUE_NAMES = {"Today", "Tomorrow", "One week later", "No date",
                                                              "Choose date..."};

// The record table of the list, defined in todo.cpp.
class TodoTable;

// The Due box of the details, a Choice that draws the due date over its closed field, defined in todo.cpp.
class DueChoice;

// The To Do List view. The window that holds the view must be deleted before the view, since the widgets refer to
// the images and the text buffer that the view owns.
class TodoView {
public:
	// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group.
	TodoView(int x, int y, int w, int h, msg::Sender s, const Settings& settings);
	~TodoView();
	TodoView(const TodoView&) = delete;
	TodoView& operator=(const TodoView&) = delete;

	// The split that holds the view; the view group makes it its resizable widget.
	Fl_Group* root = nullptr;

	Fl_Table_Row* table() const;
	text_edit::Editor* editor() const;

	// The list takes the focus when the view opens.
	void focus();

	// "3 of 12" for the status bar.
	std::string position() const;

	text_edit::EditState list_edit_state() const;

	// Reads the store and the settings again: the drop-down lists, then the table.
	void refresh(view::Ctx& ctx);

	// Shows the item `id` in the table, changing the filters when it is hidden, and selects it.
	void select(view::Ctx& ctx, std::int64_t id);

	// The title and the lines that Print prints: each row of the table.
	std::pair<std::string, std::vector<std::string>> print_lines(const view::Ctx& ctx) const;

	void new_record(view::Ctx& ctx);

	void handle(msg::TodoMsg m, view::Ctx& ctx);

	// Cut, Copy, Paste, Delete and Select All of the selected items. The text of an item is its title.
	void list_action(records::ListAction a, view::Ctx& ctx);

private:
	std::vector<std::size_t> selected_rows() const;
	std::vector<model::Todo> selected() const;
	// The lead row of the selection, the row clicked last, else the first selected row.
	std::optional<std::size_t> lead_row() const;
	// Rebuilds the columns and the table, and keeps the selection by id.
	void rebuild(const view::Ctx& ctx);
	void set_controls(bool on);
	void load_details(const model::Todo& t);
	void clear_details();
	// Shows the due date in the Due box and the day name in the field next to it.
	void show_due();
	int priority() const;
	// True when a field of the details differs from the item as it was loaded (an edit not applied yet).
	bool has_edits() const;
	void apply_fields(view::Ctx& ctx);
	void delete_selected(view::Ctx& ctx);
	// Saves an item changed from a cell, and reloads the details when they show it.
	void save_from_cell(view::Ctx& ctx, model::Todo t);
	// A click on a cell: the check box marks the item done or not done, and a single click on the priority, the due
	// date or the category opens its menu.
	void cell_click(view::Ctx& ctx, int row, int col, bool double_click);
	void paste_text(view::Ctx& ctx, std::string_view clip);

	msg::Sender s_;
	std::optional<category::IdChoice> filter_;
	std::optional<category::IdChoice> category_;
	TodoTable* table_ = nullptr;
	// The lead row, shared with the table.
	std::shared_ptr<int> lead_;
	// The items the table shows, in table order.
	std::vector<model::Todo> shown_;
	// The item selected in the table.
	std::optional<std::int64_t> selected_id_;
	// The item whose values are in the details, and a copy of it as they show it.
	std::optional<std::int64_t> loaded_id_;
	std::optional<model::Todo> loaded_;
	// The row of the last selection: the row that takes the selection when the selected item leaves the list.
	std::size_t last_pos_ = 0;
	std::unique_ptr<Fl_Text_Buffer> text_buf_;
	text_edit::Editor* text_ = nullptr;
	std::array<Fl_Round_Button*, 5> radios_{};
	DueChoice* due_box_ = nullptr;
	// The due date that the Due box shows, saved by Apply; the Due box draws it.
	std::int64_t pending_due_ = -1;
	Fl_Output* due_day_ = nullptr;
	// The note chosen in the details, saved by Apply.
	std::string pending_note_;
	Fl_Output* note_field_ = nullptr;
	Fl_Button* note_b_ = nullptr;
	std::unique_ptr<Fl_PNG_Image> note_icon_;
	std::unique_ptr<Fl_RGB_Image> note_deimage_;
	Fl_Check_Button* private_box_ = nullptr;
	Fl_Check_Button* complete_box_ = nullptr;
	Fl_Button* apply_ = nullptr;
};

// The due date that Due menu item `pick` gives: today, tomorrow, a week later or no date (-1). Item 4 asks a day in
// the date dialog, which opens on `current` or else today; nullopt when it is canceled.
std::optional<std::int64_t> due_for(std::size_t pick, std::int64_t current, std::int64_t today,
                                    const Settings& settings);

// True when `t` is not done and its due date is before today.
bool is_overdue(const model::Todo& t, std::int64_t today);

// True when `t` has no due date, or a due date of today or earlier.
bool is_due_by_today(const model::Todo& t, std::int64_t today);

// Sets the done flag. A new completion sets the due date to today when "todoRecordDone" is on.
void mark_done(model::Todo& t, bool done, const Settings& settings, std::int64_t today);

// True when the private setting and the Show Options (completed items, only due items) let `t` show.
bool todo_shown(const model::Todo& t, const Settings& settings, bool show_private, std::int64_t today);

// The Sort by index of the Show Options (0 Priority, Due Date; 1 Due Date, Priority; 2 Category, Priority; 3 Category,
// Due Date). Reads the old "todoSort" index when "todoSortBy" is not set.
std::size_t sort_index(const Settings& settings);

// The menu index of a stored sort index, or the reverse: the menu lists Category, Due Date before Category, Priority,
// so entries 2 and 3 swap. Exposed for test_todo.cpp.
std::size_t menu_sort(std::size_t i);

// `list` in the order of the Show Options. The sort is stable, so equal items keep their store order.
std::vector<model::Todo> todo_order(std::vector<model::Todo> list, const Settings& settings,
                                    const store::DesktopStore& desk);

// The due text of a To Do with no date: U+2014, as Palm Desktop shows it (written as UTF-8 bytes).
inline constexpr std::string_view NO_DATE = "\xE2\x80\x94";

// The due text of a To Do: M/d, with "!" when overdue, or NO_DATE when no date.
std::string due_label(const model::Todo& t, std::int64_t today);

// The plain black To Do check box of Palm Desktop, 13 px, at (x, y), drawn in device pixels.
void draw_check(int x, int y, bool done);

} // namespace palm::todo
