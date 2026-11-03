// The Week view, as desktop-rs/app/src/week.rs and WeekView.kt: seven day columns with the untimed row above a time
// grid, and the row of the step buttons, Go to, the title and Today that the Month view shares.
//
// The header and the grid are subclasses that override draw() and handle(int) through date::MouseFrame; FLTK runs its
// own handler first, as in Rust.
#pragma once

#include "palm/date.hpp"
#include "palm/date_shared.hpp"
#include "palm/msg.hpp"
#include "palm/view.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <utility>
#include <vector>

namespace palm::week {

// The height of the navigation row.
inline constexpr std::int32_t NAV_H = 28;

// The navigation row of the Week and Month views: back, Go to and forward at the left, the title, and Today at the
// right. Returns the title. The row is its own group with the title as its resizable widget, so a wider window widens
// the title and the buttons keep the 3.1.1 sizes: arrows 18x20, Go to and Today 75x20.
Fl_Box* nav(int x, int y, int w, msg::Sender s);

namespace detail {

// A press on a box body (move) or on the blue bar of the selected box (resize), until release.
struct Drag {
	// The Rust Drag::new.
	Drag(date_shared::Occ occ, date_shared::Rect rect, std::pair<std::int32_t, std::int32_t> origin, bool resize);

	date_shared::Occ occ;
	date_shared::Rect rect;
	std::pair<std::int32_t, std::int32_t> origin;
	bool resize = false;
	std::int32_t dur = 0;
	bool moved = false;
	std::int64_t new_day = 0;
	std::int32_t new_start = 0;
	std::int32_t new_end = 0;
};

// The field of a new event: its column, its hour (no value for the untimed row) and its untimed line.
struct Typing {
	date::TypeField field;
	std::int64_t day = 0;
	std::size_t col = 0;
	std::optional<std::int32_t> min;
	std::int32_t line = 0;
};

// What the week grid and header draw.
struct WeekModel {
	std::int64_t start = 0;
	std::int64_t day = 0;
	std::int64_t today = 0;
	std::uint32_t begins = 0;
	std::vector<std::vector<date_shared::Occ>> days;
	// The selected event, by id and day.
	std::optional<std::pair<std::int64_t, std::int64_t>> selected;
	std::int32_t untimed_h = 0;
	std::optional<date_shared::Rect> typing;
	std::optional<date_shared::Rect> outline;
	std::int32_t offset = 0;
	bool h24 = false;

	// The top of the hour `hour` in the grid's content, under the untimed strip.
	std::int32_t hour_y(std::int32_t hour) const;
	// The row of the time `min` after midnight in the grid's content.
	std::int32_t line_y(std::int32_t min) const;
	// The minute after midnight at the content row `y`.
	std::int32_t min_of_y(std::int32_t y) const;
	// The event boxes at grid width `w`; a later box in the list is drawn on top.
	std::vector<std::pair<date_shared::Rect, date_shared::Occ>> hits(std::int32_t w) const;
};

// The day names over the columns, on the panel gray. Draws the model; its mouse events go to the view as WeekHeader.
class WeekHeaderFrame : public date::MouseFrame {
public:
	WeekHeaderFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<WeekModel> model);
	void draw() override;

private:
	std::shared_ptr<WeekModel> model_;
};

// The grid of the week: draws the model, and its mouse events go to the view as WeekGrid. A resize sends Scrolled, as
// the Rust resize_callback does, so the range of the hours follows the new height.
class WeekGridFrame : public date::MouseFrame {
public:
	WeekGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<WeekModel> model);
	void draw() override;
	void resize(int x, int y, int w, int h) override;

private:
	std::shared_ptr<WeekModel> model_;
	msg::Sender sender_;
};

} // namespace detail

// The Week view. The Day view owns it and shows it when the Week tab is picked.
class WeekView {
public:
	// Builds the view in (x, y, w, h) with the navigation row on top. The group is hidden until the view is shown.
	WeekView(int x, int y, int w, int h, msg::Sender s);

