// The Date view, as desktop-rs/app/src/date.rs: the Day view, the right pane (the year, the months and the calendar,
// then the To Do or Address list) and the Day, Week and Month tabs on the right edge. The Week and Month views
// (week.hpp, month.hpp) share the tabs and take the full width.
//
// Rust closures on widgets become subclasses that override handle(int) or draw(). FLTK runs its own handler of a
// widget first, then the override, as fltk-rs runs the closure after FLTK's handler. The Rust free function
// forward_mouse is the constructor of MouseFrame. week.hpp and month.hpp include this header; this header names
// week::WeekView and month::MonthView only as forward declarations, which the owner holds through unique_ptr.
#pragma once

#include "palm/address.hpp"
#include "palm/category.hpp"
#include "palm/date_shared.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/records.hpp"
#include "palm/settings.hpp"
#include "palm/text_edit.hpp"
#include "palm/view.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Scrollbar.H>

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

class Fl_PNG_Image;

namespace palm::week {
class WeekView;
}

namespace palm::month {
class MonthView;
}

namespace palm::date {

// The width of the time column.
inline constexpr std::int32_t TIME_W = 52;

// The mouse event of `ev` (an FLTK event number), when it is one a Date Book view takes.
std::optional<msg::Mouse> mouse_of(int ev);

// The Rust forward_mouse: the mouse events of the widget go to `wrap`, which makes the message to send. Enter and
// Leave are taken as well, so the Move events keep coming. Subclasses set the wrap of their own mouse events.
class MouseFrame : public Fl_Box {
public:
	MouseFrame(int x, int y, int w, int h, msg::Sender s, std::function<msg::Msg(msg::Mouse)> wrap);
	int handle(int event) override;

private:
	msg::Sender s_;
	std::function<msg::Msg(msg::Mouse)> wrap_;
};

// A vertical scroll bar for a custom view whose content is taller than the view.
class Scroller {
public:
	// The scroll bar, a child of the group that is current when the view is built.
	Fl_Scrollbar* bar;

	// The Rust Scroller::new: `make` gives the message that the bar sends on each change.
	Scroller(int x, int y, int h, msg::Sender s, msg::Msg (*make)());

	// The current offset of the bar, in pixels.
	int offset() const;

	// Sets the range for a view of `view` px over content of `total` px, keeping the offset in range. A pending
	// offset waits until the content is taller than the view.
	void set_range(int view, int total);

	// Scrolls the bar to `y`, clamped to its range.
	void scroll_to(int y);

	// Scrolls to `y` once the range is known.
	void scroll_later(int y);

	// Turns the wheel by `lines`.
	void wheel(int lines);

private:
	// An offset asked for before the range was set, applied by the first set_range.
	std::optional<int> pending_;
};

// The input of a TypeField. Its right-click menu is the text field menu; Escape, losing the focus, a double click, and
// Enter send the events of the field.
class TypeInput : public Fl_Input {
public:
	TypeInput(int x, int y, int w, int h, msg::Sender s);
	int handle(int event) override;

private:
	msg::Sender s_;
};

// A field that types a new event or a box title, over the box.
struct TypeField {
	// The field. It belongs to the group it was opened in.
	TypeInput* input;

	// Opens a field at (x, y, w) in window coordinates, holding `text`, in the group `g`. No group is current
	// afterwards: FLTK's end() makes the parent of `g` current, and a dialog window built later would become a child
	// of that group, drawn inside the view with no title bar.
	static TypeField open(Fl_Group* g, int x, int y, int w, std::string_view text, msg::Sender s);

	// Puts the field so its text lies where the box draws its title: (x, y) is the left end and the top of the title's
	// first line, in window coordinates. FLTK draws an input's text 1 px in from its left edge and centers it in its
	// height, so the field starts 1 px to the left and is one title line high.
	void place(int x, int y, int w);

	// The text of the field, trimmed.
	std::string text() const;

