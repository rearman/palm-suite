// The parts the Day, Week, and Month views share, as desktop-rs/app/src/date_shared.rs: the time labels, the event
// boxes and their hit tests, the Current, All and Cancel question of a repeating event, Alarm Settings, the menu of an
// event box, and the repeat summary of Change Repeat.
#pragma once

#include "palm/model.hpp"
#include "palm/settings.hpp"
#include "palm/store.hpp"

#include <FL/Enumerations.H>

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

class Fl_Spinner;

namespace palm::date_shared {

// The question of a change to a repeating event.
inline constexpr std::string_view CHANGE_TEXT = "Apply the changes to the current event only or to all occurrences?";
// The gray bar on the right edge of an event box.
inline constexpr int BAR_W = 9;
// The blue bar under a selected event box.
inline constexpr int SEL_H = 4;
// The hour lines of the Day and Week grids: gray 128, as in Palm Desktop.
inline const Fl_Color HOUR_LINE = fl_rgb_color(128, 128, 128);
// The units of an alarm, in menu order.
inline constexpr std::array<std::string_view, 3> ALARM_UNITS = {"Minutes before", "Hours before", "Days before"};
inline constexpr std::array<std::string_view, 12> MONTH_LONG = {
    "January", "February", "March",     "April",   "May",      "June",
    "July",    "August",   "September", "October", "November", "December",
};

// A rectangle in logical pixels. Rust's Rect::new is new_rect, as new is a C++ keyword.
struct Rect {
	int x;
	int y;
	int w;
	int h;

	static Rect new_rect(int x, int y, int w, int h);
	bool contains(int px, int py) const;
	Rect moved(int dx, int dy) const;
};

// One day's occurrence of an event.
struct Occ {
	model::Event event;
	std::int64_t day;