	// The group of the whole Week view.
	Fl_Group* root = nullptr;

	// Moves the focus into the grid.
	void focus();

	// The field of a new event or of a title, when one is open.
	Fl_Input* field() const;

	// Scrolls so that `hour` opens the hours area. The untimed strip does not scroll, so hour 0 is at offset 0.
	void scroll_to_hour(std::int32_t hour);

	// The occurrence selected in the grid, or no value when it is no longer shown.
	std::optional<date_shared::Occ> selected_occ() const;

	// Reads the store, the settings and the day again: the week of the day, and its grid.
	void refresh(view::Ctx& ctx);

	// Sets the range and the bar of the hours after a scroll, or a resize of the grid.
	void scrolled();

	// The mouse on the day names: a press sets the day of the column under the pointer.
	void header_mouse(view::Ctx& ctx, msg::Mouse m);

	// The mouse on the grid: a press selects a box or types a new event, a drag moves or resizes a box, and the release
	// saves it or clicks.
	void grid_mouse(view::Ctx& ctx, msg::Mouse m);

	// Closes both fields; a new event and a changed title are saved.
	void close_saving(view::Ctx& ctx);

	// The events of the field of a new event or a title: Enter, Escape, losing the focus, and a double click.
	void field_event(view::Ctx& ctx, msg::FieldEv f);

private:
	// The occurrence with `id` on `day`, when the grid shows it.
	std::optional<date_shared::Occ> find(std::int64_t id, std::int64_t day) const;
	// Sets the range and the bar of the hours from the grid size and the untimed strip.
	void sync();
	// Sets the cursor of the window that holds the grid.
	void set_cursor(Fl_Cursor c) const;
	// The box of the selected timed event, local to the grid.
	std::optional<date_shared::Rect> sel_rect() const;
	// The box under the local point (lx, ly) of the grid, the last one drawn on top.
	std::optional<std::pair<date_shared::Rect, date_shared::Occ>> hit_at(std::int32_t lx, std::int32_t ly) const;
	// Moves the outline of the dragged box to the slot under the mouse, and returns it.
	date_shared::Rect update_drag(detail::Drag& d, std::int32_t lx, std::int32_t ly) const;
	// Saves a moved or resized box.
	void save_drag(view::Ctx& ctx, const detail::Drag& d);
	// A click on the grid: a new event on empty space, else the box is selected and acts.
	void click(view::Ctx& ctx, std::int32_t lx, std::int32_t ly, bool double_click);
	// The items of the menu of a box.
	void box_action(view::Ctx& ctx, const date_shared::Occ& occ);
	// Opens a field in column `col`, at the hour `min` or on the untimed line `line`, for the title of a new event.
	void start_typing(view::Ctx& ctx, std::size_t col, std::optional<std::int32_t> min, std::int32_t line);
	// Closes the field of a new event. With `save`, a title that is not empty becomes an event, selected.
	void end_typing(view::Ctx& ctx, bool save);
	// Opens the title of the box `occ` at `r` for editing, over its title area.
	void start_title_edit(date_shared::Rect r, date_shared::Occ occ);
	// Closes the title field. With `save`, a changed title that is not empty is saved.
	void end_title_edit(view::Ctx& ctx, bool save);

	msg::Sender s_;
	Fl_Box* title_ = nullptr;
	detail::WeekHeaderFrame* header_ = nullptr;
	detail::WeekGridFrame* grid_ = nullptr;
	date::Scroller scroller_;
	std::shared_ptr<detail::WeekModel> model_;
	std::optional<date_shared::Occ> selected_;
	std::optional<detail::Typing> typing_;
	// The field of a changed title, with its occurrence.
	std::optional<std::pair<date::TypeField, date_shared::Occ>> title_edit_;
	std::optional<detail::Drag> drag_;
	bool suppress_click_ = false;
};

} // namespace palm::week