	// Removes the field, and redraws its parent. The field must not be used afterwards.
	void close();
};

namespace detail {

// One row of the To Do pane.
struct PaneTodo {
	model::Todo todo;
	std::string due;
	bool overdue = false;
};

// What the right pane and the calendar draw.
struct PaneModel {
	std::int64_t day = 0;
	std::int64_t today = 0;
	std::uint32_t begins = 0;
	bool address = false;
	std::vector<PaneTodo> todos;
	bool show_pri = false;
	bool show_due = false;
	std::vector<palm::address::Row> addrs;
};

// What the Day header and grid draw. The view writes it; the draw overrides read it.
struct DayModel {
	std::vector<date_shared::Occ> occs;
	std::optional<std::int64_t> selected;
	// The untimed rows: one for each untimed event and an open untimed field, at least one.
	std::int32_t untimed_rows = 0;
	// The box of the field of a new event, local to the header (true) or the grid.
	std::optional<std::pair<bool, date_shared::Rect>> typing;
	// The outline of a box being moved or resized, local to the grid.
	std::optional<date_shared::Rect> outline;
	// The outline of the event a dragged pane row makes, local to the grid.
	std::optional<date_shared::Rect> drop_box;
	// The event whose title is open for editing: its box is drawn white, with the field over the title.
	std::optional<std::int64_t> editing;
	std::int32_t offset = 0;
	bool h24 = false;
};

// A field that types a new event into an empty hour or the untimed rows.
struct Typing {
	TypeField field;
	std::int64_t day = 0;
	std::int32_t min = 0;
	bool untimed = false;
};

// The in-place editor of a box title.
struct TitleEdit {
	TypeField field;
	date_shared::Occ occ;
};

// A press on a box: the box moves to a new time or day, or its end is resized. `moved` after 4 px.
struct Drag {
	// The Rust Drag::new.
	Drag(date_shared::Occ occ, bool resize, bool header, std::int32_t grab,
	     std::pair<std::int32_t, std::int32_t> origin);

	date_shared::Occ occ;
	bool resize = false;
	bool header = false;
	// The distance from the top of the box to the press.
	std::int32_t grab = 0;
	std::pair<std::int32_t, std::int32_t> origin;
	bool moved = false;
	std::int32_t new_start = 0;
	std::int32_t new_end = 0;
	// The calendar day under the mouse.
	std::optional<std::int64_t> drop_day;
};

// The raised bevel of the Day view's panel, without its right side: the tab strip draws that edge.
class BodyFrame : public Fl_Box {
public:
	BodyFrame(int x, int y, int w, int h);
	void draw() override;
};

// The title of the Day view. A double click on it sends TitleDouble, which picks a day.
class TitleFrame : public Fl_Box {
public:
	TitleFrame(int x, int y, int w, int h, msg::Sender s);
	int handle(int event) override;

private:
	msg::Sender s_;
};

// The untimed rows of the Day view: they draw the model, and send the mouse events of the header as Day(true, mouse).
class DayHeaderFrame : public MouseFrame {
public:
	DayHeaderFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<DayModel> model);
	void draw() override;

private:
	std::shared_ptr<DayModel> model_;
};

// The hours of the Day view: they draw the model, and send the mouse events of the grid as Day(false, mouse).
class DayGridFrame : public MouseFrame {
public:
	DayGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<DayModel> model);
	void draw() override;

private:
	std::shared_ptr<DayModel> model_;
};

// One of the twelve month buttons of the right pane. It draws the black lines of the month grid.
class MonthButton : public Fl_Button {
public:
	MonthButton(int x, int y, int w, int h, const char* label);
	void draw() override;
};

// The black top and left edge of the month buttons.
class MonthEdgeFrame : public Fl_Box {
public:
	MonthEdgeFrame(int x, int y, int w, int h);
	void draw() override;
};

// The month calendar of the right pane. It draws the model of the pane, and sends its mouse events as Calendar(mouse).
class CalendarFrame : public MouseFrame {
public:
	CalendarFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<PaneModel> pane);
	void draw() override;

private:
	std::shared_ptr<PaneModel> pane_;
};

// The Address and To Do segments of the right pane. A press on the left half sends Pane(true), on the right half
// Pane(false).
class SwitchFrame : public Fl_Box {
public:
	SwitchFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<PaneModel> pane);
	void draw() override;
	int handle(int event) override;

private:
	msg::Sender s_;
	std::shared_ptr<PaneModel> pane_;
};

// A list of the right pane: the To Do items or the addresses. It draws each row as the pane does, and its events go to
// the view, which drags a row to the Day grid. Its header is set off, with one column.
class PaneList : public records::RecordTable {
public:
	PaneList(int x, int y, int w, int h, std::shared_ptr<PaneModel> pane, bool address, msg::Sender s);
	~PaneList() override;
	void draw_cell(TableContext context, int row, int col, int x, int y, int w, int h) override;
	int handle(int event) override;

private:
	std::shared_ptr<PaneModel> pane_;
	bool address_ = false;
	// The width of the widest number of the address rows, set at the start of the page.
	std::int32_t widest_ = 0;
	msg::Sender s_;
	// The note and private icons of the To Do rows.
	std::unique_ptr<Fl_PNG_Image> note_;
	std::unique_ptr<Fl_PNG_Image> key_;
};

// The Day, Week and Month tabs on the right edge. They draw their captions turned clockwise, and a click on a tab
// shows its view. Alt+D, Alt+W and Alt+M show the Day, Week and Month views.
class TabsFrame : public Fl_Box {
public:
	TabsFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<msg::DateMode> tab_mode);
	void draw() override;
	int handle(int event) override;