	bool operator==(const Occ&) const = default;
};

// What a box fills with: the dithered yellow of an event, or white for the box of a new event.
enum class Fill { Event, White };

// The marker icons of an event: repeat, alarm, note, private.
enum class Marker { Repeat, Alarm, Note, Private };

// The items of the menu of an event box.
enum class BoxAction { Delete, Note, Alarm, Edit };

// The occurrences of `day`, untimed first, then by time. Private events show only when `show_private`.
std::vector<Occ> visible_events(const store::DesktopStore& store, std::int64_t day, bool show_private);

// Time of day in minutes after midnight, as "8:00a" or "13:30".
std::string time_label(std::int32_t min, bool h24);

// The first day of the week: 0 Sunday, 1 Monday.
std::uint32_t week_begins(const settings::Settings& settings);

// The hour that the day grids scroll to.
std::int32_t work_day(const settings::Settings& settings);

// The index into DOW_SHORT, DOW_LONG and DOW_LETTER of grid column `col`.
std::size_t dow_index(std::size_t col, std::uint32_t begins);

// Sets the alarm of a new event from the preset of the settings, when it is on.
void apply_alarm_preset(model::Event& e, const settings::Settings& settings);

// The markers that an event shows, in order.
std::vector<Marker> markers(const model::Event& e);

// Draws an event box in `r` as Palm Desktop does: the fill with a black outline and a gray bar on the right edge. A
// selected box has a darker bar and a blue bar under it. Drawn in device pixels.
void draw_box(Rect r, Fill fill, bool selected);

// Draws the box of `e` in `r`, then its markers and its title. `centered` is an untimed header row.
void paint_event(Rect r, const model::Event& e, bool selected, bool centered);

// Splits `text` into lines at its spaces: the first line at most `first_w` px wide and the others `rest_w` px, as
// `measure` gives the width of a string. A word wider than its line is cut between characters.
std::vector<std::string> wrap_words(std::string_view text, int first_w, int rest_w,
                                    const std::function<int(std::string_view)>& measure);

// X where the title of `e` starts, after its marker icons.
int title_x(Rect r, const model::Event& e);

// The marker icon of `e` under (px, py), or nullopt.
std::optional<Marker> marker_at(Rect r, const model::Event& e, int px, int py, bool centered);

// True when (px, py) is on the gray bar at the right edge of the box `r`.
bool on_handle(Rect r, int px, int py);

// True when (px, py) is on the blue bar under the selected box `r`.
bool on_resize_bar(Rect r, int px, int py);

// Draws `r` as Palm Desktop draws a drag outline: navy, 3 px thick, inside `r`.
void draw_outline(Rect r);

// A raised cell, as Palm Desktop draws each hour of the time column: panel gray, a white top edge and a shadow bottom
// edge.
void raised_cell(int x, int y, int w, int h);

// The diamond that marks the untimed rows, centered at (cx, cy).
void draw_diamond(int cx, int cy);

// Draws the hour labels of a time column of `w` px: the label and its tick sit in the middle of the hour, as in
// Palm Desktop.
void draw_hour_label(std::int32_t min, int w, int x, int mid, bool h24);

// Asks Current or All for a repeating event: true the current occurrence, false all, nullopt Cancel.
std::optional<bool> ask_repeat_scope(std::string_view text);

// Saves `work`, the edited copy of the occurrence `occ`, whose date `work.date` can differ from `occ.day`. A repeating
// event asks Current or All first: Current splits the occurrence off the series, All moves the whole series by the
// same days. False on Cancel.
bool save_occurrence(store::DesktopStore& store, const Occ& occ, model::Event work, std::string_view text);

// Applies `change` to the occurrence `occ` and saves it, asking Current or All for a repeating event. False on Cancel.
bool change_occurrence(store::DesktopStore& store, const Occ& occ, std::string_view text,
                       const std::function<void(model::Event&)>& change);

// Deletes the occurrence `occ`. A repeating event asks Current or All first. False on Cancel.
bool delete_occurrence(store::DesktopStore& store, const settings::Settings& settings, bool no_confirm, const Occ& occ);

// A spinner for a count from `min` up. The spinner is added to the current group, which owns it.
Fl_Spinner* count_spinner(int x, int y, int w, int h, int value, int min);

// The Alarm Settings dialog. Writes the alarm fields of `e` on OK, and returns true; it does not save.
bool alarm_dialog(model::Event& e);

// Opens the note of `occ` and saves a change. A click on the note icon of a box does this too.
void edit_note(store::DesktopStore& store, const Occ& occ);

// Opens Alarm Settings for `occ` and saves a change. A click on the alarm icon of a box does this too.
void edit_alarm(store::DesktopStore& store, const Occ& occ);

// Shows the menu of an event box at the pointer.
std::optional<BoxAction> event_menu();

// The line height of a box title, in Helvetica 11 (MS Sans Serif 8); the font is left set.
int title_line_h();

// The top of the first title line of a one-line box `r`, as paint_event draws it: centered in a centered row (the
// untimed rows), 1 px below the top of an hour box. The in-place field puts its text there.
int title_top(Rect r, bool centered);

// Draws `s` centered in the box (x, y, w, h).
void draw_centered(std::string_view s, int x, int y, int w, int h);

// The summary text of the Palm Desktop 3.1.1 Change Repeat dialog, measured from the official app: every 1 day "Every
// day", every 2 days "Every other day"; weekly on Tuesday "Every Tuesday" and "Every other Tuesday"; on Mon and Tue
// every 3 "Every 3rd week on Mon, and Tue"; by date "The 20th of every month" and "The 20th of every 3rd month"; yearly
// "October 20 every year". Forms not measured follow that pattern: no frequency word for 1, "other" for 2 and the
// ordinal from 3.
std::string describe_desktop(const model::Event& e);

// The Date view title: "Friday, October 23, 2026".
std::string date_title(std::int64_t day);

// The week number of the Week view title, as java.time's WeekFields weekOfYear: weeks from Sunday with the week of
// January 1 as week 1, or ISO weeks from Monday (week 1 has four days of the year, the days before it are week 0).
std::int64_t week_of_year(std::int64_t day, bool monday);

} // namespace palm::date_shared