private:
	msg::Sender s_;
	std::shared_ptr<msg::DateMode> tab_mode_;
};

// The widgets of the Day view that its group lays out. The group's resize callback holds a copy, so the group keeps
// the fixed sizes of the right pane and the buttons, and only the day area and the lists grow.
struct Place {
	Fl_Box* title;
	Fl_Box* edge;
	Fl_Box* header;
	Fl_Box* grid;
	Fl_Scrollbar* bar;
	Fl_Button* today;
	Fl_Button* edit;
	Fl_Button* new_date;
	Fl_Button* back;
	Fl_Box* year;
	Fl_Button* fwd;
	std::vector<Fl_Button*> months;
	Fl_Box* month_edge;
	Fl_Box* cal_edge;
	Fl_Box* cal;
	Fl_Box* switch_date;
	Fl_Choice* todo_cat;
	Fl_Choice* addr_cat;
	PaneList* todo_list;
	PaneList* addr_list;
	Fl_Box* look_label;
	text_edit::Input* look;
	std::shared_ptr<DayModel> model;

	// Lays the Day view out in `g`: the day area takes the width left of the right pane, the grid takes the height
	// under the title, and the lists take the height under the switch. The fixed parts keep their sizes.
	void apply(date_shared::Rect g);
};

} // namespace detail

// The Date view: the Day view with its right pane, and the Week and Month views that share its tabs.
class DateView {
public:
	// Builds the view in (x, y, w, h), the whole group of the Date view.
	DateView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings);
	~DateView();
	DateView(const DateView&) = delete;
	DateView& operator=(const DateView&) = delete;

	// The group of the whole Date view.
	Fl_Group* root = nullptr;
	// The view shown: Day, Week or Month.
	msg::DateMode mode = msg::DateMode::Day;

	// The Look up field of the right pane.
	text_edit::Input* look() const;

	// The editors of the view that take the Edit commands: an open field, else Look up.
	Fl_Input* field_widget() const;

	// Moves the focus into the view shown.
	void focus();

	// Shows the Day, Week or Month view and remembers the choice. Palm Desktop shows the right pane in the Day view
	// only; Week and Month take the full width.
	void show_mode(msg::DateMode shown);

	// Reads the store, the settings and the day again: every part of the view.
	void refresh(view::Ctx& ctx);

	// True when the view shown has a selected event, for the Edit menu: Edit Event, Delete Event, Delete and Select All
	// are gray without one, as in Palm Desktop 3.1.1.
	bool has_selected_event() const;

	// Shows the event `e` in the Day view on its date, selected.
	void select(view::Ctx& ctx, const model::Event& e);

	// The title and the lines that Print prints: the date and the events of the day, with their times.
	std::pair<std::string, std::vector<std::string>> print_lines(const view::Ctx& ctx) const;

	// The New... button and the New Event command: a new event on the day.
	void new_record(view::Ctx& ctx);

	// The Edit... button and the Edit Event command: the selected event.
	void edit_selected(view::Ctx& ctx);

	// The Delete Event command: the selected occurrence.
	void delete_selected(view::Ctx& ctx);

	// Handles one message of the Date Book.
	void handle(msg::DateMsg message, view::Ctx& ctx);

private:
	// Shows another view and remembers it. The open fields save first, so their text is not lost.
	void switch_mode(view::Ctx& ctx, msg::DateMode next);
	// The category drop-down of the pane: the To Do categories, or the Address categories and Unfiled.
	void refresh_categories(view::Ctx& ctx, bool address);
	// Fills the list of the pane mode and shows it, with Look up in Address mode.
	void refresh_lists(view::Ctx& ctx, bool address);
	// The address selected in the pane's list, by id.
	std::optional<std::int64_t> selected_addr() const;
	// Copies the state the Day header and grid draw into their model, and sizes the header.
	void sync_model();
	// Puts the open fields over their boxes.
	void place_fields();
	// The occurrence selected in the Day view.
	std::optional<date_shared::Occ> selected_occ() const;
	// The selected occurrence of the view shown: the Week view's own selection, else the Day selection.
	std::optional<date_shared::Occ> current_occ() const;
	// The day of the calendar cell at window point (x, y), or no value outside the day cells.
	std::optional<std::int64_t> calendar_day(int x, int y) const;
	// Selects the first address whose list name starts with the Look up text, ignoring case.
	void seek_address(const view::Ctx& ctx);
	// Closes the field of a new event. With `save`, a title that is not empty becomes a one-hour event, or an untimed
	// one, and the selected event.
	void end_typing_ctx(view::Ctx& ctx, bool save);
	// Closes the title editor. With `save`, a changed title that is not empty is saved for its occurrence.
	void end_title_edit_ctx(view::Ctx& ctx, bool save);
	// Adds an event with `title` on `day` at `min`, or untimed, and selects it.
	void add_event(view::Ctx& ctx, std::string_view title, std::int64_t day, std::int32_t min, bool untimed);
	// The events of the field of a new event or a title: Enter, Escape, losing the focus, and a double click.
	void field_event(view::Ctx& ctx, msg::FieldEv f);
	// Opens a field in the hour at `min`, or a new untimed row, for the title of a new event.
	void start_typing(view::Ctx& ctx, std::int32_t min, bool untimed);
	// Opens an editor on the title of the box `occ` at `r`, local to the header or the grid.
	void edit_title(date_shared::Occ occ, date_shared::Rect r, bool header);
	// The local point and the layout of the header (true) or the grid, for window point (x, y).
	std::pair<std::int32_t, std::int32_t> local(bool header, int x, int y) const;
	// The width of the header or the grid.
	std::int32_t width_of(bool header) const;
	// The box of the header or the grid at the local point, with its occurrence.
	std::optional<std::pair<date_shared::Occ, date_shared::Rect>> box_at(bool header, std::int32_t lx,
	                                                                     std::int32_t ly) const;
	// The box of the selected timed event, local to the grid.
	std::optional<date_shared::Rect> sel_box() const;
	// Sets the cursor of the window that holds the grid.
	void set_cursor(Fl_Cursor c) const;
	// The mouse on the header or the grid: the press selects or moves a box, the drag outlines it, and the release
	// saves it or clicks.
	void day_mouse(view::Ctx& ctx, bool header, msg::Mouse m);
	// A click on the header or the grid: a box is selected, and its markers, its bar or a double click act; a click on
	// empty space types a new event.
	void click(view::Ctx& ctx, bool header, std::int32_t lx, std::int32_t ly, bool double_click);
	// The items of the menu of a box.
	void box_action(view::Ctx& ctx, const date_shared::Occ& occ);
	// Sets the target of `d` for the mouse at window point (x, y), local (lx, ly): a calendar day, or an outline in
	// the grid, which it returns.
	std::optional<date_shared::Rect> update_drag(detail::Drag& d, int x, int y, std::int32_t lx, std::int32_t ly) const;
	// The mouse on the list of the right pane: a press remembers the row, a drag past 4 px drops a row on the grid,
	// and a release selects, opens, or drops.
	void pane_mouse(view::Ctx& ctx, msg::Mouse m);
	// The header (true) or the grid under window point (x, y), with the grid row, for a drop.
	std::optional<std::pair<bool, std::int32_t>> drop_target(int x, int y) const;
	// A click on a To Do row of the pane: the check box marks the item done or not done, the priority and the due date
	// open their menus, and a double click shows the item in the To Do List.
	void todo_click(view::Ctx& ctx, std::size_t row, std::int32_t lx, bool double_click);

	msg::Sender s_;
	std::shared_ptr<msg::DateMode> tab_mode_;
	detail::TabsFrame* tabs_ = nullptr;
	Fl_Group* day_group_ = nullptr;
	std::unique_ptr<week::WeekView> week_;
	std::unique_ptr<month::MonthView> month_;
	detail::TitleFrame* title_ = nullptr;
	detail::DayHeaderFrame* header_ = nullptr;
	detail::DayGridFrame* grid_ = nullptr;
	Scroller scroller_;
	std::shared_ptr<detail::DayModel> model_;
	Fl_Button* edit_b_ = nullptr;
	Fl_Box* year_ = nullptr;
	std::vector<Fl_Button*> months_;
	detail::CalendarFrame* cal_ = nullptr;
	detail::SwitchFrame* switch_date_ = nullptr;
	category::IdChoice todo_cat_;
	category::IdChoice addr_cat_;
	detail::PaneList* todo_list_ = nullptr;
	detail::PaneList* addr_list_ = nullptr;
	Fl_Box* look_label_ = nullptr;
	text_edit::Input* look_ = nullptr;
	std::shared_ptr<detail::PaneModel> pane_;
	detail::Place place_;
	// The day the grid last showed; a new day opens at the work day.
	std::optional<std::int64_t> shown_day_;
	std::vector<date_shared::Occ> occs_;
	std::optional<std::int64_t> selected_id_;
	// The To Do category of the pane, no value for All.
	std::optional<std::int32_t> todo_cat_id_;
	std::vector<model::Todo> pane_todos_;
	std::vector<model::Address> pane_addrs_;
	std::optional<detail::Typing> typing_;
	std::optional<detail::TitleEdit> title_edit_;
	std::optional<detail::Drag> drag_;
	bool suppress_click_ = false;
	// A press on a pane row: x, y, the row, and true once it drags.
	std::optional<std::tuple<std::int32_t, std::int32_t, std::int32_t, bool>> pane_press_;
};

} // namespace palm::date
